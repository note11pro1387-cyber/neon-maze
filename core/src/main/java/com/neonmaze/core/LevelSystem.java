package com.neonmaze.core;

import com.neonmaze.core.GeometryCollision.Aabb;
import com.neonmaze.core.GeometryCollision.BoundarySegment;
import com.neonmaze.core.GeometryCollision.Contact;
import com.neonmaze.core.GeometryCollision.Region;
import com.neonmaze.core.GeometryCollision.SweepStatus;
import com.neonmaze.core.GeometryCollision.Vec2;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Immutable level data model, geometry/solvability validator and campaign
 * catalog. This package is core-only and has no Android dependencies.
 *
 * <p>World geometry and the directed route graph are deliberately separate:
 * GeometryCollision is authoritative for swept-disk walkability; the graph
 * describes authored route choices and is validated against that geometry.</p>
 */
public final class LevelSystem {
    private static final double VALIDATION_EPS = 1.0e-6;
    private static final String HASH_VERSION = "NMZ-LEVEL-v4";

    private LevelSystem() { }

    public enum ZoneShape { CIRCLE, POLYGON }
    public enum GateMode { TIMED, KEY_LOCKED }
    public enum ObjectiveType { MANDATORY, OPTIONAL }

    // ---------------------------------------------------------------------
    // Zone
    // ---------------------------------------------------------------------

    /** Immutable trigger/passage zone in world coordinates. */
    public static final class Zone {
        private final String id;
        private final ZoneShape shape;
        private final double cx;
        private final double cy;
        private final double radius;
        private final List<Vec2> polygon;

        private Zone(String id, ZoneShape shape, double cx, double cy,
                     double radius, List<Vec2> polygon) {
            this.id = requireText(id, "zone id");
            this.shape = Objects.requireNonNull(shape, "zone shape");
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
            this.polygon = immutablePoints(polygon);
        }

        public static Zone circle(String id, double cx, double cy, double radius) {
            requireFinite(cx, "zone cx");
            requireFinite(cy, "zone cy");
            requireFinite(radius, "zone radius");
            if (!(radius > 0.0)) throw new IllegalArgumentException("circle radius must be > 0: " + id);
            return new Zone(id, ZoneShape.CIRCLE, cx, cy, radius, Collections.<Vec2>emptyList());
        }

        public static Zone polygon(String id, List<Vec2> vertices) {
            if (vertices == null || vertices.size() < 3) {
                throw new IllegalArgumentException("polygon zone needs at least 3 vertices: " + id);
            }
            List<Vec2> copy = immutablePoints(vertices);
            GeometryCollision.validatePolygon(copy, "zone[" + id + "]");
            return new Zone(id, ZoneShape.POLYGON, 0.0, 0.0, 0.0, copy);
        }

        public String id() { return id; }
        public ZoneShape shape() { return shape; }
        public double cx() { return cx; }
        public double cy() { return cy; }
        public double radius() { return radius; }
        public List<Vec2> polygon() { return polygon; }

        /** Boundary-inclusive membership; used for trigger contact only. */
        public boolean contains(Vec2 p) {
            if (p == null) return false;
            if (shape == ZoneShape.CIRCLE) {
                double dx = p.x() - cx;
                double dy = p.y() - cy;
                return dx * dx + dy * dy <= radius * radius + GeometryCollision.EPS;
            }
            if (GeometryCollision.pointInPolygon(p, polygon)) return true;
            return pointOnPolygonBoundary(p, polygon);
        }

