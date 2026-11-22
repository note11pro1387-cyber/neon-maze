package com.neonmaze.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelValidator;
import com.neonmaze.core.levels.Level01;
import com.neonmaze.core.levels.Level02;
import com.neonmaze.core.levels.Level03;
import com.neonmaze.core.levels.Level04;
import com.neonmaze.core.levels.Level05;

import java.io.IOException;
import java.util.Arrays;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates first-launch resource validation and optional online content update.
 * All file/network work is performed off the main thread. The game remains
 * playable from APK-bundled content if no update endpoint or network exists.
 */
public final class StartupContentManager implements AutoCloseable {
    private static final String TAG = "NMStartup";

    public enum State {
        PREPARING,
        CHECKING_REMOTE,
        DOWNLOADING,
        VERIFYING,
        INSTALLING,
        READY,
        READY_OFFLINE,
        FAILED
    }

    public static final class Snapshot {
        @NonNull public final State state;
        public final int progressPercent;
        public final long completedBytes;
        public final long totalBytes;
        public final boolean canContinueOffline;
        @Nullable public final String detail;

        Snapshot(@NonNull State state, int progressPercent, long completedBytes,
                 long totalBytes, boolean canContinueOffline, @Nullable String detail) {
            this.state = state;
            this.progressPercent = Math.max(0, Math.min(100, progressPercent));
            this.completedBytes = Math.max(0L, completedBytes);
            this.totalBytes = Math.max(0L, totalBytes);
            this.canContinueOffline = canContinueOffline;
            this.detail = detail;
        }

        public boolean isReady() {
            return state == State.READY || state == State.READY_OFFLINE;
        }
    }

    public interface Listener {
        void onStartupState(@NonNull Snapshot snapshot);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ContentPackInstaller installer;
    private final ExecutorService executor;
    private final String manifestUrl;
    private final int appVersionCode;
    private final AtomicLong generation = new AtomicLong();

    @Nullable private volatile Listener listener;
    @Nullable private volatile Future<?> currentTask;
    @Nullable private volatile Catalog validatedCatalog;
    @Nullable private volatile ContentPackInstaller.InstalledPack activePack;
    @NonNull private volatile Snapshot snapshot = new Snapshot(
            State.PREPARING, 0, 0, 0, false, null);
    private volatile boolean closed;
    private volatile boolean started;

    public StartupContentManager(@NonNull Context context,
                                 @Nullable String manifestUrl,
                                 int appVersionCode) {
        Context application = context.getApplicationContext();
        this.appContext = application != null ? application : context;
        this.manifestUrl = manifestUrl == null ? "" : manifestUrl.trim();
        this.appVersionCode = appVersionCode;
        this.installer = new ContentPackInstaller(appContext);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "neon-maze-startup-content");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public void attachListener(@NonNull Listener newListener) {
        listener = newListener;
        publish(snapshot, generation.get());
    }

    public void detachListener(@NonNull Listener oldListener) {
        if (listener == oldListener) listener = null;
    }

    public void detachListener() {
        listener = null;
    }

    @NonNull
    public Snapshot getSnapshot() {
        return snapshot;
    }

    @Nullable
    public Catalog getValidatedCatalog() {
        return validatedCatalog;
    }

    /** Cached result of startup validation; does not perform disk I/O on the UI thread. */
    @Nullable
    public ContentPackInstaller.InstalledPack getActivePack() {
        return activePack;
    }

    public synchronized void start() {
        if (closed || started) return;
        started = true;
        runBootstrap();
    }

    public synchronized void retry() {
        if (closed) return;
        cancelCurrentTask();
        started = true;
        runBootstrap();
    }

    /**
     * Skips only the optional network update. All mandatory APK resources are
     * validated before this action is offered to the screen.
     */
    public synchronized void continueOffline() {
        if (closed || !snapshot.canContinueOffline || snapshot.isReady()) return;
        generation.incrementAndGet();
        cancelCurrentTask();
        setSnapshot(new Snapshot(State.READY_OFFLINE, 100, 0, 0,
                false, "Continuing with the bundled game resources"), generation.get());
    }

