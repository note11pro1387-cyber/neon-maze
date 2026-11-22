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
 * NEON MAZE — Level 04 — Energy Vault.
 *
 * <p>Teaching goal: explore a true four-arm branch, collect the mandatory
 * key in the left arm, backtrack to the central junction, then open the
 * upper exit. The right arm contains an optional collectible. Entering the
 * wrong branch is legal; the player must retain finger contact and return
 * through the same walkable junction rather than being failed by the graph.</p>
 *
 * <p>Canonical geometry is a plus-shaped walkable polygon in a 1,000 × 1,000
 * world. Every arm is 200 world units wide. Player radius is 15 and the
 * safety margin is 5, giving an effective disk radius of 20. The full arm
 * therefore leaves 160 units of centre-space width before local turns.</p>
 *
 * <p>The exit passage occupies the full upper arm from y=650 to y=750.
 * Its finite barrier is the horizontal segment x=400..600 at y=700,
 * terminating on the two distinct walkable-region boundaries. The key
 * objective grants {@code exit-key}; the gate remains blocked until the
 * Engine has observed that key. A blocked gate is not itself a terminal
 * failure, so the player can return to the left branch.</p>
 *
 * <p>Par and Time Attack values are initial tuning values and must be
 * calibrated after complete-content implementation in the final QA phase.</p>
 *
 * <p>This class contains level data only. It has no Android, rendering,
 * collision, persistence, or Engine behavior.</p>
 */
public final class Level04 {

    public static final String LEVEL_ID = "level-04-energy-vault";
    public static final int LEVEL_REVISION = 1;
    public static final String DISPLAY_NAME_KEY = "level_04_name";
    public static final String EXIT_KEY_ID = "exit-key";

    private static final double PLAYER_RADIUS = 15.0;
    private static final double SAFETY_MARGIN = 5.0;
    private static final double ARM_WIDTH = 200.0;
    private static final double START_RADIUS = 35.0;
    private static final double GOAL_RADIUS = 30.0;
    private static final double OBJECTIVE_RADIUS = 30.0;
    private static final long PAR_TIME_MS = 22_000L;
    private static final long TIME_ATTACK_LIMIT_MS = 40_000L;

    private Level04() { }

    /** Builds the immutable Energy Vault definition; Catalog performs validation. */
    public static LevelDefinition build() {
        List<Vec2> outer = immutablePoints(
                new Vec2(400.0, 100.0),
                new Vec2(400.0, 400.0),
                new Vec2(100.0, 400.0),
                new Vec2(100.0, 600.0),
                new Vec2(400.0, 600.0),
                new Vec2(400.0, 900.0),
                new Vec2(600.0, 900.0),
                new Vec2(600.0, 600.0),
                new Vec2(900.0, 600.0),
                new Vec2(900.0, 400.0),
                new Vec2(600.0, 400.0),
                new Vec2(600.0, 100.0)
        );

        Zone start = Zone.circle("start", 500.0, 200.0, START_RADIUS);
        Zone goal = Zone.circle("goal", 500.0, 820.0, GOAL_RADIUS);

        Zone keyZone = Zone.circle("key-01-zone", 250.0, 500.0, OBJECTIVE_RADIUS);
        Objective key = Objective.keyGrant("key-01", keyZone, EXIT_KEY_ID, null);
        Zone optionalZone = Zone.circle("optional-01-zone", 750.0, 500.0, OBJECTIVE_RADIUS);
        Objective optional = Objective.optional("optional-01", optionalZone);

        Zone exitPassage = Zone.polygon("exit-gate-passage", immutablePoints(
                new Vec2(400.0, 650.0),
                new Vec2(600.0, 650.0),
                new Vec2(600.0, 750.0),
                new Vec2(400.0, 750.0)
        ));
        Gate exitGate = Gate.keyLocked("exit-gate", exitPassage,
                new Vec2(400.0, 700.0), new Vec2(600.0, 700.0), EXIT_KEY_ID);

        RouteNode startNode = new RouteNode("n0", new Vec2(500.0, 200.0));
        RouteNode junction = new RouteNode("n1", new Vec2(500.0, 500.0));
        RouteNode keyNode = new RouteNode("n2", new Vec2(250.0, 500.0));
        RouteNode optionalNode = new RouteNode("n3", new Vec2(750.0, 500.0));
        RouteNode afterGateNode = new RouteNode("n4", new Vec2(500.0, 780.0));
        RouteNode goalNode = new RouteNode("n5", new Vec2(500.0, 820.0));

        RouteGraph graph = new RouteGraph(
                immutableList(startNode, junction, keyNode, optionalNode, afterGateNode, goalNode),
                immutableList(
                        new RouteEdge("n0", "n1", ARM_WIDTH),
                        new RouteEdge("n1", "n2", ARM_WIDTH),
                        new RouteEdge("n2", "n1", ARM_WIDTH),
                        new RouteEdge("n1", "n3", ARM_WIDTH),
                        new RouteEdge("n3", "n1", ARM_WIDTH),
                        new RouteEdge("n1", "n4", ARM_WIDTH),
                        new RouteEdge("n4", "n5", ARM_WIDTH)
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
                .objectives(immutableList(key, optional))
                .gates(Collections.singletonList(exitGate))
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
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
