package com.neonmaze.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Audio, haptics, and reduced-motion/reduced-effects signaling for
 * NEON MAZE.
 *
 * <h2>Scope</h2>
 * <ul>
 *   <li>Procedural short SFX generated at construction time; no audio
 *       assets, no decode, no file I/O at event time.</li>
 *   <li>Haptics with per-event cooldown and a strict near-boundary
 *       rate limit.</li>
 *   <li>Reduced-motion and reduced-effects flags are exposed as getters
 *       for {@code GameView} and {@code ScreenUI} to read.</li>
 *   <li>Audio focus is respected via
 *       {@link AudioManager#AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK}. Focus
 *       loss stops all audio without crashing the game.</li>
 * </ul>
 *
 * <h2>Event model</h2>
 * <p>{@code GameView} calls one of the explicit event methods at the
 * moment the corresponding gameplay event occurs:</p>
 * <ul>
 *   <li>{@link #onStart()} — run begins.</li>
 *   <li>{@link #onObjectiveCollected()} — objective or key collected.</li>
 *   <li>{@link #onGateBlocked()} — player pressed against a closed or
 *       locked gate.</li>
 *   <li>{@link #onNearBoundary()} — player is close to a forbidden
 *       boundary. Rate-limited to avoid per-frame feedback.</li>
 *   <li>{@link #onFailure()} — run failed.</li>
 *   <li>{@link #onSuccess()} — run completed.</li>
 *   <li>{@link #onPause()} / {@link #onResume()} — gameplay pause events
 *       (distinct from lifecycle).</li>
 *   <li>{@link #onInterrupt()} — OS-level interruption.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #pauseAll()} stops every active {@link AudioTrack}, cancels any
 * in-flight haptic effect, and abandons audio focus. It must be called from
 * the host activity's {@code onPause}. {@link #resumeAll()} only rearms the
 * lazy focus state; it does not request focus until the next audible event.
 * {@link #release()} must be called from {@code onDestroy}.</p>
 *
 * <h2>Procedural SFX</h2>
 * <p>Each SFX is a mono 16-bit PCM buffer at
 * {@link #SAMPLE_RATE_HZ}. Buffers are generated once in the constructor
 * with a short attack/release envelope to avoid clicks. The device volume
 * controls final loudness; the app applies a modest
 * {@link #MASTER_AMPLITUDE} to prevent clipping when several sounds are
 * mixed by the system.</p>
 *
 * <h2>Threading</h2>
 * <p>Every public method is safe to call from the main thread. There is no
 * background thread: {@link AudioTrack#play()} and {@link Vibrator#vibrate}
 * are fast binder or audio-hal calls and never block on decode or I/O.
 * {@link AudioTrack#release()} is also called from the main thread.</p>
 *
 * <h2>Procedural audio</h2>
 * <p>The five-level campaign, tutorial, and all modes use one deterministic
 * procedural SFX palette. Optional visual content is resolved separately
 * and never enters this audio subsystem.</p>
 */
public final class FeedbackSystem {

    private static final String TAG = "FeedbackSystem";

    // ---------------------------------------------------------------------
    // Audio constants
    // ---------------------------------------------------------------------

    /** Mono 16-bit PCM; 22050 Hz is enough for our tones and halves memory. */
    private static final int SAMPLE_RATE_HZ = 22_050;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final double MASTER_AMPLITUDE = 0.35;
    private static final int ATTACK_MS = 5;
    private static final int RELEASE_MS = 10;

    // ---------------------------------------------------------------------
    // Cooldowns (milliseconds)
    // ---------------------------------------------------------------------

    private static final long NEAR_BOUNDARY_COOLDOWN_MS = 400L;
    private static final long GATE_BLOCKED_COOLDOWN_MS = 250L;
    private static final long OBJECTIVE_COOLDOWN_MS = 50L;
    private static final long PAUSE_RESUME_COOLDOWN_MS = 100L;

    // ---------------------------------------------------------------------
    // Haptic patterns
    // ---------------------------------------------------------------------

    private static final long[] BLOCKED_PATTERN = {0L, 30L, 40L, 30L};
    private static final long[] SUCCESS_PATTERN = {0L, 40L, 60L, 40L, 60L, 80L};
    private static final long[] FAILURE_PATTERN = {0L, 100L};

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private enum FocusState {
        NOT_REQUESTED,
        HELD,
        UNAVAILABLE,
        PAUSED,
        RELEASED
    }

    @NonNull private LocalStore.Settings settings;

    @Nullable private final AudioManager audioManager;
    @Nullable private final Object focusRequest;
    @NonNull private final AudioManager.OnAudioFocusChangeListener focusChangeListener;
    @NonNull private final Handler callbackHandler;

    /** Event key → prepared AudioTrack. Never modified after construction. */
    @NonNull private final Map<String, AudioTrack> sfxTracks;
    /** Track → mono PCM frame count used for its completion marker. */
    @NonNull private final Map<AudioTrack, Integer> trackFrameCounts;
    /** Tracks whose current playback has not yet reached its completion marker. */
    @NonNull private final Set<AudioTrack> activeTracks;

    @NonNull private final AudioTrack.OnPlaybackPositionUpdateListener playbackListener;

    @Nullable private final Vibrator vibrator;
    private final boolean vibrationAvailable;

    /** Event key → last uptimeMillis at which the event fired. */
    @NonNull private final Map<String, Long> lastTriggered;

    private FocusState focusState = FocusState.NOT_REQUESTED;
    /** True while a granted/transiently-lost focus request still has a listener registered. */
    private boolean focusRequestOutstanding;
    private boolean lifecyclePaused;
    private boolean released;
    /** Temporary slider preview; -1 means use the persisted settings value. */
    private int previewVolumePercent = -1;

    // =====================================================================
    // Construction
    // =====================================================================

    /**
     * @param context  any context; the application context is used
     * @param settings the current settings snapshot; must not be null
     */
    public FeedbackSystem(@NonNull Context context,
                          @NonNull LocalStore.Settings settings) {
        Context applicationContext = context.getApplicationContext();
        Context serviceContext = applicationContext != null ? applicationContext : context;
        this.settings = settings;
        this.audioManager = (AudioManager) serviceContext.getSystemService(
                Context.AUDIO_SERVICE);
        this.callbackHandler = new Handler(Looper.getMainLooper());
        this.focusChangeListener = this::onAudioFocusChange;
        this.playbackListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack track) {
                handleTrackCompletion(track);
            }

            @Override
            public void onPeriodicNotification(AudioTrack track) {
                // No periodic updates are registered for short static SFX.
            }
        };
        this.sfxTracks = new HashMap<>();
        this.trackFrameCounts = new HashMap<>();
        this.activeTracks = new HashSet<>();
        this.lastTriggered = new HashMap<>();

        this.vibrator = acquireVibrator(serviceContext);
        this.vibrationAvailable = hasVibrator(vibrator);

        // Building a request is local configuration only. Focus is acquired
        // lazily by the first event that is actually going to play a sound.
        this.focusRequest = buildFocusRequest();

        buildAllSfx();
    }

    /**
     * Releases every {@link AudioTrack}, abandons audio focus, and marks the
     * system as released. Safe to call multiple times.
     */
    public void release() {
        if (released) return;
        released = true;
        lifecyclePaused = true;
        stopAllTracks();
        cancelHaptics();
        abandonFocusInternal();

        for (AudioTrack track : sfxTracks.values()) {
            try {
                track.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to release AudioTrack: " + e.getMessage());
            }
        }
        sfxTracks.clear();
        trackFrameCounts.clear();
        activeTracks.clear();
        lastTriggered.clear();
        focusState = FocusState.RELEASED;
    }

    // =====================================================================
    // Settings
    // =====================================================================

    /**
     * Applies an immutable settings snapshot. Muting stops current playback
     * and abandons focus; unmuting merely rearms the next event and never
     * requests focus immediately. Disabling haptics cancels an in-flight
     * effect so no old feedback continues after the setting changes.
     */
    public void setSettings(@NonNull LocalStore.Settings newSettings) {
        if (released) return;
        LocalStore.Settings previous = this.settings;
        this.settings = newSettings;

        if (!newSettings.soundEnabled) {
            stopAllTracks();
            abandonFocusInternal();
            focusState = lifecyclePaused
                    ? FocusState.PAUSED : FocusState.NOT_REQUESTED;
        } else if (!previous.soundEnabled && !lifecyclePaused) {
            // A real Off → On transition is an explicit retry boundary.
            // Do not acquire focus until the next audible event.
            focusState = FocusState.NOT_REQUESTED;
        }

        // A committed settings snapshot ends any transient slider preview.
        previewVolumePercent = -1;
        applyTrackVolume(newSettings.volumePercent);

        if (previous.hapticsEnabled && !newSettings.hapticsEnabled) {
            cancelHaptics();
        }
    }

    /**
     * Applies an immediate, non-persisted preview while the user drags the
     * Settings volume slider. The next {@link #setSettings(LocalStore.Settings)}
     * call commits the persisted value and clears this preview.
     */
    public void previewVolumePercent(int volumePercent) {
        if (released) return;
        previewVolumePercent = Math.max(0, Math.min(100, volumePercent));
        applyTrackVolume(previewVolumePercent);
    }

    private void applyTrackVolume(int volumePercent) {
        // A mild power curve makes the lower half of the slider useful while
        // avoiding a sudden loudness jump near the upper end.
        float normalized = Math.max(0, Math.min(100, volumePercent)) / 100f;
        float gain = (float) Math.pow(normalized, 1.7d);
        for (AudioTrack track : sfxTracks.values()) {
            try {
                int result = track.setVolume(gain);
                if (result != AudioTrack.SUCCESS) {
                    Log.w(TAG, "Unable to apply SFX volume (result=" + result + ").");
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to apply SFX volume: " + error.getMessage());
            }
        }
    }

    public boolean isReducedMotion() {
        return settings.reducedMotion;
    }

    public boolean isReducedEffects() {
        return settings.reducedEffects;
    }

    public boolean isHighContrast() {
        return settings.highContrast;
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    /**
     * Stops all audio, cancels any in-flight haptic effect, and abandons
     * audio focus. Called by the host when the app leaves the foreground.
     */
    public void pauseAll() {
        if (released) return;
        lifecyclePaused = true;
        stopAllTracks();
        cancelHaptics();
        abandonFocusInternal();
        focusState = FocusState.PAUSED;
    }

    /**
     * Rearms lazy playback after foreground resume. No focus request is made
     * here; the next real SFX event is the only path that can request it.
     */
    public void resumeAll() {
        if (released) return;
        lifecyclePaused = false;
        focusState = FocusState.NOT_REQUESTED;
        lastTriggered.clear();
    }

    // =====================================================================
    // Gameplay events
    // =====================================================================

    /** Run starts: short rising two-tone plus a brief haptic tick. */
    public void onStart() {
        playSfx("start");
        vibrateOneShot("start", 20L);
    }

    /** Objective or key collected: short bell-like tone plus a tick. */
    public void onObjectiveCollected() {
        if (!checkCooldown("objective", OBJECTIVE_COOLDOWN_MS)) return;
        playSfx("objective");
        vibrateOneShot("objective", 30L);
    }

    /**
     * Player pressed against a closed or locked gate: low thud plus a
     * double-tap haptic. This is <strong>not</strong> a failure and must
     * not be treated as one; the engine already clamped the marker.
     */
    public void onGateBlocked() {
        if (!checkCooldown("gate-blocked", GATE_BLOCKED_COOLDOWN_MS)) return;
        playSfx("blocked");
        vibratePattern("gate-blocked", BLOCKED_PATTERN);
    }

    /**
     * Player is close to a forbidden boundary. Heavily rate-limited so
     * that a continuous drag along a wall does not produce a vibration
     * storm. The blueprint explicitly forbids per-MotionEvent haptics
     * near the wall.
     */
    public void onNearBoundary() {
        if (!checkCooldown("near-boundary", NEAR_BOUNDARY_COOLDOWN_MS)) return;
        playSfx("near-boundary");
        vibrateOneShot("near-boundary", 15L);
    }

    /** Run failed: descending two-tone plus a single long haptic. */
    public void onFailure() {
        playSfx("failure");
        vibratePattern("failure", FAILURE_PATTERN);
    }

    /** Run completed: ascending arpeggio plus a short celebratory pattern. */
    public void onSuccess() {
        playSfx("success");
        vibratePattern("success", SUCCESS_PATTERN);
    }

    /** Gameplay pause: a very short click, rate-limited. */
    public void onPause() {
        if (!checkCooldown("pause", PAUSE_RESUME_COOLDOWN_MS)) return;
        playSfx("pause");
    }

    /** Gameplay resume: a very short click, rate-limited. */
    public void onResume() {
        if (!checkCooldown("resume", PAUSE_RESUME_COOLDOWN_MS)) return;
        playSfx("resume");
    }

    /** OS-level interruption: no sound, no haptic, to avoid startling the user. */
    public void onInterrupt() {
        // Intentionally silent. The interruption was not player-initiated.
    }

    // =====================================================================
    // SFX generation
    // =====================================================================

    private void buildAllSfx() {
        // START: 660 Hz → 880 Hz, 80 ms each, no gap.
        registerSfx("start", concat(
                tone(660.0, 80, 1.0),
                tone(880.0, 80, 1.0)));

        // OBJECTIVE: 1046 Hz bell, 80 ms with exponential decay.
        registerSfx("objective", toneWithDecay(1046.5, 80, 1.0));

        // BLOCKED: 220 Hz thud, 100 ms.
        registerSfx("blocked", toneWithDecay(220.0, 100, 1.0));

        // NEAR-BOUNDARY: 1760 Hz short tick, 25 ms.
        registerSfx("near-boundary", toneWithDecay(1760.0, 25, 0.6));

        // FAILURE: 440 Hz → 220 Hz, 100 ms each.
        registerSfx("failure", concat(
                tone(440.0, 100, 1.0),
                tone(220.0, 100, 1.0)));

        // SUCCESS: 523 Hz → 659 Hz → 784 Hz, 80 ms each.
        registerSfx("success", concat(
                tone(523.25, 80, 1.0),
                tone(659.25, 80, 1.0),
                tone(783.99, 80, 1.0)));

        // PAUSE / RESUME: 880 Hz, 40 ms.
        short[] pauseSamples = toneWithDecay(880.0, 40, 0.7);
        registerSfx("pause", pauseSamples);
        registerSfx("resume", pauseSamples.clone());
    }

    private void registerSfx(@NonNull String key, @NonNull short[] samples) {
        AudioTrack track = buildTrack(samples);
        if (track == null) return;
        sfxTracks.put(key, track);
        trackFrameCounts.put(track, samples.length);
    }

    /**
     * Builds a {@link AudioTrack} in static mode with the supplied PCM
     * data. The constructor's buffer size must match the data size; the
     * track is stopped and ready to be replayed.
     */
    @Nullable
    private AudioTrack buildTrack(@NonNull short[] samples) {
        int bufferBytes = samples.length * BYTES_PER_SAMPLE;
        AudioTrack track = null;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack was not initialized; this SFX is unavailable.");
                track.release();
                return null;
            }

            track.setPlaybackPositionUpdateListener(playbackListener, callbackHandler);
            int effectiveVolume = previewVolumePercent >= 0
                    ? previewVolumePercent : settings.volumePercent;
            track.setVolume((float) Math.pow(effectiveVolume / 100f, 1.7d));
            int written = track.write(samples, 0, samples.length);
            if (written != samples.length) {
                Log.w(TAG, "AudioTrack write short: " + written
                        + "/" + samples.length);
                track.release();
                return null;
            }
            return track;
        } catch (RuntimeException e) {
            if (track != null) {
                try {
                    track.release();
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup of a partially initialized native track.
                }
            }
            Log.w(TAG, "Unable to build AudioTrack: " + e.getMessage());
            return null;
        }
    }

    /**
     * Plays a prepared track. A track is restarted from the beginning if the
     * same event is triggered while it is still active. Audio focus is shared
     * across overlapping tracks and released when the final marker is reached.
     */
    private void playSfx(@NonNull String key) {
        if (released || lifecyclePaused || !settings.soundEnabled) return;
        AudioTrack track = sfxTracks.get(key);
        if (track == null || !ensureAudioFocus()) return;

        Integer frameCount = trackFrameCounts.get(track);
        if (frameCount == null || frameCount <= 0) {
            Log.w(TAG, "Missing PCM frame count for SFX: " + key);
            abandonFocusIfIdle();
            return;
        }

        try {
            stopTrackForReplay(track);

            int reloadResult = track.reloadStaticData();
            if (reloadResult != AudioTrack.SUCCESS) {
                Log.w(TAG, "Unable to reload static SFX " + key
                        + " (result=" + reloadResult + ").");
                abandonFocusIfIdle();
                return;
            }

            // AudioTrack marker positions are frames. For mono PCM, one
            // short sample is one frame; no millisecond estimate is needed.
            int markerResult = track.setNotificationMarkerPosition(frameCount);
            if (markerResult != AudioTrack.SUCCESS) {
                Log.w(TAG, "Unable to register completion marker for SFX " + key
                        + " (result=" + markerResult + ").");
                abandonFocusIfIdle();
                return;
            }

            activeTracks.add(track);
            // AudioTrack.play() has no success return value. Do not sample
            // PLAYSTATE immediately: a 25 ms SFX may naturally finish before
            // that query is observed. The frame marker is the completion source.
            track.play();
        } catch (RuntimeException e) {
            activeTracks.remove(track);
            stopTrackSafely(track);
            abandonFocusIfIdle();
            Log.w(TAG, "AudioTrack error on " + key + ": " + e.getMessage());
        }
    }

    private void stopTrackForReplay(@NonNull AudioTrack track) {
        if (activeTracks.contains(track)
                || track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            stopTrackSafely(track);
        }
        activeTracks.remove(track);
    }

    private void stopTrackSafely(@NonNull AudioTrack track) {
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                track.stop();
            }
        } catch (RuntimeException ignored) {
            // Stop is best-effort; the native resource is still released later.
        }
        activeTracks.remove(track);
    }

    private void stopAllTracks() {
        for (AudioTrack track : sfxTracks.values()) {
            stopTrackSafely(track);
        }
        activeTracks.clear();
    }

    /**
     * Called on the main Handler when the static PCM playback head reaches
     * its exact frame marker. A stale callback from an older playback is
     * ignored while a restarted playback has not reached the marker yet.
     */
    private void handleTrackCompletion(@NonNull AudioTrack track) {
        if (released || lifecyclePaused || !activeTracks.contains(track)) return;
        Integer frameCount = trackFrameCounts.get(track);
        if (frameCount == null) {
            activeTracks.remove(track);
            abandonFocusIfIdle();
            return;
        }

        try {
            int playedFrames = track.getPlaybackHeadPosition();
            if (playedFrames >= 0 && playedFrames < frameCount) {
                // Usually a marker callback queued by a playback that was
                // stopped/restarted. The current playback still owns the track.
                return;
            }
            // A negative signed value is not expected for these short buffers.
            // The marker callback itself remains authoritative if the query
            // cannot provide a usable position.
        } catch (RuntimeException e) {
            // The marker has fired; if the position can no longer be queried,
            // release the active slot rather than retaining focus indefinitely.
            Log.w(TAG, "Unable to read completed AudioTrack position: "
                    + e.getMessage());
        }

        activeTracks.remove(track);
        abandonFocusIfIdle();
    }

    private void abandonFocusIfIdle() {
        if (!activeTracks.isEmpty()) return;
        abandonFocusInternal();
        if (!released && !lifecyclePaused) {
            focusState = FocusState.NOT_REQUESTED;
        }
    }

    // =====================================================================
    // Haptics
    // =====================================================================

    private void cancelHaptics() {
        if (vibrator == null || !vibrationAvailable) return;
        try {
            vibrator.cancel();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to cancel haptics: " + e.getMessage());
        }
    }

    private static boolean hasVibrator(@Nullable Vibrator vibrator) {
        if (vibrator == null) return false;
        try {
            return vibrator.hasVibrator();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to query vibrator capability: " + e.getMessage());
            return false;
        }
    }

    private void vibrateOneShot(@NonNull String key, long durationMs) {
        if (released || lifecyclePaused
                || !settings.hapticsEnabled || !vibrationAvailable) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Api26Vibration.vibrateOneShot(vibrator, durationMs);
            } else {
                //noinspection deprecation
                vibrator.vibrate(durationMs);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Haptic error on " + key + ": " + e.getMessage());
        }
    }

    private void vibratePattern(@NonNull String key, @NonNull long[] pattern) {
        if (released || lifecyclePaused
                || !settings.hapticsEnabled || !vibrationAvailable) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Api26Vibration.vibratePattern(vibrator, pattern);
            } else {
                //noinspection deprecation
                vibrator.vibrate(pattern, -1);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Haptic pattern error on " + key + ": " + e.getMessage());
        }
    }

    @Nullable
    private static Vibrator acquireVibrator(@NonNull Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Vibrator managed = Api31Vibration.getDefaultVibrator(context);
                if (managed != null) return managed;
            }
            return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to acquire vibrator: " + e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // Audio focus
    // =====================================================================

    @Nullable
    private Object buildFocusRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        try {
            return Api26AudioFocus.buildRequest(focusChangeListener, callbackHandler);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to build audio focus request: " + e.getMessage());
            return null;
        }
    }

    /**
     * Requests focus once per armed period. A denied request is latched as
     * UNAVAILABLE until lifecycle resume or an actual Sound Off → On change,
     * preventing high-frequency game events from producing Binder requests.
     */
    private boolean ensureAudioFocus() {
        if (released || lifecyclePaused || !settings.soundEnabled) return false;
        if (focusState == FocusState.HELD) return focusRequestOutstanding;
        if (focusState == FocusState.UNAVAILABLE
                || focusState == FocusState.PAUSED
                || focusState == FocusState.RELEASED) {
            return false;
        }
        if (audioManager == null) {
            focusState = FocusState.UNAVAILABLE;
            Log.w(TAG, "AudioManager unavailable; SFX disabled for this foreground session.");
            return false;
        }

        try {
            final int result;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    focusState = FocusState.UNAVAILABLE;
                    Log.w(TAG, "Audio focus request unavailable on this API level.");
                    return false;
                }
                result = Api26AudioFocus.request(audioManager, focusRequest);
            } else {
                //noinspection deprecation
                result = audioManager.requestAudioFocus(
                        focusChangeListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequestOutstanding = true;
                focusState = FocusState.HELD;
                return true;
            }

            // Delayed focus is deliberately not enabled for one-shot SFX.
            // The event is dropped instead of being replayed after it becomes stale.
            focusRequestOutstanding = false;
            focusState = FocusState.UNAVAILABLE;
            Log.w(TAG, "Audio focus unavailable; dropping current SFX (result="
                    + result + ").");
            return false;
        } catch (RuntimeException e) {
            focusRequestOutstanding = false;
            focusState = FocusState.UNAVAILABLE;
            Log.w(TAG, "Audio focus request failed: " + e.getMessage());
            return false;
        }
    }

    /** Abandons only a request that was actually granted or remains transiently active. */
    private void abandonFocusInternal() {
        if (!focusRequestOutstanding) return;
        AudioManager manager = audioManager;
        focusRequestOutstanding = false;
        if (manager == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                Api26AudioFocus.abandon(manager, focusRequest);
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                //noinspection deprecation
                manager.abandonAudioFocus(focusChangeListener);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Audio focus abandon failed: " + e.getMessage());
        }
    }

    private void onAudioFocusChange(int focusChange) {
        if (released || !focusRequestOutstanding) return;

        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (!focusRequestOutstanding) return;
            if (lifecyclePaused || !settings.soundEnabled || activeTracks.isEmpty()) {
                // No stale SFX is queued. If focus returns after an interruption,
                // relinquish it immediately; the next event can request afresh.
                abandonFocusInternal();
                focusState = lifecyclePaused
                        ? FocusState.PAUSED : FocusState.NOT_REQUESTED;
            } else {
                focusState = FocusState.HELD;
            }
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                // Android 7.1 and lower do not provide the Android O automatic
                // ducking behavior; short SFX are stopped instead of competing.
                stopAllTracks();
                focusState = FocusState.UNAVAILABLE;
            }
            // On API 26+, leave the short SFX to platform automatic ducking.
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Keep the request listener registered for the matching GAIN, but
            // drop all momentary effects. Nothing is resumed automatically.
            stopAllTracks();
            focusState = FocusState.UNAVAILABLE;
            return;
        }

        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            stopAllTracks();
            abandonFocusInternal();
            focusState = FocusState.UNAVAILABLE;
        }
    }

    /** API-26 implementation kept isolated from the minSdk 24 call path. */
    private static final class Api26AudioFocus {
        private Api26AudioFocus() { }

        @NonNull
        static Object buildRequest(
                @NonNull AudioManager.OnAudioFocusChangeListener listener,
                @NonNull Handler handler) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            return new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(listener, handler)
                    .build();
        }

        static int request(@NonNull AudioManager manager, @NonNull Object request) {
            return manager.requestAudioFocus((AudioFocusRequest) request);
        }

        static void abandon(@NonNull AudioManager manager, @NonNull Object request) {
            manager.abandonAudioFocusRequest((AudioFocusRequest) request);
        }
    }

    /** API-26 vibration implementation isolated for API 24/25 verification. */
    private static final class Api26Vibration {
        private Api26Vibration() { }

        static void vibrateOneShot(@NonNull Vibrator vibrator, long durationMs) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
        }

        static void vibratePattern(@NonNull Vibrator vibrator, @NonNull long[] pattern) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        }
    }

    /** API-31 vibrator service lookup isolated behind the SDK guard. */
    private static final class Api31Vibration {
        private Api31Vibration() { }

        @Nullable
        static Vibrator getDefaultVibrator(@NonNull Context context) {
            VibratorManager manager = (VibratorManager) context.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE);
            return manager != null ? manager.getDefaultVibrator() : null;
        }
    }

    // =====================================================================
    // Cooldown
    // =====================================================================

    /**
     * Returns true if the event may fire now, and records the trigger time.
     * Returns false if the event is inside its cooldown window.
     */
    private boolean checkCooldown(@NonNull String key, long cooldownMs) {
        if (released || lifecyclePaused) return false;
        long now = SystemClock.uptimeMillis();
        Long last = lastTriggered.get(key);
        if (last != null && (now - last) < cooldownMs) {
            return false;
        }
        lastTriggered.put(key, now);
        return true;
    }

    // =====================================================================
    // Waveform helpers
    // =====================================================================

    /**
     * Generates a single tone with a short attack and release envelope to
     * prevent clicks. The envelope is linear in amplitude, which is
     * sufficient for short SFX and avoids the cost of a full ADSR.
     */
    @NonNull
    private static short[] tone(double frequencyHz, int durationMs,
                                double amplitude) {
        int totalSamples = (int) ((long) SAMPLE_RATE_HZ * durationMs / 1000L);
        short[] out = new short[totalSamples];
        int attackSamples = Math.max(1,
                (int) ((long) SAMPLE_RATE_HZ * ATTACK_MS / 1000L));
        int releaseSamples = Math.max(1,
                (int) ((long) SAMPLE_RATE_HZ * RELEASE_MS / 1000L));
        double twoPiF = 2.0 * Math.PI * frequencyHz;
        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / SAMPLE_RATE_HZ;
            double envelope;
            if (i < attackSamples) {
                envelope = (double) i / attackSamples;
            } else if (i > totalSamples - releaseSamples) {
                envelope = (double) (totalSamples - i) / releaseSamples;
            } else {
                envelope = 1.0;
            }
            double sample = MASTER_AMPLITUDE * amplitude * envelope
                    * Math.sin(twoPiF * t);
            out[i] = (short) Math.round(sample * Short.MAX_VALUE);
        }
        return out;
    }

    /**
     * Generates a tone with exponential decay and a short attack. Used for
     * bell-like and thud-like SFX where a flat sustain would sound harsh.
     */
    @NonNull
    private static short[] toneWithDecay(double frequencyHz, int durationMs,
                                         double amplitude) {
        int totalSamples = (int) ((long) SAMPLE_RATE_HZ * durationMs / 1000L);
        short[] out = new short[totalSamples];
        int attackSamples = Math.max(1,
                (int) ((long) SAMPLE_RATE_HZ * ATTACK_MS / 1000L));
        double twoPiF = 2.0 * Math.PI * frequencyHz;
        double decayRate = 5.0 / Math.max(1, totalSamples);
        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / SAMPLE_RATE_HZ;
            double attack = i < attackSamples
                    ? (double) i / attackSamples
                    : 1.0;
            double decay = Math.exp(-decayRate * i);
            double sample = MASTER_AMPLITUDE * amplitude * attack * decay
                    * Math.sin(twoPiF * t);
            out[i] = (short) Math.round(sample * Short.MAX_VALUE);
        }
        return out;
    }

    @NonNull
    private static short[] concat(@NonNull short[]... parts) {
        int total = 0;
        for (short[] p : parts) total += p.length;
        short[] out = new short[total];
        int offset = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }
}