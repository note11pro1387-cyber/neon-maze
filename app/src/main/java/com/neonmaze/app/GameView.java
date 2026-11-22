package com.neonmaze.app;

import android.content.Context;
import android.os.Choreographer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.GameEngine.EngineState;
import com.neonmaze.core.GameEngine.GameEvent;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.ReplaySystem.GhostTimeline;

import java.util.List;

/**
 * Gameplay host. The playfield is rendered by {@link GameRenderer}, touch is
 * translated by {@link TouchInputAdapter}, and accessible controls/status are
 * provided by {@link GameHud}. This class owns only composition, viewport
 * sizing, frame scheduling, lifecycle interruption, and event forwarding.
 */
public final class GameView extends FrameLayout implements
        TouchInputAdapter.Listener, GameHud.Listener {

    /** Notifications for the Activity/ScreenUI host. All callbacks are on UI thread. */
    public interface Listener {
        void onEngineStateChanged(@NonNull EngineState previous,
                                  @NonNull EngineState current);
        void onRunOutcome(@NonNull GameEngine.RunOutcome outcome);
        void onPauseRequested();
    }

    private final RenderSurface renderSurface;
    private final GameHud hud;
    private final Choreographer.FrameCallback frameCallback;

    @Nullable private GameEngine engine;
    @Nullable private LevelDefinition level;
    @Nullable private FeedbackSystem feedback;
    @Nullable private GhostTimeline ghost;
    @Nullable private Listener listener;
    @Nullable private TouchInputAdapter.Viewport viewport;
    @Nullable private TouchInputAdapter inputAdapter;
    @Nullable private GameRenderer renderer;
    @Nullable private Choreographer choreographer;

    private EngineState previousState = EngineState.READY;
    @Nullable private GameEngine.RunOutcome deliveredOutcome;
    private boolean attached;
    private boolean frameScheduled;
    private boolean leftHanded;
    private boolean reducedEffects;
    private boolean highContrast;

    private int safeInsetLeft;
    private int safeInsetTop;
    private int safeInsetRight;
    private int safeInsetBottom;

    private static final double NEAR_BOUNDARY_WARNING_WORLD = 5.0;

    public GameView(@NonNull Context context) {
        super(context);
        renderSurface = new RenderSurface(context);
        hud = new GameHud(context);
        frameCallback = this::doFrame;
        initializeHost();
    }

    public GameView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        renderSurface = new RenderSurface(context);
        hud = new GameHud(context);
        frameCallback = this::doFrame;
        initializeHost();
    }

    public GameView(@NonNull Context context, @Nullable AttributeSet attrs,
                    int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        renderSurface = new RenderSurface(context);
        hud = new GameHud(context);
        frameCallback = this::doFrame;
        initializeHost();
    }

    private void initializeHost() {
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(true);
        setFocusable(false);

        renderSurface.setFocusable(false);
        renderSurface.setClickable(true);
        renderSurface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(renderSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hud.setListener(this);
        addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Injects one validated run and creates its shared Viewport/Input/Render graph.
     * An already active Engine instance cannot be rebound because doing so
     * would discard the adapter's captured primary pointer.
     *
     * @throws IllegalStateException when the same Engine is rebound mid-run
     */
    public void setEngine(@NonNull GameEngine newEngine,
                          @NonNull LevelDefinition newLevel,
                          @NonNull FeedbackSystem newFeedback) {
        if (engine == newEngine && (engine.getState() == EngineState.RUNNING
                || engine.getState() == EngineState.PAUSED)) {
            throw new IllegalStateException(
                    "An active GameEngine cannot be rebound to a new input adapter");
        }
        if (engine != null && engine != newEngine) {
            if (engine.getState() == EngineState.RUNNING
                    || engine.getState() == EngineState.PAUSED) {
                engine.interrupt("game-engine-replaced");
            }
            FeedbackSystem previousFeedback = feedback;
            if (previousFeedback != null) previousFeedback.pauseAll();
        } else if (feedback != null && feedback != newFeedback) {
            feedback.pauseAll();
        }
        removeFrameCallback();
        engine = newEngine;
        level = newLevel;
        feedback = newFeedback;
        ghost = null;
        previousState = newEngine.getState();
        deliveredOutcome = null;

        viewport = new TouchInputAdapter.Viewport(newLevel.worldBounds());
        inputAdapter = new TouchInputAdapter(newEngine, newLevel, viewport, this);
        renderer = new GameRenderer(viewport);
        renderer.setLevel(newLevel);
        renderer.setVisualOptions(reducedEffects, highContrast);
        hud.setHighContrast(highContrast);
        hud.setLevel(newLevel);
        hud.update(newEngine, newLevel);
        updateViewport(getWidth(), getHeight());
        renderSurface.invalidate();
        scheduleFrameIfNeeded();
    }

    public void setGhost(@Nullable GhostTimeline ghost) {
        this.ghost = ghost;
        renderSurface.invalidate();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /**
     * Called by the owning Activity from {@code onPause}. A backgrounded run
     * is interrupted, never silently paused and resumed across lifecycle loss.
     */
    public void onHostPaused() {
        interruptForLifecycle("activity-paused");
    }

    /**
     * Called by the owning Activity from {@code onResume}. This only rearms
     * lazy audio/haptic feedback; it never restarts or resumes a gameplay run.
     */
    public void onHostResumed() {
        FeedbackSystem sound = feedback;
        if (sound != null) sound.resumeAll();
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        if (current != null && currentLevel != null) hud.update(current, currentLevel);
        renderSurface.invalidate();
        scheduleFrameIfNeeded();
    }

    public void setSafeInsets(int left, int top, int right, int bottom) {
        safeInsetLeft = Math.max(0, left);
        safeInsetTop = Math.max(0, top);
        safeInsetRight = Math.max(0, right);
        safeInsetBottom = Math.max(0, bottom);
        hud.setSafeInsets(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        updateViewport(getWidth(), getHeight());
    }

    public void setLeftHanded(boolean leftHanded) {
        this.leftHanded = leftHanded;
        hud.setLeftHanded(leftHanded);
        updateViewport(getWidth(), getHeight());
    }

    public void setVisualOptions(boolean reducedEffects, boolean highContrast) {
        this.reducedEffects = reducedEffects;
        this.highContrast = highContrast;
        if (renderer != null) renderer.setVisualOptions(reducedEffects, highContrast);
        hud.setHighContrast(highContrast);
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        if (current != null && currentLevel != null) hud.update(current, currentLevel);
        renderSurface.invalidate();
    }

    @Nullable
    public GameEngine getEngine() { return engine; }

    @Override
    public void onPauseRequested() {
        GameEngine current = engine;
        if (current == null || current.getState() != EngineState.RUNNING) return;
        current.pause(SystemClock.uptimeMillis());
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onPauseRequested();
        handleEngineMutation(true);
    }

    @Override
    public void onInputChanged() {
        handleEngineMutation(true);
        updateNearBoundaryFeedback();
    }

    private void handleEngineMutation(boolean drainEvents) {
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        if (current == null || currentLevel == null) return;

        if (drainEvents) dispatchEngineEvents(current);
        synchronizeState(current);
        hud.update(current, currentLevel);
        renderSurface.invalidate();
        scheduleFrameIfNeeded();
    }

    private void dispatchEngineEvents(GameEngine current) {
        List<GameEvent> events = current.drainEvents();
        FeedbackSystem sound = feedback;
        if (sound == null || events.isEmpty()) return;
        for (int i = 0; i < events.size(); i++) {
            GameEvent event = events.get(i);
            if ("OBJECTIVE".equals(event.type())) {
                sound.onObjectiveCollected();
            } else if ("BLOCKED".equals(event.type()) && !"goal".equals(event.detail())) {
                sound.onGateBlocked();
            }
        }
    }

    private void synchronizeState(GameEngine current) {
        EngineState next = current.getState();
        if (next == previousState) return;

        FeedbackSystem sound = feedback;
        if (sound != null) {
            if (next == EngineState.RUNNING) {
                if (previousState == EngineState.READY) sound.onStart();
                else if (previousState == EngineState.PAUSED) sound.onResume();
            } else if (next == EngineState.PAUSED) {
                sound.onPause();
            } else if (next == EngineState.COMPLETED) {
                sound.onSuccess();
            } else if (next == EngineState.FAILED) {
                sound.onFailure();
            } else if (next == EngineState.INTERRUPTED) {
                sound.onInterrupt();
            }
        }

        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onEngineStateChanged(previousState, next);
            if ((next == EngineState.COMPLETED || next == EngineState.FAILED
                    || next == EngineState.INTERRUPTED)
                    && current.getOutcome() != null
                    && current.getOutcome() != deliveredOutcome) {
                deliveredOutcome = current.getOutcome();
                currentListener.onRunOutcome(deliveredOutcome);
            }
        }
        previousState = next;
    }

    private void updateNearBoundaryFeedback() {
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        FeedbackSystem sound = feedback;
        if (current == null || currentLevel == null || sound == null
                || current.getState() != EngineState.RUNNING) return;
        com.neonmaze.core.GeometryCollision.Vec2 marker = current.getMarkerPosition();
        if (marker == null) return;
        double radius = currentLevel.effectiveRadius();
        boolean insideGameplay = currentLevel.walkableRegion().containsCenter(marker, radius);
        boolean insideWarning = currentLevel.walkableRegion().containsCenter(
                marker, radius + NEAR_BOUNDARY_WARNING_WORLD);
        if (insideGameplay && !insideWarning) sound.onNearBoundary();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        hud.setSafeInsets(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        updateViewport(width, height);
    }

    private void updateViewport(int width, int height) {
        TouchInputAdapter.Viewport currentViewport = viewport;
        if (currentViewport == null || width <= 0 || height <= 0) return;
        int left = hud.playfieldLeftInsetPx(width, height);
        int top = hud.playfieldTopInsetPx(width, height);
        int right = width - hud.playfieldRightInsetPx(width, height);
        int bottom = height - hud.playfieldBottomInsetPx();
        if (right <= left || bottom <= top) {
            currentViewport.setContentRect(0f, 0f, 0f, 0f);
        } else {
            currentViewport.setContentRect(left, top, right, bottom);
        }
        GameRenderer currentRenderer = renderer;
        if (currentRenderer != null) currentRenderer.onViewportChanged();
        TouchInputAdapter currentInput = inputAdapter;
        if (currentInput != null) currentInput.onViewportChanged();
        renderSurface.invalidate();
    }

    private void scheduleFrameIfNeeded() {
        if (!attached || frameScheduled || engine == null
                || engine.getState() != EngineState.RUNNING) return;
        if (choreographer == null) choreographer = Choreographer.getInstance();
        frameScheduled = true;
        choreographer.postFrameCallback(frameCallback);
    }

    private void removeFrameCallback() {
        if (!frameScheduled || choreographer == null) return;
        choreographer.removeFrameCallback(frameCallback);
        frameScheduled = false;
    }

    private void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        GameEngine current = engine;
        if (!attached || current == null || current.getState() != EngineState.RUNNING) return;

        current.onFrame(SystemClock.uptimeMillis());
        synchronizeState(current);
        LevelDefinition currentLevel = level;
        if (currentLevel != null) hud.update(current, currentLevel);
        renderSurface.invalidate();
        if (current.getState() == EngineState.RUNNING) {
            scheduleFrameIfNeeded();
        } else {
            // Deadline can be the only event produced by a frame tick.
            dispatchEngineEvents(current);
        }
    }

    private void interruptForLifecycle(@NonNull String reason) {
        removeFrameCallback();
        TouchInputAdapter adapter = inputAdapter;
        if (adapter != null) adapter.interruptActive(reason);
        else if (engine != null) engine.interrupt(reason);
        FeedbackSystem sound = feedback;
        if (sound != null) sound.pauseAll();
        if (engine != null && level != null) hud.update(engine, level);
        renderSurface.invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        scheduleFrameIfNeeded();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        interruptForLifecycle("game-view-detached");
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) interruptForLifecycle("game-window-hidden");
        else scheduleFrameIfNeeded();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility != VISIBLE) {
            interruptForLifecycle("game-view-hidden");
        } else if (changedView == this && visibility == VISIBLE) {
            scheduleFrameIfNeeded();
        }
    }

    private final class RenderSurface extends View {
        RenderSurface(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            GameRenderer currentRenderer = renderer;
            GameEngine currentEngine = engine;
            if (currentRenderer == null || currentEngine == null) return;
            currentRenderer.draw(canvas, currentEngine, ghost, inputAdapter);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            TouchInputAdapter adapter = inputAdapter;
            if (adapter == null) return false;
            boolean handled = adapter.onTouchEvent(event);
            ViewParent parent = getParent();
            if (parent != null) {
                if (handled && adapter.isTrackingPointer()) {
                    parent.requestDisallowInterceptTouchEvent(true);
                } else if (!adapter.isTrackingPointer()) {
                    // Release interception only after the primary gesture ends.
                    // Lifting a secondary pointer must not hand the stream back
                    // to a scrolling parent while the primary is still active.
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            }
            return handled;
        }
    }
}
