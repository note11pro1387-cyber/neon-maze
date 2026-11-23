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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NEON MAZE — Level 03 — Pulse Reactor.
 *
 * <p>The upper hole creates a long lower corridor. Its side gaps are only 35
 * world units and its top gap is 30 units, both narrower than the 40-unit
 * effective player diameter; a disk cannot bypass the lower corridor around
 * the hole. The traversable corridor is y=320..700: 380 boundary-to-boundary
 * units, with a 340-unit legal centre band after the 20-unit radius is removed
 * from both sides.</p>
 *
 * <p>A single finite vertical barrier spans the corridor at x=700 and has its
 * endpoints attached to the hole boundary and outer boundary. A Safe Zone is
 * placed before it. Gate cycle is 2,200 ms Open, 800 ms Warning (passable),
 * and 1,000 ms Closed, driven by the Engine's Simulation Clock. The Safe Zone
 * does not pause that clock; collision clamping and deferred close remain
 * Engine responsibilities.</p>
 */
public final class Level03 {
    public static final String LEVEL_ID = "level-03-pulse-reactor";
    public static final int LEVEL_REVISION = 2;
    public static final String DISPLAY_NAME_KEY = "level_03_name";

    private static final double PLAYER_RADIUS = 15.0;
    private static final double SAFETY_MARGIN = 5.0;
    private static final long PAR_TIME_MS = 18_000L;
    private static final long TIME_ATTACK_LIMIT_MS = 30_000L;
    private static final long GATE_PERIOD_MS = 4_000L;
    private static final long GATE_OPEN_MS = 2_200L;
    private static final long GATE_WARNING_MS = 800L;
    private static final long GATE_PHASE_OFFSET_MS = 0L;

    private Level03() { }

    public static LevelDefinition build() {
        List<Vec2> outer = immutablePoints(
                new Vec2(100.0, 100.0),
                new Vec2(1100.0, 100.0),
                new Vec2(1100.0, 700.0),
                new Vec2(100.0, 700.0));
        List<Vec2> upperHole = immutablePoints(
                new Vec2(135.0, 130.0),
                new Vec2(1065.0, 130.0),
                new Vec2(1065.0, 320.0),
                new Vec2(135.0, 320.0));

        Zone start = Zone.circle("start", 160.0, 510.0, 35.0);
        Zone goal = Zone.circle("goal", 1040.0, 510.0, 35.0);
        Zone safeZone = Zone.circle("safe-01", 500.0, 510.0, 60.0);
        Objective reactorCell = Objective.mandatory("reactor-cell",
                Zone.circle("reactor-cell-zone", 420.0, 510.0, 22.0),
                "objective_reactor_cell_name", "objective_reactor_cell_desc");
        Objective stabilizer = Objective.mandatory("stabilizer",
                Zone.circle("stabilizer-zone", 900.0, 510.0, 22.0),
                "objective_stabilizer_name", "objective_stabilizer_desc");
        Objective afterglow = Objective.optional("optional-01",
                Zone.circle("afterglow-zone", 900.0, 580.0, 14.0),
                "objective_afterglow_shard_name", "objective_afterglow_shard_desc");
        Zone gatePassage = Zone.polygon("timed-gate-passage", immutablePoints(
                new Vec2(680.0, 320.0),
                new Vec2(720.0, 320.0),
                new Vec2(720.0, 700.0),
                new Vec2(680.0, 700.0)));
        Gate timedGate = Gate.timed("timed-gate", gatePassage,
                new Vec2(700.0, 320.0), new Vec2(700.0, 700.0),
                GATE_PERIOD_MS, GATE_OPEN_MS, GATE_WARNING_MS, GATE_PHASE_OFFSET_MS);

        RouteNode startNode = new RouteNode("n0", new Vec2(160.0, 510.0));
        RouteNode laneNode = new RouteNode("n1", new Vec2(420.0, 510.0));
        RouteNode safeNode = new RouteNode("n2", new Vec2(500.0, 510.0));
        RouteNode approachNode = new RouteNode("n3", new Vec2(650.0, 510.0));
        RouteNode afterGateNode = new RouteNode("n4", new Vec2(750.0, 510.0));
        RouteNode stabilizerNode = new RouteNode("n5", new Vec2(900.0, 510.0));
        RouteNode afterglowNode = new RouteNode("n6", new Vec2(900.0, 580.0));
        RouteNode goalApproach = new RouteNode("n7", new Vec2(1040.0, 510.0));
        RouteGraph graph = new RouteGraph(
                immutableList(startNode, laneNode, safeNode, approachNode, afterGateNode,
                        stabilizerNode, afterglowNode, goalApproach),
                immutableList(
                        new RouteEdge("n0", "n1", 380.0),
                        new RouteEdge("n1", "n2", 380.0),
                        new RouteEdge("n2", "n3", 380.0),
                        new RouteEdge("n3", "n4", 380.0),
                        new RouteEdge("n4", "n5", 380.0),
                        new RouteEdge("n5", "n6", 160.0),
                        new RouteEdge("n6", "n7", 160.0),
                        new RouteEdge("n5", "n7", 380.0)));

        return new LevelDefinition.Builder()
                .levelId(LEVEL_ID)
                .levelRevision(LEVEL_REVISION)
                .displayNameKey(DISPLAY_NAME_KEY)
                .worldBounds(new Aabb(0.0, 0.0, 1200.0, 800.0))
                .outerBoundary(outer)
                .holeBoundaries(Collections.singletonList(upperHole))
                .startZone(start)
                .goalZone(goal)
                .playerRadius(PLAYER_RADIUS)
                .safetyMargin(SAFETY_MARGIN)
                .routeGraph(graph)
                .objectives(immutableList(reactorCell, stabilizer, afterglow))
                .gates(Collections.singletonList(timedGate))
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
        List<T> copy = new ArrayList<T>(values.length);
        for (T value : values) copy.add(value);
        return Collections.unmodifiableList(copy);
    }
}
