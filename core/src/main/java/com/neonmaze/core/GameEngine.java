package com.neonmaze.core;

import com.neonmaze.core.GeometryCollision.Vec2;
import com.neonmaze.core.LevelSystem.Gate;
import com.neonmaze.core.LevelSystem.GateMode;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.ObjectiveType;
import com.neonmaze.core.LevelSystem.Zone;
import com.neonmaze.core.LevelSystem.ZoneShape;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.RunResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic game engine for NEON MAZE.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Owns the run state machine: {@link EngineState}.</li>
 *   <li>Advances active time and gate schedules independently of frame rate.</li>
 *   <li>Processes single input samples in strict chronological order.</li>
 *   <li>Arbitrates swept wall, finite-gate-barrier, objective, goal, and
 *       deadline contacts on one chronological simulation timeline.</li>
 *   <li>Tracks full-disk passage occupancy so a timed gate cannot close
 *       around a marker that has already entered its passage.</li>
 *   <li>Records a {@link ReplayTrace} of the run (when within size limits).</li>
 *   <li>Produces a {@link RunOutcome} on {@code COMPLETED}, {@code FAILED},
 *       or {@code INTERRUPTED}.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><strong>Uptime clock.</strong> Every entry point accepts a
 *       {@code uptimeMs} parameter, monotonic non-decreasing, of the same
 *       origin as {@code SystemClock.uptimeMillis()}. The engine derives
 *       active time as {@code uptimeMs - runStartUptimeMs - totalPausedMs}.</li>
 *   <li><strong>Sample ordering.</strong> Samples must be processed in
 *       strict chronological order. Out-of-order samples throw
 *       {@link IllegalArgumentException}, because that indicates an
 *       adapter bug and must not be silently swallowed.</li>
 *   <li><strong>Boundary and gate contract.</strong> The earliest swept
 *       wall contact is a {@link FailureReason#PATH_BREACH}. A closed or
 *       locked finite gate Barrier clamps the marker using its effective
 *       disk radius; gate contact is not a failure. Gate pass is recorded
 *       only when the marker centre crosses the finite Barrier segment.</li>
 *   <li><strong>Deferred close.</strong> If the player is inside a gate
 *       passage when the nominal state becomes closed, the gate remains
 *       effectively open until the player exits. Once outside, normal
 *       blocking resumes on re-entry.</li>
 *   <li><strong>Deadline tie-break.</strong> Time Attack and Daily Challenge
 *       share the simulation deadline. Swept contacts are ordered by their
 *       contact time; at an exact deadline tie, wall/gate/objective/goal
 *       priority is preserved and a valid Goal wins over the deadline.
 *       Early pointer release in these timed modes is an interruption,
 *       not an incomplete-run failure.</li>
 *   <li><strong>Snapshots and events.</strong> {@link Snapshot} copies
 *       scalar state, sets, gate states, and recent events. Its trail is an
 *       unmodifiable append-only view of the current run trail to avoid
 *       copying the full path on every frame. {@link #drainEvents()} returns
 *       and clears a bounded event buffer for presentation adapters.</li>
 *   <li><strong>Replay overflow.</strong> The run remains valid; the trace
 *       is marked unavailable. {@link RunOutcome#trace()} is then
 *       {@code null} and {@link RunOutcome#replayUnavailable()} is
 *       {@code true}.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Not thread-safe. The caller must invoke all entry points on the
 * single rendering / input thread (Android main thread).</p>
 *
 * <h2>No Android</h2>
 * This file must not import {@code android.*} or {@code androidx.*}.
 */
public final class GameEngine {

    // =====================================================================
    // Public enums
    // =====================================================================

    /** Run state. {@code OBJECTIVE_BLOCKED} is a flag, not a state. */
    public enum EngineState {
        READY,
        RUNNING,
        PAUSED,
        FAILED,
        COMPLETED,
        INTERRUPTED
    }

    /** Terminal failure reason. {@code NONE} when the run is not failed. */
    public enum FailureReason {
        NONE,
        PATH_BREACH,
        INCOMPLETE_RUN,
        TIME_EXPIRED
    }

    /** Effective gate state for rendering. */
    public enum GateRenderState {
        OPEN,
        WARNING,
        CLOSED,
        LOCKED
    }

    // =====================================================================
    // RunOutcome
    // =====================================================================

    /**
     * Immutable outcome of a run. Available after the state leaves
     * {@code RUNNING} or {@code PAUSED}. The {@code trace} may be
     * {@code null} when replay overflow occurred.
     */
    public static final class RunOutcome {

        private final ReplayTrace trace;
        private final ReplayMode mode;
        private final String interruptReason;
        private final int score;
        private final int stars;
        private final long elapsedMs;
        private final boolean assisted;
        private final RunResult result;
        private final List<String> collectedObjectiveIds;
        private final List<String> collectedKeyIds;
        private final FailureReason failureReason;
        private final boolean replayUnavailable;

        private RunOutcome(ReplayTrace trace, ReplayMode mode, String interruptReason,
                           int score, int stars, long elapsedMs, boolean assisted,
                           RunResult result, List<String> collectedObjectiveIds,
                           List<String> collectedKeyIds, FailureReason failureReason,
                           boolean replayUnavailable) {
            this.trace = trace;
            this.mode = mode;
            this.interruptReason = interruptReason;
            this.score = score;
            this.stars = stars;
            this.elapsedMs = elapsedMs;
            this.assisted = assisted;
            this.result = result;
            this.collectedObjectiveIds = Collections.unmodifiableList(
                    new ArrayList<>(collectedObjectiveIds));
            this.collectedKeyIds = Collections.unmodifiableList(
                    new ArrayList<>(collectedKeyIds));
            this.failureReason = failureReason;
            this.replayUnavailable = replayUnavailable;
        }

        public ReplayTrace trace() {
            return trace;
        }

        public ReplayMode mode() {
            return mode;
        }

        public String interruptReason() {
            return interruptReason;
        }

        public int score() {
            return score;
        }

        public int stars() {
            return stars;
        }

        public long elapsedMs() {
            return elapsedMs;
        }

        public boolean assisted() {
            return assisted;
        }

        public RunResult result() {
            return result;
        }

        public List<String> collectedObjectiveIds() {
            return collectedObjectiveIds;
        }

        public List<String> collectedKeyIds() {
            return collectedKeyIds;
        }

        public FailureReason failureReason() {
            return failureReason;
        }

        public boolean replayUnavailable() {
            return replayUnavailable;
        }
    }

    /** Immutable gate state included in a render-facing Snapshot. */
    public static final class GateSnapshot {
        private final String id;
        private final GateRenderState state;

        private GateSnapshot(String id, GateRenderState state) {
            this.id = id;
            this.state = state;
        }

        public String id() { return id; }
        public GateRenderState state() { return state; }
    }

    /** Bounded immutable event exposed to UI/audio adapters. */
    public static final class GameEvent {
        private final long activeMs;
        private final String type;
        private final String detail;

        private GameEvent(long activeMs, String type, String detail) {
            this.activeMs = activeMs;
            this.type = type;
            this.detail = detail;
        }

        public long activeMs() { return activeMs; }
        public String type() { return type; }
        public String detail() { return detail; }
    }

    /**
     * Immutable state view for renderers and HUD code. Gate/event/objective
     * collections are defensive snapshots. The trail is an unmodifiable,
     * append-only view of the current run so a renderer can reuse it without
     * copying the full path every frame; a restart replaces the backing list.
     */
    public static final class Snapshot {
        private final ReplayMode mode;
        private final EngineState state;
        private final FailureReason failureReason;
        private final String interruptReason;
        private final long activeMs;
        private final Vec2 marker;
        private List<Vec2> trail;
        private final Set<String> collectedObjectives;
        private final Set<String> collectedKeys;
        private final List<GateSnapshot> gates;
        private final List<GameEvent> recentEvents;
        private final boolean assisted;
        private final boolean objectiveBlocked;
        private final boolean replayUnavailable;
        private final int score;
        private final int stars;

        private Snapshot(ReplayMode mode, EngineState state,
                         FailureReason failureReason, String interruptReason,
                         long activeMs, Vec2 marker, List<Vec2> trail,
                         Set<String> collectedObjectives, Set<String> collectedKeys,
                         List<GateSnapshot> gates, List<GameEvent> recentEvents,
                         boolean assisted, boolean objectiveBlocked,
                         boolean replayUnavailable, int score, int stars) {
            this.mode = mode;
            this.state = state;
            this.failureReason = failureReason;
            this.interruptReason = interruptReason;
            this.activeMs = activeMs;
            this.marker = marker;
            this.trail = Objects.requireNonNull(trail, "trail");
            this.collectedObjectives = Collections.unmodifiableSet(
                    new LinkedHashSet<>(collectedObjectives));
            this.collectedKeys = Collections.unmodifiableSet(
                    new LinkedHashSet<>(collectedKeys));
            this.gates = Collections.unmodifiableList(new ArrayList<>(gates));
            this.recentEvents = Collections.unmodifiableList(new ArrayList<>(recentEvents));
            this.assisted = assisted;
            this.objectiveBlocked = objectiveBlocked;
            this.replayUnavailable = replayUnavailable;
            this.score = score;
            this.stars = stars;
        }

        public ReplayMode mode() { return mode; }
        public EngineState state() { return state; }
        public FailureReason failureReason() { return failureReason; }
        public String interruptReason() { return interruptReason; }
        public long activeMs() { return activeMs; }
        public Vec2 marker() { return marker; }
        public List<Vec2> trail() { return trail; }
        public Set<String> collectedObjectives() { return collectedObjectives; }
        public Set<String> collectedKeys() { return collectedKeys; }
        public List<GateSnapshot> gates() { return gates; }
        public List<GameEvent> recentEvents() { return recentEvents; }
        public boolean assisted() { return assisted; }
        public boolean objectiveBlocked() { return objectiveBlocked; }
        public boolean replayUnavailable() { return replayUnavailable; }
        public int score() { return score; }
        public int stars() { return stars; }
    }

    // =====================================================================
    // Immutable configuration
    // =====================================================================

    private final LevelDefinition level;
    private final ReplayMode mode;
    private final long runTimestampMs;
    private final double effectiveRadius;
    private final double resumeAnchorRadius;

    // =====================================================================
    // Mutable run state
    // =====================================================================

    private EngineState state;
    private FailureReason failureReason;
    private boolean assisted;
    private boolean objectiveBlocked;

    private long runStartUptimeMs;
    private long lastUptimeMs;
    private long lastActiveMs;
    private long pauseStartUptimeMs;
    private long totalPausedMs;

    private int activePointerId;
    private Vec2 marker;

    private List<Vec2> trail;
    private List<Vec2> trailView;

    private final Set<String> collectedKeys;
    private final Set<String> collectedObjectives;

    // Primitive, grow-on-demand replay buffers: no TraceSample allocation per input sample.
    private long[] recordedSampleTimes;
    private double[] recordedSampleX;
    private double[] recordedSampleY;
    private int recordedSampleCount;
    private long[] recordedEventTimes;
    private String[] recordedEventTypes;
    private String[] recordedEventDetails;
    private int recordedEventCount;

    private static final int RECENT_EVENT_CAPACITY = 64;
    private final GameEvent[] recentEvents = new GameEvent[RECENT_EVENT_CAPACITY];
    private int recentEventStart;
    private int recentEventCount;
    private String interruptReason;

    // Reused per-segment event arbitration storage. Capacity is derived from
    // this validated level's Gate/Objective counts in the constructor.
    private static final byte EVENT_WALL = 0;
    private static final byte EVENT_GATE_ENTRY = 1;
    private static final byte EVENT_GATE_BARRIER = 2;
    private static final byte EVENT_GATE_CROSS = 3;
    private static final byte EVENT_OBJECTIVE = 4;
    private static final byte EVENT_GOAL = 5;
    private static final byte EVENT_DEADLINE = 6;
    private static final double EVENT_TIME_EPSILON = 1e-9;
    private static final double GATE_CLAMP_BACKOFF_WORLD = 0.5;

    private final double[] eventParameters;
    private final byte[] eventKinds;
    private final int[] eventIndices;
    private int eventCount;
    private final boolean[] gateHeldOpen;
    private final int[] gateLastSide;
    private final GeometryCollision.Contact wallContact;

    private int score;
    private int stars;
    private boolean replayUnavailable;
    private RunOutcome outcome;

    // =====================================================================
    // Construction
    // =====================================================================

    /**
     * @param level           validated level definition
     * @param mode            run mode
     * @param runTimestampMs  wall-clock timestamp to stamp into the replay;
     *                        injected so tests can be deterministic
     */
    public GameEngine(LevelDefinition level, ReplayMode mode, long runTimestampMs) {
        this.level = Objects.requireNonNull(level, "level");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.runTimestampMs = runTimestampMs;
        this.effectiveRadius = level.effectiveRadius();
        this.resumeAnchorRadius = level.playerRadius() * 2.0;

        this.trail = new ArrayList<>(1024);
        this.collectedKeys = new HashSet<>();
        this.collectedObjectives = new HashSet<>();
        this.recordedSampleTimes = new long[1024];
        this.recordedSampleX = new double[1024];
        this.recordedSampleY = new double[1024];
        this.recordedEventTimes = new long[64];
        this.recordedEventTypes = new String[64];
        this.recordedEventDetails = new String[64];
        int gateCount = level.gates().size();
        int objectiveCount = level.objectives().size();
        this.eventParameters = new double[Math.max(4, gateCount * 3 + objectiveCount + 3)];
        this.eventKinds = new byte[this.eventParameters.length];
        this.eventIndices = new int[this.eventParameters.length];
        this.gateHeldOpen = new boolean[gateCount];
        this.gateLastSide = new int[gateCount];
        this.wallContact = new GeometryCollision.Contact();

        for (Gate gate : level.gates()) {
            if (!gate.hasBarrier()) {
                throw new IllegalArgumentException("gate has no finite barrier: " + gate.id());
            }
        }
        resetToReady();
    }

    // =====================================================================
    // Public state queries
    // =====================================================================

    public EngineState getState() {
        return state;
    }

    public ReplayMode getMode() {
        return mode;
    }

    public Snapshot snapshot() {
        ArrayList<GateSnapshot> gateSnapshots = new ArrayList<>(level.gates().size());
        for (int i = 0; i < level.gates().size(); i++) {
            Gate gate = level.gates().get(i);
            gateSnapshots.add(new GateSnapshot(gate.id(), computeEffectiveGateState(i)));
        }
        return new Snapshot(mode, state, failureReason, interruptReason,
                lastActiveMs, marker, getTrail(),
                sortedIds(collectedObjectives), sortedIds(collectedKeys),
                gateSnapshots, copyRecentEvents(), assisted, objectiveBlocked,
                replayUnavailable, score, stars);
    }

    /** Returns and clears the bounded UI event buffer (oldest event first). */
    public List<GameEvent> drainEvents() {
        ArrayList<GameEvent> copy = copyRecentEvents();
        for (int i = 0; i < recentEventCount; i++) {
            recentEvents[(recentEventStart + i) % RECENT_EVENT_CAPACITY] = null;
        }
        recentEventStart = 0;
        recentEventCount = 0;
        return Collections.unmodifiableList(copy);
    }

    private ArrayList<GameEvent> copyRecentEvents() {
        ArrayList<GameEvent> copy = new ArrayList<>(recentEventCount);
        for (int i = 0; i < recentEventCount; i++) {
            copy.add(recentEvents[(recentEventStart + i) % RECENT_EVENT_CAPACITY]);
        }
        return copy;
    }

    private static Set<String> sortedIds(Set<String> values) {
        ArrayList<String> ids = new ArrayList<>(values);
        Collections.sort(ids);
        return new LinkedHashSet<>(ids);
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public long getActiveMs() {
        return lastActiveMs;
    }

    public Vec2 getMarkerPosition() {
        return marker;
    }

    public List<Vec2> getTrail() {
        if (trailView == null) {
            trailView = Collections.unmodifiableList(trail);
        }
        return trailView;
    }

    public Set<String> getCollectedKeys() {
        return Collections.unmodifiableSet(collectedKeys);
    }

    public boolean isObjectiveBlocked() {
        return objectiveBlocked;
    }

    public int getScore() {
        return score;
    }

    public int getStars() {
        return stars;
    }

    public boolean isAssisted() {
        return assisted;
    }

    public boolean isReplayUnavailable() {
        return replayUnavailable;
    }

    public RunOutcome getOutcome() {
        return outcome;
    }

    public boolean isObjectiveCollected(String objectiveId) {
        return collectedObjectives.contains(objectiveId);
    }

    public GateRenderState getGateRenderState(String gateId) {
        int gateIndex = gateIndex(gateId);
        if (gateIndex < 0) {
            throw new IllegalArgumentException("unknown gate: " + gateId);
        }
        Gate gate = level.gates().get(gateIndex);
        if (state == EngineState.READY) {
            if (gate.mode() == GateMode.KEY_LOCKED) return GateRenderState.LOCKED;
            if (gate.isWarningAt(0L)) return GateRenderState.WARNING;
            return gate.isOpenAt(0L, false)
                    ? GateRenderState.OPEN : GateRenderState.CLOSED;
        }
        return computeEffectiveGateState(gateIndex);
    }

    // =====================================================================
    // Input entry points
    // =====================================================================

    /**
     * Pointer down. From {@code READY}, attempts to acquire the start
     * zone. From {@code PAUSED}, attempts to resume. Ignored otherwise.
     */
    public void onPointerDown(int pointerId, double worldX, double worldY, long uptimeMs) {
        if (state == EngineState.READY) {
            Vec2 p = new Vec2(worldX, worldY);
            if (!level.startZone().contains(p)) return;
            if (!level.walkableRegion().containsCenter(p, effectiveRadius)) return;
            beginRun(pointerId, p, uptimeMs);
            return;
        }
        if (state == EngineState.PAUSED) {
            resume(worldX, worldY, uptimeMs);
            if (state == EngineState.RUNNING) activePointerId = pointerId;
        }
    }

    /**
     * Pointer move. Processes a single sample. The adapter must call this
     * method once per historical sample and once for the current sample,
     * in strict chronological order.
     */
    public void onPointerMove(int pointerId, double worldX, double worldY, long uptimeMs) {
        if (state != EngineState.RUNNING) return;
        if (pointerId != activePointerId) return;
        if (uptimeMs < lastUptimeMs) {
            throw new IllegalArgumentException(
                    "out-of-order pointer sample: " + uptimeMs + " < " + lastUptimeMs);
        }

        Vec2 next = new Vec2(worldX, worldY);
        long sampleEndActiveMs = activeTimeAt(uptimeMs);
        if (sampleEndActiveMs < lastActiveMs) {
            throw new IllegalArgumentException("input sample precedes simulation clock");
        }
        processSample(next, sampleEndActiveMs);
        lastUptimeMs = uptimeMs;
    }

    /**
     * Pointer up. Processes the final sample exactly once. Untimed modes
     * treat an early release as an incomplete run; timed modes only fail
     * at their deadline, so an early release interrupts the attempt.
     */
    public void onPointerUp(int pointerId, double worldX, double worldY, long uptimeMs) {
        if (state != EngineState.RUNNING) return;
        if (pointerId != activePointerId) return;

        onPointerMove(pointerId, worldX, worldY, uptimeMs);
        if (state != EngineState.RUNNING) return;

        if (hasTimeLimit()) {
            if (lastActiveMs >= level.timeAttackLimitMs()) {
                lastActiveMs = level.timeAttackLimitMs();
                fail(FailureReason.TIME_EXPIRED);
            } else {
                interrupt("pointer-up-before-deadline");
            }
            return;
        }
        fail(FailureReason.INCOMPLETE_RUN);
    }

    /**
     * Pointer cancel. Transitions to {@code INTERRUPTED}; never a failure.
     */
    public void onPointerCancel(int pointerId) {
        if (state != EngineState.RUNNING && state != EngineState.PAUSED) return;
        if (pointerId != activePointerId) return;
        interrupt("pointer-cancel");
    }

    /**
     * Per-frame tick. Advances the simulation clock and evaluates a crossed
     * deadline. Input samples must be delivered in timestamp order before a
     * later frame tick, including historical MotionEvent samples.
     */
    public void onFrame(long uptimeMs) {
        if (state != EngineState.RUNNING) return;
        if (uptimeMs < lastUptimeMs) {
            throw new IllegalArgumentException("out-of-order frame clock");
        }
        long activeMs = activeTimeAt(uptimeMs);
        lastUptimeMs = uptimeMs;
        if (hasTimeLimit() && activeMs >= level.timeAttackLimitMs()) {
            lastActiveMs = level.timeAttackLimitMs();
            fail(FailureReason.TIME_EXPIRED);
            return;
        }
        if (activeMs > lastActiveMs) lastActiveMs = activeMs;
    }

    // =====================================================================
    // Pause, resume, interrupt, restart
    // =====================================================================

    /**
     * User-initiated pause. Disallowed in Time Attack, Ghost Race, and
     * Daily Challenge. Marks the run as assisted in pausable modes.
     */
    public void pause(long uptimeMs) {
        if (state != EngineState.RUNNING) return;
        if (mode == ReplayMode.TIME_ATTACK || mode == ReplayMode.GHOST_RACE
                || mode == ReplayMode.DAILY_CHALLENGE) return;
        if (uptimeMs < lastUptimeMs) {
            throw new IllegalArgumentException("out-of-order pause clock");
        }

        advanceTo(uptimeMs);
        if (state != EngineState.RUNNING) return;

        pauseStartUptimeMs = uptimeMs;
        lastUptimeMs = uptimeMs;
        state = EngineState.PAUSED;
        assisted = true;
        recordEvent(lastActiveMs, "PAUSE", "user");
    }

    /**
     * Resume from pause. The pointer must land within
     * {@code resumeAnchorRadius} of the last marker position. Otherwise the
     * run transitions to {@code INTERRUPTED}, which is not a failure.
     */
    public void resume(double worldX, double worldY, long uptimeMs) {
        if (state != EngineState.PAUSED) return;
        if (uptimeMs < pauseStartUptimeMs) {
            throw new IllegalArgumentException("resume precedes pause start");
        }
        Vec2 p = new Vec2(worldX, worldY);
        if (marker == null) {
            interrupt("resume-without-marker");
            return;
        }
        double dx = p.x() - marker.x();
        double dy = p.y() - marker.y();
        if (dx * dx + dy * dy > resumeAnchorRadius * resumeAnchorRadius) {
            interrupt("resume-out-of-anchor");
            return;
        }

        totalPausedMs += uptimeMs - pauseStartUptimeMs;
        pauseStartUptimeMs = -1L;
        lastUptimeMs = uptimeMs;
        lastActiveMs = uptimeMs - runStartUptimeMs - totalPausedMs;
        state = EngineState.RUNNING;
        recordEvent(lastActiveMs, "RESUME", "user");
    }

    /**
     * OS-level interruption (activity backgrounded, phone call, etc).
     * Transitions to {@code INTERRUPTED}; never a failure.
     */
    public void interrupt(String reason) {
        if (state != EngineState.RUNNING && state != EngineState.PAUSED) return;
        interruptReason = reason == null ? "unspecified" : reason;
        recordEvent(lastActiveMs, "INTERRUPT", interruptReason);
        state = EngineState.INTERRUPTED;
        pauseStartUptimeMs = -1L;
        buildOutcome();
    }

    /**
     * Resets the engine to {@code READY}. The previous {@link RunOutcome}
     * is discarded; the caller must read it before calling
     * {@code restart()}.
     */
    public void restart() {
        resetToReady();
    }

    // =====================================================================
    // Core loop helpers
    // =====================================================================

    private void resetToReady() {
        state = EngineState.READY;
        failureReason = FailureReason.NONE;
        assisted = false;
        objectiveBlocked = false;
        runStartUptimeMs = 0L;
        lastUptimeMs = 0L;
        lastActiveMs = 0L;
        pauseStartUptimeMs = -1L;
        totalPausedMs = 0L;
        activePointerId = -1;
        marker = null;
        trail = new ArrayList<>(1024);
        trailView = null;
        collectedKeys.clear();
        collectedObjectives.clear();
        recordedSampleCount = 0;
        for (int i = 0; i < recordedEventCount; i++) {
            recordedEventTypes[i] = null;
            recordedEventDetails[i] = null;
        }
        recordedEventCount = 0;
        recentEventStart = 0;
        recentEventCount = 0;
        interruptReason = null;
        for (int i = 0; i < recentEvents.length; i++) recentEvents[i] = null;
        eventCount = 0;
        for (int i = 0; i < gateHeldOpen.length; i++) {
            gateHeldOpen[i] = false;
            gateLastSide[i] = 0;
        }
        score = 0;
        stars = 0;
        replayUnavailable = false;
        outcome = null;
    }

    private void beginRun(int pointerId, Vec2 startPosition, long uptimeMs) {
        if (uptimeMs < 0L) throw new IllegalArgumentException("uptimeMs must be >= 0");
        runStartUptimeMs = uptimeMs;
        lastUptimeMs = uptimeMs;
        lastActiveMs = 0L;
        pauseStartUptimeMs = -1L;
        totalPausedMs = 0L;
        activePointerId = pointerId;
        for (int i = 0; i < gateHeldOpen.length; i++) {
            gateHeldOpen[i] = false;
            Gate gate = level.gates().get(i);
            gateLastSide[i] = barrierSide(startPosition.x(), startPosition.y(),
                    gate.barrierStart(), gate.barrierEnd());
        }
        marker = startPosition;
        trail.add(startPosition);
        recordSample(0L, startPosition);
        recordEvent(0L, "START", "mode=" + mode.name());
        state = EngineState.RUNNING;
    }

    /** Advances active time without ever silently moving the clock backwards. */
    private void advanceTo(long uptimeMs) {
        if (state != EngineState.RUNNING) return;
        if (uptimeMs < lastUptimeMs) {
            throw new IllegalArgumentException("out-of-order simulation clock");
        }
        long activeMs = activeTimeAt(uptimeMs);
        lastUptimeMs = uptimeMs;
        if (hasTimeLimit() && activeMs >= level.timeAttackLimitMs()) {
            lastActiveMs = level.timeAttackLimitMs();
            fail(FailureReason.TIME_EXPIRED);
            return;
        }
        if (activeMs > lastActiveMs) lastActiveMs = activeMs;
    }

    private long activeTimeAt(long uptimeMs) {
        long elapsed = uptimeMs - runStartUptimeMs;
        if (elapsed < 0L) throw new IllegalArgumentException("uptime precedes run start");
        return Math.max(0L, elapsed - totalPausedMs);
    }

    /**
     * Processes one movement segment as a chronologically ordered set of
     * first-contact events. The event arrays and wall Contact are reused.
     */
    private void processSample(Vec2 newPos, long endActiveMs) {
        final double fromX = marker.x();
        final double fromY = marker.y();
        final double moveX = newPos.x() - fromX;
        final double moveY = newPos.y() - fromY;
        final long startActiveMs = lastActiveMs;
        if (endActiveMs < startActiveMs) {
            throw new IllegalArgumentException("sample active time moved backwards");
        }

        eventCount = 0;
        wallContact.reset();
        GeometryCollision.SweepStatus wallStatus = GeometryCollision.sweepSegment(
                level.walkableRegion(), marker, newPos, effectiveRadius, wallContact);
        if (wallStatus == GeometryCollision.SweepStatus.INVALID_INPUT) {
            throw new IllegalArgumentException("invalid geometry sweep input");
        }
        if (wallStatus == GeometryCollision.SweepStatus.HIT) {
            addEvent(wallContact.t(), EVENT_WALL, -1);
        }

        // Gate passage entry and finite Barrier contact are separate events.
        for (int i = 0; i < level.gates().size(); i++) {
            Gate gate = level.gates().get(i);
            double entryT = firstDiskZoneContactParameter(
                    fromX, fromY, moveX, moveY, gate.passage(), effectiveRadius);
            if (entryT >= 0.0) addEvent(entryT, EVENT_GATE_ENTRY, i);

            double barrierT = firstDiskSegmentContactParameter(
                    fromX, fromY, moveX, moveY,
                    gate.barrierStart(), gate.barrierEnd(), effectiveRadius);
            if (barrierT >= 0.0) addEvent(barrierT, EVENT_GATE_BARRIER, i);

            double crossingT = firstFiniteBarrierCrossingParameter(
                    fromX, fromY, moveX, moveY,
                    gate.barrierStart(), gate.barrierEnd(), gateLastSide[i]);
            if (crossingT >= 0.0) addEvent(crossingT, EVENT_GATE_CROSS, i);
        }

        // Objective zones are swept too; fast samples cannot jump over them.
        for (int i = 0; i < level.objectives().size(); i++) {
            Objective objective = level.objectives().get(i);
            if (collectedObjectives.contains(objective.id())) continue;
            double objectiveT = firstZoneEntryParameter(
                    fromX, fromY, moveX, moveY, objective.zone());
            if (objectiveT >= 0.0) addEvent(objectiveT, EVENT_OBJECTIVE, i);
        }

        double goalT = firstZoneEntryParameter(
                fromX, fromY, moveX, moveY, level.goalZone());
        if (goalT >= 0.0) addEvent(goalT, EVENT_GOAL, -1);

        // Deadline is an event on the same simulation timeline. A Goal at
        // exactly the deadline wins because GOAL sorts before DEADLINE.
        if (hasTimeLimit()) {
            long deadline = level.timeAttackLimitMs();
            if (startActiveMs <= deadline && endActiveMs >= deadline) {
                double deadlineT = endActiveMs == startActiveMs ? 0.0
                        : (deadline - startActiveMs) / (double) (endActiveMs - startActiveMs);
                addEvent(deadlineT, EVENT_DEADLINE, -1);
            } else if (startActiveMs > deadline) {
                addEvent(0.0, EVENT_DEADLINE, -1);
            }
        }

        sortEvents(startActiveMs, endActiveMs);
        boolean movementBlocked = false;
        double committedX = newPos.x();
        double committedY = newPos.y();

        for (int event = 0; event < eventCount; event++) {
            byte kind = eventKinds[event];
            int index = eventIndices[event];
            double t = eventParameters[event];
            long contactMs = timeAtParameter(startActiveMs, endActiveMs, t);

            if (kind == EVENT_DEADLINE) {
                if (state == EngineState.RUNNING) {
                    lastActiveMs = level.timeAttackLimitMs();
                    fail(FailureReason.TIME_EXPIRED);
                }
                break;
            }
            if (state != EngineState.RUNNING) break;
            if (movementBlocked) continue;

            double contactX = fromX + moveX * t;
            double contactY = fromY + moveY * t;

            if (kind == EVENT_WALL) {
                lastActiveMs = contactMs;
                commitPosition(contactX, contactY, contactMs);
                fail(FailureReason.PATH_BREACH);
                break;
            }

            if (kind == EVENT_GATE_ENTRY) {
                Gate gate = level.gates().get(index);
                if (gate.isOpenAt(contactMs,
                        gate.mode() == GateMode.KEY_LOCKED
                                && collectedKeys.contains(gate.requiredKeyId()))) {
                    gateHeldOpen[index] = true;
                }
                continue;
            }

            if (kind == EVENT_GATE_BARRIER) {
                Gate gate = level.gates().get(index);
                boolean keyCollected = gate.mode() == GateMode.KEY_LOCKED
                        && collectedKeys.contains(gate.requiredKeyId());
                boolean passable = gateHeldOpen[index] || gate.isOpenAt(contactMs, keyCollected);
                if (!passable) {
                    double safeT = gateSafeParameter(fromX, fromY,
                            moveX, moveY, t, gate.barrierStart(), gate.barrierEnd());
                    committedX = fromX + moveX * safeT;
                    committedY = fromY + moveY * safeT;
                    lastActiveMs = contactMs;
                    long safeSampleTime = timeAtParameter(
                            startActiveMs, endActiveMs, safeT);
                    commitPosition(committedX, committedY, safeSampleTime);
                    recordEvent(contactMs, "BLOCKED", gate.id());
                    gateHeldOpen[index] = false;
                    movementBlocked = true;
                    continue;
                }
                // A gate that becomes passable at Barrier contact must stay
                // open while the full marker disk exits its Passage.
                gateHeldOpen[index] = true;
                continue;
            }

            if (kind == EVENT_GATE_CROSS) {
                Gate gate = level.gates().get(index);
                boolean keyCollected = gate.mode() == GateMode.KEY_LOCKED
                        && collectedKeys.contains(gate.requiredKeyId());
                if (gateHeldOpen[index] || gate.isOpenAt(contactMs, keyCollected)) {
                    gateHeldOpen[index] = true;
                    commitPosition(contactX, contactY, contactMs);
                    recordEvent(contactMs, "GATE_PASS", gate.id());
                }
                continue;
            }

            if (kind == EVENT_OBJECTIVE) {
                Objective objective = level.objectives().get(index);
                if (collectedObjectives.contains(objective.id())) continue;
                if (objective.requiredKeyId() != null
                        && !collectedKeys.contains(objective.requiredKeyId())) {
                    continue;
                }
                commitPosition(contactX, contactY, contactMs);
                collectedObjectives.add(objective.id());
                if (objective.grantsKeyId() != null) {
                    collectedKeys.add(objective.grantsKeyId());
                }
                recordEvent(contactMs, "OBJECTIVE", objective.id());
                if (allMandatoryCompleted()
                        && zoneContains(level.goalZone(), contactX, contactY)) {
                    lastActiveMs = contactMs;
                    complete();
                    break;
                }
                continue;
            }

            if (kind == EVENT_GOAL) {
                if (allMandatoryCompleted()) {
                    lastActiveMs = contactMs;
                    commitPosition(contactX, contactY, contactMs);
                    complete();
                    break;
                }
                commitPosition(contactX, contactY, contactMs);
                if (!objectiveBlocked) {
                    objectiveBlocked = true;
                    recordEvent(contactMs, "BLOCKED", "goal");
                }
            }
        }

        if (state != EngineState.RUNNING) return;

        if (!movementBlocked) {
            if (Math.hypot(moveX, moveY) > 1e-12) {
                commitPosition(newPos, endActiveMs);
            }
        }
        if (state != EngineState.RUNNING) return;

        lastActiveMs = Math.max(lastActiveMs, endActiveMs);
        for (int i = 0; i < gateHeldOpen.length; i++) {
            if (gateHeldOpen[i]
                    && !diskOverlapsZone(committedX, committedY,
                    level.gates().get(i).passage(), effectiveRadius)) {
                gateHeldOpen[i] = false;
            }
            int endSide = barrierSide(committedX, committedY,
                    level.gates().get(i).barrierStart(),
                    level.gates().get(i).barrierEnd());
            if (endSide != 0) gateLastSide[i] = endSide;
        }

        if (!level.goalZone().contains(marker)) objectiveBlocked = false;
    }

    private void commitPosition(Vec2 position, long timeMs) {
        if (position == null) return;
        commitPosition(position.x(), position.y(), timeMs, position);
    }

    private void commitPosition(double x, double y, long timeMs) {
        commitPosition(x, y, timeMs, null);
    }

    private void commitPosition(double x, double y, long timeMs, Vec2 reusable) {
        if (marker == null || Math.abs(marker.x() - x) > 1e-12
                || Math.abs(marker.y() - y) > 1e-12) {
            marker = reusable != null ? reusable : new Vec2(x, y);
            trail.add(marker);
            trailView = null;
            recordSample(timeMs, x, y);
        }
    }

    private void addEvent(double t, byte kind, int index) {
        if (!Double.isFinite(t) || t < -EVENT_TIME_EPSILON
                || t > 1.0 + EVENT_TIME_EPSILON) return;
        if (eventCount >= eventParameters.length) {
            throw new IllegalStateException("GameEngine event buffer capacity exceeded");
        }
        eventParameters[eventCount] = Math.max(0.0, Math.min(1.0, t));
        eventKinds[eventCount] = kind;
        eventIndices[eventCount] = index;
        eventCount++;
    }

    private void sortEvents(long startActiveMs, long endActiveMs) {
        for (int i = 1; i < eventCount; i++) {
            double t = eventParameters[i];
            byte kind = eventKinds[i];
            int index = eventIndices[i];
            int j = i - 1;
            while (j >= 0 && eventComesAfter(
                    eventParameters[j], eventKinds[j], t, kind,
                    startActiveMs, endActiveMs)) {
                eventParameters[j + 1] = eventParameters[j];
                eventKinds[j + 1] = eventKinds[j];
                eventIndices[j + 1] = eventIndices[j];
                j--;
            }
            eventParameters[j + 1] = t;
            eventKinds[j + 1] = kind;
            eventIndices[j + 1] = index;
        }
    }

    private static boolean eventComesAfter(double leftT, byte leftKind,
                                           double rightT, byte rightKind,
                                           long startActiveMs, long endActiveMs) {
        double leftTime = exactTimeAtParameter(startActiveMs, endActiveMs, leftT);
        double rightTime = exactTimeAtParameter(startActiveMs, endActiveMs, rightT);
        if (Math.abs(leftTime - rightTime) > EVENT_TIME_EPSILON) {
            return leftTime > rightTime;
        }
        return leftKind > rightKind;
    }

    private static double exactTimeAtParameter(long startMs, long endMs, double t) {
        if (endMs <= startMs) return startMs;
        return startMs + (endMs - startMs) * clamp01(t);
    }

    /** Converts a continuous contact time to the last fully elapsed millisecond. */
    private static long timeAtParameter(long startMs, long endMs, double t) {
        return (long) Math.floor(exactTimeAtParameter(startMs, endMs, t));
    }

    private boolean hasTimeLimit() {
        return mode == ReplayMode.TIME_ATTACK || mode == ReplayMode.DAILY_CHALLENGE;
    }

    private GateRenderState computeEffectiveGateState(int gateIndex) {
        Gate gate = level.gates().get(gateIndex);
        if (gateHeldOpen[gateIndex]) return GateRenderState.OPEN;
        if (gate.mode() == GateMode.KEY_LOCKED) {
            return collectedKeys.contains(gate.requiredKeyId())
                    ? GateRenderState.OPEN : GateRenderState.LOCKED;
        }
        if (gate.isWarningAt(lastActiveMs)) return GateRenderState.WARNING;
        return gate.isOpenAt(lastActiveMs, false)
                ? GateRenderState.OPEN : GateRenderState.CLOSED;
    }

    private int gateIndex(String gateId) {
        for (int i = 0; i < level.gates().size(); i++) {
            if (level.gates().get(i).id().equals(gateId)) return i;
        }
        return -1;
    }

    private static int barrierSide(double px, double py, Vec2 a, Vec2 b) {
        double edgeX = b.x() - a.x();
        double edgeY = b.y() - a.y();
        double value = cross(edgeX, edgeY, px - a.x(), py - a.y());
        double scale = Math.max(1.0, Math.hypot(edgeX, edgeY)
                * Math.hypot(px - a.x(), py - a.y()));
        double tolerance = Math.max(1e-9, Math.ulp(scale) * 16.0);
        if (Math.abs(value) <= tolerance) return 0;
        return value > 0.0 ? 1 : -1;
    }

    private static double firstFiniteBarrierCrossingParameter(
            double sx, double sy, double moveX, double moveY,
            Vec2 a, Vec2 b, int previousNonZeroSide) {
        double endX = sx + moveX;
        double endY = sy + moveY;
        int startSide = barrierSide(sx, sy, a, b);
        int endSide = barrierSide(endX, endY, a, b);
        int effectiveStartSide = startSide == 0 ? previousNonZeroSide : startSide;
        if (effectiveStartSide == 0 || endSide == 0
                || effectiveStartSide == endSide) return -1.0;

        double t;
        if (startSide == 0) {
            // The previous sample ended exactly on the finite barrier. Count
            // the crossing only when this segment leaves on the opposite side.
            t = 0.0;
        } else {
            double edgeX = b.x() - a.x();
            double edgeY = b.y() - a.y();
            double sideStart = cross(edgeX, edgeY, sx - a.x(), sy - a.y());
            double sideEnd = cross(edgeX, edgeY, endX - a.x(), endY - a.y());
            double denominator = sideStart - sideEnd;
            if (denominator == 0.0) return -1.0;
            t = sideStart / denominator;
        }
        if (!unitInterval(t)) return -1.0;
        double x = sx + moveX * t;
        double y = sy + moveY * t;
        double u = closestSegmentParameter(x, y, a.x(), a.y(), b.x(), b.y());
        double projectedX = a.x() + (b.x() - a.x()) * u;
        double projectedY = a.y() + (b.y() - a.y()) * u;
        double distanceSquared = (x - projectedX) * (x - projectedX)
                + (y - projectedY) * (y - projectedY);
        return distanceSquared <= 1e-12 ? clamp01(t) : -1.0;
    }

    private double firstZoneEntryParameter(double sx, double sy,
                                           double moveX, double moveY,
                                           Zone zone) {
        if (zone.shape() == ZoneShape.CIRCLE) {
            return firstPointCircleContact(sx - zone.cx(), sy - zone.cy(),
                    moveX, moveY, zone.radius() * zone.radius());
        }
        List<Vec2> polygon = zone.polygon();
        if (pointInPolygon(sx, sy, polygon)) return 0.0;
        double best = Double.POSITIVE_INFINITY;
        double ex = sx + moveX;
        double ey = sy + moveY;
        for (int i = 0; i < polygon.size(); i++) {
            Vec2 a = polygon.get(i);
            Vec2 b = polygon.get((i + 1) % polygon.size());
            double t = segmentEntryParameter(sx, sy, ex, ey,
                    a.x(), a.y(), b.x(), b.y());
            if (t < 0.0) continue;
            double probe = Math.min(1.0, t + 1e-8);
            if (pointInPolygon(sx + moveX * probe, sy + moveY * probe, polygon)
                    && t < best) best = t;
        }
        if (best != Double.POSITIVE_INFINITY) return best;
        return pointInPolygon(ex, ey, polygon) ? 1.0 : -1.0;
    }

    private double firstDiskZoneContactParameter(double sx, double sy,
                                                 double moveX, double moveY,
                                                 Zone zone, double radius) {
        if (distanceToPassage(sx, sy, zone) <= radius) return 0.0;
        if (zone.shape() == ZoneShape.CIRCLE) {
            double expanded = zone.radius() + radius;
            return firstPointCircleContact(sx - zone.cx(), sy - zone.cy(),
                    moveX, moveY, expanded * expanded);
        }
        List<Vec2> polygon = zone.polygon();
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            Vec2 a = polygon.get(i);
            Vec2 b = polygon.get((i + 1) % polygon.size());
            double t = firstPointCapsuleContact(sx, sy, moveX, moveY,
                    a.x(), a.y(), b.x(), b.y(), radius);
            if (t >= 0.0 && t < best) best = t;
        }
        return best == Double.POSITIVE_INFINITY ? -1.0 : best;
    }

    private double firstDiskSegmentContactParameter(double sx, double sy,
                                                    double moveX, double moveY,
                                                    Vec2 a, Vec2 b,
                                                    double radius) {
        double distanceSquared = pointSegmentDistanceSquared(
                sx, sy, a.x(), a.y(), b.x(), b.y());
        double radiusSquared = radius * radius;
        if (distanceSquared <= radiusSquared + 1e-9) {
            if (distanceSquared >= radiusSquared - 1e-7
                    && !movingIntoBarrier(sx, sy, moveX, moveY, a, b)) {
                // A clamped marker may slide tangentially or move away; that
                // is not a new inward collision with the finite barrier.
                return -1.0;
            }
            return 0.0;
        }
        double contactT = firstPointCapsuleContact(sx, sy, moveX, moveY,
                a.x(), a.y(), b.x(), b.y(), radius);
        if (contactT < 0.0) return -1.0;
        double contactX = sx + moveX * contactT;
        double contactY = sy + moveY * contactT;
        return movingIntoBarrier(contactX, contactY, moveX, moveY, a, b)
                ? contactT : -1.0;
    }

    private static boolean movingIntoBarrier(double px, double py,
                                             double moveX, double moveY,
                                             Vec2 a, Vec2 b) {
        double projection = closestSegmentParameter(
                px, py, a.x(), a.y(), b.x(), b.y());
        double closestX = a.x() + (b.x() - a.x()) * projection;
        double closestY = a.y() + (b.y() - a.y()) * projection;
        double normalX = px - closestX;
        double normalY = py - closestY;
        double approach = normalX * moveX + normalY * moveY;
        double scale = Math.max(1.0,
                Math.hypot(normalX, normalY) * Math.hypot(moveX, moveY));
        double tolerance = Math.ulp(scale) * 32.0;
        return approach < -tolerance;
    }

    private static double gateSafeParameter(double sx, double sy,
                                            double moveX, double moveY, double contactT,
                                            Vec2 barrierStart, Vec2 barrierEnd) {
        double contactX = sx + moveX * contactT;
        double contactY = sy + moveY * contactT;
        double projection = closestSegmentParameter(contactX, contactY,
                barrierStart.x(), barrierStart.y(), barrierEnd.x(), barrierEnd.y());
        double closestX = barrierStart.x()
                + (barrierEnd.x() - barrierStart.x()) * projection;
        double closestY = barrierStart.y()
                + (barrierEnd.y() - barrierStart.y()) * projection;
        double normalX = contactX - closestX;
        double normalY = contactY - closestY;
        double normalLength = Math.hypot(normalX, normalY);
        if (normalLength <= 1e-12) return Math.max(0.0, contactT - 1e-6);
        double inwardSpeed = -(moveX * normalX + moveY * normalY) / normalLength;
        if (inwardSpeed <= 1e-12) return Math.max(0.0, contactT - 1e-6);
        return Math.max(0.0, contactT - GATE_CLAMP_BACKOFF_WORLD / inwardSpeed);
    }

    private static double firstPointCapsuleContact(double px, double py,
                                                   double vx, double vy,
                                                   double ax, double ay,
                                                   double bx, double by,
                                                   double radius) {
        double edgeX = bx - ax;
        double edgeY = by - ay;
        double length = Math.hypot(edgeX, edgeY);
        if (!(length > 0.0)) return -1.0;
        double radiusSquared = radius * radius;
        if (pointSegmentDistanceSquared(px, py, ax, ay, bx, by)
                <= radiusSquared) return 0.0;

        double ux = edgeX / length;
        double uy = edgeY / length;
        double nx = -uy;
        double ny = ux;
        double relX = px - ax;
        double relY = py - ay;
        double alongStart = relX * ux + relY * uy;
        double acrossStart = relX * nx + relY * ny;
        double alongVelocity = vx * ux + vy * uy;
        double acrossVelocity = vx * nx + vy * ny;
        double best = Double.POSITIVE_INFINITY;

        if (acrossVelocity != 0.0) {
            double t = (radius - acrossStart) / acrossVelocity;
            if (unitInterval(t)) {
                double along = alongStart + alongVelocity * t;
                if (along >= -1e-9 && along <= length + 1e-9) best = clamp01(t);
            }
            t = (-radius - acrossStart) / acrossVelocity;
            if (unitInterval(t)) {
                double along = alongStart + alongVelocity * t;
                if (along >= -1e-9 && along <= length + 1e-9) {
                    best = Math.min(best, clamp01(t));
                }
            }
        }
        double capA = firstPointCircleContact(px - ax, py - ay,
                vx, vy, radiusSquared);
        double capB = firstPointCircleContact(px - bx, py - by,
                vx, vy, radiusSquared);
        if (capA >= 0.0) best = Math.min(best, capA);
        if (capB >= 0.0) best = Math.min(best, capB);
        return best == Double.POSITIVE_INFINITY ? -1.0 : best;
    }

    private static double firstPointCircleContact(double rx, double ry,
                                                  double vx, double vy,
                                                  double radiusSquared) {
        double c = rx * rx + ry * ry - radiusSquared;
        if (c <= 0.0) return 0.0;
        double a = vx * vx + vy * vy;
        if (a == 0.0) return -1.0;
        double b = 2.0 * (rx * vx + ry * vy);
        double discriminant = b * b - 4.0 * a * c;
        double scale = Math.max(Math.abs(b * b), Math.abs(4.0 * a * c));
        double tolerance = scale == 0.0 ? 0.0 : Math.ulp(scale) * 16.0;
        if (discriminant < -tolerance) return -1.0;
        if (discriminant < 0.0) discriminant = 0.0;
        double root = Math.sqrt(discriminant);
        double q = -0.5 * (b + Math.copySign(root, b));
        double t1;
        double t2;
        if (q == 0.0) {
            t1 = t2 = -b / (2.0 * a);
        } else {
            t1 = q / a;
            t2 = c / q;
        }
        if (t1 > t2) { double swap = t1; t1 = t2; t2 = swap; }
        if (unitInterval(t1)) return clamp01(t1);
        if (unitInterval(t2)) return clamp01(t2);
        return -1.0;
    }

    private static double segmentEntryParameter(double px, double py,
                                                double qx, double qy,
                                                double ax, double ay,
                                                double bx, double by) {
        double rx = qx - px;
        double ry = qy - py;
        double sx = bx - ax;
        double sy = by - ay;
        double denominator = cross(rx, ry, sx, sy);
        double qpx = ax - px;
        double qpy = ay - py;
        double scale = Math.max(1.0, Math.hypot(rx, ry) * Math.hypot(sx, sy));
        if (Math.abs(denominator) <= Math.ulp(scale) * 16.0) return -1.0;
        double t = cross(qpx, qpy, sx, sy) / denominator;
        double u = cross(qpx, qpy, rx, ry) / denominator;
        if (!unitInterval(t) || u < -1e-9 || u > 1.0 + 1e-9) return -1.0;
        return clamp01(t);
    }

    private double distanceToPassage(double x, double y, Zone passage) {
        if (passage.shape() == ZoneShape.POLYGON) {
            List<Vec2> polygon = passage.polygon();
            if (pointInPolygon(x, y, polygon)) return 0.0;
            double minimum = Double.POSITIVE_INFINITY;
            for (int i = 0; i < polygon.size(); i++) {
                Vec2 a = polygon.get(i);
                Vec2 b = polygon.get((i + 1) % polygon.size());
                minimum = Math.min(minimum, pointSegmentDistanceSquared(
                        x, y, a.x(), a.y(), b.x(), b.y()));
            }
            return Math.sqrt(minimum);
        }
        return Math.max(0.0, Math.hypot(x - passage.cx(), y - passage.cy())
                - passage.radius());
    }

    private boolean diskOverlapsZone(double x, double y, Zone zone, double radius) {
        return distanceToPassage(x, y, zone) <= radius + 1e-9;
    }

    private static boolean zoneContains(Zone zone, double x, double y) {
        if (zone.shape() == ZoneShape.CIRCLE) {
            double dx = x - zone.cx();
            double dy = y - zone.cy();
            return dx * dx + dy * dy <= zone.radius() * zone.radius();
        }
        return pointInPolygon(x, y, zone.polygon());
    }

    private static boolean pointInPolygon(double x, double y, List<Vec2> polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Vec2 a = polygon.get(i);
            Vec2 b = polygon.get(j);
            boolean crosses = (a.y() > y) != (b.y() > y);
            if (crosses) {
                double intersectionX = (b.x() - a.x()) * (y - a.y())
                        / (b.y() - a.y()) + a.x();
                if (x < intersectionX) inside = !inside;
            }
        }
        return inside;
    }

    private static double pointSegmentDistanceSquared(double px, double py,
                                                      double ax, double ay,
                                                      double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0.0) {
            double ox = px - ax;
            double oy = py - ay;
            return ox * ox + oy * oy;
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared;
        t = clamp01(t);
        double ox = px - (ax + dx * t);
        double oy = py - (ay + dy * t);
        return ox * ox + oy * oy;
    }

    private static double closestSegmentParameter(double px, double py,
                                                  double ax, double ay,
                                                  double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0.0) return 0.0;
        return clamp01(((px - ax) * dx + (py - ay) * dy) / lengthSquared);
    }

    private static boolean unitInterval(double t) {
        return Double.isFinite(t) && t >= -1e-9 && t <= 1.0 + 1e-9;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    // =====================================================================
    // Terminal transitions
    // =====================================================================

    private void complete() {
        if (state != EngineState.RUNNING) return;
        state = EngineState.COMPLETED;
        score = computeScore();
        stars = computeStars();
        recordEvent(lastActiveMs, "COMPLETE", "goal");
        buildOutcome();
    }

    private void fail(FailureReason reason) {
        if (state != EngineState.RUNNING) return;
        state = EngineState.FAILED;
        failureReason = reason;
        recordEvent(lastActiveMs, "FAIL", reason.name());
        buildOutcome();
    }

    private boolean allMandatoryCompleted() {
        for (Objective o : level.objectives()) {
            if (o.type() == ObjectiveType.MANDATORY
                    && !collectedObjectives.contains(o.id())) {
                return false;
            }
        }
        return true;
    }

    private int computeScore() {
        if (mode != ReplayMode.CAMPAIGN || state != EngineState.COMPLETED) return 0;
        return ProgressRules.scoreFor(level, collectedObjectives, lastActiveMs);
    }

    private int computeStars() {
        if (mode != ReplayMode.CAMPAIGN || state != EngineState.COMPLETED) return 0;
        return ProgressRules.starsFor(level, collectedObjectives, lastActiveMs, true);
    }

    // =====================================================================
    // Trace recording
    // =====================================================================

    private void recordSample(long tMs, Vec2 pos) {
        recordSample(tMs, pos.x(), pos.y());
    }

    private void recordSample(long tMs, double x, double y) {
        if (replayUnavailable) return;
        if (recordedSampleCount >= ReplaySystem.MAX_SAMPLES) {
            replayUnavailable = true;
            return;
        }
        ensureSampleCapacity(recordedSampleCount + 1);
        recordedSampleTimes[recordedSampleCount] = tMs;
        recordedSampleX[recordedSampleCount] = x;
        recordedSampleY[recordedSampleCount] = y;
        recordedSampleCount++;
    }

    private void ensureSampleCapacity(int required) {
        if (required <= recordedSampleTimes.length) return;
        int next = Math.min(ReplaySystem.MAX_SAMPLES,
                Math.max(required, recordedSampleTimes.length * 2));
        recordedSampleTimes = java.util.Arrays.copyOf(recordedSampleTimes, next);
        recordedSampleX = java.util.Arrays.copyOf(recordedSampleX, next);
        recordedSampleY = java.util.Arrays.copyOf(recordedSampleY, next);
    }

    private void recordEvent(long tMs, String type, String detail) {
        GameEvent uiEvent = new GameEvent(tMs, type, detail);
        int slot;
        if (recentEventCount < RECENT_EVENT_CAPACITY) {
            slot = (recentEventStart + recentEventCount) % RECENT_EVENT_CAPACITY;
            recentEventCount++;
        } else {
            slot = recentEventStart;
            recentEventStart = (recentEventStart + 1) % RECENT_EVENT_CAPACITY;
        }
        recentEvents[slot] = uiEvent;

        if (replayUnavailable) return;
        if (recordedEventCount >= ReplaySystem.MAX_EVENTS) {
            replayUnavailable = true;
            return;
        }
        ensureEventCapacity(recordedEventCount + 1);
        recordedEventTimes[recordedEventCount] = tMs;
        recordedEventTypes[recordedEventCount] = type;
        recordedEventDetails[recordedEventCount] = detail;
        recordedEventCount++;
    }

    private void ensureEventCapacity(int required) {
        if (required <= recordedEventTimes.length) return;
        int next = Math.min(ReplaySystem.MAX_EVENTS,
                Math.max(required, recordedEventTimes.length * 2));
        recordedEventTimes = java.util.Arrays.copyOf(recordedEventTimes, next);
        recordedEventTypes = java.util.Arrays.copyOf(recordedEventTypes, next);
        recordedEventDetails = java.util.Arrays.copyOf(recordedEventDetails, next);
    }

    // =====================================================================
    // Outcome assembly
    // =====================================================================

    private void buildOutcome() {
        ReplayTrace trace = null;
        if (!replayUnavailable) {
            try {
                ReplayTrace.Builder builder = ReplayTrace.builder()
                        .levelId(level.levelId())
                        .levelRevision(level.levelRevision())
                        .geometryHashHex(level.geometryHash())
                        .mode(mode)
                        .runTimestampMs(runTimestampMs)
                        .activeDurationMs(lastActiveMs)
                        .result(mapResult())
                        .score(score)
                        .stars(stars)
                        .assisted(assisted);
                for (int i = 0; i < recordedSampleCount; i++) {
                    builder.sample(recordedSampleTimes[i],
                            recordedSampleX[i], recordedSampleY[i]);
                }
                for (int i = 0; i < recordedEventCount; i++) {
                    builder.event(recordedEventTimes[i],
                            recordedEventTypes[i], recordedEventDetails[i]);
                }
                ReplayTrace proposed = builder.build();
                // Enforce byte cap by encoding once. If encoding rejects the
                // trace, mark it unavailable but keep the run valid.
                ReplaySystem.ReplayCodec.encode(proposed);
                trace = proposed;
            } catch (RuntimeException e) {
                replayUnavailable = true;
                trace = null;
            }
        }

        outcome = new RunOutcome(
                trace,
                mode,
                interruptReason,
                score,
                stars,
                lastActiveMs,
                assisted,
                mapResult(),
                new ArrayList<>(sortedIds(collectedObjectives)),
                new ArrayList<>(sortedIds(collectedKeys)),
                failureReason,
                replayUnavailable);
    }

    private RunResult mapResult() {
        switch (state) {
            case COMPLETED:
                return RunResult.COMPLETED;
            case FAILED:
                return RunResult.FAILED;
            case INTERRUPTED:
                return RunResult.INTERRUPTED;
            default:
                // Should not be called before a terminal state.
                return RunResult.INTERRUPTED;
        }
    }
}