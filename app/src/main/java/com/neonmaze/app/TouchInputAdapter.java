package com.neonmaze.app;

import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.GameEngine.EngineState;
import com.neonmaze.core.GeometryCollision.Aabb;
import com.neonmaze.core.LevelSystem.LevelDefinition;

/**
 * Converts Android pointer events into ordered canonical World-Space samples.
 *
 * <p>This adapter owns pointer identity and the Screen/World transform shared
 * with {@link GameRenderer}. It deliberately contains no collision, gate,
 * objective, result, rendering, or persistence rules.</p>
 *
 * <h2>Pointer policy</h2>
 * <ul>
 *   <li>The primary pointer is captured by stable pointer ID, never by index.</li>
 *   <li>Every MOVE historical sample is delivered oldest-first, followed by
 *       the current sample.</li>
 *   <li>A normal ACTION_UP delivers historical samples and then calls
 *       {@link GameEngine#onPointerUp(int, double, double, long)} exactly once
 *       for the current position. The current point is not also sent as MOVE.</li>
 *   <li>Secondary pointers never take ownership. Losing the primary pointer
 *       through ACTION_POINTER_UP or an invalid index interrupts the run.</li>
 *   <li>ACTION_CANCEL and explicit host interruption are interruptions, not
 *       gameplay failures.</li>
 * </ul>
 *
 * <p>All callbacks and engine calls occur on the Android UI thread, matching
 * View dispatch and the single-threaded GameEngine contract. This adapter
 * performs no explicit temporary allocation per sample. The current Core
 * engine still constructs its own {@code Vec2} samples while recording a run;
 * that separate hot-path allocation policy is tracked for Part09 profiling.</p>
 */
public final class TouchInputAdapter {

    public static final int INVALID_POINTER_ID = -1;

    /** Receives lightweight invalidation/state notifications on the UI thread. */
    public interface Listener {
        void onInputChanged();
    }

    /**
     * Uniform-scale viewport mapping between canonical World Space and the
     * host View's local pixel coordinates. The content rectangle may exclude
     * HUD/safe-area insets; unused space is letterboxed rather than stretched.
     */
    public static final class Viewport {
        private Aabb worldBounds;
        private float contentLeft;
        private float contentTop;
        private float contentRight;
        private float contentBottom;
        private double scale;
        private double offsetX;
        private double offsetY;
        private int revision;

        public Viewport(@NonNull Aabb worldBounds) {
            setWorldBounds(worldBounds);
        }

        public void setWorldBounds(@NonNull Aabb bounds) {
            if (bounds.maxX() <= bounds.minX() || bounds.maxY() <= bounds.minY()) {
                throw new IllegalArgumentException("Viewport world bounds must have positive size");
            }
            this.worldBounds = bounds;
            recompute();
        }

        /** Sets the usable View-local rectangle in pixels. */
        public void setContentRect(float left, float top, float right, float bottom) {
            if (!Float.isFinite(left) || !Float.isFinite(top)
                    || !Float.isFinite(right) || !Float.isFinite(bottom)
                    || right < left || bottom < top) {
                throw new IllegalArgumentException("Viewport content rectangle is invalid");
            }
            if (Float.compare(contentLeft, left) != 0
                    || Float.compare(contentTop, top) != 0
                    || Float.compare(contentRight, right) != 0
                    || Float.compare(contentBottom, bottom) != 0) {
                contentLeft = left;
                contentTop = top;
                contentRight = right;
                contentBottom = bottom;
                recompute();
            }
        }

        private void recompute() {
            double worldWidth = worldBounds.maxX() - worldBounds.minX();
            double worldHeight = worldBounds.maxY() - worldBounds.minY();
            double screenWidth = contentRight - contentLeft;
            double screenHeight = contentBottom - contentTop;
            if (worldWidth <= 0.0 || worldHeight <= 0.0
                    || screenWidth <= 0.0 || screenHeight <= 0.0) {
                scale = 0.0;
                offsetX = contentLeft;
                offsetY = contentTop;
                revision++;
                return;
            }
            scale = Math.min(screenWidth / worldWidth, screenHeight / worldHeight);
            double usedWidth = worldWidth * scale;
            double usedHeight = worldHeight * scale;
            offsetX = contentLeft + (screenWidth - usedWidth) * 0.5;
            offsetY = contentTop + (screenHeight - usedHeight) * 0.5;
            revision++;
        }

        public boolean isReady() {
            return scale > 0.0 && Double.isFinite(scale);
        }

        public double scale() { return scale; }
        public int revision() { return revision; }
        public Aabb worldBounds() { return worldBounds; }
        public float contentLeft() { return contentLeft; }
        public float contentTop() { return contentTop; }
        public float contentRight() { return contentRight; }
        public float contentBottom() { return contentBottom; }

        public double screenToWorldX(float screenX) {
            requireReady();
            return worldBounds.minX() + (screenX - offsetX) / scale;
        }

        /** Screen Y is inverted so canonical World Y increases upward. */
        public double screenToWorldY(float screenY) {
            requireReady();
            return worldBounds.maxY() - (screenY - offsetY) / scale;
        }

