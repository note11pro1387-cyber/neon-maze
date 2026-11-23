package com.neonmaze.core.levels;

import com.neonmaze.core.GeometryCollision.Aabb;
import com.neonmaze.core.GeometryCollision.Vec2;
import com.neonmaze.core.LevelSystem.Gate;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.RouteEdge;
import com.neonmaze.core.LevelSystem.RouteGraph;
import com.neonmaze.core.LevelSystem.RouteNode;
import com.neonmaze.core.LevelSystem.Zone;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NEON MAZE — Level 05 — The Core.
 *
 * <p>Final authored Campaign level. It combines only mechanics already
 * introduced earlier: a precision-width plus corridor, a timed barrier in
 * the approach, a Safe Zone before that timed barrier, a mandatory key in
 * the left branch, a mandatory stabilizer beyond the timed gate, a key-locked
 * exit barrier, and optional rewards in the right branch and pre-gate spur.
 * No moving hazard, random rule, or new mechanic is introduced here.</p>
 *
 * <p>All four arms are 120 world units wide. With player radius 15 and
 * safety margin 5, the effective radius is 20 and the legal centre band
 * is 80 units wide. The key and right-branch shard use 30-unit triggers;
 * the mandatory stabilizer and pre-gate shard use tighter 18- and 10-unit
 * triggers to reward precise tracing. The Safe Zone radius is 32; its
 * combined radius and player clearance is 52, leaving 8 units in the arm.
 * It is a planning landmark only and never pauses the Simulation Clock.</p>
 *
 * <p>The timed barrier spans the full lower arm at y=360. Its passage is
 * x=440..560 and y=320..400. The upper exit barrier spans the full upper
 * arm at y=700 and remains key-locked until {@code exit-key} is collected
 * in the left branch. Both barriers terminate on distinct region boundary
 * segments; neither can be bypassed through a side gap.</p>
 *
 * <p>Par, Time Attack limit, and Gate schedule are tuning defaults, not
 * playtest-calibrated guarantees.</p>
 *
 * <p>This class contains level data only and imports no Android APIs.</p>
 */
public final class Level05 {

    public static final String LEVEL_ID = "level-05-the-core";
    public static final int LEVEL_REVISION = 2;
    public static final String DISPLAY_NAME_KEY = "level_05_name";
    public static final String EXIT_KEY_ID = "exit-key";

    private static final double PLAYER_RADIUS = 15.0;
    private static final double SAFETY_MARGIN = 5.0;
    private static final double ARM_WIDTH = 120.0;
    private static final double START_GOAL_RADIUS = 25.0;
    private static final double OBJECTIVE_RADIUS = 30.0;
    private static final double SAFE_ZONE_RADIUS = 32.0;

    private static final long PAR_TIME_MS = 35_000L;
    private static final long TIME_ATTACK_LIMIT_MS = 55_000L;
    private static final long TIMED_GATE_PERIOD_MS = 3_000L;
    private static final long TIMED_GATE_OPEN_MS = 1_000L;
    private static final long TIMED_GATE_WARNING_MS = 400L;
    private static final long TIMED_GATE_PHASE_OFFSET_MS = 0L;

    private Level05() { }