        public Aabb bounds() {
            if (shape == ZoneShape.CIRCLE) {
                return new Aabb(cx - radius, cy - radius, cx + radius, cy + radius);
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Vec2 p : polygon) {
                minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y());
                maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y());
            }
            return new Aabb(minX, minY, maxX, maxY);
        }

        /** Area centroid for polygons; circles use their centre. */
        public Vec2 centroid() {
            if (shape == ZoneShape.CIRCLE) return new Vec2(cx, cy);
            double twiceArea = 0.0, xSum = 0.0, ySum = 0.0;
            for (int i = 0; i < polygon.size(); i++) {
                Vec2 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
                double cross = a.x() * b.y() - b.x() * a.y();
                twiceArea += cross;
                xSum += (a.x() + b.x()) * cross;
                ySum += (a.y() + b.y()) * cross;
            }
            if (Math.abs(twiceArea) <= GeometryCollision.EPS) {
                throw new IllegalStateException("degenerate polygon zone: " + id);
            }
            return new Vec2(xSum / (3.0 * twiceArea), ySum / (3.0 * twiceArea));
        }
    }

    // ---------------------------------------------------------------------
    // Gate
    // ---------------------------------------------------------------------

    /**
     * Gate schedule and the finite physical barrier that the Engine sweeps.
     * Timed cycle order is OPEN -> WARNING (still passable) -> CLOSED.
     */
    public static final class Gate {
        private final String id;
        private final Zone passage;
        private final GateMode mode;
        private final Vec2 barrierStart;
        private final Vec2 barrierEnd;
        private final long periodMs;
        private final long openMs;
        private final long warningMs;
        private final long phaseOffsetMs;
        private final String requiredKeyId;

        private Gate(String id, Zone passage, GateMode mode,
                     Vec2 barrierStart, Vec2 barrierEnd, long periodMs,
                     long openMs, long warningMs, long phaseOffsetMs,
                     String requiredKeyId) {
            this.id = requireText(id, "gate id");
            this.passage = Objects.requireNonNull(passage, "gate passage");
            this.mode = Objects.requireNonNull(mode, "gate mode");
            if ((barrierStart == null) != (barrierEnd == null)) {
                throw new IllegalArgumentException("gate barrier endpoints must both be present or absent: " + id);
            }
            if (barrierStart != null && distanceSquared(barrierStart, barrierEnd) <= GeometryCollision.EPS) {
                throw new IllegalArgumentException("gate barrier must have non-zero length: " + id);
            }
            this.barrierStart = barrierStart;
            this.barrierEnd = barrierEnd;
            this.periodMs = periodMs;
            this.openMs = openMs;
            this.warningMs = warningMs;
            this.phaseOffsetMs = phaseOffsetMs;
            this.requiredKeyId = requiredKeyId;
        }

        /** Legacy factory retained for source compatibility; validator rejects barrier-less gates. */
        public static Gate timed(String id, Zone passage, long periodMs,
                                 long openMs, long warningMs, long phaseOffsetMs) {
            return timedInternal(id, passage, null, null, periodMs, openMs, warningMs, phaseOffsetMs);
        }

        public static Gate timed(String id, Zone passage, Vec2 barrierStart, Vec2 barrierEnd,
                                 long periodMs, long openMs, long warningMs, long phaseOffsetMs) {
            return timedInternal(id, passage, barrierStart, barrierEnd,
                    periodMs, openMs, warningMs, phaseOffsetMs);
        }

        private static Gate timedInternal(String id, Zone passage, Vec2 start, Vec2 end,
                                          long periodMs, long openMs, long warningMs,
                                          long phaseOffsetMs) {
            if (periodMs <= 0L) throw new IllegalArgumentException("gate periodMs must be > 0: " + id);
            if (openMs <= 0L) throw new IllegalArgumentException("gate openMs must be > 0: " + id);
            if (warningMs < 0L) throw new IllegalArgumentException("gate warningMs must be >= 0: " + id);
            if (openMs > periodMs || warningMs > periodMs - openMs
                    || openMs + warningMs >= periodMs) {
                throw new IllegalArgumentException("openMs + warningMs must be < periodMs: " + id);
            }
            if (phaseOffsetMs < 0L || phaseOffsetMs >= periodMs) {
                throw new IllegalArgumentException("phaseOffsetMs must be in [0, periodMs): " + id);
            }
            return new Gate(id, passage, GateMode.TIMED, start, end,
                    periodMs, openMs, warningMs, phaseOffsetMs, null);
        }

        /** Legacy factory retained; current Campaign gates must use the barrier overload. */
        public static Gate keyLocked(String id, Zone passage, String requiredKeyId) {
            return keyLockedInternal(id, passage, null, null, requiredKeyId);
        }

        public static Gate keyLocked(String id, Zone passage, Vec2 barrierStart,
                                     Vec2 barrierEnd, String requiredKeyId) {
            return keyLockedInternal(id, passage, barrierStart, barrierEnd, requiredKeyId);
        }

        private static Gate keyLockedInternal(String id, Zone passage, Vec2 start,
                                              Vec2 end, String requiredKeyId) {
            String key = requireText(requiredKeyId, "required key id");
            return new Gate(id, passage, GateMode.KEY_LOCKED, start, end,
                    0L, 0L, 0L, 0L, key);
        }

        public String id() { return id; }
        public Zone passage() { return passage; }
        public GateMode mode() { return mode; }
        public Vec2 barrierStart() { return barrierStart; }
        public Vec2 barrierEnd() { return barrierEnd; }
        public boolean hasBarrier() { return barrierStart != null && barrierEnd != null; }
        public long periodMs() { return periodMs; }
        public long openMs() { return openMs; }
        public long warningMs() { return warningMs; }
        public long phaseOffsetMs() { return phaseOffsetMs; }
        public String requiredKeyId() { return requiredKeyId; }

        /** Warning is intentionally traversable; deferred close is an Engine occupancy rule. */
        public boolean isOpenAt(long simTimeMs, boolean keyCollected) {
            if (mode == GateMode.KEY_LOCKED) return keyCollected;
            long phase = phaseAt(simTimeMs);
            return phase < openMs + warningMs;
        }

        public boolean isWarningAt(long simTimeMs) {
            if (mode == GateMode.KEY_LOCKED || warningMs == 0L) return false;
            long phase = phaseAt(simTimeMs);
            return phase >= openMs && phase < openMs + warningMs;
        }

        public boolean isFullyOpenAt(long simTimeMs, boolean keyCollected) {
            if (mode == GateMode.KEY_LOCKED) return keyCollected;
            return phaseAt(simTimeMs) < openMs;
        }

        public boolean isBlockingAt(long simTimeMs, boolean keyCollected) {
            return !isOpenAt(simTimeMs, keyCollected);
        }

        private long phaseAt(long simTimeMs) {
            // Reduce both operands before adding: simTimeMs + phaseOffsetMs can overflow.
            long timePhase = Math.floorMod(simTimeMs, periodMs);
            long offset = Math.floorMod(phaseOffsetMs, periodMs);
            long threshold = periodMs - offset;
            return timePhase >= threshold ? timePhase - threshold : timePhase + offset;
        }
    }

    // ---------------------------------------------------------------------
    // Objective
    // ---------------------------------------------------------------------

    public static final class Objective {
        private final String id;
        private final ObjectiveType type;
        private final Zone zone;
        private final String grantsKeyId;
        private final String requiredKeyId;

        private Objective(String id, ObjectiveType type, Zone zone,
                          String grantsKeyId, String requiredKeyId) {
            this.id = requireText(id, "objective id");
            this.type = Objects.requireNonNull(type, "objective type");
            this.zone = Objects.requireNonNull(zone, "objective zone");
            this.grantsKeyId = emptyToNull(grantsKeyId);
            this.requiredKeyId = emptyToNull(requiredKeyId);
        }

        public static Objective mandatory(String id, Zone zone) {
            return new Objective(id, ObjectiveType.MANDATORY, zone, null, null);
        }
        public static Objective optional(String id, Zone zone) {
            return new Objective(id, ObjectiveType.OPTIONAL, zone, null, null);
        }
        public static Objective keyGrant(String id, Zone zone, String grantsKeyId, String requiredKeyId) {
            return new Objective(id, ObjectiveType.MANDATORY, zone, grantsKeyId, requiredKeyId);
        }
        public String id() { return id; }
        public ObjectiveType type() { return type; }
        public Zone zone() { return zone; }
        public String grantsKeyId() { return grantsKeyId; }
        public String requiredKeyId() { return requiredKeyId; }
    }

    // ---------------------------------------------------------------------
    // Route graph (Java 8 / Android API 24 compatible; no records)
    // ---------------------------------------------------------------------

    public static final class RouteNode {
        private final String id;
        private final Vec2 position;
        public RouteNode(String id, Vec2 position) {
            this.id = requireText(id, "route node id");
            this.position = Objects.requireNonNull(position, "route node position");
        }
        public String id() { return id; }
        public Vec2 position() { return position; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RouteNode)) return false;
            RouteNode that = (RouteNode) other;
            return id.equals(that.id) && position.equals(that.position);
        }
        @Override public int hashCode() { return 31 * id.hashCode() + position.hashCode(); }
        @Override public String toString() { return "RouteNode[id=" + id + ", position=" + position + "]"; }
    }

    public static final class RouteEdge {
        private final String fromId;
        private final String toId;
        private final double width;
        public RouteEdge(String fromId, String toId, double width) {
            this.fromId = requireText(fromId, "route edge fromId");
            this.toId = requireText(toId, "route edge toId");
            requireFinite(width, "route edge width");
            if (!(width > 0.0)) throw new IllegalArgumentException("route edge width must be > 0");
            this.width = width;
        }
        public String fromId() { return fromId; }
        public String toId() { return toId; }
        public double width() { return width; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RouteEdge)) return false;
            RouteEdge that = (RouteEdge) other;
            return fromId.equals(that.fromId) && toId.equals(that.toId)
                    && Double.compare(width, that.width) == 0;
        }
        @Override public int hashCode() {
            int result = 31 * fromId.hashCode() + toId.hashCode();
            return 31 * result + Double.hashCode(width);
        }
        @Override public String toString() {
            return "RouteEdge[fromId=" + fromId + ", toId=" + toId + ", width=" + width + "]";
        }
    }

    public static final class RouteGraph {
        private final List<RouteNode> nodes;
        private final List<RouteEdge> edges;
        private final Map<String, RouteNode> byId;

        public RouteGraph(List<RouteNode> nodes, List<RouteEdge> edges) {
            if (nodes == null || edges == null) throw new NullPointerException("route graph lists");
            if (nodes.size() < 2) throw new IllegalArgumentException("route graph needs at least two nodes");
            List<RouteNode> nodeCopy = immutableCopy(nodes, "route nodes");
            List<RouteEdge> edgeCopy = immutableCopy(edges, "route edges");
            LinkedHashMap<String, RouteNode> index = new LinkedHashMap<String, RouteNode>();
            for (RouteNode node : nodeCopy) {
                if (index.put(node.id(), node) != null) {
                    throw new IllegalArgumentException("duplicate route node id: " + node.id());
                }
            }
            for (RouteEdge edge : edgeCopy) {
                if (!index.containsKey(edge.fromId()) || !index.containsKey(edge.toId())) {
                    throw new IllegalArgumentException("route edge references missing node: " + edge);
                }
                if (edge.fromId().equals(edge.toId())) {
                    throw new IllegalArgumentException("route edge must not be a self-loop: " + edge.fromId());
                }
            }
            this.nodes = nodeCopy;
            this.edges = edgeCopy;
            this.byId = Collections.unmodifiableMap(index);
        }
        public List<RouteNode> nodes() { return nodes; }
        public List<RouteEdge> edges() { return edges; }
        public RouteNode node(String id) { return byId.get(id); }
        public int outDegree(String id) {
            int count = 0;
            for (RouteEdge edge : edges) if (edge.fromId().equals(id)) count++;
            return count;
        }
        public int degree(String id) {
            int count = 0;
            for (RouteEdge edge : edges) {
                if (edge.fromId().equals(id) || edge.toId().equals(id)) count++;
            }
            return count;
        }
        public boolean isReachable(String startId, String goalId) {
            if (!byId.containsKey(startId) || !byId.containsKey(goalId)) return false;
            if (startId.equals(goalId)) return true;
            Set<String> visited = new HashSet<String>();
            ArrayDeque<String> queue = new ArrayDeque<String>();
            visited.add(startId); queue.add(startId);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                for (RouteEdge edge : edges) {
                    if (edge.fromId().equals(current) && visited.add(edge.toId())) {
                        if (edge.toId().equals(goalId)) return true;
                        queue.addLast(edge.toId());
                    }
                }
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Difficulty metrics
    // ---------------------------------------------------------------------

    /** Derived descriptive metrics. They never alter Engine rules. */
    public static final class DifficultyProfile {
        private final double pathLengthWorldUnits;
        private final double minWidthWorldUnits;
        private final int turnCount;
        private final int branchCount;
        private final int mandatoryObjectiveCount;
        private final int optionalObjectiveCount;
        private final int timedGateCount;
        private final int keyLockedGateCount;
        private final double maxWidthWorldUnits;
        private final double steeringIndex;
        private final double backtrackingDistanceWorldUnits;
        private final double minimumParallelSectionSpacingWorldUnits;
        private final int waitingOpportunityCount;

        public DifficultyProfile(double pathLengthWorldUnits, double minWidthWorldUnits,
                                 int turnCount, int branchCount,
                                 int mandatoryObjectiveCount, int optionalObjectiveCount,
                                 int timedGateCount, int keyLockedGateCount) {
            this(pathLengthWorldUnits, minWidthWorldUnits, turnCount, branchCount,
                    mandatoryObjectiveCount, optionalObjectiveCount, timedGateCount,
                    keyLockedGateCount, minWidthWorldUnits,
                    pathLengthWorldUnits > 0.0 ? turnCount * 1000.0 / pathLengthWorldUnits : 0.0,
                    0.0, minWidthWorldUnits, 0);
        }

        public DifficultyProfile(double pathLengthWorldUnits, double minWidthWorldUnits,
                                 int turnCount, int branchCount,
                                 int mandatoryObjectiveCount, int optionalObjectiveCount,
                                 int timedGateCount, int keyLockedGateCount,
                                 double maxWidthWorldUnits, double steeringIndex,
                                 double backtrackingDistanceWorldUnits,
                                 double minimumParallelSectionSpacingWorldUnits,
                                 int waitingOpportunityCount) {
            requireFinite(pathLengthWorldUnits, "difficulty path length");
            requireFinite(minWidthWorldUnits, "difficulty min width");
            requireFinite(maxWidthWorldUnits, "difficulty max width");
            requireFinite(steeringIndex, "difficulty steering index");
            requireFinite(backtrackingDistanceWorldUnits, "difficulty backtracking distance");
            requireFinite(minimumParallelSectionSpacingWorldUnits, "difficulty parallel spacing");
            if (!(pathLengthWorldUnits > 0.0) || !(minWidthWorldUnits > 0.0)
                    || maxWidthWorldUnits < minWidthWorldUnits
                    || steeringIndex < 0.0 || backtrackingDistanceWorldUnits < 0.0
                    || minimumParallelSectionSpacingWorldUnits < 0.0) {
                throw new IllegalArgumentException("difficulty metrics are outside valid ranges");
            }
            if (turnCount < 0 || branchCount < 0 || mandatoryObjectiveCount < 0
                    || optionalObjectiveCount < 0 || timedGateCount < 0
                    || keyLockedGateCount < 0 || waitingOpportunityCount < 0) {
                throw new IllegalArgumentException("difficulty counts must be >= 0");
            }
            this.pathLengthWorldUnits = pathLengthWorldUnits;
            this.minWidthWorldUnits = minWidthWorldUnits;
            this.turnCount = turnCount;
            this.branchCount = branchCount;
            this.mandatoryObjectiveCount = mandatoryObjectiveCount;
            this.optionalObjectiveCount = optionalObjectiveCount;
            this.timedGateCount = timedGateCount;
            this.keyLockedGateCount = keyLockedGateCount;
            this.maxWidthWorldUnits = maxWidthWorldUnits;
            this.steeringIndex = steeringIndex;
            this.backtrackingDistanceWorldUnits = backtrackingDistanceWorldUnits;
            this.minimumParallelSectionSpacingWorldUnits = minimumParallelSectionSpacingWorldUnits;
            this.waitingOpportunityCount = waitingOpportunityCount;
        }
        public double pathLengthWorldUnits() { return pathLengthWorldUnits; }
        public double minWidthWorldUnits() { return minWidthWorldUnits; }
        public int turnCount() { return turnCount; }
        public int branchCount() { return branchCount; }
        public int mandatoryObjectiveCount() { return mandatoryObjectiveCount; }
        public int optionalObjectiveCount() { return optionalObjectiveCount; }
        public int timedGateCount() { return timedGateCount; }
        public int keyLockedGateCount() { return keyLockedGateCount; }
        public double maxWidthWorldUnits() { return maxWidthWorldUnits; }
        public double steeringIndex() { return steeringIndex; }
        public double backtrackingDistanceWorldUnits() { return backtrackingDistanceWorldUnits; }
        public double minimumParallelSectionSpacingWorldUnits() { return minimumParallelSectionSpacingWorldUnits; }
        public int waitingOpportunityCount() { return waitingOpportunityCount; }
    }

    // ---------------------------------------------------------------------
    // Level definition
    // ---------------------------------------------------------------------

    public static final class LevelDefinition {
        private final String levelId;
        private final int levelRevision;
        private final String displayNameKey;
        private final Aabb worldBounds;
        private final List<Vec2> outerBoundary;
        private final List<List<Vec2>> holeBoundaries;
        private final Zone startZone;
        private final Zone goalZone;
        private final double playerRadius;
        private final double safetyMargin;
        private final RouteGraph routeGraph;
        private final List<Objective> objectives;
        private final List<Gate> gates;
        private final List<Zone> safeZones;
        private final long parTimeMs;
        private final long timeAttackLimitMs;
        private final DifficultyProfile difficultyProfile;
        private final String geometryHash;
        private final Region walkableRegion;

        private LevelDefinition(Builder b) {
            levelId = requireText(b.levelId, "levelId");
            if (b.levelRevision < 1) throw new IllegalArgumentException("levelRevision must be >= 1");
            levelRevision = b.levelRevision;
            displayNameKey = requireText(b.displayNameKey, "displayNameKey");
            worldBounds = Objects.requireNonNull(b.worldBounds, "worldBounds");
            outerBoundary = immutablePoints(Objects.requireNonNull(b.outerBoundary, "outerBoundary"));
            List<List<Vec2>> holeCopy = new ArrayList<List<Vec2>>();
            for (List<Vec2> hole : nullToEmpty(b.holeBoundaries)) holeCopy.add(immutablePoints(hole));
            holeBoundaries = Collections.unmodifiableList(holeCopy);
            startZone = Objects.requireNonNull(b.startZone, "startZone");
            goalZone = Objects.requireNonNull(b.goalZone, "goalZone");
            requireFinite(b.playerRadius, "playerRadius");
            requireFinite(b.safetyMargin, "safetyMargin");
            if (!(b.playerRadius > 0.0) || b.safetyMargin < 0.0) {
                throw new IllegalArgumentException("player radius must be > 0 and safety margin >= 0");
            }
            playerRadius = b.playerRadius;
            safetyMargin = b.safetyMargin;
            routeGraph = Objects.requireNonNull(b.routeGraph, "routeGraph");
            objectives = immutableCopy(nullToEmpty(b.objectives), "objectives");
            gates = immutableCopy(nullToEmpty(b.gates), "gates");
            safeZones = immutableCopy(nullToEmpty(b.safeZones), "safeZones");
            if (b.parTimeMs <= 0L || b.timeAttackLimitMs <= 0L) {
                throw new IllegalArgumentException("par and time-attack times must be > 0");
            }
            parTimeMs = b.parTimeMs;
            timeAttackLimitMs = b.timeAttackLimitMs;
            walkableRegion = new Region(outerBoundary, holeBoundaries);
            difficultyProfile = deriveDifficultyProfile(this, b.difficultyProfile);
            geometryHash = GeometryHash.compute(this);
        }

        public String levelId() { return levelId; }
        public int levelRevision() { return levelRevision; }
        public String displayNameKey() { return displayNameKey; }
        public Aabb worldBounds() { return worldBounds; }
        public List<Vec2> outerBoundary() { return outerBoundary; }
        public List<List<Vec2>> holeBoundaries() { return holeBoundaries; }
        public Zone startZone() { return startZone; }
        public Zone goalZone() { return goalZone; }
        public double playerRadius() { return playerRadius; }
        public double safetyMargin() { return safetyMargin; }
        public double effectiveRadius() { return playerRadius + safetyMargin; }
        public RouteGraph routeGraph() { return routeGraph; }
        public List<Objective> objectives() { return objectives; }
        public List<Gate> gates() { return gates; }
        public List<Zone> safeZones() { return safeZones; }
        public long parTimeMs() { return parTimeMs; }
        public long timeAttackLimitMs() { return timeAttackLimitMs; }
        public DifficultyProfile difficultyProfile() { return difficultyProfile; }
        public String geometryHash() { return geometryHash; }
        public Region walkableRegion() { return walkableRegion; }
        public Gate gate(String id) {
            for (Gate gate : gates) if (gate.id().equals(id)) return gate;
            return null;
        }
        public Objective objective(String id) {
            for (Objective objective : objectives) if (objective.id().equals(id)) return objective;
            return null;
        }

        public static final class Builder {
            private String levelId;
            private int levelRevision = 1;
            private String displayNameKey;
            private Aabb worldBounds;
            private List<Vec2> outerBoundary;
            private List<List<Vec2>> holeBoundaries = Collections.emptyList();
            private Zone startZone;
            private Zone goalZone;
            private double playerRadius;
            private double safetyMargin;
            private RouteGraph routeGraph;
            private List<Objective> objectives = Collections.emptyList();
            private List<Gate> gates = Collections.emptyList();
            private List<Zone> safeZones = Collections.emptyList();
            private long parTimeMs;
            private long timeAttackLimitMs;
            private DifficultyProfile difficultyProfile;

            public Builder levelId(String v) { levelId = v; return this; }
            public Builder levelRevision(int v) { levelRevision = v; return this; }
            public Builder displayNameKey(String v) { displayNameKey = v; return this; }
            public Builder worldBounds(Aabb v) { worldBounds = v; return this; }
            public Builder outerBoundary(List<Vec2> v) { outerBoundary = v; return this; }
            public Builder holeBoundaries(List<List<Vec2>> v) { holeBoundaries = v; return this; }
            public Builder startZone(Zone v) { startZone = v; return this; }
            public Builder goalZone(Zone v) { goalZone = v; return this; }
            public Builder playerRadius(double v) { playerRadius = v; return this; }
            public Builder safetyMargin(double v) { safetyMargin = v; return this; }
            public Builder routeGraph(RouteGraph v) { routeGraph = v; return this; }
            public Builder objectives(List<Objective> v) { objectives = v; return this; }
            public Builder gates(List<Gate> v) { gates = v; return this; }
            public Builder safeZones(List<Zone> v) { safeZones = v; return this; }
            public Builder parTimeMs(long v) { parTimeMs = v; return this; }
            public Builder timeAttackLimitMs(long v) { timeAttackLimitMs = v; return this; }
            /** Legacy hint retained for source compatibility; final metrics are derived from actual data. */
            public Builder difficultyProfile(DifficultyProfile v) { difficultyProfile = v; return this; }
            public LevelDefinition build() { return new LevelDefinition(this); }
        }
    }

    // ---------------------------------------------------------------------
    // Campaign Catalog
    // ---------------------------------------------------------------------

    /** Immutable ordered source of truth for campaign progression and Daily Challenge selection. */
    public static final class Catalog {
        private final List<LevelDefinition> levels;
        private final Map<String, LevelDefinition> byId;
        private final String catalogHash;

        public Catalog(List<LevelDefinition> levels) {
            if (levels == null) throw new NullPointerException("catalog levels");
            List<LevelDefinition> copy = immutableCopy(levels, "catalog levels");
            LinkedHashMap<String, LevelDefinition> index = new LinkedHashMap<String, LevelDefinition>();
            for (LevelDefinition level : copy) {
                Objects.requireNonNull(level, "catalog level");
                LevelValidator.requireValid(level);
                if (index.put(level.levelId(), level) != null) {
                    throw new IllegalArgumentException("duplicate catalog level id: " + level.levelId());
                }
            }
            this.levels = copy;
            this.byId = Collections.unmodifiableMap(index);
            this.catalogHash = computeCatalogHash(copy);
        }
        public List<LevelDefinition> all() { return levels; }
        public LevelDefinition byId(String id) {
            LevelDefinition value = byId.get(id);
            if (value == null) throw new IllegalArgumentException("unknown levelId: " + id);
            return value;
        }
        public boolean contains(String id) { return byId.containsKey(id); }
        public LevelDefinition get(int index) { return levels.get(index); }
        public boolean isEmpty() { return levels.isEmpty(); }
        public int size() { return levels.size(); }
        public String catalogHash() { return catalogHash; }
    }

    // ---------------------------------------------------------------------
    // Geometry / catalog hashing
    // ---------------------------------------------------------------------

    public static final class GeometryHash {
        private GeometryHash() { }
        public static String compute(LevelDefinition level) {
            Objects.requireNonNull(level, "level");
            StringBuilder out = new StringBuilder(4096);
            token(out, HASH_VERSION);
            token(out, level.levelId()); number(out, level.levelRevision()); token(out, level.displayNameKey());
            Aabb box = level.worldBounds();
            number(out, box.minX()); number(out, box.minY()); number(out, box.maxX()); number(out, box.maxY());
            appendPolygon(out, level.outerBoundary());
            List<List<Vec2>> canonicalHoles = new ArrayList<List<Vec2>>(level.holeBoundaries());
            Collections.sort(canonicalHoles, new Comparator<List<Vec2>>() {
                @Override public int compare(List<Vec2> a, List<Vec2> b) {
                    return canonicalPolygonKey(a).compareTo(canonicalPolygonKey(b));
                }
            });
            number(out, canonicalHoles.size());
            for (List<Vec2> hole : canonicalHoles) appendPolygon(out, hole);
            appendZone(out, level.startZone()); appendZone(out, level.goalZone());
            number(out, level.playerRadius()); number(out, level.safetyMargin());
            number(out, level.parTimeMs()); number(out, level.timeAttackLimitMs());
            List<Objective> objectives = new ArrayList<Objective>(level.objectives());
            Collections.sort(objectives, new Comparator<Objective>() {
                @Override public int compare(Objective a, Objective b) { return a.id().compareTo(b.id()); }
            });
            number(out, objectives.size());
            for (Objective objective : objectives) {
                token(out, objective.id()); token(out, objective.type().name());
                token(out, nullToEmpty(objective.grantsKeyId())); token(out, nullToEmpty(objective.requiredKeyId()));
                appendZone(out, objective.zone());
            }
            List<Gate> gates = new ArrayList<Gate>(level.gates());
            Collections.sort(gates, new Comparator<Gate>() {
                @Override public int compare(Gate a, Gate b) { return a.id().compareTo(b.id()); }
            });
            number(out, gates.size());
            for (Gate gate : gates) {
                token(out, gate.id()); token(out, gate.mode().name());
                number(out, gate.periodMs()); number(out, gate.openMs());
                number(out, gate.warningMs()); number(out, gate.phaseOffsetMs());
                token(out, nullToEmpty(gate.requiredKeyId()));
                number(out, gate.hasBarrier() ? 1 : 0);
                if (gate.hasBarrier()) {
                    number(out, gate.barrierStart().x()); number(out, gate.barrierStart().y());
                    number(out, gate.barrierEnd().x()); number(out, gate.barrierEnd().y());
                }
                appendZone(out, gate.passage());
            }
            List<Zone> safeZones = new ArrayList<Zone>(level.safeZones());
            Collections.sort(safeZones, new Comparator<Zone>() {
                @Override public int compare(Zone a, Zone b) { return a.id().compareTo(b.id()); }
            });
            number(out, safeZones.size());
            for (Zone zone : safeZones) appendZone(out, zone);
            List<RouteNode> nodes = new ArrayList<RouteNode>(level.routeGraph().nodes());
            Collections.sort(nodes, new Comparator<RouteNode>() {
                @Override public int compare(RouteNode a, RouteNode b) { return a.id().compareTo(b.id()); }
            });
            number(out, nodes.size());
            for (RouteNode node : nodes) {
                token(out, node.id()); number(out, node.position().x()); number(out, node.position().y());
            }
            List<RouteEdge> edges = new ArrayList<RouteEdge>(level.routeGraph().edges());
            Collections.sort(edges, new Comparator<RouteEdge>() {
                @Override public int compare(RouteEdge a, RouteEdge b) {
                    int first = a.fromId().compareTo(b.fromId());
                    if (first != 0) return first;
                    int second = a.toId().compareTo(b.toId());
                    return second != 0 ? second : Double.compare(a.width(), b.width());
                }
            });
            number(out, edges.size());
            for (RouteEdge edge : edges) {
                token(out, edge.fromId()); token(out, edge.toId()); number(out, edge.width());
            }
            return sha256Hex(out.toString());
        }

        private static void appendZone(StringBuilder out, Zone zone) {
            token(out, zone.id()); token(out, zone.shape().name());
            if (zone.shape() == ZoneShape.CIRCLE) {
                number(out, zone.cx()); number(out, zone.cy()); number(out, zone.radius());
            } else appendPolygon(out, zone.polygon());
        }
        private static void appendPolygon(StringBuilder out, List<Vec2> polygon) {
            List<Vec2> canonical = canonicalPolygon(polygon);
            number(out, canonical.size());
            for (Vec2 point : canonical) { number(out, point.x()); number(out, point.y()); }
        }

        private static String canonicalPolygonKey(List<Vec2> polygon) {
            StringBuilder key = new StringBuilder();
            for (Vec2 point : canonicalPolygon(polygon)) {
                key.append(Double.toHexString(point.x())).append(',')
                        .append(Double.toHexString(point.y())).append(';');
            }
            return key.toString();
        }

        /** Normalize winding and starting vertex so ring rotation/reversal does not alter identity. */
        private static List<Vec2> canonicalPolygon(List<Vec2> polygon) {
            if (polygon == null || polygon.isEmpty()) return Collections.emptyList();
            List<Vec2> points = new ArrayList<Vec2>(polygon);
            if (GeometryCollision.signedArea(points) < 0.0) Collections.reverse(points);
            int first = 0;
            for (int i = 1; i < points.size(); i++) {
                Vec2 candidate = points.get(i), current = points.get(first);
                int xOrder = Double.compare(candidate.x(), current.x());
                if (xOrder < 0 || (xOrder == 0 && Double.compare(candidate.y(), current.y()) < 0)) first = i;
            }
            if (first == 0) return points;
            List<Vec2> rotated = new ArrayList<Vec2>(points.size());
            for (int i = 0; i < points.size(); i++) rotated.add(points.get((first + i) % points.size()));
            return rotated;
        }
    }

    // ---------------------------------------------------------------------
    // Validator
    // ---------------------------------------------------------------------

    public static final class LevelValidator {
        private LevelValidator() { }

        public static List<String> validate(LevelDefinition level) {
            List<String> problems = new ArrayList<String>();
            if (level == null) { problems.add("level must not be null"); return immutableCopy(problems, "problems"); }
            if (level.levelRevision() < 1) problems.add("levelRevision must be >= 1");
            if (level.parTimeMs() <= 0L) problems.add("parTimeMs must be > 0");
            if (level.timeAttackLimitMs() <= 0L) problems.add("timeAttackLimitMs must be > 0");
            if (!(level.effectiveRadius() > 0.0) || !Double.isFinite(level.effectiveRadius())) {
                problems.add("effective player radius must be finite and > 0");
                return immutableCopy(problems, "problems");
            }
            Aabb world = level.worldBounds();
            checkPolygonWithinWorld(level.outerBoundary(), world, "outer boundary", problems);
            for (int i = 0; i < level.holeBoundaries().size(); i++) {
                checkPolygonWithinWorld(level.holeBoundaries().get(i), world, "hole[" + i + "]", problems);
            }
            Region region = level.walkableRegion();
            double playerRadius = level.effectiveRadius();

            // Start/Goal are trigger regions. Only their authored anchor must be
            // disk-walkable; the full trigger radius is intentionally not required
            // to fit inside the corridor.
            if (!region.containsCenter(level.startZone().centroid(), playerRadius)) {
                problems.add("start anchor is not disk-walkable: " + level.startZone().id());
            }
            if (!region.containsCenter(level.goalZone().centroid(), playerRadius)) {
                problems.add("goal anchor is not disk-walkable: " + level.goalZone().id());
            }

            Map<String, RouteNode> nodes = new HashMap<String, RouteNode>();
            for (RouteNode node : level.routeGraph().nodes()) {
                if (nodes.put(node.id(), node) != null) problems.add("duplicate route node id: " + node.id());
                if (!region.containsCenter(node.position(), playerRadius)) {
                    problems.add("route node is not disk-walkable: " + node.id());
                }
            }
            Set<String> directedEdgeKeys = new HashSet<String>();
            for (RouteEdge edge : level.routeGraph().edges()) {
                RouteNode from = nodes.get(edge.fromId());
                RouteNode to = nodes.get(edge.toId());
                if (from == null || to == null) {
                    problems.add("route edge references unknown node: " + edge);
                    continue;
                }
                if (edge.width() + VALIDATION_EPS < 2.0 * playerRadius) {
                    problems.add("declared route edge width is too small: " + edge);
                }
                double length = distance(from.position(), to.position());
                if (length <= GeometryCollision.EPS) {
                    problems.add("route edge has zero length: " + edge);
                } else {
                    Contact contact = new Contact();
                    SweepStatus status = GeometryCollision.sweepSegment(region,
                            from.position(), to.position(), playerRadius, contact);
                    if (status != SweepStatus.CLEAR) {
                        problems.add("route edge fails swept-disk validation: " + edge.fromId()
                                + " -> " + edge.toId() + " (" + status + ")");
                    }
                    // Very short objective spurs inherit the surrounding corridor
                    // width; their perpendicular section is not a corridor cross-section.
                    if (length + VALIDATION_EPS >= 2.0 * playerRadius) {
                        double measuredWidth = measureCrossSectionWidth(region, from.position(), to.position());
                        if (Double.isFinite(measuredWidth) && measuredWidth > 0.0) {
                            double tolerance = Math.max(1.0, edge.width() * 0.01);
                            if (edge.width() > measuredWidth + tolerance) {
                                problems.add("route edge width metadata overstates boundary geometry: "
                                        + edge.fromId() + " -> " + edge.toId() + " (declared="
                                        + edge.width() + ", measured=" + measuredWidth + ")");
                            }
                        } else {
                            problems.add("route edge has no measurable two-sided corridor width: "
                                    + edge.fromId() + " -> " + edge.toId());
                        }
                    }
                }
                String key = edge.fromId() + "\u0000" + edge.toId();
                if (!directedEdgeKeys.add(key)) problems.add("duplicate directed route edge: " + edge);
            }
            validateRouteTopology(level.routeGraph(), nodes, problems);

            String startNode = nearestNodeId(level.routeGraph(), level.startZone().centroid());
            String goalNode = nearestNodeId(level.routeGraph(), level.goalZone().centroid());
            if (startNode == null || goalNode == null
                    || !level.routeGraph().isReachable(startNode, goalNode)) {
                problems.add("route graph has no directed start-to-goal path");
            }

            Set<String> grantedKeys = new HashSet<String>();
            Set<String> objectiveIds = new HashSet<String>();
            for (Objective objective : level.objectives()) {
                if (!objectiveIds.add(objective.id())) problems.add("duplicate objective id: " + objective.id());
                if (!isZonePlayable(objective.zone(), region, playerRadius)) {
                    problems.add("objective zone is not disk-walkable: " + objective.id());
                }
                if (objective.grantsKeyId() != null) {
                    if (!grantedKeys.add(objective.grantsKeyId())) {
                        problems.add("duplicate key grant id: " + objective.grantsKeyId());
                    }
                }
                if (objective.requiredKeyId() != null && !grantedKeys.contains(objective.requiredKeyId())) {
                    // Order-independent check is repeated after all grants below.
                }
                if (!routeIntersectsZone(level.routeGraph(), objective.zone())) {
                    problems.add("objective trigger is not intersected by any route edge: " + objective.id());
                }
            }
            for (Objective objective : level.objectives()) {
                if (objective.requiredKeyId() != null && !grantedKeys.contains(objective.requiredKeyId())) {
                    problems.add("objective requires an unknown key: " + objective.id()
                            + " / " + objective.requiredKeyId());
                }
            }

            Set<String> gateIds = new HashSet<String>();
            for (Gate gate : level.gates()) {
                if (!gateIds.add(gate.id())) problems.add("duplicate gate id: " + gate.id());
                if (!gate.hasBarrier()) {
                    problems.add("gate is missing finite barrier endpoints: " + gate.id());
                } else {
                    validateBarrier(gate, region, problems);
                }
                if (gate.mode() == GateMode.TIMED) {
                    if (gate.periodMs() <= 0L || gate.openMs() <= 0L || gate.warningMs() < 0L
                            || gate.openMs() + gate.warningMs() >= gate.periodMs()) {
                        problems.add("timed gate schedule is invalid: " + gate.id());
                    }
                } else if (!grantedKeys.contains(gate.requiredKeyId())) {
                    problems.add("key-locked gate requires an unknown key: " + gate.id());
                }
                Vec2 passageCenter = gate.passage().centroid();
                if (!region.containsCenter(passageCenter, playerRadius)) {
                    problems.add("gate passage centre is not disk-walkable: " + gate.id());
                }
                if (gate.passage().contains(level.startZone().centroid())
                        || gate.passage().contains(level.goalZone().centroid())) {
                    problems.add("gate passage overlaps start/goal anchor: " + gate.id());
                }
                for (Objective objective : level.objectives()) {
                    if (gate.passage().contains(objective.zone().centroid())) {
                        problems.add("gate passage contains objective anchor: " + gate.id()
                                + " / " + objective.id());
                    }
                }
                if (gate.hasBarrier()) {
                    boolean crossedByRoute = false;
                    for (RouteEdge edge : level.routeGraph().edges()) {
                        RouteNode from = nodes.get(edge.fromId()), to = nodes.get(edge.toId());
                        if (from != null && to != null && edgeCrossesBarrier(
                                from.position(), to.position(), gate.barrierStart(), gate.barrierEnd())) {
                            crossedByRoute = true;
                            break;
                        }
                    }
                    if (!crossedByRoute) {
                        problems.add("gate finite barrier does not intersect a route edge: " + gate.id());
                    }
                }
            }
            for (int i = 0; i < level.gates().size(); i++) {
                for (int j = i + 1; j < level.gates().size(); j++) {
                    if (zonesOverlap(level.gates().get(i).passage(), level.gates().get(j).passage())) {
                        problems.add("gate passages overlap: " + level.gates().get(i).id()
                                + " / " + level.gates().get(j).id());
                    }
                }
            }
            SearchResult completion = findCompletionPath(level);
            if (!completion.complete) {
                problems.add("no valid Start-to-Goal route completes all mandatory objectives and key-gate requirements");
            }

            Set<String> safeIds = new HashSet<String>();
            for (Zone safe : level.safeZones()) {
                if (!safeIds.add(safe.id())) problems.add("duplicate safe-zone id: " + safe.id());
                if (!isZonePlayable(safe, region, playerRadius)) {
                    problems.add("safe zone is not fully disk-walkable: " + safe.id());
                }
                for (Gate gate : level.gates()) {
                    if (zonesOverlap(safe, gate.passage())) {
                        problems.add("safe zone overlaps gate passage: " + safe.id() + " / " + gate.id());
                    }
                }
            }

            DifficultyProfile metrics = level.difficultyProfile();
            if (metrics == null || metrics.pathLengthWorldUnits() <= 0.0
                    || metrics.minWidthWorldUnits() + VALIDATION_EPS < 2.0 * playerRadius) {
                problems.add("derived difficulty profile is invalid for the effective radius");
            }
            return immutableCopy(problems, "validation problems");
        }

        public static void requireValid(LevelDefinition level) {
            List<String> problems = validate(level);
            if (!problems.isEmpty()) {
                String name = level == null ? "<null>" : level.levelId();
                throw new IllegalArgumentException("invalid level " + name + ": " + problems);
            }
        }

        private static void validateBarrier(Gate gate, Region region, List<String> problems) {
            Vec2 start = gate.barrierStart(), end = gate.barrierEnd();
            if (start == null || end == null || distanceSquared(start, end) <= GeometryCollision.EPS) {
                problems.add("gate barrier is degenerate: " + gate.id()); return;
            }
            int startBoundary = matchingBoundaryIndex(region, start);
            int endBoundary = matchingBoundaryIndex(region, end);
            if (startBoundary < 0 || endBoundary < 0) {
                problems.add("gate barrier endpoints must attach to walkable-region boundaries: " + gate.id());
                return;
            }
            if (startBoundary == endBoundary) {
                problems.add("gate barrier endpoints attach to the same boundary segment: " + gate.id());
            }
            Vec2 middle = midpoint(start, end);
            if (!gate.passage().contains(middle)) {
                problems.add("gate barrier midpoint must lie inside its passage zone: " + gate.id());
            }
            for (int i = 1; i < 8; i++) {
                double t = i / 8.0;
                Vec2 sample = new Vec2(start.x() + (end.x() - start.x()) * t,
                        start.y() + (end.y() - start.y()) * t);
                if (!GeometryCollision.pointInPolygon(sample, region.outer())) {
                    problems.add("gate barrier leaves outer walkable polygon: " + gate.id());
                    break;
                }
                boolean inHole = false;
                for (List<Vec2> hole : region.holes()) {
                    if (GeometryCollision.pointInPolygon(sample, hole)) { inHole = true; break; }
                }
                if (inHole) {
                    problems.add("gate barrier crosses a hole: " + gate.id());
                    break;
                }
            }
        }

        private static int matchingBoundaryIndex(Region region, Vec2 point) {
            List<BoundarySegment> boundaries = region.boundarySegments();
            for (int i = 0; i < boundaries.size(); i++) {
                BoundarySegment edge = boundaries.get(i);
                if (GeometryCollision.pointSegmentDistanceSquared(point, edge.start(), edge.end())
                        <= VALIDATION_EPS * VALIDATION_EPS) return i;
            }
            return -1;
        }

        private static boolean isZonePlayable(Zone zone, Region region, double radius) {
            if (zone == null) return false;
            if (zone.shape() == ZoneShape.CIRCLE) {
                // Exact disk containment: the player-centre trigger circle and
                // the player's effective collision disk must fit together.
                // Sampling a finite number of perimeter points can miss a
                // narrow concavity between samples.
                double combinedRadius = zone.radius() + radius;
                if (!Double.isFinite(combinedRadius)) return false;
                return region.containsCenter(new Vec2(zone.cx(), zone.cy()), combinedRadius);
            }
            List<Vec2> poly = zone.polygon();
            for (int i = 0; i < poly.size(); i++) {
                Vec2 a = poly.get(i), b = poly.get((i + 1) % poly.size());
                if (!region.containsCenter(a, radius)) return false;
                Vec2 midpoint = midpoint(a, b);
                if (!region.containsCenter(midpoint, radius)) return false;
            }
            return region.containsCenter(zone.centroid(), radius);
        }

        private static void checkPolygonWithinWorld(List<Vec2> polygon, Aabb world,
                                                    String label, List<String> problems) {
            for (Vec2 point : polygon) {
                if (point.x() < world.minX() || point.x() > world.maxX()
                        || point.y() < world.minY() || point.y() > world.maxY()) {
                    problems.add(label + " vertex outside world bounds: " + point);
                    return;
                }
            }
        }

        private static String nearestNodeId(RouteGraph graph, Vec2 target) {
            String nearest = null;
            double best = Double.POSITIVE_INFINITY;
            for (RouteNode node : graph.nodes()) {
                double distance = distanceSquared(node.position(), target);
                if (distance < best) { best = distance; nearest = node.id(); }
            }
            return nearest;
        }

        private static boolean zonesOverlap(Zone a, Zone b) {
            if (!a.bounds().intersects(b.bounds())) return false;
            if (a.shape() == ZoneShape.CIRCLE && b.shape() == ZoneShape.CIRCLE) {
                double rr = a.radius() + b.radius();
                return distanceSquared(new Vec2(a.cx(), a.cy()), new Vec2(b.cx(), b.cy()))
                        <= rr * rr + GeometryCollision.EPS;
            }
            if (a.shape() == ZoneShape.CIRCLE) return circlePolygonOverlap(a, b.polygon());
            if (b.shape() == ZoneShape.CIRCLE) return circlePolygonOverlap(b, a.polygon());
            return polygonPolygonOverlap(a.polygon(), b.polygon());
        }

        private static boolean circlePolygonOverlap(Zone circle, List<Vec2> polygon) {
            Vec2 center = new Vec2(circle.cx(), circle.cy());
            if (GeometryCollision.pointInPolygon(center, polygon) || pointOnPolygonBoundary(center, polygon)) return true;
            double limit = circle.radius() * circle.radius();
            for (int i = 0; i < polygon.size(); i++) {
                Vec2 a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
                if (GeometryCollision.pointSegmentDistanceSquared(center, a, b) <= limit + GeometryCollision.EPS) return true;
            }
            return false;
        }

        private static boolean polygonPolygonOverlap(List<Vec2> a, List<Vec2> b) {
            for (Vec2 point : a) if (GeometryCollision.pointInPolygon(point, b) || pointOnPolygonBoundary(point, b)) return true;
            for (Vec2 point : b) if (GeometryCollision.pointInPolygon(point, a) || pointOnPolygonBoundary(point, a)) return true;
            for (int i = 0; i < a.size(); i++) {
                Vec2 a1 = a.get(i), a2 = a.get((i + 1) % a.size());
                for (int j = 0; j < b.size(); j++) {
                    Vec2 b1 = b.get(j), b2 = b.get((j + 1) % b.size());
                    if (GeometryCollision.segmentDistanceSquared(a1, a2, b1, b2)
                            <= GeometryCollision.EPS) return true;
                }
            }
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Legacy explicit registry (kept for API compatibility)
    // ---------------------------------------------------------------------

    public static final class LevelRegistry {
        private final List<LevelDefinition> levels;
        public LevelRegistry(List<LevelDefinition> levels) {
            Catalog catalog = new Catalog(levels);
            this.levels = catalog.all();
        }
        public List<LevelDefinition> all() { return levels; }
        public int size() { return levels.size(); }
        public LevelDefinition get(int index) { return levels.get(index); }
        public LevelDefinition byId(String id) {
            for (LevelDefinition level : levels) if (level.levelId().equals(id)) return level;
            throw new IllegalArgumentException("unknown levelId: " + id);
        }
    }

    // ---------------------------------------------------------------------
    // Derived metrics / canonical helpers
    // ---------------------------------------------------------------------

    private static DifficultyProfile deriveDifficultyProfile(LevelDefinition level,
                                                               DifficultyProfile ignoredHint) {
        RouteGraph graph = level.routeGraph();
        String start = nearestNodeId(graph, level.startZone().centroid());
        String goal = nearestNodeId(graph, level.goalZone().centroid());
        SearchResult completion = findCompletionPath(level);
        double pathLength = completion.complete ? completion.distance : shortestPathLength(graph, start, goal);
        if (!(pathLength > 0.0) || !Double.isFinite(pathLength)) pathLength = 1.0;

        double minWidth = Double.POSITIVE_INFINITY, maxWidth = 0.0;
        for (RouteEdge edge : graph.edges()) {
            RouteNode from = graph.node(edge.fromId()), to = graph.node(edge.toId());
            double measured = distance(from.position(), to.position()) + VALIDATION_EPS
                    >= 2.0 * level.effectiveRadius()
                    ? measureCrossSectionWidth(level.walkableRegion(), from.position(), to.position())
                    : Double.NaN;
            double width = Double.isFinite(measured) && measured > 0.0 ? measured : edge.width();
            minWidth = Math.min(minWidth, width);
            maxWidth = Math.max(maxWidth, width);
        }
        if (!Double.isFinite(minWidth)) minWidth = Math.max(1.0, 2.0 * level.effectiveRadius());
        if (maxWidth < minWidth) maxWidth = minWidth;

        int branches = countBranchNodes(graph);
        int turns = countPathTurns(graph, completion.nodePath);
        double totalTurnRadians = totalPathTurnRadians(graph, completion.nodePath);
        double backtracking = 0.0;
        Set<String> countedPairs = new HashSet<String>();
        for (RouteEdge edge : graph.edges()) {
            String forward = edge.fromId() + "\u0000" + edge.toId();
            String reverse = edge.toId() + "\u0000" + edge.fromId();
            if (countedPairs.contains(forward)) continue;
            for (RouteEdge candidate : graph.edges()) {
                if (candidate.fromId().equals(edge.toId()) && candidate.toId().equals(edge.fromId())) {
                    backtracking += 2.0 * distance(graph.node(edge.fromId()).position(),
                            graph.node(edge.toId()).position());
                    countedPairs.add(forward);
                    countedPairs.add(reverse);
                    break;
                }
            }
        }
        int mandatory = 0, optional = 0, timed = 0, locked = 0;
        for (Objective objective : level.objectives()) {
            if (objective.type() == ObjectiveType.MANDATORY) mandatory++; else optional++;
        }
        for (Gate gate : level.gates()) {
            if (gate.mode() == GateMode.TIMED) timed++; else locked++;
        }
        double steering = pathLength > 0.0 ? totalTurnRadians * 1000.0 / pathLength : 0.0;
        double parallelSpacing = minimumParallelSpacing(graph, 2.0 * level.effectiveRadius());
        int waitingOpportunities = countWaitingOpportunities(level, completion.nodePath);
        return new DifficultyProfile(pathLength, minWidth, turns, branches,
                mandatory, optional, timed, locked, maxWidth, steering, backtracking,
                parallelSpacing, waitingOpportunities);
    }

    private static int countBranchNodes(RouteGraph graph) {
        int branches = 0;
        for (RouteNode node : graph.nodes()) {
            Set<String> neighbours = new HashSet<String>();
            for (RouteEdge edge : graph.edges()) {
                if (edge.fromId().equals(node.id())) neighbours.add(edge.toId());
                if (edge.toId().equals(node.id())) neighbours.add(edge.fromId());
            }
            if (neighbours.size() > 2) branches++;
        }
        return branches;
    }

    private static int countPathTurns(RouteGraph graph, List<String> path) {
        int count = 0;
        if (path == null) return 0;
        for (int i = 1; i + 1 < path.size(); i++) {
            Vec2 a = graph.node(path.get(i - 1)).position();
            Vec2 b = graph.node(path.get(i)).position();
            Vec2 c = graph.node(path.get(i + 1)).position();
            double ax = b.x() - a.x(), ay = b.y() - a.y();
            double bx = c.x() - b.x(), by = c.y() - b.y();
            double angle = Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
            if (angle > Math.toRadians(5.0)) count++;
        }
        return count;
    }

    private static double totalPathTurnRadians(RouteGraph graph, List<String> path) {
        double total = 0.0;
        if (path == null) return 0.0;
        for (int i = 1; i + 1 < path.size(); i++) {
            Vec2 a = graph.node(path.get(i - 1)).position();
            Vec2 b = graph.node(path.get(i)).position();
            Vec2 c = graph.node(path.get(i + 1)).position();
            double ax = b.x() - a.x(), ay = b.y() - a.y();
            double bx = c.x() - b.x(), by = c.y() - b.y();
            total += Math.atan2(Math.abs(ax * by - ay * bx), ax * bx + ay * by);
        }
        return total;
    }

    private static double minimumParallelSpacing(RouteGraph graph, double minimumSectionLength) {
        double minimum = Double.POSITIVE_INFINITY;
        List<RouteEdge> edges = graph.edges();
        for (int i = 0; i < edges.size(); i++) {
            RouteEdge a = edges.get(i);
            Vec2 a1 = graph.node(a.fromId()).position(), a2 = graph.node(a.toId()).position();
            double adx = a2.x() - a1.x(), ady = a2.y() - a1.y();
            double alen = Math.hypot(adx, ady);
            if (alen < minimumSectionLength || alen <= GeometryCollision.EPS) continue;
            double ux = adx / alen, uy = ady / alen;
            double aMin = Math.min(a1.x() * ux + a1.y() * uy, a2.x() * ux + a2.y() * uy);
            double aMax = Math.max(a1.x() * ux + a1.y() * uy, a2.x() * ux + a2.y() * uy);
            for (int j = i + 1; j < edges.size(); j++) {
                RouteEdge b = edges.get(j);
                if (a.fromId().equals(b.toId()) && a.toId().equals(b.fromId())) continue;
                if (a.fromId().equals(b.fromId()) || a.fromId().equals(b.toId())
                        || a.toId().equals(b.fromId()) || a.toId().equals(b.toId())) continue;
                Vec2 b1 = graph.node(b.fromId()).position(), b2 = graph.node(b.toId()).position();
                double bdx = b2.x() - b1.x(), bdy = b2.y() - b1.y();
                double blen = Math.hypot(bdx, bdy);
                if (blen < minimumSectionLength || blen <= GeometryCollision.EPS) continue;
                double parallel = Math.abs((adx * bdy - ady * bdx) / (alen * blen));
                if (parallel > 0.02) continue;
                double bProjection1 = b1.x() * ux + b1.y() * uy;
                double bProjection2 = b2.x() * ux + b2.y() * uy;
                double overlap = Math.min(aMax, Math.max(bProjection1, bProjection2))
                        - Math.max(aMin, Math.min(bProjection1, bProjection2));
                if (overlap <= VALIDATION_EPS) continue;
                double midAx = (a1.x() + a2.x()) * 0.5;
                double midAy = (a1.y() + a2.y()) * 0.5;
                double midBx = (b1.x() + b2.x()) * 0.5;
                double midBy = (b1.y() + b2.y()) * 0.5;
                double spacing = Math.abs((midBx - midAx) * uy - (midBy - midAy) * ux);
                if (spacing > VALIDATION_EPS) minimum = Math.min(minimum, spacing);
            }
        }
        return Double.isFinite(minimum) ? minimum : 0.0;
    }

    private static int countWaitingOpportunities(LevelDefinition level, List<String> path) {
        if (path == null || path.size() < 2) return 0;
        List<Double> timedGateProgress = new ArrayList<Double>();
        for (Gate gate : level.gates()) {
            if (gate.mode() == GateMode.TIMED && gate.hasBarrier()) {
                timedGateProgress.add(projectProgress(level.routeGraph(), path,
                        midpoint(gate.barrierStart(), gate.barrierEnd())).progress);
            }
        }
        if (timedGateProgress.isEmpty()) return 0;
        int count = 0;
        for (Zone safe : level.safeZones()) {
            Projection projection = projectProgress(level.routeGraph(), path, safe.centroid());
            boolean nearRoute = projection.distanceSquared
                    <= square(safe.shape() == ZoneShape.CIRCLE ? safe.radius() + level.effectiveRadius()
                    : level.effectiveRadius() * 2.0);
            if (!nearRoute) continue;
            for (Double gateProgress : timedGateProgress) {
                if (projection.progress + VALIDATION_EPS < gateProgress) { count++; break; }
            }
        }
        return count;
    }

    private static final class Projection {
        private final double progress;
        private final double distanceSquared;
        private Projection(double progress, double distanceSquared) {
            this.progress = progress; this.distanceSquared = distanceSquared;
        }
    }

    private static Projection projectProgress(RouteGraph graph, List<String> path, Vec2 point) {
        double cumulative = 0.0;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestProgress = 0.0;
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec2 a = graph.node(path.get(i)).position(), b = graph.node(path.get(i + 1)).position();
            double dx = b.x() - a.x(), dy = b.y() - a.y();
            double lengthSquared = dx * dx + dy * dy;
            double length = Math.sqrt(lengthSquared);
            double t = lengthSquared <= GeometryCollision.EPS ? 0.0
                    : ((point.x() - a.x()) * dx + (point.y() - a.y()) * dy) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = a.x() + dx * t, py = a.y() + dy * t;
            double distanceSquared = square(point.x() - px) + square(point.y() - py);
            if (distanceSquared < bestDistance) {
                bestDistance = distanceSquared;
                bestProgress = cumulative + length * t;
            }
            cumulative += length;
        }
        return new Projection(bestProgress, bestDistance);
    }

    private static double shortestPathLength(RouteGraph graph, String start, String goal) {
        if (start == null || goal == null) return 0.0;
        Map<String, Double> distances = new HashMap<String, Double>();
        Set<String> visited = new HashSet<String>();
        for (RouteNode node : graph.nodes()) distances.put(node.id(), Double.POSITIVE_INFINITY);
        distances.put(start, 0.0);
        for (int step = 0; step < graph.nodes().size(); step++) {
            String current = null;
            double best = Double.POSITIVE_INFINITY;
            for (Map.Entry<String, Double> entry : distances.entrySet()) {
                if (!visited.contains(entry.getKey()) && entry.getValue() < best) {
                    current = entry.getKey(); best = entry.getValue();
                }
            }
            if (current == null) break;
            if (current.equals(goal)) return best;
            visited.add(current);
            for (RouteEdge edge : graph.edges()) {
                if (!edge.fromId().equals(current)) continue;
                double candidate = best + distance(graph.node(edge.fromId()).position(),
                        graph.node(edge.toId()).position());
                if (candidate < distances.get(edge.toId())) distances.put(edge.toId(), candidate);
            }
        }
        return 0.0;
    }

    private static final class SearchState {
        private final String nodeId;
        private final BigInteger mandatoryMask;
        private final BigInteger keyMask;
        private SearchState(String nodeId, BigInteger mandatoryMask, BigInteger keyMask) {
            this.nodeId = nodeId; this.mandatoryMask = mandatoryMask; this.keyMask = keyMask;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SearchState)) return false;
            SearchState that = (SearchState) other;
            return nodeId.equals(that.nodeId) && mandatoryMask.equals(that.mandatoryMask)
                    && keyMask.equals(that.keyMask);
        }
        @Override public int hashCode() {
            return 31 * (31 * nodeId.hashCode() + mandatoryMask.hashCode()) + keyMask.hashCode();
        }
    }

    private static final class QueueEntry {
        private final SearchState state;
        private final double distance;
        private QueueEntry(SearchState state, double distance) { this.state = state; this.distance = distance; }
    }

    private static final class SearchResult {
        private final boolean complete;
        private final double distance;
        private final List<String> nodePath;
        private SearchResult(boolean complete, double distance, List<String> nodePath) {
            this.complete = complete; this.distance = distance;
            this.nodePath = nodePath == null ? Collections.<String>emptyList() : nodePath;
        }
        private static SearchResult failed() { return new SearchResult(false, 0.0, Collections.<String>emptyList()); }
    }

    /** Dijkstra on (node, mandatory-objective mask, acquired-key mask), with finite key barriers. */
    private static SearchResult findCompletionPath(LevelDefinition level) {
        RouteGraph graph = level.routeGraph();
        String start = nearestNodeId(graph, level.startZone().centroid());
        String goal = nearestNodeId(graph, level.goalZone().centroid());
        if (start == null || goal == null) return SearchResult.failed();

        List<Objective> mandatory = new ArrayList<Objective>();
        Set<String> keyIds = new HashSet<String>();
        for (Objective objective : level.objectives()) {
            if (objective.type() == ObjectiveType.MANDATORY) mandatory.add(objective);
            if (objective.grantsKeyId() != null) keyIds.add(objective.grantsKeyId());
        }
        Map<String, Integer> mandatoryBits = new HashMap<String, Integer>();
        for (int i = 0; i < mandatory.size(); i++) mandatoryBits.put(mandatory.get(i).id(), i);
        List<String> sortedKeys = new ArrayList<String>(keyIds);
        Collections.sort(sortedKeys);
        Map<String, Integer> keyBits = new HashMap<String, Integer>();
        for (int i = 0; i < sortedKeys.size(); i++) keyBits.put(sortedKeys.get(i), i);
        BigInteger allMandatory = mandatory.isEmpty() ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(mandatory.size()).subtract(BigInteger.ONE);

        SearchState initial = collectAtPoint(level, graph.node(start).position(),
                BigInteger.ZERO, BigInteger.ZERO, mandatoryBits, keyBits);
        final Map<SearchState, Double> distances = new HashMap<SearchState, Double>();
        final Map<SearchState, SearchState> previous = new HashMap<SearchState, SearchState>();
        PriorityQueue<QueueEntry> queue = new PriorityQueue<QueueEntry>(16,
                new Comparator<QueueEntry>() {
                    @Override public int compare(QueueEntry a, QueueEntry b) {
                        return Double.compare(a.distance, b.distance);
                    }
                });
        distances.put(initial, 0.0); queue.add(new QueueEntry(initial, 0.0));
        int expanded = 0;
        while (!queue.isEmpty() && expanded < 100_000) {
            QueueEntry entry = queue.poll();
            Double known = distances.get(entry.state);
            if (known == null || entry.distance > known + VALIDATION_EPS) continue;
            expanded++;
            if (entry.state.nodeId.equals(goal) && entry.state.mandatoryMask.equals(allMandatory)) {
                List<String> path = new ArrayList<String>();
                SearchState cursor = entry.state;
                while (cursor != null) {
                    path.add(cursor.nodeId);
                    cursor = previous.get(cursor);
                }
                Collections.reverse(path);
                return new SearchResult(true, entry.distance, Collections.unmodifiableList(path));
            }
            for (RouteEdge edge : graph.edges()) {
                if (!edge.fromId().equals(entry.state.nodeId)) continue;
                Vec2 from = graph.node(edge.fromId()).position();
                Vec2 to = graph.node(edge.toId()).position();
                SearchState next = advanceAcrossEdge(level, from, to, entry.state,
                        mandatoryBits, keyBits);
                if (next == null) continue; // Key barrier encountered before its key was acquired.
                double candidate = entry.distance + distance(from, to);
                Double old = distances.get(next);
                if (old == null || candidate + VALIDATION_EPS < old) {
                    distances.put(next, candidate);
                    previous.put(next, entry.state);
                    queue.add(new QueueEntry(next, candidate));
                }
            }
        }
        return SearchResult.failed();
    }

    private static SearchState collectAtPoint(LevelDefinition level, Vec2 point,
                                              BigInteger mandatoryMask, BigInteger keyMask,
                                              Map<String, Integer> mandatoryBits,
                                              Map<String, Integer> keyBits) {
        BigInteger mandatory = mandatoryMask, keys = keyMask;
        for (Objective objective : level.objectives()) {
            if (!objective.zone().contains(point)) continue;
            if (objective.requiredKeyId() != null) {
                Integer requiredBit = keyBits.get(objective.requiredKeyId());
                if (requiredBit == null || !keys.testBit(requiredBit)) continue;
            }
            Integer mandatoryBit = mandatoryBits.get(objective.id());
            if (mandatoryBit != null) mandatory = mandatory.setBit(mandatoryBit);
            if (objective.grantsKeyId() != null) {
                Integer grantBit = keyBits.get(objective.grantsKeyId());
                if (grantBit != null) keys = keys.setBit(grantBit);
            }
        }
        return new SearchState(nearestNodeId(level.routeGraph(), point), mandatory, keys);
    }

    private static SearchState advanceAcrossEdge(LevelDefinition level, Vec2 from, Vec2 to,
                                                 SearchState current,
                                                 Map<String, Integer> mandatoryBits,
                                                 Map<String, Integer> keyBits) {
        List<EdgeEvent> events = new ArrayList<EdgeEvent>();
        for (Objective objective : level.objectives()) {
            double t = firstZoneEntryParameter(from, to, objective.zone());
            if (t >= 0.0) events.add(EdgeEvent.objective(t, objective));
        }
        for (Gate gate : level.gates()) {
            if (gate.mode() != GateMode.KEY_LOCKED || !gate.hasBarrier()) continue;
            double t = segmentIntersectionParameter(from, to, gate.barrierStart(), gate.barrierEnd());
            if (t >= 0.0) events.add(EdgeEvent.gate(t, gate));
        }
        Collections.sort(events, new Comparator<EdgeEvent>() {
            @Override public int compare(EdgeEvent a, EdgeEvent b) {
                int time = Double.compare(a.parameter, b.parameter);
                if (time != 0) return time;
                // At an exact tie, a barrier is evaluated before a pickup.
                if (a.gate != null && b.gate == null) return -1;
                if (a.gate == null && b.gate != null) return 1;
                String aId = a.objective != null ? a.objective.id() : a.gate.id();
                String bId = b.objective != null ? b.objective.id() : b.gate.id();
                return aId.compareTo(bId);
            }
        });
        BigInteger mandatory = current.mandatoryMask;
        BigInteger keys = current.keyMask;
        for (EdgeEvent event : events) {
            if (event.gate != null) {
                Integer requiredBit = keyBits.get(event.gate.requiredKeyId());
                if (requiredBit == null || !keys.testBit(requiredBit)) return null;
                continue;
            }
            Objective objective = event.objective;
            if (objective.requiredKeyId() != null) {
                Integer requiredBit = keyBits.get(objective.requiredKeyId());
                if (requiredBit == null || !keys.testBit(requiredBit)) continue;
            }
            Integer mandatoryBit = mandatoryBits.get(objective.id());
            if (mandatoryBit != null) mandatory = mandatory.setBit(mandatoryBit);
            if (objective.grantsKeyId() != null) {
                Integer grantBit = keyBits.get(objective.grantsKeyId());
                if (grantBit != null) keys = keys.setBit(grantBit);
            }
        }
        return new SearchState(nearestNodeId(level.routeGraph(), to), mandatory, keys);
    }

    private static final class EdgeEvent {
        private final double parameter;
        private final Objective objective;
        private final Gate gate;
        private EdgeEvent(double parameter, Objective objective, Gate gate) {
            this.parameter = parameter; this.objective = objective; this.gate = gate;
        }
        private static EdgeEvent objective(double parameter, Objective value) {
            return new EdgeEvent(parameter, value, null);
        }
        private static EdgeEvent gate(double parameter, Gate value) {
            return new EdgeEvent(parameter, null, value);
        }
    }

    private static boolean routeIntersectsZone(RouteGraph graph, Zone zone) {
        for (RouteEdge edge : graph.edges()) {
            Vec2 from = graph.node(edge.fromId()).position();
            Vec2 to = graph.node(edge.toId()).position();
            if (firstZoneEntryParameter(from, to, zone) >= 0.0) return true;
        }
        return false;
    }

    /** First marker-centre entry into a trigger zone along a straight graph edge. */
    private static double firstZoneEntryParameter(Vec2 from, Vec2 to, Zone zone) {
        if (zone.shape() == ZoneShape.CIRCLE) {
            double dx = to.x() - from.x(), dy = to.y() - from.y();
            double ox = from.x() - zone.cx(), oy = from.y() - zone.cy();
            double c = ox * ox + oy * oy - zone.radius() * zone.radius();
            if (c <= GeometryCollision.EPS) return 0.0;
            double a = dx * dx + dy * dy;
            if (a <= GeometryCollision.EPS) return -1.0;
            double b = 2.0 * (ox * dx + oy * dy);
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant < 0.0) return -1.0;
            double t = (-b - Math.sqrt(Math.max(0.0, discriminant))) / (2.0 * a);
            return t >= -VALIDATION_EPS && t <= 1.0 + VALIDATION_EPS
                    ? Math.max(0.0, Math.min(1.0, t)) : -1.0;
        }
        List<Vec2> polygon = zone.polygon();
        if (GeometryCollision.pointInPolygon(from, polygon) || pointOnPolygonBoundary(from, polygon)) return 0.0;
        double earliest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            double t = segmentIntersectionParameter(from, to,
                    polygon.get(i), polygon.get((i + 1) % polygon.size()));
            if (t >= 0.0) earliest = Math.min(earliest, t);
        }
        if (Double.isFinite(earliest)) return earliest;
        if (GeometryCollision.pointInPolygon(to, polygon) || pointOnPolygonBoundary(to, polygon)) return 1.0;
        return -1.0;
    }

    /** Parameter on AB for a proper finite-segment intersection; parallel overlap is not a crossing. */
    private static double segmentIntersectionParameter(Vec2 a, Vec2 b, Vec2 c, Vec2 d) {
        double rx = b.x() - a.x(), ry = b.y() - a.y();
        double sx = d.x() - c.x(), sy = d.y() - c.y();
        double denominator = rx * sy - ry * sx;
        if (Math.abs(denominator) <= VALIDATION_EPS) return -1.0;
        double qx = c.x() - a.x(), qy = c.y() - a.y();
        double t = (qx * sy - qy * sx) / denominator;
        double u = (qx * ry - qy * rx) / denominator;
        if (t < -VALIDATION_EPS || t > 1.0 + VALIDATION_EPS
                || u < -VALIDATION_EPS || u > 1.0 + VALIDATION_EPS) return -1.0;
        return Math.max(0.0, Math.min(1.0, t));
    }

    private static boolean edgeCrossesBarrier(Vec2 from, Vec2 to, Vec2 barrierStart, Vec2 barrierEnd) {
        return segmentIntersectionParameter(from, to, barrierStart, barrierEnd) >= 0.0;
    }

    private static void validateRouteTopology(RouteGraph graph, Map<String, RouteNode> nodes,
                                              List<String> problems) {
        List<RouteEdge> edges = graph.edges();
        for (int i = 0; i < edges.size(); i++) {
            RouteEdge a = edges.get(i);
            Vec2 a1 = nodes.get(a.fromId()).position(), a2 = nodes.get(a.toId()).position();
            for (int j = i + 1; j < edges.size(); j++) {
                RouteEdge b = edges.get(j);
                Vec2 b1 = nodes.get(b.fromId()).position(), b2 = nodes.get(b.toId()).position();
                boolean sameUndirected = (a.fromId().equals(b.toId()) && a.toId().equals(b.fromId()))
                        || (a.fromId().equals(b.fromId()) && a.toId().equals(b.toId()));
                if (sameUndirected) continue; // explicit reversible traversal of one segment
                boolean sharedEndpoint = a.fromId().equals(b.fromId()) || a.fromId().equals(b.toId())
                        || a.toId().equals(b.fromId()) || a.toId().equals(b.toId());
                double separation = GeometryCollision.segmentDistanceSquared(a1, a2, b1, b2);
                if (separation > VALIDATION_EPS * VALIDATION_EPS) continue;
                if (!sharedEndpoint) {
                    problems.add("route edges cross/touch without a shared graph node: "
                            + a.fromId() + "->" + a.toId() + " / " + b.fromId() + "->" + b.toId());
                    continue;
                }
                Vec2 common, otherA, otherB;
                if (a.fromId().equals(b.fromId())) {
                    common = nodes.get(a.fromId()).position();
                    otherA = a2; otherB = b2;
                } else if (a.fromId().equals(b.toId())) {
                    common = nodes.get(a.fromId()).position();
                    otherA = a2; otherB = b1;
                } else if (a.toId().equals(b.fromId())) {
                    common = nodes.get(a.toId()).position();
                    otherA = a1; otherB = b2;
                } else {
                    common = nodes.get(a.toId()).position();
                    otherA = a1; otherB = b1;
                }
                double ax = otherA.x() - common.x(), ay = otherA.y() - common.y();
                double bx = otherB.x() - common.x(), by = otherB.y() - common.y();
                double cross = ax * by - ay * bx;
                double dot = ax * bx + ay * by;
                if (Math.abs(cross) <= VALIDATION_EPS && dot > VALIDATION_EPS) {
                    problems.add("collinear route edges overlap beyond their shared node: "
                            + a.fromId() + "->" + a.toId() + " / " + b.fromId() + "->" + b.toId());
                }
            }
        }
    }

    /** Width of the region section normal to the segment at its midpoint. */
    private static double measureCrossSectionWidth(Region region, Vec2 start, Vec2 end) {
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double length = Math.hypot(dx, dy);
        if (length <= GeometryCollision.EPS) return Double.NaN;
        double nx = -dy / length, ny = dx / length;
        Vec2 center = midpoint(start, end);
        double nearestPositive = Double.POSITIVE_INFINITY;
        double nearestNegative = Double.NEGATIVE_INFINITY;
        for (BoundarySegment boundary : region.boundarySegments()) {
            Vec2 a = boundary.start(), b = boundary.end();
            double sx = b.x() - a.x(), sy = b.y() - a.y();
            double denominator = nx * sy - ny * sx;
            if (Math.abs(denominator) <= VALIDATION_EPS) continue;
            double qx = a.x() - center.x(), qy = a.y() - center.y();
            double t = (qx * sy - qy * sx) / denominator;
            double u = (qx * ny - qy * nx) / denominator;
            if (u < -VALIDATION_EPS || u > 1.0 + VALIDATION_EPS) continue;
            if (t > VALIDATION_EPS) nearestPositive = Math.min(nearestPositive, t);
            else if (t < -VALIDATION_EPS) nearestNegative = Math.max(nearestNegative, t);
        }
        if (!Double.isFinite(nearestPositive) || !Double.isFinite(nearestNegative)) return Double.NaN;
        return nearestPositive - nearestNegative;
    }

    private static String computeCatalogHash(List<LevelDefinition> levels) {
        StringBuilder canonical = new StringBuilder("NMZ-CATALOG-v1\n");
        for (LevelDefinition level : levels) {
            token(canonical, level.levelId()); number(canonical, level.levelRevision());
            token(canonical, level.geometryHash());
        }
        return sha256Hex(canonical.toString());
    }

    private static void token(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append(safe.length()).append(':').append(safe).append('\n');
    }
    private static void number(StringBuilder out, double value) { token(out, Double.toString(value)); }
    private static void number(StringBuilder out, long value) { token(out, Long.toString(value)); }
    private static String sha256Hex(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray();
            char[] output = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int b = bytes[i] & 0xff;
                output[i * 2] = digits[b >>> 4]; output[i * 2 + 1] = digits[b & 0x0f];
            }
            return new String(output);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<Vec2> immutablePoints(List<Vec2> values) {
        List<Vec2> copy = new ArrayList<Vec2>();
        for (Vec2 value : values) copy.add(Objects.requireNonNull(value, "polygon point"));
        return Collections.unmodifiableList(copy);
    }
    private static <T> List<T> immutableCopy(List<T> values, String name) {
        if (values == null) throw new NullPointerException(name);
        List<T> copy = new ArrayList<T>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " item"));
        return Collections.unmodifiableList(copy);
    }
    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? Collections.<T>emptyList() : values;
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String nearestNodeId(RouteGraph graph, Vec2 target) {
        String nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (RouteNode node : graph.nodes()) {
            double distance = distanceSquared(node.position(), target);
            if (distance < best) { best = distance; nearest = node.id(); }
        }
        return nearest;
    }
    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        if (!value.equals(value.trim())) throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
        return value;
    }
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
    private static double square(double value) { return value * value; }
    private static double distanceSquared(Vec2 a, Vec2 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y();
        return dx * dx + dy * dy;
    }
    private static double distance(Vec2 a, Vec2 b) { return Math.sqrt(distanceSquared(a, b)); }
    private static Vec2 midpoint(Vec2 a, Vec2 b) {
        return new Vec2(a.x() + (b.x() - a.x()) * 0.5, a.y() + (b.y() - a.y()) * 0.5);
    }
    private static boolean pointOnPolygonBoundary(Vec2 point, List<Vec2> polygon) {
        for (int i = 0; i < polygon.size(); i++) {
            if (GeometryCollision.pointSegmentDistanceSquared(point, polygon.get(i),
                    polygon.get((i + 1) % polygon.size())) <= GeometryCollision.EPS) return true;
        }
        return false;
    }
}
