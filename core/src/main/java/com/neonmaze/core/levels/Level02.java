package com.neonmaze.core.levels;

import com.neonmaze.core.GeometryCollision.Aabb;
import com.neonmaze.core.GeometryCollision.Vec2;
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
 * NEON MAZE — Level 02 — Precision Core.
 *
 * <p>A central concave island separates two valid, non-crossing routes.
 * The left lane is the wider/safer alternative; the right lane is narrower
 * and contains an optional side-pocket collectible. Start and Goal connect
 * to both routes through open upper/lower transfer bays. The optional pickup
 * is offset from the right route centreline by 30 units. The Engine collects
 * it when the marker centre enters the 14-unit trigger, so the player must
 * make a deliberate lateral excursion rather than merely follow the lane.</p>
 *
 * <p>Player radius 15 + safety margin 5 gives an effective disk radius 20.
 * The authored minimum corridor width is 160, not the post-radius centre
 * band. Every graph edge is independently validated with the actual swept
 * disk by LevelSystem.LevelValidator.</p>
 */
public final class Level02 {
    public static final String LEVEL_ID = "level-02-precision-core";
    public static final int LEVEL_REVISION = 2;
    public static final String DISPLAY_NAME_KEY = "level_02_name";

    private static final double PLAYER_RADIUS = 15.0;
    private static final double SAFETY_MARGIN = 5.0;
    private static final long PAR_TIME_MS = 15_000L;
    private static final long TIME_ATTACK_LIMIT_MS = 25_000L;

    private Level02() { }

    public static LevelDefinition build() {
        List<Vec2> outer = immutablePoints(
                new Vec2(80.0, 100.0),
                new Vec2(920.0, 100.0),
                new Vec2(920.0, 900.0),
                new Vec2(80.0, 900.0));
        List<Vec2> centralIsland = immutablePoints(
                new Vec2(300.0, 300.0),
                new Vec2(760.0, 300.0),
                new Vec2(760.0, 450.0),
                new Vec2(740.0, 500.0),
                new Vec2(760.0, 550.0),
                new Vec2(760.0, 700.0),
                new Vec2(300.0, 700.0));

        Zone start = Zone.circle("start", 500.0, 200.0, 40.0);
        Zone goal = Zone.circle("goal", 500.0, 800.0, 40.0);
        Zone beaconZone = Zone.circle("route-beacon-zone", 500.0, 260.0, 12.0);
        Objective beacon = Objective.mandatory("route-beacon", beaconZone,
                "objective_route_beacon_name", "objective_route_beacon_desc");
        Zone optionalZone = Zone.circle("optional-01-zone", 840.0, 500.0, 14.0);
        Objective optional = Objective.optional("optional-01", optionalZone,
                "objective_precision_shard_name", "objective_precision_shard_desc");

        RouteNode startNode = new RouteNode("start", new Vec2(500.0, 200.0));
        RouteNode beaconNode = new RouteNode("route-beacon", new Vec2(500.0, 260.0));
        RouteNode leftEntry = new RouteNode("left-entry", new Vec2(190.0, 200.0));
        RouteNode leftMid = new RouteNode("left-mid", new Vec2(190.0, 500.0));
        RouteNode leftExit = new RouteNode("left-exit", new Vec2(190.0, 800.0));

        RouteNode rightEntry = new RouteNode("right-entry", new Vec2(870.0, 200.0));
        RouteNode rightMid = new RouteNode("right-mid", new Vec2(870.0, 500.0));
        RouteNode optionalNode = new RouteNode("optional-node", new Vec2(840.0, 500.0));
        RouteNode rightExit = new RouteNode("right-exit", new Vec2(870.0, 800.0));
        RouteNode goalNode = new RouteNode("goal-node", new Vec2(500.0, 800.0));

        RouteGraph graph = new RouteGraph(
                immutableList(startNode, beaconNode, leftEntry, leftMid, leftExit,
                        rightEntry, rightMid, optionalNode, rightExit, goalNode),
                immutableList(
                        new RouteEdge("start", "route-beacon", 200.0),
                        new RouteEdge("route-beacon", "left-entry", 140.0),
                        new RouteEdge("route-beacon", "right-entry", 140.0),
                        new RouteEdge("left-entry", "left-mid", 220.0),
                        new RouteEdge("left-mid", "left-exit", 220.0),
                        new RouteEdge("left-exit", "goal-node", 200.0),
                        new RouteEdge("right-entry", "right-mid", 160.0),
                        new RouteEdge("right-mid", "optional-node", 160.0),
                        new RouteEdge("optional-node", "right-mid", 160.0),
                        new RouteEdge("right-mid", "right-exit", 160.0),
                        new RouteEdge("right-exit", "goal-node", 200.0)));

        return new LevelDefinition.Builder()
                .levelId(LEVEL_ID)
                .levelRevision(LEVEL_REVISION)
                .displayNameKey(DISPLAY_NAME_KEY)
                .worldBounds(new Aabb(0.0, 0.0, 1000.0, 1000.0))
                .outerBoundary(outer)
                .holeBoundaries(Collections.singletonList(centralIsland))
                .startZone(start)
                .goalZone(goal)
                .playerRadius(PLAYER_RADIUS)
                .safetyMargin(SAFETY_MARGIN)
                .routeGraph(graph)
                .objectives(immutableList(beacon, optional))
                .gates(Collections.<com.neonmaze.core.LevelSystem.Gate>emptyList())
                .safeZones(Collections.<Zone>emptyList())
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
