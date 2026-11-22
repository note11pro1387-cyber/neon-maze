package com.neonmaze.core.levels;

import com.neonmaze.core.GeometryCollision.Aabb;
import com.neonmaze.core.GeometryCollision.Vec2;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.RouteEdge;
import com.neonmaze.core.LevelSystem.RouteGraph;
import com.neonmaze.core.LevelSystem.RouteNode;
import com.neonmaze.core.LevelSystem.Zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NEON MAZE — Level 01 — Awakening.
 *
 * <p>First-contact teaching level: enter Start, maintain one continuous
 * touch, trace a wide U-shaped corridor through two 90-degree turns and
 * reach Goal. There are no branches, objectives, gates or hazards.</p>
 *
 * <p>Start/goal anchors remain at the authored AI-01 locations. The validator
 * checks each anchor with the full effective disk; the trigger circle is not
 * incorrectly required to lie entirely inside the walkable region.</p>
 */
public final class Level01 {
    public static final String LEVEL_ID = "level-01-awakening";
    public static final int LEVEL_REVISION = 1;
    public static final String DISPLAY_NAME_KEY = "level_01_name";

    private static final double PLAYER_RADIUS = 15.0;
    private static final double SAFETY_MARGIN = 5.0;
    private static final double ZONE_RADIUS = 40.0;
    private static final long PAR_TIME_MS = 12_000L;
    private static final long TIME_ATTACK_LIMIT_MS = 20_000L;

    private Level01() { }

    public static LevelDefinition build() {
        List<Vec2> outerBoundary = immutablePoints(
                new Vec2(100.0, 100.0),
                new Vec2(100.0, 900.0),
                new Vec2(900.0, 900.0),
                new Vec2(900.0, 100.0),
                new Vec2(700.0, 100.0),
                new Vec2(700.0, 700.0),
                new Vec2(300.0, 700.0),
                new Vec2(300.0, 100.0));

        Zone start = Zone.circle("start", 200.0, 150.0, ZONE_RADIUS);
        Zone goal = Zone.circle("goal", 800.0, 150.0, ZONE_RADIUS);

        RouteNode startNode = new RouteNode("n0", new Vec2(200.0, 150.0));
        RouteNode lowerTurn = new RouteNode("n1", new Vec2(200.0, 800.0));
        RouteNode upperTurn = new RouteNode("n2", new Vec2(800.0, 800.0));
        RouteNode goalNode = new RouteNode("n3", new Vec2(800.0, 150.0));
        RouteGraph graph = new RouteGraph(
                immutableList(startNode, lowerTurn, upperTurn, goalNode),
                immutableList(
                        new RouteEdge("n0", "n1", 200.0),
                        new RouteEdge("n1", "n2", 200.0),
                        new RouteEdge("n2", "n3", 200.0)));

        return new LevelDefinition.Builder()
                .levelId(LEVEL_ID)
                .levelRevision(LEVEL_REVISION)
                .displayNameKey(DISPLAY_NAME_KEY)
                .worldBounds(new Aabb(0.0, 0.0, 1000.0, 1000.0))
                .outerBoundary(outerBoundary)
                .holeBoundaries(Collections.<List<Vec2>>emptyList())
                .startZone(start)
                .goalZone(goal)
                .playerRadius(PLAYER_RADIUS)
                .safetyMargin(SAFETY_MARGIN)
                .routeGraph(graph)
                .objectives(Collections.emptyList())
                .gates(Collections.emptyList())
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