    private void runBootstrap() {
        long taskGeneration = generation.incrementAndGet();
        setSnapshot(new Snapshot(State.PREPARING, 0, 0, 0,
                false, null), taskGeneration);
        currentTask = executor.submit(() -> {
            try {
                verifyBundledResources();
                if (isStale(taskGeneration)) return;
                validatedCatalog = buildAndValidateCatalog();
                if (isStale(taskGeneration)) return;
                activePack = installer.getActivePack();
                if (manifestUrl.isEmpty()) {
                    // No endpoint is configured for this private contest build.
                    // Clean interrupted local work, but do not show a fake
                    // network-check state or briefly expose an offline override.
                    installer.checkAndInstall("", appVersionCode,
                            (phase, percent, completed, total) -> { });
                    activePack = installer.getActivePack();
                    setSnapshot(new Snapshot(State.READY, 100, 0, 0, false, null),
                            taskGeneration);
                    return;
                }
                setSnapshot(new Snapshot(State.CHECKING_REMOTE, 8, 0, 0,
                        true, null), taskGeneration);

                ContentPackInstaller.Result result = installer.checkAndInstall(
                        manifestUrl, appVersionCode, (phase, percent, completed, total) -> {
                            if (isStale(taskGeneration)) return;
                            State mapped = mapPhase(phase);
                            int overall = mapOverallProgress(phase, percent);
                            setSnapshot(new Snapshot(mapped, overall, completed, total,
                                    true, null), taskGeneration);
                        });
                if (isStale(taskGeneration)) return;
                activePack = installer.getActivePack();
                State finalState;
                switch (result.status) {
                    case NO_REMOTE_CONFIGURED:
                    case UP_TO_DATE:
                    case UPDATED:
                    case KEPT_NEWER_LOCAL_VERSION:
                        finalState = State.READY;
                        break;
                    case READY_BUNDLED_ONLY:
                    case KEPT_PREVIOUS_AFTER_ERROR:
                    default:
                        finalState = State.READY_OFFLINE;
                        break;
                }
                if (result.message != null) {
                    Log.w(TAG, "Optional content update was not applied: " + result.message);
                }
                setSnapshot(new Snapshot(finalState, 100, 0, 0, false,
                        result.message), taskGeneration);
            } catch (Exception error) {
                if (isStale(taskGeneration)) return;
                Log.e(TAG, "Required bundled startup resources are unavailable", error);
                setSnapshot(new Snapshot(State.FAILED, 0, 0, 0,
                        false, error.getMessage()), taskGeneration);
            }
        });
    }

    private void verifyBundledResources() throws IOException {
        verifyAsset("fonts/Vazirmatn-Regular.ttf");
        verifyAsset("fonts/Vazirmatn-Bold.ttf");
        if (!UiKit.preloadPersianTypefaces(appContext)) {
            throw new IOException("Bundled Vazirmatn fonts cannot be loaded by Android");
        }

        byte[] licenseBytes;
        try (InputStream input = appContext.getAssets().open("fonts/OFL.txt");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 64 * 1024) {
                    throw new IOException("Bundled font license exceeds its size limit");
                }
                output.write(buffer, 0, read);
            }
            licenseBytes = output.toByteArray();
        }
        String license = new String(licenseBytes, StandardCharsets.UTF_8)
                .toUpperCase(Locale.ROOT);
        if (!license.contains("SIL OPEN FONT LICENSE") || !license.contains("VERSION 1.1")) {
            throw new IOException("Bundled Vazirmatn SIL OFL 1.1 license is missing or invalid");
        }
    }

    private Catalog buildAndValidateCatalog() {
        Catalog catalog = new Catalog(Arrays.asList(
                Level01.build(), Level02.build(), Level03.build(),
                Level04.build(), Level05.build()));
        if (catalog.size() != 5) {
            throw new IllegalStateException("Campaign catalog must contain exactly five levels");
        }
        for (int i = 0; i < catalog.size(); i++) {
            LevelValidator.requireValid(catalog.get(i));
        }
        return catalog;
    }

    private void verifyAsset(String path) throws IOException {
        try (InputStream input = appContext.getAssets().open(path)) {
            byte[] header = new byte[4];
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset < 4) throw new IOException("Bundled resource is incomplete: " + path);
            if (path.endsWith(".ttf")) {
                boolean sfnt = (header[0] == 0 && header[1] == 1
                        && header[2] == 0 && header[3] == 0)
                        || header[0] == 'O' && header[1] == 'T'
                        && header[2] == 'T' && header[3] == 'O';
                if (!sfnt) throw new IOException("Bundled font is invalid: " + path);
            }
        }
    }

    private boolean isStale(long taskGeneration) {
        return closed || generation.get() != taskGeneration
                || Thread.currentThread().isInterrupted();
    }

    private void cancelCurrentTask() {
        installer.cancelCurrentRequest();
        Future<?> task = currentTask;
        currentTask = null;
        if (task != null) task.cancel(true);
    }

    private static State mapPhase(ContentPackInstaller.Phase phase) {
        switch (phase) {
            case DOWNLOADING: return State.DOWNLOADING;
            case VERIFYING: return State.VERIFYING;
            case EXTRACTING:
            case ACTIVATING: return State.INSTALLING;
            case CHECKING_REMOTE:
            default: return State.CHECKING_REMOTE;
        }
    }

    private static int mapOverallProgress(ContentPackInstaller.Phase phase, int phasePercent) {
        int value = Math.max(0, Math.min(100, phasePercent));
        switch (phase) {
            case CHECKING_REMOTE: return 8;
            case DOWNLOADING: return 10 + value * 65 / 100;
            case VERIFYING: return 78;
            case EXTRACTING: return 80 + value * 15 / 100;
            case ACTIVATING: return 97 + value * 3 / 100;
            default: return value;
        }
    }

    private void setSnapshot(@NonNull Snapshot next, long taskGeneration) {
        if (isStale(taskGeneration)) return;
        snapshot = next;
        publish(next, taskGeneration);
    }

    private void publish(@NonNull Snapshot value, long taskGeneration) {
        mainHandler.post(() -> {
            if (closed || generation.get() != taskGeneration) return;
            Listener current = listener;
            if (current != null) current.onStartupState(value);
        });
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        cancelCurrentTask();
        listener = null;
        executor.shutdownNow();
    }
}
