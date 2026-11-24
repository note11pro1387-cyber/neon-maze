package com.neonmaze.app;

/**
 * Deterministic, state-driven timing for gameplay motion cues.
 *
 * <p>This class owns presentation time only. It never advances or mutates the
 * GameEngine, and it allocates nothing while a frame is evaluated. Motion is
 * driven by the host's existing Choreographer callback.</p>
 */
public final class GameMotionSystem {

    public static final int CUE_NONE = 0;
    public static final int CUE_START = 1;
    public static final int CUE_OBJECTIVE = 2;
    public static final int CUE_KEY = 3;
    public static final int CUE_GATE = 4;
    public static final int CUE_BLOCKED = 5;
    public static final int CUE_PAUSE = 6;
    public static final int CUE_RESUME = 7;

    public static final int TERMINAL_NONE = 0;
    public static final int TERMINAL_SUCCESS = 1;
    public static final int TERMINAL_FAILURE = 2;

    private static final long CUE_DURATION_NANOS = 520_000_000L;
    private static final long TERMINAL_DURATION_NANOS = 720_000_000L;

    private boolean reducedMotion;
    private int cueType = CUE_NONE;
    private long cueStartNanos;
    private long cueDurationNanos = CUE_DURATION_NANOS;
    private int terminalType = TERMINAL_NONE;
    private long terminalStartNanos;
    private long terminalDurationNanos = TERMINAL_DURATION_NANOS;
    private boolean terminalImmediate;

    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
        if (reducedMotion) {
            cueType = CUE_NONE;
            cueStartNanos = 0L;
            cueDurationNanos = 0L;
            terminalType = TERMINAL_NONE;
            terminalStartNanos = 0L;
            terminalDurationNanos = 0L;
        } else {
            cueDurationNanos = CUE_DURATION_NANOS;
            // A terminal event created while Reduced Motion was enabled must
            // not regain a delay if the setting changes before its listener
            // becomes available.
            if (!terminalImmediate) terminalDurationNanos = TERMINAL_DURATION_NANOS;
        }
    }

    public boolean isReducedMotion() {
        return reducedMotion;
    }

    public void triggerCue(int type, long nowNanos) {
        if (reducedMotion || type == CUE_NONE) return;
        cueType = type;
        cueStartNanos = Math.max(0L, nowNanos);
        cueDurationNanos = CUE_DURATION_NANOS;
    }

    public void triggerTerminal(int type, long nowNanos) {
        if (type != TERMINAL_SUCCESS && type != TERMINAL_FAILURE) return;
        terminalType = type;
        terminalStartNanos = Math.max(0L, nowNanos);
        terminalImmediate = reducedMotion;
        terminalDurationNanos = terminalImmediate ? 0L : TERMINAL_DURATION_NANOS;
    }

    public float cueProgress(long nowNanos) {
        if (cueType == CUE_NONE || reducedMotion || cueDurationNanos <= 0L) return 1f;
        return progress(nowNanos, cueStartNanos, cueDurationNanos);
    }

    public float terminalProgress(long nowNanos) {
        if (terminalType == TERMINAL_NONE || terminalDurationNanos <= 0L) return 1f;
        return progress(nowNanos, terminalStartNanos, terminalDurationNanos);
    }

    public boolean isAnimating(long nowNanos) {
        boolean cueActive = cueType != CUE_NONE && !reducedMotion
                && nowNanos - cueStartNanos < cueDurationNanos;
        boolean terminalActive = terminalType != TERMINAL_NONE
                && nowNanos - terminalStartNanos < terminalDurationNanos;
        return cueActive || terminalActive;
    }

    public boolean isTerminalComplete(long nowNanos) {
        return terminalType == TERMINAL_NONE || reducedMotion
                || nowNanos - terminalStartNanos >= terminalDurationNanos;
    }

    public int cueType() {
        return cueType;
    }

    public int terminalType() {
        return terminalType;
    }

    public void clear() {
        cueType = CUE_NONE;
        cueStartNanos = 0L;
        terminalType = TERMINAL_NONE;
        terminalStartNanos = 0L;
        terminalImmediate = false;
    }

    private static float progress(long nowNanos, long startNanos, long durationNanos) {
        if (nowNanos <= startNanos) return 0f;
        if (durationNanos <= 0L) return 1f;
        float value = (float) (nowNanos - startNanos) / (float) durationNanos;
        return Math.max(0f, Math.min(1f, value));
    }
}
