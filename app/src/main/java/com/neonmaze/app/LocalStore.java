package com.neonmaze.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.DailyChallenge;
import com.neonmaze.core.GameEngine;
import com.neonmaze.core.LevelSystem;
import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.ProgressRules;
import com.neonmaze.core.ReplaySystem;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.RunResult;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local-only persistence for NEON MAZE.
 *
 * <h2>Scope</h2>
 * <ul>
 *   <li>SQLite for profile, level progress, run records, achievements, and
 *       daily records.</li>
 *   <li>SharedPreferences for the eight settings values exposed by the
 *       approved Settings screen, including the in-app SFX volume.</li>
 *   <li>Photo avatar imported by {@link AvatarPipeline}; the original
 *       {@link Uri} is never persisted.</li>
 *   <li>All database and file I/O runs on a single-thread {@link Executor};
 *       callbacks are posted back to the caller-provided
 *       {@link MainThreadPoster}.</li>
 * </ul>
 *
 * <h2>Atomic commit</h2>
 * <p>A terminal run is evaluated by the pure Core {@link ProgressRules} model.
 * Its run row, replay blob, Campaign snapshot, achievement progress and Daily
 * best are committed in one SQLite transaction. Non-Campaign modes cannot
 * write Campaign stars or unlocks. Mode records are reconstructed from the
 * canonical run rows rather than duplicated in a second mutable table.</p>
 *
 * <h2>Schema versioning</h2>
 * <p>{@link DatabaseHelper#DATABASE_VERSION} is independent of the app's
 * {@code versionCode}. Migrations are sequential and non-destructive.</p>
 *
 * <h2>Threading</h2>
 * <p>Every public method is safe to call from any thread. Work is queued on
 * a single background thread; results are delivered on the thread supplied
 * via {@link MainThreadPoster}. No database query or file I/O is performed
 * on the caller's thread.</p>
 */
public final class LocalStore {

    private static final String TAG = "LocalStore";

    // ---------------------------------------------------------------------
    // Database constants
    // ---------------------------------------------------------------------

    private static final String DATABASE_NAME = "neon_maze.db";
    private static final int DATABASE_VERSION = 2;

    // SharedPreferences
    private static final String PREFS_NAME = "neon_maze_settings";
    private static final String KEY_SOUND = "sound_enabled";
    private static final String KEY_HAPTICS = "haptics_enabled";
    private static final String KEY_REDUCED_MOTION = "reduced_motion";
    private static final String KEY_REDUCED_EFFECTS = "reduced_effects";
    private static final String KEY_HIGH_CONTRAST = "high_contrast";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_LEFT_HANDED = "left_handed_ui";
    private static final String KEY_VOLUME_PERCENT = "volume_percent";
    private static final String KEY_EQUIPPED_TRAIL = "equipped_trail";
    private static final String KEY_EQUIPPED_FRAME = "equipped_frame";
    private static final String KEY_EQUIPPED_EMBLEM = "equipped_emblem";

    // Replay payload retention is bounded per level/mode; scalar run history is retained.
    private static final int MAX_RUN_RECORDS_PER_LEVEL_MODE = 20;

    private final Context appContext;
    private final DatabaseHelper dbHelper;
    private final SharedPreferences prefs;
    private final ExecutorService executor;
    private final MainThreadPoster mainThreadPoster;
    private final AvatarPipeline avatarPipeline;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong callbackGeneration = new AtomicLong(1L);
    private final Object lifecycleLock = new Object();

    /**
     * @param context          any context; the application context is used
     * @param mainThreadPoster a poster that dispatches callbacks onto the
     *                         main thread; in production this is usually
     *                         {@code runnable -> new Handler(Looper.getMainLooper()).post(runnable)}
     */
    public LocalStore(@NonNull Context context,
                      @NonNull MainThreadPoster mainThreadPoster) {
        Objects.requireNonNull(context, "context");
        Context application = context.getApplicationContext();
        this.appContext = application != null ? application : context;
        this.dbHelper = new DatabaseHelper(appContext);
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.avatarPipeline = new AvatarPipeline(appContext);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "neon-maze-db");
            t.setDaemon(true);
            return t;
        });
        this.mainThreadPoster = Objects.requireNonNull(mainThreadPoster, "mainThreadPoster");
    }

    /**
     * Invalidates callbacks and closes the database helper after queued work.
     * This method is idempotent and does not interrupt an in-flight transaction.
     */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            callbackGeneration.incrementAndGet();
            try {
                executor.execute(dbHelper::close);
            } catch (RejectedExecutionException ignored) {
                dbHelper.close();
            }
            executor.shutdown();
        }
    }

    /** Invalidates already queued UI callbacks without cancelling their storage operations. */
    public void invalidatePendingCallbacks() {
        callbackGeneration.incrementAndGet();
    }

    // =====================================================================
    // Public callback types
    // =====================================================================

    /** Dispatches a {@link Runnable} on the main thread. */
    public interface MainThreadPoster {
        void post(Runnable runnable);
    }

    /** Generic result callback delivered on the main thread. */
    public interface Callback<T> {
        void onResult(T value);
        void onError(Exception error);
    }

    private interface StoreWork<T> {
        T run() throws Exception;
    }

    private <T> void enqueue(@NonNull Callback<T> callback, @NonNull StoreWork<T> work) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(work, "work");
        boolean rejected = false;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                rejected = true;
            } else {
                final long generation = callbackGeneration.get();
                try {
                    executor.execute(() -> {
                        try {
                            T value = work.run();
                            postResult(generation, callback, value);
                        } catch (Exception error) {
                            postError(generation, callback, error);
                        }
                    });
                } catch (RejectedExecutionException exception) {
                    rejected = true;
                }
            }
        }
        if (rejected) postClosedError(callback);
    }

    private <T> void postResult(long generation, Callback<T> callback, T value) {
        if (generation != callbackGeneration.get()) return;
        try {
            mainThreadPoster.post(() -> {
                if (!closed.get() && generation == callbackGeneration.get()) {
                    callback.onResult(value);
                }
            });
        } catch (RuntimeException ignored) {
            // The host's main-thread dispatcher may already be shutting down.
        }
    }

    private <T> void postError(long generation, Callback<T> callback, Exception error) {
        if (generation != callbackGeneration.get()) return;
        try {
            mainThreadPoster.post(() -> {
                if (!closed.get() && generation == callbackGeneration.get()) {
                    callback.onError(error);
                }
            });
        } catch (RuntimeException ignored) {
            // The host's main-thread dispatcher may already be shutting down.
        }
    }

    private <T> void postClosedError(Callback<T> callback) {
        try {
            mainThreadPoster.post(() -> callback.onError(
                    new IllegalStateException("LocalStore is closed")));
        } catch (RuntimeException ignored) {
            // No callback target remains.
        }
    }

    // =====================================================================
    // Data classes
    // =====================================================================

    /** Local player profile. All fields are nullable except {@code id}. */
    public static final class Profile {
        public final long id;
        @Nullable public final String displayName;
        public final int avatarType;       // 0 = built-in, 1 = photo
        @Nullable public final String avatarAssetId;
        @Nullable public final String photoPath;
        public final long createdAt;
        public final long updatedAt;

        public Profile(long id, @Nullable String displayName, int avatarType,
                       @Nullable String avatarAssetId, @Nullable String photoPath,
                       long createdAt, long updatedAt) {
            this.id = id;
            this.displayName = displayName;
            this.avatarType = avatarType;
            this.avatarAssetId = avatarAssetId;
            this.photoPath = photoPath;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /** Per-level progress. {@code optionalMask} is a bitmask of collected optional objectives. */
    public static final class LevelProgress {
        public final String levelId;
        public final boolean unlocked;
        public final boolean completed;
        public final int stars;
        public final long bestTimeMs;
        public final int bestScore;
        public final int attempts;
        public final int completions;
        public final int optionalMask;
        public final long updatedAt;

        public LevelProgress(String levelId, boolean unlocked, boolean completed,
                             int stars, long bestTimeMs, int bestScore,
                             int attempts, int completions, int optionalMask,
                             long updatedAt) {
            this.levelId = levelId;
            this.unlocked = unlocked;
            this.completed = completed;
            this.stars = stars;
            this.bestTimeMs = bestTimeMs;
            this.bestScore = bestScore;
            this.attempts = attempts;
            this.completions = completions;
            this.optionalMask = optionalMask;
            this.updatedAt = updatedAt;
        }
    }

    /** One completed or interrupted run. */
    public static final class RunRecord {
        public final long id;
        public final String mode;
        public final String levelId;
        public final String result;
        public final long elapsedMs;
        public final int score;
        public final int stars;
        public final boolean assisted;
        @Nullable public final byte[] replayBlob;
        public final int replaySchemaVersion;
        public final int levelRevision;
        @Nullable public final String geometryHash;
        public final long createdAt;

        public RunRecord(long id, String mode, String levelId, String result,
                         long elapsedMs, int score, int stars, boolean assisted,
                         @Nullable byte[] replayBlob, int replaySchemaVersion,
                         int levelRevision, @Nullable String geometryHash,
                         long createdAt) {
            this.id = id;
            this.mode = mode;
            this.levelId = levelId;
            this.result = result;
            this.elapsedMs = elapsedMs;
            this.score = score;
            this.stars = stars;
            this.assisted = assisted;
            this.replayBlob = replayBlob;
            this.replaySchemaVersion = replaySchemaVersion;
            this.levelRevision = levelRevision;
            this.geometryHash = geometryHash;
            this.createdAt = createdAt;
        }
    }

    /** Achievement progress. {@code progressValue} is interpreted per achievement. */
    public static final class Achievement {
        public final String achievementId;
        @Nullable public final Long unlockedAt;
        public final int progressValue;

        public Achievement(String achievementId, @Nullable Long unlockedAt,
                           int progressValue) {
            this.achievementId = achievementId;
            this.unlockedAt = unlockedAt;
            this.progressValue = progressValue;
        }
    }

    /** One daily-challenge record keyed by UTC date string. */
    public static final class DailyRecord {
        public final String challengeDateUtc;
        public final String challengeId;
        public final String levelId;
        public final long bestTimeMs;
        public final int score;
        @Nullable public final byte[] replayBlob;
        public final long updatedAt;

        public DailyRecord(String challengeDateUtc, String challengeId,
                           String levelId, long bestTimeMs, int score,
                           @Nullable byte[] replayBlob, long updatedAt) {
            this.challengeDateUtc = Objects.requireNonNull(challengeDateUtc, "challengeDateUtc");
            this.challengeId = Objects.requireNonNull(challengeId, "challengeId");
            this.levelId = Objects.requireNonNull(levelId, "levelId");
            if (bestTimeMs < 0L || score < 0) {
                throw new IllegalArgumentException("Daily record time and score must be non-negative");
            }
            this.bestTimeMs = bestTimeMs;
            this.score = score;
            this.replayBlob = replayBlob == null ? null : replayBlob.clone();
            this.updatedAt = updatedAt;
        }
    }

    /** Immutable, slot-validated Cosmetic loadout persisted locally in SharedPreferences. */
    public static final class CosmeticLoadout {
        public final String trailId;
        public final String frameId;
        public final String emblemId;

        public CosmeticLoadout(String trailId, String frameId, String emblemId) {
            this.trailId = Objects.requireNonNull(trailId, "trailId");
            this.frameId = Objects.requireNonNull(frameId, "frameId");
            this.emblemId = emblemId == null ? "" : emblemId;
        }

        public String forKind(int kind) {
            if (kind == ProgressRules.COSMETIC_TRAIL) return trailId;
            if (kind == ProgressRules.COSMETIC_FRAME) return frameId;
            if (kind == ProgressRules.COSMETIC_EMBLEM) return emblemId;
            throw new IllegalArgumentException("unknown Cosmetic slot");
        }

        public static CosmeticLoadout defaults() {
            return new CosmeticLoadout("trail.mint", "frame.none", "");
        }
    }

    /** Immutable snapshot of all persisted user settings. */
    public static final class Settings {
        public final boolean soundEnabled;
        public final boolean hapticsEnabled;
        public final boolean reducedMotion;
        public final boolean reducedEffects;
        public final boolean highContrast;
        public final String language;   // "fa" or "en"
        public final boolean leftHandedUi;
        public final int volumePercent;

        /** Backward-compatible constructor for older callers; volume defaults to 80%. */
        public Settings(boolean soundEnabled, boolean hapticsEnabled,
                        boolean reducedMotion, boolean reducedEffects,
                        boolean highContrast, String language,
                        boolean leftHandedUi) {
            this(soundEnabled, hapticsEnabled, reducedMotion, reducedEffects,
                    highContrast, language, leftHandedUi, 80);
        }

        public Settings(boolean soundEnabled, boolean hapticsEnabled,
                        boolean reducedMotion, boolean reducedEffects,
                        boolean highContrast, String language,
                        boolean leftHandedUi, int volumePercent) {
            this.soundEnabled = soundEnabled;
            this.hapticsEnabled = hapticsEnabled;
            this.reducedMotion = reducedMotion;
            this.reducedEffects = reducedEffects;
            this.highContrast = highContrast;
            this.language = language;
            this.leftHandedUi = leftHandedUi;
            if (volumePercent < 0 || volumePercent > 100) {
                throw new IllegalArgumentException("volumePercent must be in [0, 100]");
            }
            this.volumePercent = volumePercent;
        }
    }

    // =====================================================================
    // Settings (SharedPreferences — synchronous, no DB)
    // =====================================================================

    public Settings loadSettings() {
        return new Settings(
                prefs.getBoolean(KEY_SOUND, true),
                prefs.getBoolean(KEY_HAPTICS, true),
                prefs.getBoolean(KEY_REDUCED_MOTION, false),
                prefs.getBoolean(KEY_REDUCED_EFFECTS, false),
                prefs.getBoolean(KEY_HIGH_CONTRAST, false),
                normalizedLanguage(prefs.getString(KEY_LANGUAGE, "fa")),
                prefs.getBoolean(KEY_LEFT_HANDED, false),
                clampVolume(prefs.getInt(KEY_VOLUME_PERCENT, 80)));
    }

    private static int clampVolume(int volume) {
        return Math.max(0, Math.min(100, volume));
    }

    private static String normalizedLanguage(@Nullable String language) {
        return "en".equals(language) ? "en" : "fa";
    }

    public void saveSettings(@NonNull Settings s) {
        Objects.requireNonNull(s, "settings");
        if (!"fa".equals(s.language) && !"en".equals(s.language)) {
            throw new IllegalArgumentException("language must be 'fa' or 'en'");
        }
        prefs.edit()
                .putBoolean(KEY_SOUND, s.soundEnabled)
                .putBoolean(KEY_HAPTICS, s.hapticsEnabled)
                .putBoolean(KEY_REDUCED_MOTION, s.reducedMotion)
                .putBoolean(KEY_REDUCED_EFFECTS, s.reducedEffects)
                .putBoolean(KEY_HIGH_CONTRAST, s.highContrast)
                .putString(KEY_LANGUAGE, s.language)
                .putBoolean(KEY_LEFT_HANDED, s.leftHandedUi)
                .putInt(KEY_VOLUME_PERCENT, s.volumePercent)
                .apply();
    }

    public void resetSettings() {
        // A Settings reset must not silently unequip a Cosmetic loadout.
        prefs.edit().remove(KEY_SOUND).remove(KEY_HAPTICS)
                .remove(KEY_REDUCED_MOTION).remove(KEY_REDUCED_EFFECTS)
                .remove(KEY_HIGH_CONTRAST).remove(KEY_LANGUAGE)
                .remove(KEY_LEFT_HANDED).remove(KEY_VOLUME_PERCENT).apply();
    }

    private void resetCosmeticLoadout() {
        prefs.edit().remove(KEY_EQUIPPED_TRAIL).remove(KEY_EQUIPPED_FRAME)
                .remove(KEY_EQUIPPED_EMBLEM).apply();
    }

    /** Reads the active loadout; stale/unknown IDs safely fall back to slot defaults. */
    @NonNull
    public CosmeticLoadout loadCosmeticLoadout() {
        CosmeticLoadout defaults = CosmeticLoadout.defaults();
        String trail = validCosmeticId(prefs.getString(KEY_EQUIPPED_TRAIL, defaults.trailId),
                ProgressRules.COSMETIC_TRAIL) ? prefs.getString(KEY_EQUIPPED_TRAIL, defaults.trailId)
                : defaults.trailId;
        String frame = validCosmeticId(prefs.getString(KEY_EQUIPPED_FRAME, defaults.frameId),
                ProgressRules.COSMETIC_FRAME) ? prefs.getString(KEY_EQUIPPED_FRAME, defaults.frameId)
                : defaults.frameId;
        String emblem = prefs.getString(KEY_EQUIPPED_EMBLEM, "");
        if (!emblem.isEmpty() && !validCosmeticId(emblem, ProgressRules.COSMETIC_EMBLEM)) {
            emblem = "";
        }
        return new CosmeticLoadout(trail, frame, emblem);
    }

    /**
     * Saves a complete slot set atomically. The caller must only offer unlocked
     * items; this method independently rejects unknown IDs and cross-slot IDs.
     */
    public void saveCosmeticLoadout(@NonNull CosmeticLoadout loadout) {
        Objects.requireNonNull(loadout, "loadout");
        if (!validCosmeticId(loadout.trailId, ProgressRules.COSMETIC_TRAIL)
                || !validCosmeticId(loadout.frameId, ProgressRules.COSMETIC_FRAME)
                || (!loadout.emblemId.isEmpty()
                && !validCosmeticId(loadout.emblemId, ProgressRules.COSMETIC_EMBLEM))) {
            throw new IllegalArgumentException("Cosmetic loadout contains an unknown or wrong-slot item");
        }
        prefs.edit().putString(KEY_EQUIPPED_TRAIL, loadout.trailId)
                .putString(KEY_EQUIPPED_FRAME, loadout.frameId)
                .putString(KEY_EQUIPPED_EMBLEM, loadout.emblemId).apply();
    }

    private static boolean validCosmeticId(@Nullable String id, int expectedKind) {
        if (id == null || id.isEmpty()) return false;
        for (ProgressRules.CosmeticDef definition : ProgressRules.cosmetics()) {
            if (definition.id().equals(id)) return definition.kind() == expectedKind;
        }
        return false;
    }

    // =====================================================================
    // Profile
    // =====================================================================

    public void loadProfile(@NonNull Callback<Profile> callback) {
        enqueue(callback, this::loadProfileSync);
    }

    @Nullable
    private Profile loadProfileSync() {
        try (SQLiteDatabase db = dbHelper.getReadableDatabase();
             Cursor c = db.query("profile", null, null, null, null, null, "id ASC", "1")) {
            if (!c.moveToFirst()) {
                try { avatarPipeline.cleanupOrphans(null); }
                catch (IOException cleanup) { Log.w(TAG, "avatar orphan cleanup deferred", cleanup); }
                return null;
            }
            String photoPath = c.isNull(c.getColumnIndexOrThrow("photo_path"))
                    ? null : c.getString(c.getColumnIndexOrThrow("photo_path"));
            int avatarType = c.getInt(c.getColumnIndexOrThrow("avatar_type"));
            String assetId = c.isNull(c.getColumnIndexOrThrow("avatar_asset_id"))
                    ? null : c.getString(c.getColumnIndexOrThrow("avatar_asset_id"));
            if (avatarType == 1 && (photoPath == null
                    || !avatarPipeline.isManagedAvatarPath(photoPath)
                    || !new File(photoPath).isFile())) {
                avatarType = 0;
                photoPath = null;
                assetId = null;
            }
            String activePhotoPath = avatarType == 1 ? photoPath : null;
            try { avatarPipeline.cleanupOrphans(activePhotoPath); }
            catch (IOException cleanup) { Log.w(TAG, "avatar orphan cleanup deferred", cleanup); }
            return new Profile(c.getLong(c.getColumnIndexOrThrow("id")),
                    c.isNull(c.getColumnIndexOrThrow("display_name"))
                            ? null : c.getString(c.getColumnIndexOrThrow("display_name")),
                    avatarType, assetId, photoPath,
                    c.getLong(c.getColumnIndexOrThrow("created_at")),
                    c.getLong(c.getColumnIndexOrThrow("updated_at")));
        }
    }

    public void saveProfile(@NonNull String displayName,
                            int avatarType,
                            @Nullable String avatarAssetId,
                            @Nullable String photoPath,
                            @NonNull Callback<Void> callback) {
        final String safeName = Objects.requireNonNull(displayName, "displayName").trim();
        enqueue(callback, () -> {
            if (safeName.isEmpty()) throw new IllegalArgumentException("displayName must not be empty");
            if (avatarType != 0 && avatarType != 1) {
                throw new IllegalArgumentException("avatarType must be 0 (built-in) or 1 (photo)");
            }
            if (avatarType == 1 && (photoPath == null
                    || !avatarPipeline.isManagedAvatarPath(photoPath)
                    || !new File(photoPath).isFile())) {
                throw new IllegalArgumentException("photo avatar path is not a committed app-owned file");
            }
            if (avatarType == 0 && photoPath != null) {
                throw new IllegalArgumentException("built-in avatar must not carry a photo path");
            }
            String oldPhotoPath = null;
            long now = System.currentTimeMillis();
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                db.beginTransaction();
                try {
                    try (Cursor old = db.query("profile", new String[]{"photo_path"},
                            null, null, null, null, "id ASC", "1")) {
                        if (old.moveToFirst() && !old.isNull(0)) oldPhotoPath = old.getString(0);
                    }
                    ContentValues values = new ContentValues();
                    values.put("display_name", safeName);
                    values.put("avatar_type", avatarType);
                    if (avatarType == 1) values.putNull("avatar_asset_id");
                    else values.put("avatar_asset_id", avatarAssetId);
                    values.put("photo_path", photoPath);
                    values.put("updated_at", now);
                    long profileId;
                    try (Cursor row = db.query("profile", new String[]{"id"},
                            null, null, null, null, "id ASC", "1")) {
                        profileId = row.moveToFirst() ? row.getLong(0) : -1L;
                    }
                    if (profileId < 0L) {
                        values.put("created_at", now);
                        db.insertOrThrow("profile", null, values);
                    } else {
                        db.update("profile", values, "id = ?",
                                new String[]{String.valueOf(profileId)});
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Exception failure) {
                // Keep a newly imported avatar available for an immediate profile-save retry.
                // The Profile row remains unchanged because the SQLite transaction rolled back;
                // explicit discard or the seven-day orphan cleanup removes this unreferenced file.
                throw failure;
            }
            if (oldPhotoPath != null && !oldPhotoPath.equals(photoPath)) {
                try { avatarPipeline.delete(oldPhotoPath); }
                catch (IOException cleanup) { Log.w(TAG, "old avatar cleanup deferred", cleanup); }
            }
            return null;
        });
    }

    // =====================================================================
    // Level progress
    // =====================================================================

    public void loadAllLevelProgress(@NonNull Callback<Map<String, LevelProgress>> callback) {
        enqueue(callback, () -> {
            Map<String, LevelProgress> map = new HashMap<>();
            try (SQLiteDatabase db = dbHelper.getReadableDatabase();
                 Cursor c = db.query("level_progress", null, null, null, null, null, null)) {
                while (c.moveToNext()) {
                    LevelProgress progress = readProgress(c);
                    map.put(progress.levelId, progress);
                }
            }
            return Collections.unmodifiableMap(map);
        });
    }

    private static LevelProgress readProgress(Cursor c) {
        return new LevelProgress(
                c.getString(c.getColumnIndexOrThrow("level_id")),
                c.getInt(c.getColumnIndexOrThrow("unlocked")) != 0,
                c.getInt(c.getColumnIndexOrThrow("completed")) != 0,
                c.getInt(c.getColumnIndexOrThrow("stars")),
                c.getLong(c.getColumnIndexOrThrow("best_time_ms")),
                c.getInt(c.getColumnIndexOrThrow("best_score")),
                c.getInt(c.getColumnIndexOrThrow("attempts")),
                c.getInt(c.getColumnIndexOrThrow("completions")),
                c.getInt(c.getColumnIndexOrThrow("optional_mask")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")));
    }

    // =====================================================================
    // Atomic run recording — ProgressRules is the only progression authority
    // =====================================================================

    public void recordRun(@NonNull RunRecordInput input,
                          @NonNull Callback<RecordRunResult> callback) {
        enqueue(callback, () -> recordRunSync(input));
    }

    private RecordRunResult recordRunSync(RunRecordInput input) {
        Objects.requireNonNull(input, "input");
        Catalog catalog = input.catalog;
        ProgressRules.RunRecord run = input.progressRun;
        if (catalog.isEmpty()) {
            throw new IllegalArgumentException("LocalStore requires a non-empty Campaign Catalog");
        }
        LevelDefinition level = catalog.byId(run.levelId());
        ReplayTrace trace = input.trace;
        byte[] replayBlob = encodeValidatedTrace(trace, level, run);
        if (run.mode() == ReplayMode.DAILY_CHALLENGE) {
            String challengeDate = dateFromChallengeId(run.dailyChallengeId());
            if (trace != null && !challengeDate.equals(DailyChallenge.utcDate(trace.runTimestampMs()))) {
                throw new IllegalArgumentException("Daily Replay timestamp does not match the challenge UTC date");
            }
        }
        long now = System.currentTimeMillis();
        String payloadHash = payloadHash(run);

        try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
            db.beginTransaction();
            try {
                try (Cursor existing = db.query("run_records",
                        new String[]{"id", "payload_hash"}, "run_id = ?",
                        new String[]{run.runId()}, null, null, null)) {
                    if (existing.moveToFirst()) {
                        long existingRowId = existing.getLong(0);
                        String storedHash = existing.getString(1);
                        if (!payloadHash.equals(storedHash)) {
                            throw new SQLiteException(
                                    "runId was already committed with a different result payload");
                        }
                        ProgressRules.ProgressSnapshot current = readProgressSnapshot(db, catalog);
                        db.setTransactionSuccessful();
                        return new RecordRunResult(existingRowId,
                                Collections.<String>emptyList(),
                                Collections.<String>emptyList(), 0, 0, current);
                    }
                }
                ProgressRules.ProgressSnapshot prior = readProgressSnapshot(db, catalog);
                ProgressRules.ProgressDelta delta = ProgressRules.evaluate(catalog, prior, run);
                if (trace != null && run.mode() == ReplayMode.CAMPAIGN
                        && run.result() == RunResult.COMPLETED
                        && (trace.score() != delta.scoreAwarded()
                        || trace.stars() != delta.starsAwarded())) {
                    throw new IllegalArgumentException("Campaign Replay score/stars differ from ProgressRules");
                }
                int score = run.mode() == ReplayMode.CAMPAIGN
                        ? delta.scoreAwarded() : (trace == null ? 0 : trace.score());
                int stars = run.mode() == ReplayMode.CAMPAIGN
                        ? delta.starsAwarded() : (trace == null ? 0 : trace.stars());
                if (run.result() != RunResult.COMPLETED) {
                    score = 0;
                    stars = 0;
                }

                ContentValues runValues = new ContentValues();
                runValues.put("run_id", run.runId());
                runValues.put("payload_hash", payloadHash);
                runValues.put("mode", run.mode().name());
                runValues.put("level_id", run.levelId());
                runValues.put("result", run.result().name());
                runValues.put("elapsed_ms", run.elapsedMs());
                runValues.put("score", score);
                runValues.put("stars", stars);
                runValues.put("assisted", run.assisted() ? 1 : 0);
                runValues.put("replay_blob", replayBlob);
                runValues.put("replay_schema_version", trace == null ? 0 : trace.schemaVersion());
                runValues.put("level_revision", level.levelRevision());
                runValues.put("geometry_hash", level.geometryHash());
                runValues.put("created_at", now);
                long runId = db.insertOrThrow("run_records", null, runValues);

                persistProgressSnapshot(db, catalog, delta.snapshot(), now);
                ProgressRules.StatsView stats = ProgressRules.statsView(catalog, delta.snapshot());
                persistAchievements(db, catalog, delta.snapshot(), stats, now);
                if (run.mode() == ReplayMode.DAILY_CHALLENGE
                        && run.result() == RunResult.COMPLETED) {
                    persistDailyBest(db, run, level, trace, replayBlob, score, now);
                }
                pruneOldReplays(db, run.levelId(), run.mode());
                db.setTransactionSuccessful();
                return new RecordRunResult(runId,
                        new ArrayList<>(delta.newlyUnlockedLevelIds()),
                        new ArrayList<>(delta.newlyUnlockedAchievementIds()),
                        delta.scoreAwarded(), delta.starsAwarded(), delta.snapshot());
            } finally {
                db.endTransaction();
            }
        }
    }

    /** SHA-256 over the canonical run outcome; optional Replay bytes are deliberately excluded. */
    private static String payloadHash(ProgressRules.RunRecord run) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(run.runId());
                out.writeUTF(run.levelId());
                out.writeUTF(run.mode().name());
                out.writeUTF(run.result().name());
                out.writeLong(run.elapsedMs());
                out.writeBoolean(run.assisted());
                writeNullableUtf(out, run.dailyChallengeId());
                writeCanonicalSet(out, run.collectedObjectiveIds());
                writeCanonicalSet(out, run.collectedKeyIds());
                writeCanonicalSet(out, run.passedGateIds());
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            final char[] hex = "0123456789abcdef".toCharArray();
            char[] output = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                output[i * 2] = hex[value >>> 4];
                output[i * 2 + 1] = hex[value & 0x0f];
            }
            return new String(output);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode the run payload", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static void writeNullableUtf(DataOutputStream out, @Nullable String value)
            throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeUTF(value);
    }

    private static void writeCanonicalSet(DataOutputStream out, Set<String> values)
            throws IOException {
        ArrayList<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        out.writeInt(sorted.size());
        for (String value : sorted) out.writeUTF(value);
    }

    @Nullable
    private static byte[] encodeValidatedTrace(@Nullable ReplayTrace trace,
                                               LevelDefinition level,
                                               ProgressRules.RunRecord run) {
        if (trace == null) return null;
        if (!trace.levelId().equals(run.levelId())
                || trace.levelRevision() != level.levelRevision()
                || !trace.geometryHashHex().equalsIgnoreCase(level.geometryHash())
                || trace.mode() != run.mode() || trace.result() != run.result()
                || trace.assisted() != run.assisted()
                || trace.activeDurationMs() != run.elapsedMs()) {
            throw new IllegalArgumentException("ReplayTrace does not match the run/catalog contract");
        }
        byte[] encoded = ReplaySystem.ReplayCodec.encode(trace);
        if (encoded.length > ReplaySystem.MAX_BYTES) {
            throw new IllegalArgumentException("encoded ReplayTrace exceeds the storage limit");
        }
        return encoded;
    }

    private static ProgressRules.ProgressSnapshot readProgressSnapshot(SQLiteDatabase db,
                                                                          Catalog catalog) {
        Map<String, LevelProgress> stored = new HashMap<>();
        try (Cursor cursor = db.query("level_progress", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                LevelProgress value = readProgress(cursor);
                stored.put(value.levelId, value);
            }
        }

        Map<String, ProgressRules.LevelProgress> campaign = new TreeMap<>();
        Set<String> unlocked = new TreeSet<>();
        unlocked.add(catalog.get(0).levelId());
        int campaignCompletions = 0;
        int personalBestCount = 0;
        for (LevelDefinition level : catalog.all()) {
            LevelProgress raw = stored.get(level.levelId());
            CampaignAggregate aggregate = readCampaignAggregate(db, level.levelId());
            int fullMask = ProgressRules.fullOptionalMask(level);
            int optionalMask = raw == null ? 0 : raw.optionalMask & fullMask;
            int stars = aggregate.completed ? Math.max(1, Math.min(3, aggregate.stars)) : 0;
            campaign.put(level.levelId(), ProgressRules.LevelProgress.restore(
                    aggregate.completed, stars, aggregate.bestTimeMs,
                    aggregate.bestScore, aggregate.attempts, optionalMask,
                    fullMask, fullMask != 0));
            if (raw != null && raw.unlocked) unlocked.add(level.levelId());
            if (aggregate.completed) {
                unlocked.add(level.levelId());
                campaignCompletions = safeAdd(campaignCompletions, aggregate.completions);
                int index = indexOfLevel(catalog, level.levelId());
                if (index >= 0 && index + 1 < catalog.size()) {
                    unlocked.add(catalog.get(index + 1).levelId());
                }
            }
            if (aggregate.bestTimeMs > 0L) personalBestCount++;
        }

        Map<String, ProgressRules.ModeBest> modeRecords = readModeRecords(db, catalog);
        Set<String> completedDailyIds = new TreeSet<>();
        try (Cursor cursor = db.query("daily_records",
                new String[]{"challenge_id", "level_id", "best_time_ms"},
                "best_time_ms > 0", null, null, null, null)) {
            while (cursor.moveToNext()) {
                String challengeId = cursor.getString(0);
                String levelId = cursor.getString(1);
                if (DailyChallenge.hasValidChallengeIdShape(challengeId)
                        && catalog.contains(levelId)
                        && levelId.equals(DailyChallenge.levelIdFromChallengeId(challengeId))) {
                    completedDailyIds.add(challengeId);
                }
            }
        }

        Set<String> unlockedAchievements = new TreeSet<>();
        Set<String> gatePassed = new TreeSet<>();
        Set<String> keyCollected = new TreeSet<>();
        Map<String, Integer> achievementProgress = new HashMap<>();
        try (Cursor cursor = db.query("achievements",
                new String[]{"achievement_id", "unlocked_at", "progress_value"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String id = canonicalAchievementId(cursor.getString(0));
                if (id == null) continue;
                if (!cursor.isNull(1)) unlockedAchievements.add(id);
                int value = cursor.getInt(2);
                Integer previous = achievementProgress.get(id);
                if (previous == null || value > previous) achievementProgress.put(id, value);
                if (ProgressRules.ACH_GATE_RUNNER.equals(id) && value > 0) {
                    gatePassed.add(ProgressRules.LEVEL_03_ID);
                }
                if (ProgressRules.ACH_VAULT_FINDER.equals(id) && value > 0) {
                    keyCollected.add(ProgressRules.LEVEL_04_ID);
                }
            }
        }

        Set<String> defaultCosmetics = new TreeSet<>();
        for (ProgressRules.CosmeticDef cosmetic : ProgressRules.cosmetics()) {
            if (cosmetic.defaultUnlocked()) defaultCosmetics.add(cosmetic.id());
        }
        ProgressRules.ProgressSnapshot base = ProgressRules.ProgressSnapshot.restore(
                campaign, modeRecords, unlocked, completedDailyIds, gatePassed, keyCollected,
                unlockedAchievements, defaultCosmetics, campaignCompletions,
                completedDailyIds.size(), personalBestCount);
        ProgressRules.StatsView stats = ProgressRules.statsView(catalog, base);
        Set<String> allCosmetics = new TreeSet<>(defaultCosmetics);
        for (ProgressRules.CosmeticDef cosmetic : ProgressRules.cosmetics()) {
            if (ProgressRules.cosmeticUnlocked(cosmetic, stats)) allCosmetics.add(cosmetic.id());
        }
        return ProgressRules.ProgressSnapshot.restore(campaign, modeRecords, unlocked,
                completedDailyIds, gatePassed, keyCollected, unlockedAchievements,
                allCosmetics, campaignCompletions, completedDailyIds.size(), personalBestCount);
    }

    private static CampaignAggregate readCampaignAggregate(SQLiteDatabase db, String levelId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*), "
                        + "SUM(CASE WHEN UPPER(result) = 'COMPLETED' THEN 1 ELSE 0 END), "
                        + "MAX(CASE WHEN UPPER(result) = 'COMPLETED' THEN stars ELSE 0 END), "
                        + "MIN(CASE WHEN UPPER(result) = 'COMPLETED' AND assisted = 0 "
                        + "AND elapsed_ms > 0 THEN elapsed_ms END), "
                        + "MAX(CASE WHEN UPPER(result) = 'COMPLETED' AND assisted = 0 THEN score ELSE 0 END) "
                        + "FROM run_records WHERE level_id = ? AND UPPER(mode) = 'CAMPAIGN'",
                new String[]{levelId})) {
            if (!cursor.moveToFirst()) return new CampaignAggregate(0, 0, 0, 0L, 0);
            int attempts = cursor.getInt(0);
            int completions = cursor.isNull(1) ? 0 : cursor.getInt(1);
            int stars = cursor.isNull(2) ? 0 : cursor.getInt(2);
            long bestTime = cursor.isNull(3) ? 0L : cursor.getLong(3);
            int bestScore = cursor.isNull(4) ? 0 : cursor.getInt(4);
            return new CampaignAggregate(attempts, completions, stars, bestTime, bestScore);
        }
    }

    private static Map<String, ProgressRules.ModeBest> readModeRecords(SQLiteDatabase db,
                                                                         Catalog catalog) {
        Map<String, ProgressRules.ModeBest> records = new TreeMap<>();
        String[] modes = {ReplayMode.TIME_ATTACK.name(), ReplayMode.GHOST_RACE.name()};
        try (Cursor cursor = db.rawQuery(
                "SELECT level_id, mode, MIN(elapsed_ms), COUNT(*) FROM run_records "
                        + "WHERE UPPER(result) = 'COMPLETED' "
                        + "AND UPPER(mode) IN (?, ?) GROUP BY level_id, UPPER(mode)",
                modes)) {
            while (cursor.moveToNext()) {
                String levelId = cursor.getString(0);
                ReplayMode mode = ReplayMode.valueOf(cursor.getString(1));
                if (!catalog.contains(levelId)) continue;
                String key = modeRecordKey(levelId, mode, "");
                records.put(key, ProgressRules.ModeBest.restore(
                        cursor.isNull(2) ? 0L : cursor.getLong(2), cursor.getInt(3)));
            }
        }
        try (Cursor cursor = db.query("daily_records",
                new String[]{"level_id", "challenge_id", "best_time_ms"},
                "best_time_ms > 0", null, null, null, null)) {
            while (cursor.moveToNext()) {
                String levelId = cursor.getString(0);
                String challengeId = cursor.getString(1);
                if (!catalog.contains(levelId)
                        || !DailyChallenge.hasValidChallengeIdShape(challengeId)
                        || !levelId.equals(DailyChallenge.levelIdFromChallengeId(challengeId))) {
                    continue;
                }
                records.put(modeRecordKey(levelId, ReplayMode.DAILY_CHALLENGE, challengeId),
                        ProgressRules.ModeBest.restore(cursor.getLong(2), 1));
            }
        }
        return records;
    }

    private static String modeRecordKey(String levelId, ReplayMode mode, String dailyChallengeId) {
        String challenge = mode == ReplayMode.DAILY_CHALLENGE ? dailyChallengeId : "";
        return levelId + "|" + mode.code() + "|" + challenge;
    }

    private static void persistProgressSnapshot(SQLiteDatabase db, Catalog catalog,
                                                ProgressRules.ProgressSnapshot snapshot,
                                                long now) {
        for (LevelDefinition level : catalog.all()) {
            ProgressRules.LevelProgress progress = snapshot.level(level.levelId());
            if (progress == null) {
                progress = ProgressRules.LevelProgress.restore(false, 0, 0L, 0, 0,
                        0, ProgressRules.fullOptionalMask(level),
                        ProgressRules.fullOptionalMask(level) != 0);
            }
            int completions = scalarInt(db,
                    "SELECT COUNT(*) FROM run_records WHERE level_id = ? "
                            + "AND UPPER(mode) = 'CAMPAIGN' AND UPPER(result) = 'COMPLETED'",
                    new String[]{level.levelId()});
            ContentValues values = new ContentValues();
            values.put("level_id", level.levelId());
            values.put("unlocked", snapshot.isLevelUnlocked(level.levelId()) ? 1 : 0);
            values.put("completed", progress.completed() ? 1 : 0);
            values.put("stars", progress.stars());
            values.put("best_time_ms", progress.bestUnassistedTimeMs());
            values.put("best_score", progress.bestUnassistedScore());
            values.put("attempts", progress.attempts());
            values.put("completions", completions);
            values.put("optional_mask", progress.optionalMask());
            values.put("updated_at", now);
            db.insertWithOnConflict("level_progress", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static void persistAchievements(SQLiteDatabase db, Catalog catalog,
                                            ProgressRules.ProgressSnapshot snapshot,
                                            ProgressRules.StatsView stats, long now) {
        for (ProgressRules.AchievementDef definition : ProgressRules.achievements()) {
            String id = definition.id();
            Long previousUnlockedAt = readAchievementUnlockedAt(db, id);
            boolean unlocked = snapshot.isAchievementUnlocked(id);
            Long unlockedAt = previousUnlockedAt;
            if (unlocked && unlockedAt == null) unlockedAt = now;
            int progress = ProgressRules.achievementProgress(id, stats, catalog);
            if (ProgressRules.ACH_GATE_RUNNER.equals(id)
                    && snapshot.gatePassedLevelIds().contains(ProgressRules.LEVEL_03_ID)) {
                progress = Math.max(progress, 1);
            }
            if (ProgressRules.ACH_VAULT_FINDER.equals(id)
                    && snapshot.keyCollectedLevelIds().contains(ProgressRules.LEVEL_04_ID)) {
                progress = Math.max(progress, 1);
            }
            ContentValues values = new ContentValues();
            values.put("achievement_id", id);
            if (unlockedAt == null) values.putNull("unlocked_at");
            else values.put("unlocked_at", unlockedAt);
            values.put("progress_value", progress);
            db.insertWithOnConflict("achievements", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    @Nullable
    private static Long readAchievementUnlockedAt(SQLiteDatabase db, String canonicalId) {
        Long earliest = null;
        try (Cursor cursor = db.query("achievements", new String[]{"unlocked_at"},
                "achievement_id = ? OR achievement_id = ?", legacyAchievementArgs(canonicalId),
                null, null, null)) {
            while (cursor.moveToNext()) {
                if (cursor.isNull(0)) continue;
                long value = cursor.getLong(0);
                if (earliest == null || value < earliest) earliest = value;
            }
        }
        return earliest;
    }

    private static String[] legacyAchievementArgs(String canonicalId) {
        return new String[]{canonicalId, legacyAchievementId(canonicalId)};
    }

    private static String legacyAchievementId(String canonicalId) {
        switch (canonicalId) {
            case ProgressRules.ACH_FIRST_TRACE: return "first-trace";
            case ProgressRules.ACH_PRECISION_LEARNER: return "precision-learner";
            case ProgressRules.ACH_GATE_RUNNER: return "gate-runner";
            case ProgressRules.ACH_VAULT_FINDER: return "vault-finder";
            case ProgressRules.ACH_CORE_COMPLETE: return "core-complete";
            case ProgressRules.ACH_OBJECTIVE_HUNTER: return "objective-hunter";
            case ProgressRules.ACH_PERSONAL_BEST: return "personal-best";
            case ProgressRules.ACH_DAILY_SPARK: return "daily-spark";
            default: return canonicalId;
        }
    }

    @Nullable
    private static String canonicalAchievementId(String storedId) {
        if (storedId == null) return null;
        if (storedId.indexOf('-') < 0) return storedId;
        if ("first-trace".equals(storedId)) return ProgressRules.ACH_FIRST_TRACE;
        if ("precision-learner".equals(storedId)) return ProgressRules.ACH_PRECISION_LEARNER;
        if ("gate-runner".equals(storedId)) return ProgressRules.ACH_GATE_RUNNER;
        if ("vault-finder".equals(storedId)) return ProgressRules.ACH_VAULT_FINDER;
        if ("core-complete".equals(storedId)) return ProgressRules.ACH_CORE_COMPLETE;
        if ("objective-hunter".equals(storedId)) return ProgressRules.ACH_OBJECTIVE_HUNTER;
        if ("personal-best".equals(storedId)) return ProgressRules.ACH_PERSONAL_BEST;
        if ("daily-spark".equals(storedId)) return ProgressRules.ACH_DAILY_SPARK;
        return storedId;
    }

    private static int scalarInt(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static int safeAdd(int left, int right) {
        long value = (long) left + (long) right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int indexOfLevel(Catalog catalog, String levelId) {
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).levelId().equals(levelId)) return i;
        }
        return -1;
    }

    private static final class CampaignAggregate {
        final int attempts;
        final int completions;
        final boolean completed;
        final int stars;
        final long bestTimeMs;
        final int bestScore;

        CampaignAggregate(int attempts, int completions, int stars,
                          long bestTimeMs, int bestScore) {
            this.attempts = attempts;
            this.completions = completions;
            this.completed = completions > 0;
            this.stars = stars;
            this.bestTimeMs = bestTimeMs;
            this.bestScore = bestScore;
        }
    }

    private static void persistDailyBest(SQLiteDatabase db, ProgressRules.RunRecord run,
                                         LevelDefinition level, @Nullable ReplayTrace trace,
                                         @Nullable byte[] replayBlob, int score, long now) {
        String challengeId = run.dailyChallengeId();
        String date = dateFromChallengeId(challengeId);
        DailyChallenge.canonicalUtcDate(date);
        long oldTime = 0L;
        String oldChallenge = null;
        try (Cursor cursor = db.query("daily_records",
                new String[]{"challenge_id", "best_time_ms"},
                "challenge_date_utc = ?", new String[]{date}, null, null, null)) {
            if (cursor.moveToFirst()) {
                oldChallenge = cursor.getString(0);
                oldTime = cursor.getLong(1);
            }
        }
        if (challengeId.equals(oldChallenge) && oldTime > 0L && oldTime <= run.elapsedMs()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("challenge_date_utc", date);
        values.put("challenge_id", challengeId);
        values.put("level_id", level.levelId());
        values.put("best_time_ms", run.elapsedMs());
        values.put("score", score);
        values.put("replay_blob", replayBlob);
        values.put("updated_at", now);
        db.insertWithOnConflict("daily_records", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String dateFromChallengeId(String challengeId) {
        if (!DailyChallenge.hasValidChallengeIdShape(challengeId)) {
            throw new IllegalArgumentException("invalid Daily Challenge ID");
        }
        int versionEnd = challengeId.indexOf('-', 6);
        return challengeId.substring(versionEnd + 1, versionEnd + 11);
    }

    private static void pruneOldReplays(SQLiteDatabase db, String levelId, ReplayMode mode) {
        long protectedBestId = -1L;
        if (mode == ReplayMode.CAMPAIGN || mode == ReplayMode.TIME_ATTACK
                || mode == ReplayMode.GHOST_RACE) {
            String sql = "SELECT id FROM run_records WHERE level_id = ? AND UPPER(mode) = ? "
                    + "AND UPPER(result) = 'COMPLETED' AND replay_blob IS NOT NULL "
                    + (mode == ReplayMode.CAMPAIGN ? "AND assisted = 0 " : "")
                    + "ORDER BY elapsed_ms ASC, created_at ASC, id ASC LIMIT 1";
            try (Cursor best = db.rawQuery(sql, new String[]{levelId, mode.name()})) {
                if (best.moveToFirst()) protectedBestId = best.getLong(0);
            }
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT id FROM run_records WHERE level_id = ? AND UPPER(mode) = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT -1 OFFSET "
                        + MAX_RUN_RECORDS_PER_LEVEL_MODE,
                new String[]{levelId, mode.name()})) {
            List<Long> pruneIds = new ArrayList<>();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                if (id != protectedBestId) pruneIds.add(id);
            }
            ContentValues clear = new ContentValues();
            clear.putNull("replay_blob");
            for (Long id : pruneIds) {
                db.update("run_records", clear, "id = ?",
                        new String[]{String.valueOf(id)});
            }
        }
    }

    /** Input to {@link #recordRun}; Catalog and Engine outcome are immutable Core values. */
    public static final class RunRecordInput {
        public final Catalog catalog;
        public final ProgressRules.RunRecord progressRun;
        @Nullable public final ReplayTrace trace;

        public RunRecordInput(@NonNull Catalog catalog,
                              @NonNull ProgressRules.RunRecord progressRun,
                              @Nullable ReplayTrace trace) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            this.progressRun = Objects.requireNonNull(progressRun, "progressRun");
            this.trace = trace;
        }

        public static RunRecordInput fromOutcome(@NonNull Catalog catalog,
                                                 @NonNull String runId,
                                                 @NonNull LevelDefinition level,
                                                 @NonNull GameEngine.RunOutcome outcome,
                                                 @Nullable Set<String> observedPassedGateIds,
                                                 @Nullable String dailyChallengeId) {
            ProgressRules.RunRecord record = ProgressRules.RunRecord.fromOutcome(
                    runId, level, outcome,
                    observedPassedGateIds == null ? Collections.<String>emptySet()
                            : observedPassedGateIds,
                    dailyChallengeId);
            return new RunRecordInput(catalog, record, outcome.trace());
        }
    }

    /** Result of a committed run and its authoritative progression delta. */
    public static final class RecordRunResult {
        public final long runId;
        public final List<String> newlyUnlockedLevels;
        public final List<String> newlyEarnedAchievements;
        public final int scoreAwarded;
        public final int starsAwarded;
        public final ProgressRules.ProgressSnapshot progressSnapshot;

        public RecordRunResult(long runId, List<String> newlyUnlockedLevels,
                               List<String> newlyEarnedAchievements, int scoreAwarded,
                               int starsAwarded, ProgressRules.ProgressSnapshot snapshot) {
            this.runId = runId;
            this.newlyUnlockedLevels = Collections.unmodifiableList(
                    new ArrayList<>(newlyUnlockedLevels));
            this.newlyEarnedAchievements = Collections.unmodifiableList(
                    new ArrayList<>(newlyEarnedAchievements));
            this.scoreAwarded = scoreAwarded;
            this.starsAwarded = starsAwarded;
            this.progressSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    // =====================================================================
    // Achievements read API
    // =====================================================================

    public void loadAchievements(@NonNull Callback<List<Achievement>> callback) {
        enqueue(callback, () -> {
            Map<String, Achievement> byId = new TreeMap<>();
            try (SQLiteDatabase db = dbHelper.getReadableDatabase();
                 Cursor cursor = db.query("achievements", null, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    String canonical = canonicalAchievementId(
                            cursor.getString(cursor.getColumnIndexOrThrow("achievement_id")));
                    if (canonical == null) continue;
                    Long unlockedAt = cursor.isNull(cursor.getColumnIndexOrThrow("unlocked_at"))
                            ? null : cursor.getLong(cursor.getColumnIndexOrThrow("unlocked_at"));
                    int progress = cursor.getInt(cursor.getColumnIndexOrThrow("progress_value"));
                    Achievement previous = byId.get(canonical);
                    Long earliest = previous == null ? unlockedAt : previous.unlockedAt;
                    if (unlockedAt != null && (earliest == null || unlockedAt < earliest)) {
                        earliest = unlockedAt;
                    }
                    int maxProgress = previous == null ? progress
                            : Math.max(previous.progressValue, progress);
                    byId.put(canonical, new Achievement(canonical, earliest, maxProgress));
                }
            }
            for (ProgressRules.AchievementDef definition : ProgressRules.achievements()) {
                if (!byId.containsKey(definition.id())) {
                    byId.put(definition.id(), new Achievement(definition.id(), null, 0));
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(byId.values()));
        });
    }

    // =====================================================================
    // Daily records
    // =====================================================================

    public void loadDailyRecord(@NonNull String utcDate,
                                @NonNull Callback<DailyRecord> callback) {
        enqueue(callback, () -> {
            String canonicalDate = DailyChallenge.canonicalUtcDate(utcDate);
            try (SQLiteDatabase db = dbHelper.getReadableDatabase();
                 Cursor cursor = db.query("daily_records", null,
                         "challenge_date_utc = ?", new String[]{canonicalDate},
                         null, null, null)) {
                if (!cursor.moveToFirst()) return null;
                String challengeId = cursor.getString(cursor.getColumnIndexOrThrow("challenge_id"));
                String levelId = cursor.getString(cursor.getColumnIndexOrThrow("level_id"));
                long bestTime = cursor.getLong(cursor.getColumnIndexOrThrow("best_time_ms"));
                int score = cursor.getInt(cursor.getColumnIndexOrThrow("score"));
                byte[] blob = cursor.isNull(cursor.getColumnIndexOrThrow("replay_blob"))
                        ? null : cursor.getBlob(cursor.getColumnIndexOrThrow("replay_blob"));
                if (!DailyChallenge.hasValidChallengeIdShape(challengeId)
                        || !canonicalDate.equals(dateFromChallengeId(challengeId))
                        || !levelId.equals(DailyChallenge.levelIdFromChallengeId(challengeId))) {
                    throw new IOException("stored Daily record identity is inconsistent");
                }
                if (blob != null) {
                    ReplayTrace trace = ReplaySystem.ReplayCodec.decode(blob);
                    if (trace == null || trace.mode() != ReplayMode.DAILY_CHALLENGE
                            || trace.result() != RunResult.COMPLETED
                            || !trace.levelId().equals(levelId)
                            || trace.activeDurationMs() != bestTime
                            || trace.score() != score) blob = null;
                }
                return new DailyRecord(canonicalDate, challengeId, levelId, bestTime,
                        score, blob, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
            }
        });
    }

    public void saveDailyRecord(@NonNull DailyRecord record,
                                @NonNull Callback<Void> callback) {
        enqueue(callback, () -> {
            String date = DailyChallenge.canonicalUtcDate(record.challengeDateUtc);
            if (!DailyChallenge.hasValidChallengeIdShape(record.challengeId)
                    || !date.equals(dateFromChallengeId(record.challengeId))
                    || !record.levelId.equals(DailyChallenge.levelIdFromChallengeId(record.challengeId))
                    || record.bestTimeMs <= 0L) {
                throw new IllegalArgumentException("Daily record does not match its canonical challenge identity");
            }
            if (record.replayBlob != null) {
                ReplayTrace trace = ReplaySystem.ReplayCodec.decode(record.replayBlob);
                if (trace == null || trace.mode() != ReplayMode.DAILY_CHALLENGE
                        || trace.result() != RunResult.COMPLETED
                        || !trace.levelId().equals(record.levelId)
                        || trace.activeDurationMs() != record.bestTimeMs
                        || trace.score() != record.score) {
                    throw new IllegalArgumentException("Daily replay blob is invalid or incompatible");
                }
            }
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                db.beginTransaction();
                try {
                    long previousTime = 0L;
                    String previousId = null;
                    try (Cursor previous = db.query("daily_records",
                            new String[]{"challenge_id", "best_time_ms"},
                            "challenge_date_utc = ?", new String[]{date}, null, null, null)) {
                        if (previous.moveToFirst()) {
                            previousId = previous.getString(0);
                            previousTime = previous.getLong(1);
                        }
                    }
                    if (record.challengeId.equals(previousId)
                            && previousTime > 0L && previousTime <= record.bestTimeMs) {
                        db.setTransactionSuccessful();
                        return null;
                    }
                    ContentValues values = new ContentValues();
                    values.put("challenge_date_utc", date);
                    values.put("challenge_id", record.challengeId);
                    values.put("level_id", record.levelId);
                    values.put("best_time_ms", record.bestTimeMs);
                    values.put("score", record.score);
                    values.put("replay_blob", record.replayBlob);
                    values.put("updated_at", System.currentTimeMillis());
                    db.insertWithOnConflict("daily_records", null, values,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            return null;
        });
    }

    // =====================================================================
    // Replay access for Ghost Race and Path Review
    // =====================================================================

    public void loadBestReplay(@NonNull String levelId, @NonNull String mode,
                               int levelRevision, @NonNull String geometryHash,
                               @NonNull Callback<byte[]> callback) {
        enqueue(callback, () -> {
            ReplayMode replayMode;
            try { replayMode = ReplayMode.valueOf(mode); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("unknown replay mode", invalid); }
            if (replayMode != ReplayMode.CAMPAIGN && replayMode != ReplayMode.TIME_ATTACK
                    && replayMode != ReplayMode.GHOST_RACE) {
                return null;
            }
            String selection = "level_id = ? AND UPPER(mode) = ? AND UPPER(result) = 'COMPLETED' "
                    + "AND level_revision = ? AND geometry_hash = ? AND replay_blob IS NOT NULL"
                    + (replayMode == ReplayMode.CAMPAIGN ? " AND assisted = 0" : "");
            try (SQLiteDatabase db = dbHelper.getReadableDatabase();
                 Cursor cursor = db.query("run_records",
                         new String[]{"replay_blob", "elapsed_ms", "score", "stars",
                                 "assisted", "replay_schema_version"},
                         selection, new String[]{levelId, replayMode.name(),
                                 String.valueOf(levelRevision), geometryHash},
                         null, null, "elapsed_ms ASC, created_at ASC", null)) {
                while (cursor.moveToNext()) {
                    byte[] blob = cursor.getBlob(0);
                    ReplayTrace trace = ReplaySystem.ReplayCodec.decode(blob);
                    if (trace == null || trace.result() != RunResult.COMPLETED
                            || trace.mode() != replayMode || !trace.levelId().equals(levelId)
                            || trace.levelRevision() != levelRevision
                            || !trace.geometryHashHex().equalsIgnoreCase(geometryHash)
                            || trace.activeDurationMs() != cursor.getLong(1)
                            || trace.score() != cursor.getInt(2)
                            || trace.stars() != cursor.getInt(3)
                            || trace.assisted() != (cursor.getInt(4) != 0)
                            || trace.schemaVersion() != cursor.getInt(5)) continue;
                    return blob.clone();
                }
            }
            return null;
        });
    }

    // =====================================================================
    // Photo avatar — processing is delegated to the dedicated pipeline
    // =====================================================================

    public void savePhotoAvatar(@NonNull Uri sourceUri,
                                @NonNull Callback<String> callback) {
        enqueue(callback, () -> avatarPipeline.save(sourceUri).absolutePath());
    }

    /** Removes a newly imported avatar when the profile editor is cancelled before saveProfile. */
    public void discardPhotoAvatar(@NonNull String photoPath,
                                   @NonNull Callback<Void> callback) {
        enqueue(callback, () -> {
            Profile current = loadProfileSync();
            if (current != null && photoPath.equals(current.photoPath)) {
                throw new IllegalStateException("cannot discard the avatar currently used by the profile");
            }
            avatarPipeline.delete(photoPath);
            return null;
        });
    }

    /** Switches the active profile to its built-in avatar, then removes the old photo. */
    public void deletePhotoAvatar(@NonNull Callback<Void> callback) {
        enqueue(callback, () -> {
            String oldPath = null;
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                db.beginTransaction();
                try {
                    try (Cursor cursor = db.query("profile", new String[]{"photo_path"},
                            null, null, null, null, "id ASC", "1")) {
                        if (cursor.moveToFirst() && !cursor.isNull(0)) oldPath = cursor.getString(0);
                    }
                    ContentValues values = new ContentValues();
                    values.put("avatar_type", 0);
                    values.putNull("photo_path");
                    values.putNull("avatar_asset_id");
                    values.put("updated_at", System.currentTimeMillis());
                    db.update("profile", values, null, null);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            if (oldPath != null) {
                try { avatarPipeline.delete(oldPath); }
                catch (IOException cleanup) {
                    Log.w(TAG, "inactive avatar cleanup deferred", cleanup);
                }
            }
            return null;
        });
    }

    // =====================================================================
    // Explicit reset operations — no reset is performed automatically
    // =====================================================================

    public void resetProgress(@NonNull Callback<Void> callback) {
        enqueue(callback, () -> {
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                db.beginTransaction();
                try {
                    db.delete("level_progress", null, null);
                    db.delete("run_records", null, null);
                    db.delete("achievements", null, null);
                    db.delete("daily_records", null, null);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            resetCosmeticLoadout();
            return null;
        });
    }

    public void resetProfile(@NonNull Callback<Void> callback) {
        enqueue(callback, () -> {
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                db.beginTransaction();
                try {
                    db.delete("profile", null, null);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            avatarPipeline.deleteAll();
            return null;
        });
    }

    public void resetAll(@NonNull Callback<Void> callback) {
        enqueue(callback, () -> {
            try (SQLiteDatabase db = dbHelper.getWritableDatabase()) {
                db.beginTransaction();
                try {
                    db.delete("profile", null, null);
                    db.delete("level_progress", null, null);
                    db.delete("run_records", null, null);
                    db.delete("achievements", null, null);
                    db.delete("daily_records", null, null);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            prefs.edit().clear().apply();
            avatarPipeline.deleteAll();
            return null;
        });
    }

    // =====================================================================
    // SQLiteOpenHelper
    // =====================================================================

    private static final class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE profile ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "display_name TEXT,"
                    + "avatar_type INTEGER NOT NULL DEFAULT 0,"
                    + "avatar_asset_id TEXT,"
                    + "photo_path TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE level_progress ("
                    + "level_id TEXT PRIMARY KEY,"
                    + "unlocked INTEGER NOT NULL DEFAULT 0,"
                    + "completed INTEGER NOT NULL DEFAULT 0,"
                    + "stars INTEGER NOT NULL DEFAULT 0,"
                    + "best_time_ms INTEGER NOT NULL DEFAULT 0,"
                    + "best_score INTEGER NOT NULL DEFAULT 0,"
                    + "attempts INTEGER NOT NULL DEFAULT 0,"
                    + "completions INTEGER NOT NULL DEFAULT 0,"
                    + "optional_mask INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE run_records ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "run_id TEXT NOT NULL,"
                    + "payload_hash TEXT NOT NULL,"
                    + "mode TEXT NOT NULL,"
                    + "level_id TEXT NOT NULL,"
                    + "result TEXT NOT NULL,"
                    + "elapsed_ms INTEGER NOT NULL,"
                    + "score INTEGER NOT NULL,"
                    + "stars INTEGER NOT NULL,"
                    + "assisted INTEGER NOT NULL DEFAULT 0,"
                    + "replay_blob BLOB,"
                    + "replay_schema_version INTEGER NOT NULL DEFAULT 0,"
                    + "level_revision INTEGER NOT NULL DEFAULT 0,"
                    + "geometry_hash TEXT,"
                    + "created_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX idx_run_records_level_mode "
                    + "ON run_records(level_id, mode, created_at DESC)");
            db.execSQL("CREATE UNIQUE INDEX idx_run_records_run_id "
                    + "ON run_records(run_id)");

            db.execSQL("CREATE TABLE achievements ("
                    + "achievement_id TEXT PRIMARY KEY,"
                    + "unlocked_at INTEGER,"
                    + "progress_value INTEGER NOT NULL DEFAULT 0)");

            db.execSQL("CREATE TABLE daily_records ("
                    + "challenge_date_utc TEXT PRIMARY KEY,"
                    + "challenge_id TEXT NOT NULL,"
                    + "level_id TEXT NOT NULL,"
                    + "best_time_ms INTEGER NOT NULL DEFAULT 0,"
                    + "score INTEGER NOT NULL DEFAULT 0,"
                    + "replay_blob BLOB,"
                    + "updated_at INTEGER NOT NULL)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            int version = oldVersion;
            if (version == 1 && newVersion >= 2) {
                // Preserve every historical row. Legacy rows receive distinct IDs
                // that cannot be mistaken for a newly generated app Run UUID.
                db.execSQL("ALTER TABLE run_records ADD COLUMN run_id TEXT");
                db.execSQL("ALTER TABLE run_records ADD COLUMN payload_hash TEXT");
                db.execSQL("UPDATE run_records SET run_id = 'legacy:' || id "
                        + "WHERE run_id IS NULL OR run_id = ''");
                db.execSQL("UPDATE run_records SET payload_hash = 'legacy:' || id "
                        + "WHERE payload_hash IS NULL OR payload_hash = ''");
                db.execSQL("CREATE UNIQUE INDEX idx_run_records_run_id "
                        + "ON run_records(run_id)");
                version = 2;
            }
            if (version != newVersion) {
                throw new SQLiteException("No approved migration path from schema "
                        + oldVersion + " to " + newVersion);
            }
        }
    }
}