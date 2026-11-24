package com.neonmaze.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Choreographer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.util.Log;
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
        void onRestartRequested();
        void onExitRequested();
    }

    private final RenderSurface renderSurface;
    private final GameHud hud;
    private final Choreographer.FrameCallback frameCallback;
    private final GameMotionSystem motion = new GameMotionSystem();

    @Nullable private GameEngine engine;
    @Nullable private LevelDefinition level;
    @Nullable private FeedbackSystem feedback;
    @Nullable private GhostTimeline ghost;
    @Nullable private Listener listener;
    @Nullable private TouchInputAdapter.Viewport viewport;
    @Nullable private TouchInputAdapter inputAdapter;
    @Nullable private GameRenderer renderer;
    @Nullable private Bitmap boardTextureBitmap;
    @Nullable private Choreographer choreographer;

    private EngineState previousState = EngineState.READY;
    @Nullable private GameEngine.RunOutcome deliveredOutcome;
    @Nullable private GameEngine.RunOutcome pendingOutcome;
    private boolean attached;
    private boolean frameScheduled;
    private boolean leftHanded;
    private boolean reducedMotion;
    private boolean reducedEffects;
    private boolean highContrast;
    private LocalStore.CosmeticLoadout cosmeticLoadout = LocalStore.CosmeticLoadout.defaults();
    private int lastDebugViewportRevision = Integer.MIN_VALUE;
    @Nullable private String lastDebugViewportState;
    @Nullable private String lastAccessibilitySummary;
    @Nullable private String lastAccessibilityLevelId;
    @Nullable private EngineState lastAccessibilityState;
    @Nullable private String lastAccessibilityGateId;
    @Nullable private String lastAccessibilityGateState;
    private int lastAccessibilityCollected = Integer.MIN_VALUE;
    private int lastAccessibilityMandatory = Integer.MIN_VALUE;
    private int lastAccessibilityMandatoryCollected = Integer.MIN_VALUE;
    private int lastAccessibilityObjectiveCount = Integer.MIN_VALUE;
    private int lastAccessibilityKeyCount = Integer.MIN_VALUE;

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

        renderSurface.setFocusable(true);
        renderSurface.setClickable(true);
        renderSurface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        renderSurface.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        renderSurface.setContentDescription(getContext().getString(R.string.game_playfield_accessibility));
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
        pendingOutcome = null;
        motion.clear();

        viewport = new TouchInputAdapter.Viewport(newLevel.worldBounds());
        inputAdapter = new TouchInputAdapter(newEngine, newLevel, viewport, this);
        if (boardTextureBitmap == null || boardTextureBitmap.isRecycled()) {
            BitmapFactory.Options textureOptions = new BitmapFactory.Options();
            textureOptions.inScaled = false;
            textureOptions.inPreferredConfig = Bitmap.Config.RGB_565;
            boardTextureBitmap = BitmapFactory.decodeResource(getResources(),
                    R.drawable.prism_floor, textureOptions);
            // Warm the immutable texture before the first gameplay frame so
            // the initial draw does not pay the GPU upload cost where supported.
            if (boardTextureBitmap != null) boardTextureBitmap.prepareToDraw();
        }
        renderer = new GameRenderer(viewport, boardTextureBitmap);
        renderer.onSurfaceSizeChanged(renderSurface.getWidth(), renderSurface.getHeight());
        renderer.setLevel(newLevel);
        renderer.setZoneLabels(getContext().getString(R.string.gameplay_start_label),
                getContext().getString(R.string.gameplay_goal_label));
        renderer.setVisualOptions(reducedEffects, highContrast);
        renderer.setReducedMotion(reducedMotion);
        motion.setReducedMotion(reducedMotion);
        renderer.setMotionState(GameMotionSystem.CUE_NONE, 1f,
                GameMotionSystem.TERMINAL_NONE, 1f);
        renderer.setCosmeticLoadout(cosmeticLoadout);
        hud.setHighContrast(highContrast);
        hud.setLevel(newLevel);
        hud.update(newEngine, newLevel);
        updateAccessibilitySummary(true);
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
        if (listener != null) {
            deliverPendingOutcomeIfReady(System.nanoTime());
            scheduleFrameIfNeeded();
        }
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
        updateAccessibilitySummary(true);
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

    public void setCosmeticLoadout(@NonNull LocalStore.CosmeticLoadout loadout) {
        cosmeticLoadout = loadout;
        if (renderer != null) renderer.setCosmeticLoadout(loadout);
        renderSurface.invalidate();
    }

    public void setVisualOptions(boolean reducedMotion, boolean reducedEffects,
                                 boolean highContrast) {
        this.reducedMotion = reducedMotion;
        this.reducedEffects = reducedEffects;
        this.highContrast = highContrast;
        motion.setReducedMotion(reducedMotion);
        if (renderer != null) {
            renderer.setVisualOptions(reducedEffects, highContrast);
            renderer.setReducedMotion(reducedMotion);
            applyMotionState(System.nanoTime());
        }
        hud.setHighContrast(highContrast);
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        if (current != null && currentLevel != null) hud.update(current, currentLevel);
        renderSurface.invalidate();
        deliverPendingOutcomeIfReady(System.nanoTime());
        scheduleFrameIfNeeded();
    }

    /** Compatibility overload for call sites that do not expose Reduced Motion. */
    public void setVisualOptions(boolean reducedEffects, boolean highContrast) {
        setVisualOptions(false, reducedEffects, highContrast);
    }

    @Nullable
    public GameEngine getEngine() { return engine; }

    @Override
    public void onPlayfieldInsetsChanged() {
        // The HUD reports its measured, font-scale-aware footprint after layout.
        // Recompute the shared Viewport only after those dimensions are current.
        updateViewport(getWidth(), getHeight());
        renderSurface.invalidate();
    }

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
    public void onRestartRequested() {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onRestartRequested();
    }

    @Override
    public void onExitRequested() {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onExitRequested();
    }

    @Override
    public void onInputChanged() {
        handleEngineMutation(true);
    }

    private void handleEngineMutation(boolean drainEvents) {
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        if (current == null || currentLevel == null) return;

        if (drainEvents) dispatchEngineEvents(current);
        synchronizeState(current);
        hud.update(current, currentLevel);
        updateNearBoundaryFeedback();
        applyMotionState(System.nanoTime());
        renderSurface.invalidate();
        deliverPendingOutcomeIfReady(System.nanoTime());
        scheduleFrameIfNeeded();
    }

    private void dispatchEngineEvents(GameEngine current) {
        List<GameEvent> events = current.drainEvents();
        if (events.isEmpty()) return;
        FeedbackSystem sound = feedback;
        LevelDefinition currentLevel = level;
        if (currentLevel == null) return;
        long nowNanos = System.nanoTime();
        for (int i = 0; i < events.size(); i++) {
            GameEvent event = events.get(i);
            if ("OBJECTIVE".equals(event.type())) {
                com.neonmaze.core.LevelSystem.Objective objective = currentLevel.objective(event.detail());
                if (objective != null && objective.grantsKeyId() != null) {
                    motion.triggerCue(GameMotionSystem.CUE_KEY, nowNanos);
                    if (sound != null) sound.onKeyCollected();
                } else {
                    motion.triggerCue(GameMotionSystem.CUE_OBJECTIVE, nowNanos);
                    if (sound != null) sound.onObjectiveCollected();
                }
            } else if ("GATE_PASS".equals(event.type())) {
                motion.triggerCue(GameMotionSystem.CUE_GATE, nowNanos);
                if (sound != null) sound.onGatePassed();
            } else if ("BLOCKED".equals(event.type())) {
                motion.triggerCue(GameMotionSystem.CUE_BLOCKED, nowNanos);
                if (sound != null && !"goal".equals(event.detail())) sound.onGateBlocked();
            }
        }
    }

    private void synchronizeState(GameEngine current) {
        EngineState next = current.getState();
        if (next == previousState) return;

        EngineState previous = previousState;
        long nowNanos = System.nanoTime();
        if (next != EngineState.RUNNING && renderer != null) {
            renderer.setBoundaryWarning(false);
        }
        FeedbackSystem sound = feedback;
        if (sound != null) {
            if (next == EngineState.RUNNING) {
                if (previous == EngineState.READY) sound.onStart();
                else if (previous == EngineState.PAUSED) sound.onResume();
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

        if (next == EngineState.RUNNING) {
            motion.triggerCue(previous == EngineState.PAUSED
                    ? GameMotionSystem.CUE_RESUME : GameMotionSystem.CUE_START, nowNanos);
        } else if (next == EngineState.PAUSED) {
            motion.triggerCue(GameMotionSystem.CUE_PAUSE, nowNanos);
        } else if (next == EngineState.COMPLETED) {
            motion.triggerTerminal(GameMotionSystem.TERMINAL_SUCCESS, nowNanos);
        } else if (next == EngineState.FAILED) {
            motion.triggerTerminal(GameMotionSystem.TERMINAL_FAILURE, nowNanos);
        }

        Listener currentListener = listener;
        if (currentListener != null) currentListener.onEngineStateChanged(previous, next);
        previousState = next;

        if ((next == EngineState.COMPLETED || next == EngineState.FAILED
                || next == EngineState.INTERRUPTED)
                && current.getOutcome() != null
                && current.getOutcome() != deliveredOutcome
                && current.getOutcome() != pendingOutcome) {
            pendingOutcome = current.getOutcome();
        }
        applyMotionState(nowNanos);
        deliverPendingOutcomeIfReady(nowNanos);
        scheduleFrameIfNeeded();
    }

    private void applyMotionState(long nowNanos) {
        GameRenderer currentRenderer = renderer;
        if (currentRenderer == null) return;
        currentRenderer.setPresentationTimeNanos(nowNanos);
        currentRenderer.setMotionState(motion.cueType(), motion.cueProgress(nowNanos),
                motion.terminalType(), motion.terminalProgress(nowNanos));
    }

    private void deliverPendingOutcomeIfReady(long nowNanos) {
        GameEngine.RunOutcome outcome = pendingOutcome;
        if (outcome == null || !motion.isTerminalComplete(nowNanos)) return;
        Listener currentListener = listener;
        if (currentListener == null) return;
        pendingOutcome = null;
        if (outcome == deliveredOutcome) return;
        deliveredOutcome = outcome;
        currentListener.onRunOutcome(outcome);
    }

    private void updateNearBoundaryFeedback() {
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        FeedbackSystem sound = feedback;
        GameRenderer currentRenderer = renderer;
        if (current == null || currentLevel == null
                || current.getState() != EngineState.RUNNING) {
            if (currentRenderer != null) currentRenderer.setBoundaryWarning(false);
            return;
        }
        com.neonmaze.core.GeometryCollision.Vec2 marker = current.getMarkerPosition();
        if (marker == null) {
            if (currentRenderer != null) currentRenderer.setBoundaryWarning(false);
            return;
        }
        double radius = currentLevel.effectiveRadius();
        boolean insideGameplay = currentLevel.walkableRegion().containsCenter(marker, radius);
        boolean insideWarning = currentLevel.walkableRegion().containsCenter(
                marker, radius + NEAR_BOUNDARY_WARNING_WORLD);
        boolean warning = insideGameplay && !insideWarning;
        if (currentRenderer != null) currentRenderer.setBoundaryWarning(warning);
        if (warning && sound != null) sound.onNearBoundary();
        renderSurface.invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        hud.setSafeInsets(safeInsetLeft, safeInsetTop, safeInsetRight, safeInsetBottom);
        updateViewport(width, height);
    }

    private void updateViewport(int width, int height) {
        TouchInputAdapter.Viewport currentViewport = viewport;
        if (currentViewport == null || width <= 0 || height <= 0) {
            logViewportState("WAITING_FOR_LAYOUT view=" + width + "x" + height);
            return;
        }
        int left = hud.playfieldLeftInsetPx(width, height);
        int top = hud.playfieldTopInsetPx(width, height);
        int right = width - hud.playfieldRightInsetPx(width, height);
        int bottom = height - hud.playfieldBottomInsetPx();
        if (right <= left || bottom <= top) {
            currentViewport.setContentRect(0f, 0f, 0f, 0f);
        } else {
            currentViewport.setContentRect(left, top, right, bottom);
        }
        if (BuildConfig.DEBUG) {
            String state = "view=" + width + "x" + height
                    + " content=" + currentViewport.contentLeft() + ","
                    + currentViewport.contentTop() + "-" + currentViewport.contentRight()
                    + "," + currentViewport.contentBottom()
                    + " proposed=" + left + "," + top + "-" + right + "," + bottom
                    + " ready=" + currentViewport.isReady()
                    + " scale=" + currentViewport.scale()
                    + " revision=" + currentViewport.revision();
            if (currentViewport.revision() != lastDebugViewportRevision
                    || !state.equals(lastDebugViewportState)) {
                logViewportState(state);
                lastDebugViewportRevision = currentViewport.revision();
            }
        }
        GameRenderer currentRenderer = renderer;
        if (currentRenderer != null) currentRenderer.onViewportChanged();
        TouchInputAdapter currentInput = inputAdapter;
        if (currentInput != null) currentInput.onViewportChanged();
        renderSurface.invalidate();
    }


    private void logViewportState(@NonNull String state) {
        if (BuildConfig.DEBUG && !state.equals(lastDebugViewportState)) {
            lastDebugViewportState = state;
            Log.d("NeonMazeViewport", state);
        }
    }

    private void scheduleFrameIfNeeded() {
        GameEngine current = engine;
        if (!attached || frameScheduled || current == null) return;
        boolean engineRunning = current.getState() == EngineState.RUNNING;
        if (!engineRunning && !motion.isAnimating(System.nanoTime())) return;
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
        if (!attached || current == null) return;

        if (current.getState() == EngineState.RUNNING) {
            current.onFrame(SystemClock.uptimeMillis());
            synchronizeState(current);
        }
        dispatchEngineEvents(current);
        long nowNanos = System.nanoTime();
        applyMotionState(nowNanos);
        LevelDefinition currentLevel = level;
        if (currentLevel != null) hud.update(current, currentLevel);
        renderSurface.invalidate();
        deliverPendingOutcomeIfReady(nowNanos);
        scheduleFrameIfNeeded();
    }

    private void interruptForLifecycle(@NonNull String reason) {
        removeFrameCallback();
        TouchInputAdapter adapter = inputAdapter;
        if (adapter != null) adapter.interruptActive(reason);
        else if (engine != null) engine.interrupt(reason);
        FeedbackSystem sound = feedback;
        if (sound != null) sound.pauseAll();
        if (engine != null && level != null) hud.update(engine, level);
        if (pendingOutcome != null) {
            motion.clear();
            applyMotionState(System.nanoTime());
            deliverPendingOutcomeIfReady(System.nanoTime());
        }
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

    private void updateAccessibilitySummary(boolean force) {
        GameEngine current = engine;
        LevelDefinition currentLevel = level;
        if (current == null || currentLevel == null) return;
        int collected = 0;
        int mandatory = 0;
        int mandatoryCollected = 0;
        for (com.neonmaze.core.LevelSystem.Objective objective : currentLevel.objectives()) {
            boolean required = objective.type() == com.neonmaze.core.LevelSystem.ObjectiveType.MANDATORY
                    ;
            boolean complete = current.isObjectiveCollected(objective.id());
            if (complete) collected++;
            if (required) {
                mandatory++;
                if (complete) mandatoryCollected++;
            }
        }
        String gateId = current.getRelevantGateId();
        String gateState = gateId == null ? "none" : current.getGateRenderState(gateId).name();
        int objectiveCount = currentLevel.objectives().size();
        int keyCount = current.getCollectedKeyCount();
        EngineState engineState = current.getState();
        String levelId = currentLevel.levelId();
        // Render frames compare only primitive/reference state. Localized text is
        // rebuilt only when a gameplay semantic changes, never per animation frame.
        String normalizedGateId = gateId == null ? "" : gateId;
        if (!force && levelId.equals(lastAccessibilityLevelId)
                && engineState == lastAccessibilityState
                && collected == lastAccessibilityCollected
                && mandatory == lastAccessibilityMandatory
                && mandatoryCollected == lastAccessibilityMandatoryCollected
                && objectiveCount == lastAccessibilityObjectiveCount
                && keyCount == lastAccessibilityKeyCount
                && normalizedGateId.equals(lastAccessibilityGateId)
                && gateState.equals(lastAccessibilityGateState)) return;
        lastAccessibilityLevelId = levelId;
        lastAccessibilityState = engineState;
        lastAccessibilityCollected = collected;
        lastAccessibilityMandatory = mandatory;
        lastAccessibilityMandatoryCollected = mandatoryCollected;
        lastAccessibilityObjectiveCount = objectiveCount;
        lastAccessibilityKeyCount = keyCount;
        lastAccessibilityGateId = normalizedGateId;
        lastAccessibilityGateState = gateState;
        int levelNameId = getResources().getIdentifier(currentLevel.displayNameKey(),
                "string", getContext().getPackageName());
        String levelName = levelNameId == 0 ? levelId : getContext().getString(levelNameId);
        String summary = getContext().getString(R.string.game_accessibility_summary,
                levelName, getContext().getString(accessibilityStateResource(engineState)),
                collected, objectiveCount, mandatoryCollected, mandatory, keyCount,
                getContext().getString(accessibilityGateResource(gateState)));
        lastAccessibilitySummary = summary;
        renderSurface.setContentDescription(getContext().getString(
                R.string.game_playfield_accessibility_detail, summary));
    }

    private static int accessibilityStateResource(EngineState state) {
        switch (state) {
            case RUNNING: return R.string.game_accessibility_state_running;
            case PAUSED: return R.string.game_accessibility_state_paused;
            case FAILED: return R.string.game_accessibility_state_failed;
            case COMPLETED: return R.string.game_accessibility_state_completed;
            case INTERRUPTED: return R.string.game_accessibility_state_interrupted;
            case READY:
            default: return R.string.game_accessibility_state_ready;
        }
    }

    private static int accessibilityGateResource(String state) {
        if ("OPEN".equals(state)) return R.string.game_accessibility_gate_open;
        if ("WARNING".equals(state)) return R.string.game_accessibility_gate_warning;
        if ("CLOSED".equals(state)) return R.string.game_accessibility_gate_closed;
        if ("LOCKED".equals(state)) return R.string.game_accessibility_gate_locked;
        return R.string.game_accessibility_gate_none;
    }

    private final class RenderSurface extends View {
        RenderSurface(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            GameRenderer currentRenderer = renderer;
            if (currentRenderer != null) currentRenderer.onSurfaceSizeChanged(width, height);
            if (BuildConfig.DEBUG) {
                Log.d("NeonMazeSurface", "size=" + width + "x" + height);
            }
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            GameRenderer currentRenderer = renderer;
            GameEngine currentEngine = engine;
            if (currentRenderer == null || currentEngine == null) return;
            currentRenderer.draw(canvas, currentEngine, ghost, inputAdapter);
            updateAccessibilitySummary(false);
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