    /** Builds the immutable The Core definition; Catalog performs validation. */
    public static LevelDefinition build() {
        List<Vec2> outer = immutablePoints(
                new Vec2(440.0, 100.0),
                new Vec2(560.0, 100.0),
                new Vec2(560.0, 440.0),
                new Vec2(900.0, 440.0),
                new Vec2(900.0, 560.0),
                new Vec2(560.0, 560.0),
                new Vec2(560.0, 900.0),
                new Vec2(440.0, 900.0),
                new Vec2(440.0, 560.0),
                new Vec2(100.0, 560.0),
                new Vec2(100.0, 440.0),
                new Vec2(440.0, 440.0)
        );

        Zone start = Zone.circle("start", 500.0, 150.0, START_GOAL_RADIUS);
        Zone goal = Zone.circle("goal", 500.0, 820.0, START_GOAL_RADIUS);

        Zone keyZone = Zone.circle("key-01-zone", 250.0, 500.0, OBJECTIVE_RADIUS);
        Objective key = Objective.keyGrant("key-01", keyZone, EXIT_KEY_ID, null,
                "objective_core_key_name", "objective_core_key_desc");
        Zone optionalZone = Zone.circle("optional-01-zone", 750.0, 500.0, OBJECTIVE_RADIUS);
        Objective optional = Objective.optional("optional-01", optionalZone,
                "objective_core_shard_name", "objective_core_shard_desc");
        Zone stabilizerZone = Zone.circle("core-stabilizer-zone", 500.0, 600.0, 18.0);
        Objective stabilizer = Objective.mandatory("core-stabilizer", stabilizerZone,
                "objective_core_stabilizer_name", "objective_core_stabilizer_desc");
        Zone phaseZone = Zone.circle("phase-shard-zone", 520.0, 280.0, 10.0);
        Objective phaseShard = Objective.optional("optional-02", phaseZone,
                "objective_phase_shard_name", "objective_phase_shard_desc");

        Zone timedPassage = Zone.polygon("timed-gate-passage", immutablePoints(
                new Vec2(440.0, 320.0),
                new Vec2(560.0, 320.0),
                new Vec2(560.0, 400.0),
                new Vec2(440.0, 400.0)
        ));
        Gate timedGate = Gate.timed("timed-gate", timedPassage,
                new Vec2(440.0, 360.0), new Vec2(560.0, 360.0),
                TIMED_GATE_PERIOD_MS, TIMED_GATE_OPEN_MS,
                TIMED_GATE_WARNING_MS, TIMED_GATE_PHASE_OFFSET_MS);

        Zone exitPassage = Zone.polygon("exit-gate-passage", immutablePoints(
                new Vec2(440.0, 650.0),
                new Vec2(560.0, 650.0),
                new Vec2(560.0, 750.0),
                new Vec2(440.0, 750.0)
        ));
        Gate exitGate = Gate.keyLocked("exit-gate", exitPassage,
                new Vec2(440.0, 700.0), new Vec2(560.0, 700.0), EXIT_KEY_ID);

        Zone safeZone = Zone.circle("safe-01", 500.0, 240.0, SAFE_ZONE_RADIUS);

        RouteNode startNode = new RouteNode("n0", new Vec2(500.0, 150.0));
        RouteNode phaseShardNode = new RouteNode("n5", new Vec2(520.0, 280.0));
        RouteNode junction = new RouteNode("n1", new Vec2(500.0, 500.0));
        RouteNode keyNode = new RouteNode("n2", new Vec2(250.0, 500.0));
        RouteNode optionalNode = new RouteNode("n3", new Vec2(750.0, 500.0));
        RouteNode stabilizerNode = new RouteNode("n6", new Vec2(500.0, 600.0));
        RouteNode goalNode = new RouteNode("n4", new Vec2(500.0, 820.0));

        RouteGraph graph = new RouteGraph(
                immutableList(startNode, phaseShardNode, junction, keyNode,
                        optionalNode, stabilizerNode, goalNode),
                immutableList(
                        new RouteEdge("n0", "n1", ARM_WIDTH),
                        new RouteEdge("n0", "n5", 50.0),
                        new RouteEdge("n5", "n1", 50.0),
                        new RouteEdge("n1", "n2", ARM_WIDTH),
                        new RouteEdge("n2", "n1", ARM_WIDTH),
                        new RouteEdge("n1", "n3", ARM_WIDTH),
                        new RouteEdge("n3", "n1", ARM_WIDTH),
                        new RouteEdge("n1", "n6", ARM_WIDTH),
                        new RouteEdge("n6", "n4", ARM_WIDTH)
                ));

        return new LevelDefinition.Builder()
                .levelId(LEVEL_ID)
                .levelRevision(LEVEL_REVISION)
                .displayNameKey(DISPLAY_NAME_KEY)
                .worldBounds(new Aabb(0.0, 0.0, 1000.0, 1000.0))
                .outerBoundary(outer)
                .holeBoundaries(Collections.<List<Vec2>>emptyList())
                .startZone(start)
                .goalZone(goal)
                .playerRadius(PLAYER_RADIUS)
                .safetyMargin(SAFETY_MARGIN)
                .routeGraph(graph)
                .objectives(immutableList(key, stabilizer, optional, phaseShard))
                .gates(immutableList(timedGate, exitGate))
                .safeZones(Collections.singletonList(safeZone))
                .parTimeMs(PAR_TIME_MS)
                .timeAttackLimitMs(TIME_ATTACK_LIMIT_MS)
                .build();
    }

    private static List<Vec2> immutablePoints(Vec2... points) {
        return Collections.unmodifiableList(Arrays.asList(points));
    }

    @SafeVarargs
    private static <T> List<T> immutableList(T... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
