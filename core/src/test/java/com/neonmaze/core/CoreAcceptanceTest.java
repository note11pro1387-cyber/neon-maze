package com.neonmaze.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.neonmaze.core.GeometryCollision.Aabb;
import com.neonmaze.core.GeometryCollision.Region;
import com.neonmaze.core.GeometryCollision.Vec2;
import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.DifficultyProfile;
import com.neonmaze.core.LevelSystem.Gate;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.LevelRegistry;
import com.neonmaze.core.LevelSystem.LevelValidator;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.RouteEdge;
import com.neonmaze.core.LevelSystem.RouteGraph;
import com.neonmaze.core.LevelSystem.RouteNode;
import com.neonmaze.core.LevelSystem.Zone;
import com.neonmaze.core.ReplaySystem;
import com.neonmaze.core.ReplaySystem.Compatibility;
import com.neonmaze.core.ReplaySystem.GhostTimeline;
import com.neonmaze.core.ReplaySystem.ReplayCodec;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.RunResult;
import com.neonmaze.core.ReplaySystem.TraceSample;
import com.neonmaze.core.levels.Level01;
import com.neonmaze.core.levels.Level02;
import com.neonmaze.core.levels.Level03;
import com.neonmaze.core.levels.Level04;
import com.neonmaze.core.levels.Level05;
import com.neonmaze.core.levels.BuiltInLevelCatalog;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Acceptance test for the {@code :core} module.
 *
 * <p>This file is the single source of truth for the claims that
 * {@code GeometryCollision}, {@code LevelSystem}, the five concrete levels,
 * {@code ReplaySystem}, and {@code GameEngine} make about themselves.
 * Every assertion here is either a contract that the blueprint requires or
 * a behavior that the engine implements. A failing test is a real defect,
 * not a test to be deleted or weakened.</p>
 *
 * <h2>Design notes recorded as tests</h2>
 * <ul>
 *   <li>{@link EngineScoringTest#campaignCompletionComputesScoreAndStars}
 *       pins the <em>current</em> behavior that Level01 awards 3 stars
 *       vacuously (no optional objectives exist). This may need a design
 *       review; if the rule is changed to “3 stars requires at least one
 *       optional objective”, this test will need to be updated accordingly.</li>
 *   <li>{@link EngineTimeTest#timeAttackCompletesEvenAtExpirySample}
 *       pins the engine's tie-break: the sample that reaches the goal
 *       wins over the time limit <em>for that sample</em>, because
 *       {@code processSample} runs before {@code checkTimeLimit}. This is
 *       the player-friendly interpretation of blueprint §5.3.</li>
 * </ul>
 */
@RunWith(Enclosed.class)
public class CoreAcceptanceTest {

    // =====================================================================
    // Shared helpers
    // =====================================================================

    static void requireNoProblems(LevelDefinition level) {
        List<String> problems = LevelValidator.validate(level);
        if (!problems.isEmpty()) {
            fail("Level " + level.levelId() + " has validation problems: " + problems);
        }
    }

    static LevelDefinition buildOpenRoomNoOptional() {
        Zone start = Zone.circle("start", 100, 100, 30);
        Zone goal = Zone.circle("goal", 400, 400, 30);
        return buildOpenRoom("synthetic-simple", start, goal,
                List.of(), 5000L, 10000L,
                new DifficultyProfile(600, 200, 0, 0, 0, 0, 0, 0));
    }

    static LevelDefinition buildOpenRoomWithOptional() {
        Zone start = Zone.circle("start", 100, 100, 30);
        Zone goal = Zone.circle("goal", 400, 400, 30);
        Zone optionalZone = Zone.circle("optional-zone", 300, 200, 20);
        Objective optional = Objective.optional("optional-01", optionalZone);
        return buildOpenRoom("synthetic-optional", start, goal,
                List.of(optional), 5000L, 10000L,
                new DifficultyProfile(600, 200, 0, 0, 0, 1, 0, 0));
    }

    static LevelDefinition buildOpenRoomWithMandatory() {
        Zone start = Zone.circle("start", 100, 100, 30);
        Zone goal = Zone.circle("goal", 400, 400, 30);
        Zone mandatoryZone = Zone.circle("mandatory-zone", 200, 400, 20);
        Objective mandatory = Objective.mandatory("mandatory-01", mandatoryZone);
        return buildOpenRoom("synthetic-mandatory", start, goal,
                List.of(mandatory), 10000L, 30000L,
                new DifficultyProfile(600, 200, 0, 0, 1, 0, 0, 0));
    }

    static LevelDefinition buildOpenRoom(String id, Zone start, Zone goal,
                                         List<Objective> objectives,
                                         long parMs, long taLimitMs,
                                         DifficultyProfile dp) {
        // Every synthetic pickup is an authored waypoint on this test route.
        // Runtime Engine tests remain free to attempt a shortcut and verify that
        // mandatory objectives are not silently collected when bypassed.
        java.util.ArrayList<RouteNode> routeNodes = new java.util.ArrayList<>();
        java.util.ArrayList<RouteEdge> routeEdges = new java.util.ArrayList<>();
        routeNodes.add(new RouteNode("n0", new Vec2(100, 100)));
        String previous = "n0";
        int index = 0;
        for (Objective objective : objectives) {
            String nodeId = "objective-" + index++;
            routeNodes.add(new RouteNode(nodeId, objective.zone().centroid()));
            routeEdges.add(new RouteEdge(previous, nodeId, 400));
            previous = nodeId;
        }
        routeNodes.add(new RouteNode("n1", new Vec2(400, 400)));
        routeEdges.add(new RouteEdge(previous, "n1", 400));
        RouteGraph route = new RouteGraph(routeNodes, routeEdges);
        return new LevelDefinition.Builder()
                .levelId(id)
                .levelRevision(1)
                .displayNameKey("synthetic")
                .worldBounds(new Aabb(0, 0, 500, 500))
                .outerBoundary(List.of(
                        new Vec2(0, 0), new Vec2(500, 0),
                        new Vec2(500, 500), new Vec2(0, 500)))
                .holeBoundaries(List.of())
                .startZone(start)
                .goalZone(goal)
                .playerRadius(10)
                .safetyMargin(5)
                .routeGraph(route)
                .objectives(objectives)
                .gates(List.of())
                .safeZones(List.of())
                .parTimeMs(parMs)
                .timeAttackLimitMs(taLimitMs)
                .difficultyProfile(dp)
                .build();
    }

    static LevelDefinition copyWithRouteGraph(LevelDefinition source, RouteGraph route) {
        return new LevelDefinition.Builder()
                .levelId(source.levelId())
                .levelRevision(source.levelRevision())
                .displayNameKey(source.displayNameKey())
                .worldBounds(source.worldBounds())
                .outerBoundary(source.outerBoundary())
                .holeBoundaries(source.holeBoundaries())
                .startZone(source.startZone())
                .goalZone(source.goalZone())
                .playerRadius(source.playerRadius())
                .safetyMargin(source.safetyMargin())
                .routeGraph(route)
                .objectives(source.objectives())
                .gates(source.gates())
                .safeZones(source.safeZones())
                .parTimeMs(source.parTimeMs())
                .timeAttackLimitMs(source.timeAttackLimitMs())
                .build();
    }

    // =====================================================================
    // Level validation
    // =====================================================================

    public static class LevelsTest {
        @Test public void level01Validates() { requireNoProblems(Level01.build()); }
        @Test public void level02Validates() { requireNoProblems(Level02.build()); }
        @Test public void level03Validates() { requireNoProblems(Level03.build()); }
        @Test public void level04Validates() { requireNoProblems(Level04.build()); }
        @Test public void level05Validates() { requireNoProblems(Level05.build()); }

        @Test public void registryAcceptsAllFive() {
            LevelRegistry reg = new LevelRegistry(List.of(
                    Level01.build(), Level02.build(), Level03.build(),
                    Level04.build(), Level05.build()));
            assertEquals(5, reg.size());
            assertEquals(Level01.LEVEL_ID, reg.get(0).levelId());
            assertEquals(Level05.LEVEL_ID, reg.get(4).levelId());
        }

        @Test public void registryRejectsDuplicateLevelId() {
            LevelDefinition a = Level01.build();
            try {
                new LevelRegistry(List.of(a, a));
                fail("duplicate levelId must be rejected");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        @Test public void syntheticOpenRoomValidates() {
            requireNoProblems(buildOpenRoomNoOptional());
            requireNoProblems(buildOpenRoomWithOptional());
            requireNoProblems(buildOpenRoomWithMandatory());
        }

        @Test public void circularObjectiveRequiresExactCombinedDiskClearance() {
            Zone start = Zone.circle("start", 100.0, 100.0, 30.0);
            Zone goal = Zone.circle("goal", 400.0, 400.0, 30.0);
            Objective nearWall = Objective.optional("near-wall",
                    Zone.circle("near-wall-zone", 20.0, 100.0, 10.0));
            LevelDefinition level = buildOpenRoom("synthetic-near-wall", start, goal,
                    List.of(nearWall), 5_000L, 10_000L,
                    new DifficultyProfile(600.0, 200.0, 0, 0, 0, 1, 0, 0));
            List<String> problems = LevelValidator.validate(level);
            assertTrue(problems.toString(), problems.toString().contains("objective zone is not disk-walkable: near-wall"));
        }

        @Test public void level02OptionalIsARealDetourNotTheMainline() {
            LevelDefinition level = Level02.build();
            Objective optional = level.objective("optional-01");
            assertNotNull(optional);
            assertFalse(optional.zone().contains(level.routeGraph().node("right-mid").position()));
            assertTrue(optional.zone().contains(level.routeGraph().node("optional-node").position()));
            assertEquals(1, level.difficultyProfile().optionalObjectiveCount());
            assertEquals(160.0, level.difficultyProfile().minWidthWorldUnits(), 1e-6);
            assertEquals(1_220.0, level.difficultyProfile().pathLengthWorldUnits(), 1e-6);
        }

        @Test public void level03GateUsesFiniteBarrierAndOpenWarningClosedCycle() {
            LevelDefinition level = Level03.build();
            Gate gate = level.gates().get(0);
            assertTrue(gate.hasBarrier());
            assertTrue(gate.isOpenAt(0L, false));
            assertFalse(gate.isWarningAt(2_199L));
            assertTrue(gate.isWarningAt(2_200L));
            assertTrue(gate.isOpenAt(2_200L, false));
            assertFalse(gate.isOpenAt(3_000L, false));
            assertTrue(gate.isOpenAt(4_000L, false));
            assertEquals(1, level.difficultyProfile().waitingOpportunityCount());
        }

        @Test public void derivedPathIncludesMandatoryKeyDetour() {
            assertEquals(1_120.0, Level04.build().difficultyProfile().pathLengthWorldUnits(), 1e-6);
            assertEquals(1_170.0, Level05.build().difficultyProfile().pathLengthWorldUnits(), 1e-6);
        }

        @Test public void keyGateCannotBeReachedThroughAnIrreversibleKeyDeadEnd() {
            LevelDefinition level = Level04.build();
            List<RouteEdge> edges = new ArrayList<>(level.routeGraph().edges());
            edges.removeIf(edge -> edge.fromId().equals("n2") && edge.toId().equals("n1"));
            RouteGraph broken = new RouteGraph(level.routeGraph().nodes(), edges);
            List<String> problems = LevelValidator.validate(copyWithRouteGraph(level, broken));
            assertTrue(problems.toString(), problems.toString().contains("no valid Start-to-Goal route"));
        }

        @Test public void geometryHashIgnoresPolygonRingStartAndWinding() {
            LevelDefinition original = Level01.build();
            List<Vec2> ring = new ArrayList<>(original.outerBoundary());
            Collections.reverse(ring);
            Collections.rotate(ring, 2);
            LevelDefinition reordered = new LevelDefinition.Builder()
                    .levelId(original.levelId()).levelRevision(original.levelRevision())
                    .displayNameKey(original.displayNameKey()).worldBounds(original.worldBounds())
                    .outerBoundary(ring).holeBoundaries(original.holeBoundaries())
                    .startZone(original.startZone()).goalZone(original.goalZone())
                    .playerRadius(original.playerRadius()).safetyMargin(original.safetyMargin())
                    .routeGraph(original.routeGraph()).objectives(original.objectives())
                    .gates(original.gates()).safeZones(original.safeZones())
                    .parTimeMs(original.parTimeMs()).timeAttackLimitMs(original.timeAttackLimitMs()).build();
            assertEquals(original.geometryHash(), reordered.geometryHash());
        }

        @Test public void catalogExposesFiveOrderedLevelsAndStableDailySelection() {
            Catalog catalog = new Catalog(List.of(Level01.build(), Level02.build(), Level03.build(),
                    Level04.build(), Level05.build()));
            assertEquals(5, catalog.size());
            assertEquals(Level01.LEVEL_ID, catalog.get(0).levelId());
            assertEquals(Level05.LEVEL_ID, catalog.get(4).levelId());
            assertEquals(catalog.catalogHash(), DailyChallenge.canonicalCatalogHash(catalog));
        }

        @Test public void builtInCatalogKeepsAllFiveValidatedLevelsInOrder() {
            Catalog catalog = BuiltInLevelCatalog.create();
            assertEquals(5, catalog.size());
            assertEquals(Level01.LEVEL_ID, catalog.get(0).levelId());
            assertEquals(Level02.LEVEL_ID, catalog.get(1).levelId());
            assertEquals(Level03.LEVEL_ID, catalog.get(2).levelId());
            assertEquals(Level04.LEVEL_ID, catalog.get(3).levelId());
            assertEquals(Level05.LEVEL_ID, catalog.get(4).levelId());
            for (LevelDefinition level : catalog.all()) requireNoProblems(level);
        }

        @Test public void dailyChallengeSupportsCatalogGrowthWithoutFiveLevelAssumption() {
            LevelDefinition base = Level01.build();
            LevelDefinition sixth = new LevelDefinition.Builder()
                    .levelId("level-06-afterglow").levelRevision(1)
                    .displayNameKey("level_06_name").worldBounds(base.worldBounds())
                    .outerBoundary(base.outerBoundary()).holeBoundaries(base.holeBoundaries())
                    .startZone(base.startZone()).goalZone(base.goalZone())
                    .playerRadius(base.playerRadius()).safetyMargin(base.safetyMargin())
                    .routeGraph(base.routeGraph()).objectives(base.objectives())
                    .gates(base.gates()).safeZones(base.safeZones())
                    .parTimeMs(base.parTimeMs()).timeAttackLimitMs(base.timeAttackLimitMs()).build();
            Catalog expanded = new Catalog(List.of(Level01.build(), Level02.build(), Level03.build(),
                    Level04.build(), Level05.build(), sixth));
            assertEquals(6, expanded.size());
            DailyChallenge.Selection first = DailyChallenge.forUtcDate(
                    "2026-11-24", ReplaySystem.GAME_RULES_VERSION, expanded);
            DailyChallenge.Selection repeated = DailyChallenge.forUtcDate(
                    "2026-11-24", ReplaySystem.GAME_RULES_VERSION, expanded);
            assertEquals(first.challengeId(), repeated.challengeId());
            assertTrue(first.levelNumber() >= 1 && first.levelNumber() <= expanded.size());
            assertTrue(DailyChallenge.hasValidChallengeIdShape(first.challengeId()));
            assertEquals(first.levelId(), DailyChallenge.levelIdFromChallengeId(first.challengeId()));
            assertFalse(DailyChallenge.hasValidChallengeIdShape(
                    "NMZR-D2-2026-11-24-level 06"));
        }
    }

    // =====================================================================
    // Geometry basics
    // =====================================================================

    public static class GeometryBasicsTest {

        private static final List<Vec2> UNIT_SQUARE = List.of(
                new Vec2(0, 0), new Vec2(10, 0),
                new Vec2(10, 10), new Vec2(0, 10));

        @Test public void pointInPolygonInsideAndOutside() {
            assertTrue(GeometryCollision.pointInPolygon(new Vec2(5, 5), UNIT_SQUARE));
            assertFalse(GeometryCollision.pointInPolygon(new Vec2(15, 5), UNIT_SQUARE));
            assertFalse(GeometryCollision.pointInPolygon(new Vec2(-1, 5), UNIT_SQUARE));
        }

        @Test public void validatePolygonAcceptsSquare() {
            GeometryCollision.validatePolygon(UNIT_SQUARE, "square");
        }

        @Test public void validatePolygonRejectsSelfIntersection() {
            List<Vec2> bowtie = List.of(
                    new Vec2(0, 0), new Vec2(10, 10),
                    new Vec2(10, 0), new Vec2(0, 10));
            try {
                GeometryCollision.validatePolygon(bowtie, "bowtie");
                fail("self-intersection must be rejected");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        @Test public void validatePolygonRejectsTooFewVertices() {
            try {
                GeometryCollision.validatePolygon(
                        List.of(new Vec2(0, 0), new Vec2(1, 1)), "line");
                fail();
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        @Test public void validatePolygonRejectsDuplicateVertices() {
            try {
                GeometryCollision.validatePolygon(List.of(
                        new Vec2(0, 0), new Vec2(10, 0),
                        new Vec2(10, 10), new Vec2(10, 10)), "dup");
                fail();
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }

        @Test public void pointSegmentDistanceForZeroLengthSegment() {
            double d2 = GeometryCollision.pointSegmentDistanceSquared(
                    new Vec2(3, 4), new Vec2(0, 0), new Vec2(0, 0));
            assertEquals(25.0, d2, 1e-9);
        }

        @Test public void pointSegmentDistanceForPerpendicularProjection() {
            double d2 = GeometryCollision.pointSegmentDistanceSquared(
                    new Vec2(5, 5), new Vec2(0, 0), new Vec2(10, 0));
            assertEquals(25.0, d2, 1e-9);
        }

        @Test public void segmentDistanceForParallelSegments() {
            double d2 = GeometryCollision.segmentDistanceSquared(
                    new Vec2(0, 0), new Vec2(10, 0),
                    new Vec2(0, 5), new Vec2(10, 5));
            assertEquals(25.0, d2, 1e-9);
        }

        @Test public void segmentDistanceForDegeneratePair() {
            double d2 = GeometryCollision.segmentDistanceSquared(
                    new Vec2(0, 0), new Vec2(0, 0),
                    new Vec2(3, 4), new Vec2(3, 4));
            assertEquals(25.0, d2, 1e-9);
        }
    }

    // =====================================================================
    // Sweep collision
    // =====================================================================

    public static class SweepCollisionTest {

        private static Region smallRoom() {
            return new Region(
                    List.of(new Vec2(0, 0), new Vec2(100, 0),
                            new Vec2(100, 100), new Vec2(0, 100)),
                    List.of());
        }

        @Test public void interiorSweepIsSafe() {
            assertTrue(GeometryCollision.isSweepSafe(
                    smallRoom(), new Vec2(30, 30), new Vec2(70, 70), 10));
        }

        @Test public void sweepCrossingWallIsUnsafe() {
            assertFalse(GeometryCollision.isSweepSafe(
                    smallRoom(), new Vec2(50, 50), new Vec2(120, 50), 10));
        }

        @Test public void tinyStepAcrossWallIsCaught() {
            // Tunneling guard: even a one-unit step that puts the centre
            // within the effective radius of the wall must be rejected.
            assertFalse(GeometryCollision.isSweepSafe(
                    smallRoom(), new Vec2(95, 50), new Vec2(96, 50), 10));
        }

        @Test public void zeroLengthSweepInsideIsSafe() {
            assertTrue(GeometryCollision.isSweepSafe(
                    smallRoom(), new Vec2(50, 50), new Vec2(50, 50), 10));
        }

        @Test public void startOutsideRegionIsUnsafe() {
            assertFalse(GeometryCollision.isSweepSafe(
                    smallRoom(), new Vec2(-50, 50), new Vec2(50, 50), 10));
        }

        @Test public void firstContactTimeIsZeroWhenStartIsUnsafe() {
            double t = GeometryCollision.firstContactTime(
                    smallRoom(), new Vec2(-50, 50), new Vec2(50, 50), 10);
            assertEquals(0.0, t, 1e-9);
        }

        @Test public void firstContactTimeIsOneWhenFullySafe() {
            double t = GeometryCollision.firstContactTime(
                    smallRoom(), new Vec2(30, 30), new Vec2(70, 70), 10);
            assertEquals(1.0, t, 1e-9);
        }

        @Test public void firstContactTimeIsIntermediateForCrossing() {
            double t = GeometryCollision.firstContactTime(
                    smallRoom(), new Vec2(50, 50), new Vec2(150, 50), 10);
            assertTrue("t must be strictly between 0 and 1", t > 0.0 && t < 1.0);
        }
    }

    // =====================================================================
    // Engine — start / stop
    // =====================================================================

    public static class EngineStartStopTest {

        @Test public void downOutsideStartZoneIsIgnored() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 500, 500, 0L);
            assertEquals(GameEngine.EngineState.READY, e.getState());
        }

        @Test public void downInsideStartZoneBeginsRun() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            assertEquals(GameEngine.EngineState.RUNNING, e.getState());
        }

        @Test public void upBeforeGoalFailsWithIncompleteRun() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 100L);
            e.onPointerUp(1, 200, 500, 200L);
            assertEquals(GameEngine.EngineState.FAILED, e.getState());
            assertEquals(GameEngine.FailureReason.INCOMPLETE_RUN,
                    e.getFailureReason());
        }

        @Test public void cancelInterruptsWithoutRecordingFailure() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 100L);
            e.onPointerCancel(1);
            assertEquals(GameEngine.EngineState.INTERRUPTED, e.getState());
            assertEquals(GameEngine.FailureReason.NONE, e.getFailureReason());
        }

        @Test public void wrongPointerIdDoesNotMoveMarker() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(2, 200, 500, 100L);
            assertNotNull(e.getMarkerPosition());
            assertEquals(200.0, e.getMarkerPosition().x(), 1e-9);
            assertEquals(150.0, e.getMarkerPosition().y(), 1e-9);
        }

        @Test public void outOfOrderSampleIsRejected() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 1000L);
            e.onPointerMove(1, 200, 500, 2000L);
            try {
                e.onPointerMove(1, 200, 600, 1500L);
                fail("out-of-order sample must throw");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    // =====================================================================
    // Engine — path completion and failure
    // =====================================================================

    public static class EnginePathTest {

        @Test public void straightThroughLevel01CorridorCompletes() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 800, 2_000L);
            e.onPointerMove(1, 800, 800, 4_000L);
            e.onPointerMove(1, 800, 150, 6_000L);
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            assertNotNull(e.getOutcome());
            assertNull("no failure reason expected", nonNoneReason(e));
        }

        @Test public void wallCrossingFailsWithPathBreach() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 50, 150, 500L);
            assertEquals(GameEngine.EngineState.FAILED, e.getState());
            assertEquals(GameEngine.FailureReason.PATH_BREACH,
                    e.getFailureReason());
        }

        @Test public void manySmallStepsAlsoComplete() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            long t = 100L;
            for (int y = 200; y <= 800; y += 50) {
                e.onPointerMove(1, 200, y, t += 100L);
            }
            for (int x = 250; x <= 800; x += 50) {
                e.onPointerMove(1, x, 800, t += 100L);
            }
            for (int y = 750; y >= 150; y -= 50) {
                e.onPointerMove(1, 800, y, t += 100L);
            }
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
        }

        private static GameEngine.FailureReason nonNoneReason(GameEngine e) {
            GameEngine.FailureReason r = e.getFailureReason();
            return r == GameEngine.FailureReason.NONE ? null : r;
        }
    }

    // =====================================================================
    // Engine — time and pause
    // =====================================================================

    public static class EngineTimeTest {

        @Test public void campaignHasNoTimeLimit() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onFrame(25_000L);
            assertEquals(GameEngine.EngineState.RUNNING, e.getState());
        }

        @Test public void timeAttackExpiresWithoutGoal() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.TIME_ATTACK, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 5_000L);
            e.onFrame(25_000L);
            assertEquals(GameEngine.EngineState.FAILED, e.getState());
            assertEquals(GameEngine.FailureReason.TIME_EXPIRED,
                    e.getFailureReason());
        }

        @Test public void timeAttackCompletesEvenAtExpirySample() {
            // The sample that reaches the goal also crosses the limit.
            // processSample runs first, so COMPLETED wins.
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.TIME_ATTACK, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 800, 5_000L);
            e.onPointerMove(1, 800, 800, 10_000L);
            e.onPointerMove(1, 800, 150, 20_001L);
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
        }

        @Test public void pauseInCampaignMarksAssisted() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 1_000L);
            e.pause(1_500L);
            assertEquals(GameEngine.EngineState.PAUSED, e.getState());
            assertTrue(e.isAssisted());
        }

        @Test public void resumeInsideAnchorSucceeds() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 1_000L);
            e.pause(1_500L);
            // Level01 resumeAnchorRadius = playerRadius * 2 = 30.
            // (200, 500) -> (210, 510) is ~14 units away, inside anchor.
            e.onPointerDown(2, 210, 510, 2_000L);
            assertEquals(GameEngine.EngineState.RUNNING, e.getState());
        }

        @Test public void resumeOutsideAnchorInterrupts() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 1_000L);
            e.pause(1_500L);
            e.onPointerDown(2, 500, 500, 2_000L);
            assertEquals(GameEngine.EngineState.INTERRUPTED, e.getState());
        }

        @Test public void pauseInTimeAttackIsNotAllowed() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.TIME_ATTACK, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 1_000L);
            e.pause(1_500L);
            assertEquals(GameEngine.EngineState.RUNNING, e.getState());
        }

        @Test public void interruptIsNotFailure() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 500, 1_000L);
            e.interrupt("test");
            assertEquals(GameEngine.EngineState.INTERRUPTED, e.getState());
            assertEquals(GameEngine.FailureReason.NONE, e.getFailureReason());
        }
    }

    // =====================================================================
    // Engine — scoring and stars
    // =====================================================================

    public static class EngineScoringTest {

        @Test public void campaignCompletionComputesScoreAndStars() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 800, 2_000L);
            e.onPointerMove(1, 800, 800, 4_000L);
            e.onPointerMove(1, 800, 150, 6_000L);
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            // Level01: par = 12000, no optional objectives. The player
            // endpoint is sampled at 6000 ms, but the marker enters Goal at
            // 5876 ms and the score uses that exact contact time.
            assertEquals(5_876L, e.getActiveMs());
            assertEquals(1153, e.getScore());
            // Completion is timestamped at the first swept-disk contact with
            // the Goal zone, not at the end of the final pointer sample.
            // A no-Optional level uses the explicit 80%-of-Par third-star target.
            assertEquals(3, e.getStars());
        }

        @Test public void noOptionalLevelUsesFastParThresholdForThirdStar() {
            LevelDefinition level = Level01.build();
            long threshold = (long) Math.floor(level.parTimeMs()
                    * ProgressRules.NO_OPTIONAL_THREE_STAR_PAR_RATIO);
            assertEquals(3, ProgressRules.starsFor(level, Collections.emptySet(), threshold, true));
            assertEquals(2, ProgressRules.starsFor(level, Collections.emptySet(), threshold + 1L, true));
            assertEquals(2, ProgressRules.starsFor(level, Collections.emptySet(), level.parTimeMs(), true));
            assertEquals(1, ProgressRules.starsFor(level, Collections.emptySet(), level.parTimeMs() + 1L, true));
        }

        @Test public void practiceModeGivesNoScore() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.PRACTICE, 0L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 800, 2_000L);
            e.onPointerMove(1, 800, 800, 4_000L);
            e.onPointerMove(1, 800, 150, 6_000L);
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            assertEquals(0, e.getScore());
            assertEquals(0, e.getStars());
        }

        @Test public void optionalCollectedAddsScoreAndThirdStar() {
            LevelDefinition level = buildOpenRoomWithOptional();
            GameEngine e = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 100, 100, 0L);
            e.onPointerMove(1, 300, 200, 1_000L); // collect optional
            e.onPointerMove(1, 400, 400, 2_000L); // reach goal
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            // The swept-disk Goal contact occurs at 1865 ms, before the
            // final pointer sample at 2000 ms. The time bonus is therefore
            // floor(300 * (5000 - 1865) / 5000) = 188.
            assertEquals(1_865L, e.getActiveMs());
            assertEquals(1438, e.getScore());
            assertEquals(3, e.getStars());
        }

        @Test public void optionalMissedGivesTwoStars() {
            LevelDefinition level = buildOpenRoomWithOptional();
            GameEngine e = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 100, 100, 0L);
            e.onPointerMove(1, 400, 400, 2_000L);
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            // Completion is recorded at first Goal contact (1858 ms), not
            // at the 2000 ms end of the pointer sample.
            assertEquals(1_858L, e.getActiveMs());
            assertEquals(1188, e.getScore());
            assertEquals(2, e.getStars());
        }

        @Test public void overParGivesOneStar() {
            LevelDefinition level = buildOpenRoomWithOptional();
            GameEngine e = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 100, 100, 0L);
            e.onPointerMove(1, 400, 400, 8_000L);
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            assertEquals(1000, e.getScore());
            assertEquals(1, e.getStars());
        }
    }

    // =====================================================================
    // Engine — objective blocking and gate blocking
    // =====================================================================

    public static class EngineObjectiveTest {

        @Test public void goalReachedWithoutMandatorySetsObjectiveBlocked() {
            LevelDefinition level = buildOpenRoomWithMandatory();
            GameEngine e = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 100, 100, 0L);
            e.onPointerMove(1, 400, 400, 2_000L);
            assertEquals(GameEngine.EngineState.RUNNING, e.getState());
            assertTrue("objective-blocked flag must be set",
                    e.isObjectiveBlocked());
        }

        @Test public void collectingMandatoryThenGoalCompletes() {
            LevelDefinition level = buildOpenRoomWithMandatory();
            GameEngine e = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 100, 100, 0L);
            e.onPointerMove(1, 200, 400, 2_000L); // collect mandatory
            e.onPointerMove(1, 400, 400, 4_000L); // reach goal
            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            assertFalse(e.isObjectiveBlocked());
        }
    }

    public static class EngineGateTest {

        @Test public void gatePassIsCapturedAndNextGateBecomesRelevant() {
            LevelDefinition level = Level05.build();
            GameEngine engine = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            assertEquals("timed-gate", engine.getRelevantGateId());
            engine.onPointerDown(1, 500.0, 150.0, 0L);
            engine.onPointerMove(1, 500.0, 400.0, 1_000L);
            assertTrue(engine.getPassedGateIds().contains("timed-gate"));
            assertEquals("exit-gate", engine.getRelevantGateId());
            engine.interrupt("test-after-first-gate");
            assertTrue(engine.getOutcome().passedGateIds().contains("timed-gate"));
            ProgressRules.RunRecord record = ProgressRules.RunRecord.fromOutcome(
                    "gate-pass-test", level, engine.getOutcome(), (String) null);
            assertTrue(record.passedGateIds().contains("timed-gate"));
        }

        @Test public void presentationTrailAndGateLedgerSurviveReplayOverflow() {
            GameEngine engine = new GameEngine(Level03.build(), ReplayMode.CAMPAIGN, 0L);
            engine.onPointerDown(1, 160.0, 510.0, 0L);
            for (int i = 1; i <= 95_100; i++) {
                double x = 160.0 + (880.0 * i / 95_100.0);
                engine.onPointerMove(1, x, 510.0, i);
                if (engine.getState() != GameEngine.EngineState.RUNNING) break;
            }
            assertEquals(GameEngine.EngineState.COMPLETED, engine.getState());
            assertTrue(engine.getTrail().size() <= GameEngine.MAX_TRAIL_POINTS);
            assertTrue(engine.getOutcome().replayUnavailable());
            assertNull(engine.getOutcome().trace());
            assertTrue(engine.getOutcome().passedGateIds().contains("timed-gate"));
            ProgressRules.RunRecord record = ProgressRules.RunRecord.fromOutcome(
                    "gate-overflow-test", Level03.build(), engine.getOutcome(), (String) null);
            assertTrue(record.passedGateIds().contains("timed-gate"));
        }

        @Test public void lockedGateBlocksButDoesNotFail() {
            LevelDefinition level = Level04.build();
            GameEngine e = new GameEngine(level, ReplayMode.CAMPAIGN, 0L);
            e.onPointerDown(1, 500, 200, 0L);
            e.onPointerMove(1, 500, 500, 1_000L); // junction
            e.onPointerMove(1, 500, 700, 2_000L); // attempt locked gate
            assertEquals(GameEngine.EngineState.RUNNING, e.getState());
            // The marker may enter the passage, but its 20-unit effective
            // disk must stop before the y=700 barrier (at approximately 679.5).
            // This confirms it is safely on the approach side of the barrier.
            assertTrue("marker must remain inside passage before barrier",
                    e.getMarkerPosition().y() > 650.0
                            && e.getMarkerPosition().y() < 680.0);
            assertEquals(GameEngine.FailureReason.NONE, e.getFailureReason());
        }
    }

    // =====================================================================
    // Replay
    // =====================================================================

    public static class ReplayRoundTripTest {

        @Test public void startOnlyTraceRoundTrip() {
            LevelDefinition level = Level01.build();
            ReplayTrace trace = ReplayTrace.builder()
                    .levelId(level.levelId())
                    .levelRevision(level.levelRevision())
                    .geometryHashHex(level.geometryHash())
                    .mode(ReplayMode.CAMPAIGN)
                    .runTimestampMs(1_000L)
                    .activeDurationMs(0L)
                    .sample(0, 200, 150)
                    .result(RunResult.COMPLETED)
                    .score(0)
                    .stars(0)
                    .assisted(false)
                    .build();

            byte[] bytes = ReplayCodec.encode(trace);
            ReplayTrace decoded = ReplayCodec.decode(bytes);

            assertNotNull(decoded);
            assertEquals(trace.levelId(), decoded.levelId());
            assertEquals(trace.levelRevision(), decoded.levelRevision());
            assertEquals(trace.mode(), decoded.mode());
            assertEquals(trace.result(), decoded.result());
            assertEquals(1, decoded.samples().size());
            assertEquals(0L, decoded.startPosition().tMs());
            assertEquals(200.0, decoded.startPosition().x(), 1e-9);
            assertEquals(150.0, decoded.startPosition().y(), 1e-9);
            assertEquals(0, decoded.events().size());
        }

        @Test public void traceWithoutStartAnchorIsRejected() {
            LevelDefinition level = Level01.build();
            try {
                ReplayTrace.builder()
                        .levelId(level.levelId())
                        .levelRevision(level.levelRevision())
                        .geometryHashHex(level.geometryHash())
                        .mode(ReplayMode.CAMPAIGN)
                        .runTimestampMs(1_000L)
                        .activeDurationMs(0L)
                        .result(RunResult.INTERRUPTED)
                        .score(0).stars(0).assisted(false)
                        .build();
                fail("a replay without a t=0 start anchor must be rejected");
            } catch (IllegalArgumentException expected) {
                // The first trace sample is the canonical start-position anchor.
            }
        }

        @Test public void populatedTraceRoundTrip() {
            LevelDefinition level = Level01.build();
            ReplayTrace trace = ReplayTrace.builder()
                    .levelId(level.levelId())
                    .levelRevision(level.levelRevision())
                    .geometryHashHex(level.geometryHash())
                    .mode(ReplayMode.CAMPAIGN)
                    .runTimestampMs(1_000L)
                    .activeDurationMs(6_000L)
                    .sample(0, 200, 150)
                    .sample(2_000, 200, 800)
                    .sample(4_000, 800, 800)
                    .sample(6_000, 800, 150)
                    .event(0, "START", "campaign")
                    .event(6_000, "COMPLETE", "goal")
                    .result(RunResult.COMPLETED)
                    .score(1_150)
                    .stars(2)
                    .assisted(false)
                    .build();

            byte[] bytes = ReplayCodec.encode(trace);
            ReplayTrace decoded = ReplayCodec.decode(bytes);

            assertNotNull(decoded);
            assertEquals(4, decoded.samples().size());
            assertEquals(2, decoded.events().size());
            assertEquals(1_150, decoded.score());
            assertEquals(2, decoded.stars());
        }

        @Test public void corruptedCrcIsRejected() {
            LevelDefinition level = Level01.build();
            ReplayTrace trace = ReplayTrace.builder()
                    .levelId(level.levelId())
                    .levelRevision(level.levelRevision())
                    .geometryHashHex(level.geometryHash())
                    .mode(ReplayMode.CAMPAIGN)
                    .runTimestampMs(1_000L)
                    .activeDurationMs(0L)
                    .sample(0, 200, 150)
                    .result(RunResult.COMPLETED)
                    .score(0).stars(0).assisted(false)
                    .build();

            byte[] bytes = ReplayCodec.encode(trace);
            bytes[bytes.length / 2] ^= 0x01;

            assertNull(ReplayCodec.decode(bytes));
        }

        @Test public void shortOrNullInputIsRejected() {
            assertNull(ReplayCodec.decode(null));
            assertNull(ReplayCodec.decode(new byte[0]));
            assertNull(ReplayCodec.decode(new byte[] {1, 2, 3}));
        }

        @Test public void incompatibleLevelReportsUnavailable() {
            LevelDefinition level01 = Level01.build();
            LevelDefinition level02 = Level02.build();
            ReplayTrace trace = ReplayTrace.builder()
                    .levelId(level01.levelId())
                    .levelRevision(level01.levelRevision())
                    .geometryHashHex(level01.geometryHash())
                    .mode(ReplayMode.CAMPAIGN)
                    .runTimestampMs(1_000L)
                    .activeDurationMs(0L)
                    .sample(0, 200, 150)
                    .result(RunResult.COMPLETED)
                    .score(0).stars(0).assisted(false)
                    .build();

            assertEquals(Compatibility.AVAILABLE,
                    trace.checkCompatibility(level01));
            assertEquals(Compatibility.UNAVAILABLE,
                    trace.checkCompatibility(level02));
        }

        @Test public void ghostTimelineInterpolatesBetweenSamples() {
            LevelDefinition level = Level01.build();
            ReplayTrace trace = ReplayTrace.builder()
                    .levelId(level.levelId())
                    .levelRevision(level.levelRevision())
                    .geometryHashHex(level.geometryHash())
                    .mode(ReplayMode.CAMPAIGN)
                    .runTimestampMs(1_000L)
                    .activeDurationMs(6_000L)
                    .sample(0, 200, 150)
                    .sample(6_000, 800, 150)
                    .result(RunResult.COMPLETED)
                    .score(0).stars(0).assisted(false)
                    .build();

            GhostTimeline ghost = GhostTimeline.from(trace, level);
            assertNotNull(ghost);
            TraceSample mid = ghost.positionAt(3_000L);
            assertEquals(500.0, mid.x(), 1e-6);
            assertEquals(150.0, mid.y(), 1e-6);
        }

        @Test public void ghostIsNullForIncompatibleLevel() {
            LevelDefinition level01 = Level01.build();
            LevelDefinition level02 = Level02.build();
            ReplayTrace trace = ReplayTrace.builder()
                    .levelId(level01.levelId())
                    .levelRevision(level01.levelRevision())
                    .geometryHashHex(level01.geometryHash())
                    .mode(ReplayMode.CAMPAIGN)
                    .runTimestampMs(1_000L)
                    .activeDurationMs(1_000L)
                    .sample(0, 200, 150)
                    .result(RunResult.COMPLETED)
                    .score(0).stars(0).assisted(false)
                    .build();

            assertNull(GhostTimeline.from(trace, level02));
        }
    }

    // =====================================================================
    // End-to-end smoke: full run produces a valid outcome
    // =====================================================================

    public static class EndToEndTest {

        @Test public void completedRunProducesConsistentOutcome() {
            GameEngine e = new GameEngine(
                    Level01.build(), ReplayMode.CAMPAIGN, 42L);
            e.onPointerDown(1, 200, 150, 0L);
            e.onPointerMove(1, 200, 800, 2_000L);
            e.onPointerMove(1, 800, 800, 4_000L);
            e.onPointerMove(1, 800, 150, 6_000L);

            assertEquals(GameEngine.EngineState.COMPLETED, e.getState());
            GameEngine.RunOutcome outcome = e.getOutcome();
            assertNotNull(outcome);
            assertEquals(RunResult.COMPLETED, outcome.result());
            assertFalse(outcome.assisted());
            assertEquals(1_153, outcome.score());
            assertEquals(5_876L, outcome.elapsedMs());
            assertNotNull("trace must be recorded", outcome.trace());

            // The trace must survive a full encode/decode round-trip.
            byte[] bytes = ReplayCodec.encode(outcome.trace());
            ReplayTrace decoded = ReplayCodec.decode(bytes);
            assertNotNull(decoded);
            assertEquals(Level01.LEVEL_ID, decoded.levelId());
        }
    }
}