        public float worldToScreenX(double worldX) {
            requireReady();
            return (float) (offsetX + (worldX - worldBounds.minX()) * scale);
        }

        public float worldToScreenY(double worldY) {
            requireReady();
            return (float) (offsetY + (worldBounds.maxY() - worldY) * scale);
        }

        private void requireReady() {
            if (!isReady()) throw new IllegalStateException("Viewport is not laid out");
        }
    }

    private final GameEngine engine;
    private final Viewport viewport;

    @Nullable private Listener listener;
    private int activePointerId = INVALID_POINTER_ID;
    private int activeViewportRevision = -1;
    private long lastDeliveredUptimeMs = -1L;
    private float lastScreenX;
    private float lastScreenY;
    private double lastWorldX;
    private double lastWorldY;
    private boolean hasPointerPosition;

    public TouchInputAdapter(@NonNull GameEngine engine,
                             @NonNull LevelDefinition level,
                             @NonNull Viewport viewport,
                             @Nullable Listener listener) {
        Aabb bounds = level.worldBounds();
        Aabb viewportBounds = viewport.worldBounds();
        if (Double.compare(bounds.minX(), viewportBounds.minX()) != 0
                || Double.compare(bounds.minY(), viewportBounds.minY()) != 0
                || Double.compare(bounds.maxX(), viewportBounds.maxX()) != 0
                || Double.compare(bounds.maxY(), viewportBounds.maxY()) != 0) {
            throw new IllegalArgumentException(
                    "Viewport world bounds must match the injected level");
        }
        this.engine = engine;
        this.viewport = viewport;
        this.listener = listener;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * Dispatches one MotionEvent. Returns true while this adapter owns the
     * primary gesture, including secondary-pointer transitions.
     */
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return handleDown(event);
            case MotionEvent.ACTION_MOVE:
                return handleMove(event);
            case MotionEvent.ACTION_UP:
                return handleUp(event);
            case MotionEvent.ACTION_CANCEL:
                return handleCancel("motion-event-cancel");
            case MotionEvent.ACTION_POINTER_DOWN:
                return activePointerId != INVALID_POINTER_ID;
            case MotionEvent.ACTION_POINTER_UP:
                return handlePointerUp(event);
            default:
                return activePointerId != INVALID_POINTER_ID;
        }
    }

    private boolean handleDown(MotionEvent event) {
        if (event.getPointerCount() <= 0 || !viewport.isReady()) return false;
        if (activePointerId != INVALID_POINTER_ID) {
            interruptActive("new-down-before-primary-release");
        }

        EngineState before = engine.getState();
        if (before != EngineState.READY && before != EngineState.PAUSED) return false;

        int index = event.getActionIndex();
        if (index < 0 || index >= event.getPointerCount()) return false;
        float sx = event.getX(index);
        float sy = event.getY(index);
        long uptime = event.getEventTime();
        if (!isFiniteSample(sx, sy, uptime)) return false;

        int pointerId = event.getPointerId(index);
        double wx = viewport.screenToWorldX(sx);
        double wy = viewport.screenToWorldY(sy);
        engine.onPointerDown(pointerId, wx, wy, uptime);

        EngineState after = engine.getState();
        if (after == EngineState.RUNNING) {
            activePointerId = pointerId;
            activeViewportRevision = viewport.revision();
            lastDeliveredUptimeMs = uptime;
            rememberPosition(sx, sy, wx, wy);
            notifyInputChanged();
            return true;
        }

        // A bad resume anchor transitions PAUSED to INTERRUPTED. Consume the
        // down because the state transition belongs to this gameplay gesture.
        if (before == EngineState.PAUSED && after == EngineState.INTERRUPTED) {
            clearPointer();
            notifyInputChanged();
            return true;
        }
        clearPointer();
        return false;
    }

    private boolean handleMove(MotionEvent event) {
        if (activePointerId == INVALID_POINTER_ID) return false;
        if (!hasStableViewport()) {
            interruptActive("viewport-changed-during-gesture");
            return true;
        }
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            interruptActive("primary-pointer-index-lost");
            return true;
        }

        int historySize = event.getHistorySize();
        for (int historyIndex = 0; historyIndex < historySize; historyIndex++) {
            float sx = event.getHistoricalX(pointerIndex, historyIndex);
            float sy = event.getHistoricalY(pointerIndex, historyIndex);
            long uptime = event.getHistoricalEventTime(historyIndex);
            if (!dispatchMoveSample(sx, sy, uptime)) return true;
            if (engine.getState() != EngineState.RUNNING) {
                clearPointer();
                notifyInputChanged();
                return true;
            }
        }

        if (!dispatchMoveSample(event.getX(pointerIndex), event.getY(pointerIndex),
                event.getEventTime())) return true;
        notifyInputChanged();
        if (engine.getState() != EngineState.RUNNING) clearPointer();
        return true;
    }

    private boolean handleUp(MotionEvent event) {
        if (activePointerId == INVALID_POINTER_ID) return false;
        if (!hasStableViewport()) {
            interruptActive("viewport-changed-before-pointer-up");
            return true;
        }
        int pointerIndex = event.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            interruptActive("primary-pointer-missing-on-up");
            return true;
        }

        int historySize = event.getHistorySize();
        for (int historyIndex = 0; historyIndex < historySize; historyIndex++) {
            float sx = event.getHistoricalX(pointerIndex, historyIndex);
            float sy = event.getHistoricalY(pointerIndex, historyIndex);
            long uptime = event.getHistoricalEventTime(historyIndex);
            if (!dispatchMoveSample(sx, sy, uptime)) return true;
            if (engine.getState() != EngineState.RUNNING) {
                clearPointer();
                notifyInputChanged();
                return true;
            }
        }

        // IMPORTANT: do not dispatch the current ACTION_UP sample as MOVE.
        // GameEngine.onPointerUp processes this final position exactly once.
        float sx = event.getX(pointerIndex);
        float sy = event.getY(pointerIndex);
        long uptime = event.getEventTime();
        if (!isMonotonicAndFinite(sx, sy, uptime)) {
            interruptActive("invalid-final-pointer-sample");
            return true;
        }
        double wx = viewport.screenToWorldX(sx);
        double wy = viewport.screenToWorldY(sy);
        rememberPosition(sx, sy, wx, wy);
        engine.onPointerUp(activePointerId, wx, wy, uptime);
        lastDeliveredUptimeMs = uptime;
        clearPointer();
        notifyInputChanged();
        return true;
    }

    private boolean handlePointerUp(MotionEvent event) {
        if (activePointerId == INVALID_POINTER_ID) return false;
        int index = event.getActionIndex();
        if (index < 0 || index >= event.getPointerCount()) {
            interruptActive("invalid-pointer-up-index");
            return true;
        }
        int liftedPointerId = event.getPointerId(index);
        if (liftedPointerId == activePointerId) {
            // The gameplay pointer disappeared while another pointer remains.
            // This is a lost primary gesture, not a normal early release.
            interruptActive("primary-pointer-lost");
        }
        // Secondary pointers never acquire, replace, or release the primary.
        return true;
    }

    private boolean handleCancel(String reason) {
        if (activePointerId == INVALID_POINTER_ID) return false;
        interruptActive(reason);
        return true;
    }

    private boolean dispatchMoveSample(float screenX, float screenY, long uptimeMs) {
        if (!isMonotonicAndFinite(screenX, screenY, uptimeMs)) {
            interruptActive("non-monotonic-or-invalid-pointer-sample");
            return false;
        }
        double worldX = viewport.screenToWorldX(screenX);
        double worldY = viewport.screenToWorldY(screenY);
        engine.onPointerMove(activePointerId, worldX, worldY, uptimeMs);
        lastDeliveredUptimeMs = uptimeMs;
        rememberPosition(screenX, screenY, worldX, worldY);
        return true;
    }

    private boolean isMonotonicAndFinite(float screenX, float screenY, long uptimeMs) {
        return hasStableViewport() && isFiniteSample(screenX, screenY, uptimeMs)
                && (lastDeliveredUptimeMs < 0L || uptimeMs >= lastDeliveredUptimeMs);
    }

    private boolean hasStableViewport() {
        return viewport.isReady() && activeViewportRevision == viewport.revision();
    }

    /**
     * Called by the host after a layout/inset change. A gesture cannot safely
     * continue across a Screen↔World transform change, so the run is interrupted.
     */
    public void onViewportChanged() {
        if (activePointerId != INVALID_POINTER_ID && !hasStableViewport()) {
            interruptActive("viewport-changed-during-gesture");
        }
    }

    private static boolean isFiniteSample(float x, float y, long uptimeMs) {
        return Float.isFinite(x) && Float.isFinite(y) && uptimeMs >= 0L;
    }

    private void rememberPosition(float sx, float sy, double wx, double wy) {
        lastScreenX = sx;
        lastScreenY = sy;
        lastWorldX = wx;
        lastWorldY = wy;
        hasPointerPosition = true;
    }

    /** Interrupts an active run when the host/view is destroyed or hidden. */
    public void interruptActive(@NonNull String reason) {
        if (activePointerId != INVALID_POINTER_ID
                || engine.getState() == EngineState.RUNNING
                || engine.getState() == EngineState.PAUSED) {
            engine.interrupt(reason);
        }
        clearPointer();
        notifyInputChanged();
    }

    /** Clears adapter ownership without modifying the engine. */
    public void clearPointer() {
        activePointerId = INVALID_POINTER_ID;
        activeViewportRevision = -1;
        lastDeliveredUptimeMs = -1L;
        hasPointerPosition = false;
    }

    public boolean isTrackingPointer() {
        return activePointerId != INVALID_POINTER_ID;
    }

    public int activePointerId() { return activePointerId; }
    public boolean hasPointerPosition() { return hasPointerPosition; }
    public float lastScreenX() { return lastScreenX; }
    public float lastScreenY() { return lastScreenY; }
    public double lastWorldX() { return lastWorldX; }
    public double lastWorldY() { return lastWorldY; }

    @NonNull
    public Viewport viewport() { return viewport; }

    private void notifyInputChanged() {
        Listener current = listener;
        if (current != null) current.onInputChanged();
    }
}
