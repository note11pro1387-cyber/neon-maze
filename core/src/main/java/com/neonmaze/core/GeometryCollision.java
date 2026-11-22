package com.neonmaze.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java geometry and continuous collision primitives for NEON MAZE.
 *
 * <p>All inputs use canonical World Space. Geometry is deliberately limited to
 * finite coordinates in {@link #MAX_ABS_WORLD_COORDINATE}; with that envelope,
 * the squared-distance operations used by the game remain comfortably inside
 * the finite range of {@code double}.</p>
 *
 * <p>The player is represented by a disk whose radius is supplied by the
 * caller as {@code playerRadius + safetyMargin}. Exact tangency is a collision.
 * Numerical tolerances are dimensioned and scale-aware; they are never used as
 * a substitute for the gameplay safety margin.</p>
 *
 * <p>The hot sweep path scans a prebuilt boundary array and uses primitive
 * arithmetic only. It does not allocate a collection, point, vector, stream,
 * optional, or temporary array per sample. {@link Region#queryBoundaries(Aabb)}
 * remains as a compatibility/cold-path convenience and intentionally returns
 * a newly allocated list.</p>
 *
 * <p>No Android or third-party geometry dependency is used.</p>
 */
public final class GeometryCollision {

    /**
     * Legacy tolerance retained for callers in LevelSystem. New geometry code
     * uses dimension-specific helpers rather than treating this as universal.
     */
    public static final double EPS = 1e-9;

    /** Maximum absolute coordinate accepted for canonical World Space data. */
    public static final double MAX_ABS_WORLD_COORDINATE = 1_000_000.0;

    private static final double MIN_LINEAR_TOLERANCE = 1e-9;
    private static final double TIME_TOLERANCE = 1e-12;
    private static final double ORIENTATION_ERROR_FACTOR = 8.0;
    private static final double AREA_ERROR_FACTOR = 32.0;

    private GeometryCollision() {
        // Utility class.
    }

    // ---------------------------------------------------------------------
    // Primitive value types
    // ---------------------------------------------------------------------

    /** Immutable 2D point/vector. Accessor names preserve the former record API. */
    public static final class Vec2 {
        private final double x;
        private final double y;

        public Vec2(double x, double y) {
            requireFinite(x, "Vec2.x");
            requireFinite(y, "Vec2.y");
            this.x = x;
            this.y = y;
        }

        public double x() { return x; }
        public double y() { return y; }

        public Vec2 add(Vec2 other) {
            requireNonNull(other, "other");
            return new Vec2(x + other.x, y + other.y);
        }

        public Vec2 sub(Vec2 other) {
            requireNonNull(other, "other");
            return new Vec2(x - other.x, y - other.y);
        }

        public Vec2 scale(double scalar) {
            requireFinite(scalar, "scalar");
            return new Vec2(x * scalar, y * scalar);
        }

        public double dot(Vec2 other) {
            requireNonNull(other, "other");
            return checkedFinite(x * other.x + y * other.y, "dot product");
        }

        public double cross(Vec2 other) {
            requireNonNull(other, "other");
            return checkedFinite(x * other.y - y * other.x, "cross product");
        }

        public double lengthSq() {
            return checkedFinite(x * x + y * y, "vector length squared");
        }

        public double distanceSq(Vec2 other) {
            requireNonNull(other, "other");
            double dx = x - other.x;
            double dy = y - other.y;
            return checkedFinite(dx * dx + dy * dy, "distance squared");
        }

        public static Vec2 lerp(Vec2 a, Vec2 b, double t) {
            requireNonNull(a, "a");
            requireNonNull(b, "b");
            requireFinite(t, "t");
            return new Vec2(a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Vec2)) return false;
            Vec2 that = (Vec2) other;
            return Double.compare(x, that.x) == 0
                    && Double.compare(y, that.y) == 0;
        }

        @Override
        public int hashCode() {
            int result = Double.hashCode(x);
            result = 31 * result + Double.hashCode(y);
            return result;
        }

        @Override
        public String toString() {
            return "Vec2[x=" + x + ", y=" + y + "]";
        }
    }

    /** Immutable axis-aligned bounding box in World Space. */
    public static final class Aabb {
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;

        public Aabb(double minX, double minY, double maxX, double maxY) {
            requireFinite(minX, "Aabb.minX");
            requireFinite(minY, "Aabb.minY");
            requireFinite(maxX, "Aabb.maxX");
            requireFinite(maxY, "Aabb.maxY");
            if (minX > maxX || minY > maxY) {
                throw new IllegalArgumentException("AABB minimum must not exceed maximum");
            }
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        public double minX() { return minX; }
        public double minY() { return minY; }
        public double maxX() { return maxX; }
        public double maxY() { return maxY; }

        public static Aabb fromSegment(Vec2 a, Vec2 b) {
            requireNonNull(a, "a");
            requireNonNull(b, "b");
            return new Aabb(Math.min(a.x, b.x), Math.min(a.y, b.y),
                    Math.max(a.x, b.x), Math.max(a.y, b.y));
        }

        public Aabb expanded(double radius) {
            requireFinite(radius, "radius");
            if (radius < 0.0) {
                throw new IllegalArgumentException("AABB expansion radius must be >= 0");
            }
            return new Aabb(minX - radius, minY - radius,
                    maxX + radius, maxY + radius);
        }

        public boolean intersects(Aabb other) {
            requireNonNull(other, "other");
            return intersects(other.minX, other.minY, other.maxX, other.maxY);
        }

        private boolean intersects(double otherMinX, double otherMinY,
                                   double otherMaxX, double otherMaxY) {
            return !(maxX < otherMinX || otherMaxX < minX
                    || maxY < otherMinY || otherMaxY < minY);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Aabb)) return false;
            Aabb that = (Aabb) other;
            return Double.compare(minX, that.minX) == 0
                    && Double.compare(minY, that.minY) == 0
                    && Double.compare(maxX, that.maxX) == 0
                    && Double.compare(maxY, that.maxY) == 0;
        }

        @Override
        public int hashCode() {
            int result = Double.hashCode(minX);
            result = 31 * result + Double.hashCode(minY);
            result = 31 * result + Double.hashCode(maxX);
            result = 31 * result + Double.hashCode(maxY);
            return result;
        }

        @Override
        public String toString() {
            return "Aabb[minX=" + minX + ", minY=" + minY
                    + ", maxX=" + maxX + ", maxY=" + maxY + "]";
        }
    }

    /**
     * Immutable boundary edge. The cached AABB and free-space normal are
     * prepared when a Region is constructed, not during a sweep.
     */
    public static final class BoundarySegment {
        private final Vec2 start;
        private final Vec2 end;
        private final Aabb bounds;
        private final double freeNormalX;
        private final double freeNormalY;
        private final int index;

        /** Compatibility constructor for an independently created edge. */
        public BoundarySegment(Vec2 start, Vec2 end) {
            this(start, end, calculateLeftNormalX(start, end),
                    calculateLeftNormalY(start, end), -1);
        }

        private BoundarySegment(Vec2 start, Vec2 end,
                                double freeNormalX, double freeNormalY, int index) {
            requireNonNull(start, "start");
            requireNonNull(end, "end");
            requireWorldPoint(start, "boundary start");
            requireWorldPoint(end, "boundary end");
            double dx = end.x - start.x;
            double dy = end.y - start.y;
            if (dx == 0.0 && dy == 0.0) {
                throw new IllegalArgumentException("Boundary segment must have non-zero length");
            }
            this.start = start;
            this.end = end;
            this.bounds = Aabb.fromSegment(start, end);
            this.freeNormalX = freeNormalX;
            this.freeNormalY = freeNormalY;
            this.index = index;
        }

        public Vec2 start() { return start; }
        public Vec2 end() { return end; }
        public Aabb aabb() { return bounds; }

        private static double calculateLeftNormalX(Vec2 a, Vec2 b) {
            requireNonNull(a, "start");
            requireNonNull(b, "end");
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double length = Math.hypot(dx, dy);
            if (!(length > 0.0) || !Double.isFinite(length)) {
                throw new IllegalArgumentException("Boundary segment must have finite non-zero length");
            }
            return -dy / length;
        }

        private static double calculateLeftNormalY(Vec2 a, Vec2 b) {
            requireNonNull(a, "start");
            requireNonNull(b, "end");
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double length = Math.hypot(dx, dy);
            if (!(length > 0.0) || !Double.isFinite(length)) {
                throw new IllegalArgumentException("Boundary segment must have finite non-zero length");
            }
            return dx / length;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BoundarySegment)) return false;
            BoundarySegment that = (BoundarySegment) other;
            return start.equals(that.start) && end.equals(that.end);
        }

        @Override
        public int hashCode() {
            return 31 * start.hashCode() + end.hashCode();
        }

        @Override
        public String toString() {
            return "BoundarySegment[start=" + start + ", end=" + end + "]";
        }
    }

    /** Outcome of the canonical swept-disk query. */
    public enum SweepStatus {
        /** No boundary contact was found and the destination remains walkable. */
        CLEAR,
        /** A boundary contact or conservative fail-closed breach was found. */
        HIT,
        /** One or more arguments were invalid; this must never be treated as safe. */
        INVALID_INPUT
    }

    /**
     * Caller-owned mutable sweep output. Reuse one instance per Engine thread;
     * it is intentionally not thread-safe and allocates nothing when populated.
     */
    public static final class Contact {
        private boolean populated;
        private double t;
        private double centerX;
        private double centerY;
        private double boundaryX;
        private double boundaryY;
        private double normalX;
        private double normalY;
        private int boundaryIndex;

        public Contact() {
            reset();
        }

        public void reset() {
            populated = false;
            t = 1.0;
            centerX = 0.0;
            centerY = 0.0;
            boundaryX = 0.0;
            boundaryY = 0.0;
            normalX = 0.0;
            normalY = 0.0;
            boundaryIndex = -1;
        }

        public boolean hasContact() { return populated; }
        public double t() { return t; }
        public double centerX() { return centerX; }
        public double centerY() { return centerY; }
        public double boundaryX() { return boundaryX; }
        public double boundaryY() { return boundaryY; }
        public double normalX() { return normalX; }
        public double normalY() { return normalY; }
        public int boundaryIndex() { return boundaryIndex; }

        private void set(double t, double centerX, double centerY,
                         double boundaryX, double boundaryY,
                         double normalX, double normalY, int boundaryIndex) {
            this.populated = true;
            this.t = t;
            this.centerX = centerX;
            this.centerY = centerY;
            this.boundaryX = boundaryX;
            this.boundaryY = boundaryY;
            this.normalX = normalX;
            this.normalY = normalY;
            this.boundaryIndex = boundaryIndex;
        }
    }

    // ---------------------------------------------------------------------
    // Canonical walkable Region
    // ---------------------------------------------------------------------

    /**
     * Immutable walkable region: the strict interior of Outer minus the
     * interiors of disjoint, strictly contained Hole polygons.
     */
    public static final class Region {
        private final List<Vec2> outer;
        private final List<List<Vec2>> holes;
        private final List<BoundarySegment> boundarySegments;
        private final BoundarySegment[] boundaryArray;

        public Region(List<Vec2> outer, List<List<Vec2>> holes) {
            if (outer == null) {
                throw new IllegalArgumentException("outer polygon must not be null");
            }
            validatePolygon(outer, "outer");

            List<List<Vec2>> inputHoles = holes == null
                    ? Collections.<List<Vec2>>emptyList() : holes;
            for (int i = 0; i < inputHoles.size(); i++) {
                validatePolygon(inputHoles.get(i), "hole[" + i + "]");
                requireHoleInsideOuter(inputHoles.get(i), outer, i);
                for (int j = 0; j < i; j++) {
                    requireHolesDisjoint(inputHoles.get(j), inputHoles.get(i), j, i);
                }
            }

            this.outer = immutableCopy(outer);
            ArrayList<List<Vec2>> holeCopies = new ArrayList<>(inputHoles.size());
            for (List<Vec2> hole : inputHoles) {
                holeCopies.add(immutableCopy(hole));
            }
            this.holes = Collections.unmodifiableList(holeCopies);

            ArrayList<BoundarySegment> segments = new ArrayList<>();
            appendEdges(segments, this.outer, false, 0);
            int nextIndex = this.outer.size();
            for (List<Vec2> hole : this.holes) {
                appendEdges(segments, hole, true, nextIndex);
                nextIndex += hole.size();
            }
            this.boundarySegments = Collections.unmodifiableList(segments);
            this.boundaryArray = segments.toArray(new BoundarySegment[0]);
        }

        public List<Vec2> outer() { return outer; }
        public List<List<Vec2>> holes() { return holes; }
        public List<BoundarySegment> boundarySegments() { return boundarySegments; }

        /**
         * True only when the complete effective-radius disk lies strictly
         * within the walkable area. Invalid points/radii fail closed.
         */
        public boolean containsCenter(Vec2 point, double effectiveRadius) {
            if (point == null || !isWorldPoint(point)
                    || !isValidRadius(effectiveRadius)) {
                return false;
            }
            if (!containsCenterUnchecked(this, point.x, point.y, effectiveRadius)) {
                return false;
            }
            return true;
        }

        /**
         * Cold-path compatibility query. This method allocates a filtered list;
         * do not call it from Engine sample processing. Sweeps use the cached
         * internal edge array directly.
         */
        public List<BoundarySegment> queryBoundaries(Aabb bounds) {
            requireNonNull(bounds, "bounds");
            ArrayList<BoundarySegment> result = new ArrayList<>();
            for (BoundarySegment edge : boundaryArray) {
                if (edge.bounds.intersects(bounds.minX, bounds.minY,
                        bounds.maxX, bounds.maxY)) {
                    result.add(edge);
                }
            }
            return result;
        }

        private void appendEdges(List<BoundarySegment> output, List<Vec2> polygon,
                                 boolean hole, int firstIndex) {
            double area = signedArea(polygon);
            boolean areaPositive = area > 0.0;
            int count = polygon.size();
            for (int i = 0; i < count; i++) {
                Vec2 a = polygon.get(i);
                Vec2 b = polygon.get((i + 1) % count);
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double length = Math.hypot(dx, dy);
                boolean freeSideIsLeft = hole ? !areaPositive : areaPositive;
                double nx = freeSideIsLeft ? -dy / length : dy / length;
                double ny = freeSideIsLeft ? dx / length : -dx / length;
                output.add(new BoundarySegment(a, b, nx, ny, firstIndex + i));
            }
        }
    }

    /*
     * Shared primitive implementation: deliberately accepts scalar coordinates
     * so the Engine's sample-to-sample collision check creates no Vec2 objects.
     */
    private static boolean containsCenterUnchecked(Region region,
                                                   double px, double py,
                                                   double effectiveRadius) {
        if (!pointInPolygonCoordinates(px, py, region.outer)) return false;
        for (int i = 0; i < region.holes.size(); i++) {
            if (pointInPolygonCoordinates(px, py, region.holes.get(i))) return false;
        }
        for (BoundarySegment edge : region.boundaryArray) {
            double distanceSquared = pointSegmentDistanceSquaredUnchecked(
                    px, py, edge.start.x, edge.start.y,
                    edge.end.x, edge.end.y);
            double tolerance = coordinateTolerance(px, py,
                    edge.start.x, edge.start.y, edge.end.x, edge.end.y);
            double allowed = effectiveRadius + tolerance;
            if (distanceSquared <= allowed * allowed) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Point-in-polygon and distance queries
    // ---------------------------------------------------------------------

    /**
     * Even-odd point-in-simple-polygon test. Boundary points are classified as
     * outside; callers requiring a disk clearance must also use Region.
     * Invalid input returns false.
     */
    public static boolean pointInPolygon(Vec2 point, List<Vec2> polygon) {
        if (point == null || !isWorldPoint(point)) return false;
        return pointInPolygonCoordinates(point.x, point.y, polygon);
    }

    private static boolean pointInPolygonCoordinates(double px, double py,
                                                     List<Vec2> polygon) {
        if (polygon == null || polygon.size() < 3 || !isWorldPoint(px, py)) {
            return false;
        }
        int count = polygon.size();
        boolean inside = false;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            Vec2 a = polygon.get(j);
            Vec2 b = polygon.get(i);
            if (!isWorldPoint(a) || !isWorldPoint(b)) return false;
            if (pointOnSegment(px, py, a.x, a.y, b.x, b.y)) return false;
            boolean straddles = (a.y > py) != (b.y > py);
            if (straddles) {
                double xAtRay = a.x + (py - a.y) * (b.x - a.x) / (b.y - a.y);
                if (xAtRay > px) inside = !inside;
            }
        }
        return inside;
    }

    /** Squared distance from a point to a finite segment. */
    public static double pointSegmentDistanceSquared(Vec2 point, Vec2 a, Vec2 b) {
        requireWorldPoint(point, "point");
        requireWorldPoint(a, "a");
        requireWorldPoint(b, "b");
        return pointSegmentDistanceSquaredUnchecked(point.x, point.y,
                a.x, a.y, b.x, b.y);
    }

    /**
     * Squared minimum distance between two finite segments. In 2D, if the
     * segments do not intersect, a closest pair contains an endpoint of at
     * least one segment; four point-to-segment candidates are sufficient.
     */
    public static double segmentDistanceSquared(Vec2 p1, Vec2 q1,
                                               Vec2 p2, Vec2 q2) {
        requireWorldPoint(p1, "p1");
        requireWorldPoint(q1, "q1");
        requireWorldPoint(p2, "p2");
        requireWorldPoint(q2, "q2");
        if (segmentsIntersect(p1.x, p1.y, q1.x, q1.y,
                p2.x, p2.y, q2.x, q2.y)) {
            return 0.0;
        }
        double d1 = pointSegmentDistanceSquaredUnchecked(
                p1.x, p1.y, p2.x, p2.y, q2.x, q2.y);
        double d2 = pointSegmentDistanceSquaredUnchecked(
                q1.x, q1.y, p2.x, p2.y, q2.x, q2.y);
        double d3 = pointSegmentDistanceSquaredUnchecked(
                p2.x, p2.y, p1.x, p1.y, q1.x, q1.y);
        double d4 = pointSegmentDistanceSquaredUnchecked(
                q2.x, q2.y, p1.x, p1.y, q1.x, q1.y);
        return Math.min(Math.min(d1, d2), Math.min(d3, d4));
    }

    // ---------------------------------------------------------------------
    // Canonical swept disk API
    // ---------------------------------------------------------------------

    /**
     * Sweeps a disk over the complete movement segment.
     *
     * <p>The caller supplies a reusable {@link Contact} output. A hit reports
     * the earliest contact parameter, centre, closest boundary point, a
     * free-space-facing normal, and a stable boundary index. Ties within
     * {@link #TIME_TOLERANCE} choose the lower canonical boundary index.</p>
     *
     * <p>Invalid input is reported separately from a geometric hit and is
     * never interpreted as clear. The output is reset for CLEAR and
     * INVALID_INPUT.</p>
     */
    public static SweepStatus sweepSegment(Region region, Vec2 from, Vec2 to,
                                           double effectiveRadius, Contact out) {
        if (out == null) return SweepStatus.INVALID_INPUT;
        out.reset();
        if (region == null || from == null || to == null
                || !isWorldPoint(from) || !isWorldPoint(to)
                || !isValidRadius(effectiveRadius)) {
            return SweepStatus.INVALID_INPUT;
        }
        return sweepInternal(region, from.x, from.y, to.x, to.y,
                effectiveRadius, out);
    }

    /**
     * Compatibility predicate used by the current GameEngine. Invalid input
     * fails closed (false); the canonical status API is available when the
     * caller needs to distinguish INVALID_INPUT from HIT.
     */
    public static boolean isSweepSafe(Region region, Vec2 from, Vec2 to,
                                      double effectiveRadius) {
        if (region == null || from == null || to == null
                || !isWorldPoint(from) || !isWorldPoint(to)
                || !isValidRadius(effectiveRadius)) {
            return false;
        }
        return sweepInternal(region, from.x, from.y, to.x, to.y,
                effectiveRadius, null) == SweepStatus.CLEAR;
    }

    /**
     * Earliest normalized contact time. Returns 1.0 when fully clear. For
     * compatibility with the historic scalar API, invalid input maps to 0.0
     * (the conservative start of the segment); use sweepSegment() to inspect
     * INVALID_INPUT explicitly.
     */
    public static double firstContactTime(Region region, Vec2 from, Vec2 to,
                                          double effectiveRadius) {
        if (region == null || from == null || to == null
                || !isWorldPoint(from) || !isWorldPoint(to)
                || !isValidRadius(effectiveRadius)) {
            return 0.0;
        }
        Contact scratch = new Contact();
        SweepStatus status = sweepInternal(region, from.x, from.y,
                to.x, to.y, effectiveRadius, scratch);
        if (status == SweepStatus.CLEAR) return 1.0;
        if (status == SweepStatus.INVALID_INPUT) return 0.0;
        // Reserve exactly 1.0 for a fully clear sweep, matching the legacy
        // scalar contract. A contact at the final endpoint is represented by
        // the immediately preceding double (the status API keeps exact HIT).
        return scratch.t() >= 1.0 ? Math.nextAfter(1.0, 0.0) : scratch.t();
    }

    private static SweepStatus sweepInternal(Region region,
                                             double fromX, double fromY,
                                             double toX, double toY,
                                             double effectiveRadius,
                                             Contact output) {
        if (!isWorldPoint(fromX, fromY) || !isWorldPoint(toX, toY)
                || !isValidRadius(effectiveRadius)) {
            return SweepStatus.INVALID_INPUT;
        }

        if (!containsCenterUnchecked(region, fromX, fromY, effectiveRadius)) {
            if (output != null) setNearestBoundaryContact(
                    region, fromX, fromY, 0.0, output, true);
            return SweepStatus.HIT;
        }

        double moveX = toX - fromX;
        double moveY = toY - fromY;
        double bestT = Double.POSITIVE_INFINITY;
        BoundarySegment bestEdge = null;
        double bestCenterX = 0.0;
        double bestCenterY = 0.0;
        double bestBoundaryX = 0.0;
        double bestBoundaryY = 0.0;
        double bestNormalX = 0.0;
        double bestNormalY = 0.0;

        double maxCoordinate = maxAbs(fromX, fromY, toX, toY);
        double broadTolerance = Math.max(MIN_LINEAR_TOLERANCE,
                Math.ulp(Math.max(1.0, maxCoordinate)) * 8.0);
        double expandedRadius = effectiveRadius + broadTolerance;
        double sweepMinX = Math.min(fromX, toX) - expandedRadius;
        double sweepMinY = Math.min(fromY, toY) - expandedRadius;
        double sweepMaxX = Math.max(fromX, toX) + expandedRadius;
        double sweepMaxY = Math.max(fromY, toY) + expandedRadius;

        for (BoundarySegment edge : region.boundaryArray) {
            if (!edge.bounds.intersects(sweepMinX, sweepMinY,
                    sweepMaxX, sweepMaxY)) {
                continue;
            }
            double edgeTolerance = coordinateTolerance(fromX, fromY,
                    toX, toY, edge.start.x, edge.start.y,
                    edge.end.x, edge.end.y);
            double radius = effectiveRadius + edgeTolerance;
            double candidateT = firstPointCapsuleContact(
                    fromX, fromY, moveX, moveY,
                    edge.start.x, edge.start.y,
                    edge.end.x, edge.end.y, radius);
            if (candidateT < 0.0 || candidateT > 1.0 + TIME_TOLERANCE) {
                continue;
            }
            candidateT = clamp(candidateT, 0.0, 1.0);
            if (bestEdge != null
                    && candidateT > bestT - TIME_TOLERANCE) {
                // Edges are visited in canonical index order. Keeping the
                // earlier edge in a tolerance tie implements the tie-break.
                continue;
            }

            double centerX = fromX + moveX * candidateT;
            double centerY = fromY + moveY * candidateT;
            double edgeX = edge.end.x - edge.start.x;
            double edgeY = edge.end.y - edge.start.y;
            double lengthSquared = edgeX * edgeX + edgeY * edgeY;
            double projection = ((centerX - edge.start.x) * edgeX
                    + (centerY - edge.start.y) * edgeY) / lengthSquared;
            projection = clamp(projection, 0.0, 1.0);
            double boundaryX = edge.start.x + projection * edgeX;
            double boundaryY = edge.start.y + projection * edgeY;
            double normalX = centerX - boundaryX;
            double normalY = centerY - boundaryY;
            double normalLength = Math.hypot(normalX, normalY);
            if (normalLength > edgeTolerance) {
                normalX /= normalLength;
                normalY /= normalLength;
                if (normalX * edge.freeNormalX
                        + normalY * edge.freeNormalY < -TIME_TOLERANCE) {
                    normalX = edge.freeNormalX;
                    normalY = edge.freeNormalY;
                }
            } else {
                normalX = edge.freeNormalX;
                normalY = edge.freeNormalY;
            }

            bestT = candidateT;
            bestEdge = edge;
            bestCenterX = centerX;
            bestCenterY = centerY;
            bestBoundaryX = boundaryX;
            bestBoundaryY = boundaryY;
            bestNormalX = normalX;
            bestNormalY = normalY;
        }

        if (bestEdge != null) {
            if (output != null) {
                output.set(bestT, bestCenterX, bestCenterY,
                        bestBoundaryX, bestBoundaryY,
                        bestNormalX, bestNormalY, bestEdge.index);
            }
            return SweepStatus.HIT;
        }

        // A valid start and invalid end must imply a boundary crossing. If
        // rounding or malformed upstream state evades the analytic candidates,
        // fail closed at t=1 rather than returning a false CLEAR.
        if (!containsCenterUnchecked(region, toX, toY, effectiveRadius)) {
            if (output != null) setNearestBoundaryContact(
                    region, toX, toY, 1.0, output, false);
            return SweepStatus.HIT;
        }
        return SweepStatus.CLEAR;
    }

    private static void setNearestBoundaryContact(Region region,
                                                  double centerX, double centerY,
                                                  double t, Contact output,
                                                  boolean useRegionNormal) {
        BoundarySegment nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        double nearestX = 0.0;
        double nearestY = 0.0;
        for (BoundarySegment edge : region.boundaryArray) {
            double distanceSquared = pointSegmentDistanceSquaredUnchecked(
                    centerX, centerY, edge.start.x, edge.start.y,
                    edge.end.x, edge.end.y);
            if (distanceSquared < nearestDistance) {
                double dx = edge.end.x - edge.start.x;
                double dy = edge.end.y - edge.start.y;
                double lengthSquared = dx * dx + dy * dy;
                double projection = ((centerX - edge.start.x) * dx
                        + (centerY - edge.start.y) * dy) / lengthSquared;
                projection = clamp(projection, 0.0, 1.0);
                nearestX = edge.start.x + projection * dx;
                nearestY = edge.start.y + projection * dy;
                nearestDistance = distanceSquared;
                nearest = edge;
            }
        }
        if (nearest == null) {
            output.reset();
            return;
        }
        double normalX = nearest.freeNormalX;
        double normalY = nearest.freeNormalY;
        if (!useRegionNormal && nearestDistance > 0.0) {
            double dx = centerX - nearestX;
            double dy = centerY - nearestY;
            double length = Math.hypot(dx, dy);
            if (length > 0.0) {
                double candidateX = dx / length;
                double candidateY = dy / length;
                if (candidateX * normalX + candidateY * normalY > 0.0) {
                    normalX = candidateX;
                    normalY = candidateY;
                }
            }
        }
        output.set(t, centerX, centerY, nearestX, nearestY,
                normalX, normalY, nearest.index);
    }

    /**
     * Earliest point-vs-capsule entry. The capsule is the union of the edge's
     * finite strip and its two circular caps. Returns -1 when no contact exists.
     */
    private static double firstPointCapsuleContact(double pointX, double pointY,
                                                   double moveX, double moveY,
                                                   double ax, double ay,
                                                   double bx, double by,
                                                   double radius) {
        double edgeX = bx - ax;
        double edgeY = by - ay;
        double edgeLength = Math.hypot(edgeX, edgeY);
        if (!(edgeLength > 0.0) || !Double.isFinite(edgeLength)) return -1.0;

        double radiusSquared = radius * radius;
        double startDistanceSquared = pointSegmentDistanceSquaredUnchecked(
                pointX, pointY, ax, ay, bx, by);
        if (startDistanceSquared <= radiusSquared) return 0.0;

        double best = Double.POSITIVE_INFINITY;
        double ux = edgeX / edgeLength;
        double uy = edgeY / edgeLength;
        double nx = -uy;
        double ny = ux;
        double relativeX = pointX - ax;
        double relativeY = pointY - ay;
        double alongStart = relativeX * ux + relativeY * uy;
        double acrossStart = relativeX * nx + relativeY * ny;
        double alongVelocity = moveX * ux + moveY * uy;
        double acrossVelocity = moveX * nx + moveY * ny;

        if (acrossVelocity != 0.0) {
            double tPositive = (radius - acrossStart) / acrossVelocity;
            if (isUnitTime(tPositive)) {
                double along = alongStart + alongVelocity * tPositive;
                if (along >= -MIN_LINEAR_TOLERANCE
                        && along <= edgeLength + MIN_LINEAR_TOLERANCE) {
                    best = clamp(tPositive, 0.0, 1.0);
                }
            }
            double tNegative = (-radius - acrossStart) / acrossVelocity;
            if (isUnitTime(tNegative)) {
                double along = alongStart + alongVelocity * tNegative;
                if (along >= -MIN_LINEAR_TOLERANCE
                        && along <= edgeLength + MIN_LINEAR_TOLERANCE) {
                    double candidate = clamp(tNegative, 0.0, 1.0);
                    if (candidate < best) best = candidate;
                }
            }
        }

        double capA = firstPointCircleContact(pointX - ax, pointY - ay,
                moveX, moveY, radiusSquared);
        if (capA >= 0.0 && capA < best) best = capA;
        double capB = firstPointCircleContact(pointX - bx, pointY - by,
                moveX, moveY, radiusSquared);
        if (capB >= 0.0 && capB < best) best = capB;

        return best == Double.POSITIVE_INFINITY ? -1.0 : best;
    }

    private static double firstPointCircleContact(double relativeX,
                                                  double relativeY,
                                                  double moveX,
                                                  double moveY,
                                                  double radiusSquared) {
        double a = moveX * moveX + moveY * moveY;
        if (a == 0.0) return -1.0;
        double b = 2.0 * (relativeX * moveX + relativeY * moveY);
        double c = relativeX * relativeX + relativeY * relativeY - radiusSquared;
        if (c <= 0.0) return 0.0;

        double bSquared = b * b;
        double fourAc = 4.0 * a * c;
        double discriminant = bSquared - fourAc;
        double discriminantScale = Math.max(Math.abs(bSquared), Math.abs(fourAc));
        double discriminantTolerance = discriminantScale == 0.0
                ? 0.0 : Math.ulp(discriminantScale) * 16.0;
        if (discriminant < -discriminantTolerance) return -1.0;
        if (discriminant < 0.0) discriminant = 0.0;

        double squareRoot = Math.sqrt(discriminant);
        double q = -0.5 * (b + Math.copySign(squareRoot, b));
        double first;
        double second;
        if (q == 0.0) {
            first = -b / (2.0 * a);
            second = first;
        } else {
            first = q / a;
            second = c / q;
        }
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }
        if (isUnitTime(first)) return clamp(first, 0.0, 1.0);
        if (isUnitTime(second)) return clamp(second, 0.0, 1.0);
        return -1.0;
    }

    // ---------------------------------------------------------------------
    // Polygon validation and robust segment predicates
    // ---------------------------------------------------------------------

    /** Validates a finite, non-degenerate, simple polygon; throws on failure. */
    public static void validatePolygon(List<Vec2> polygon, String name) {
        String label = name == null ? "polygon" : name;
        if (polygon == null || polygon.size() < 3) {
            throw new IllegalArgumentException(label + ": polygon needs at least 3 vertices");
        }
        int count = polygon.size();
        double maxCoordinate = 0.0;
        double maxEdgeLength = 0.0;
        for (int i = 0; i < count; i++) {
            Vec2 a = polygon.get(i);
            Vec2 b = polygon.get((i + 1) % count);
            requireWorldPoint(a, label + " vertex[" + i + "]");
            requireWorldPoint(b, label + " vertex[" + ((i + 1) % count) + "]");
            maxCoordinate = Math.max(maxCoordinate, maxAbs(a.x, a.y));
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double edgeLength = Math.hypot(dx, dy);
            if (edgeLength <= coordinateTolerance(a.x, a.y, b.x, b.y)) {
                throw new IllegalArgumentException(label
                        + ": duplicate/zero-length adjacent edge at " + i);
            }
            maxEdgeLength = Math.max(maxEdgeLength, edgeLength);
        }

        double area = signedAreaUnchecked(polygon);
        double areaScale = Math.max(1.0, maxEdgeLength * maxEdgeLength);
        double coordinateError = Math.ulp(Math.max(1.0, maxCoordinate))
                * maxEdgeLength * count;
        double areaTolerance = Math.max(
                Math.ulp(areaScale) * AREA_ERROR_FACTOR,
                coordinateError * AREA_ERROR_FACTOR);
        if (!Double.isFinite(area) || Math.abs(area) <= areaTolerance) {
            throw new IllegalArgumentException(label + ": zero/negligible polygon area");
        }

        for (int i = 0; i < count; i++) {
            Vec2 a1 = polygon.get(i);
            Vec2 a2 = polygon.get((i + 1) % count);
            for (int j = i + 1; j < count; j++) {
                // Adjacent edges share an authored endpoint by definition.
                if (j == i || j == i + 1 || (i == 0 && j == count - 1)) {
                    continue;
                }
                Vec2 b1 = polygon.get(j);
                Vec2 b2 = polygon.get((j + 1) % count);
                if (segmentsIntersect(a1.x, a1.y, a2.x, a2.y,
                        b1.x, b1.y, b2.x, b2.y)) {
                    throw new IllegalArgumentException(label
                            + ": self-intersection/touch between edges " + i + " and " + j);
                }
            }
        }
    }

    /** Signed polygon area; positive for counter-clockwise order in Y-up space. */
    public static double signedArea(List<Vec2> polygon) {
        if (polygon == null || polygon.size() < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 vertices");
        }
        for (int i = 0; i < polygon.size(); i++) {
            requireWorldPoint(polygon.get(i), "polygon vertex[" + i + "]");
        }
        return signedAreaUnchecked(polygon);
    }

    private static double signedAreaUnchecked(List<Vec2> polygon) {
        Vec2 origin = polygon.get(0);
        double sum = 0.0;
        double compensation = 0.0;
        for (int i = 1; i < polygon.size() - 1; i++) {
            Vec2 a = polygon.get(i);
            Vec2 b = polygon.get(i + 1);
            double cross = (a.x - origin.x) * (b.y - origin.y)
                    - (a.y - origin.y) * (b.x - origin.x);
            double adjusted = cross - compensation;
            double next = sum + adjusted;
            compensation = (next - sum) - adjusted;
            sum = next;
        }
        return sum * 0.5;
    }

    private static void requireHoleInsideOuter(List<Vec2> hole,
                                               List<Vec2> outer,
                                               int holeIndex) {
        for (int i = 0; i < hole.size(); i++) {
            Vec2 point = hole.get(i);
            if (!pointInPolygon(point, outer)) {
                throw new IllegalArgumentException("hole[" + holeIndex
                        + "]: vertex " + i + " is outside or touches outer boundary");
            }
        }
        if (ringsIntersectOrTouch(hole, outer)) {
            throw new IllegalArgumentException("hole[" + holeIndex
                    + "]: edge crosses or touches outer boundary");
        }
    }

    private static void requireHolesDisjoint(List<Vec2> earlier,
                                             List<Vec2> later,
                                             int earlierIndex,
                                             int laterIndex) {
        if (ringsIntersectOrTouch(earlier, later)
                || pointInPolygon(earlier.get(0), later)
                || pointInPolygon(later.get(0), earlier)) {
            throw new IllegalArgumentException("hole[" + earlierIndex + "] and hole["
                    + laterIndex + "]: overlap, touch, or nesting is not supported");
        }
    }

    private static boolean ringsIntersectOrTouch(List<Vec2> first,
                                                 List<Vec2> second) {
        for (int i = 0; i < first.size(); i++) {
            Vec2 a = first.get(i);
            Vec2 b = first.get((i + 1) % first.size());
            for (int j = 0; j < second.size(); j++) {
                Vec2 c = second.get(j);
                Vec2 d = second.get((j + 1) % second.size());
                if (segmentsIntersect(a.x, a.y, b.x, b.y,
                        c.x, c.y, d.x, d.y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(double ax, double ay,
                                             double bx, double by,
                                             double cx, double cy,
                                             double dx, double dy) {
        int o1 = orientationSign(ax, ay, bx, by, cx, cy);
        int o2 = orientationSign(ax, ay, bx, by, dx, dy);
        int o3 = orientationSign(cx, cy, dx, dy, ax, ay);
        int o4 = orientationSign(cx, cy, dx, dy, bx, by);

        if (o1 * o2 < 0 && o3 * o4 < 0) return true;
        if (o1 == 0 && pointOnSegment(cx, cy, ax, ay, bx, by)) return true;
        if (o2 == 0 && pointOnSegment(dx, dy, ax, ay, bx, by)) return true;
        if (o3 == 0 && pointOnSegment(ax, ay, cx, cy, dx, dy)) return true;
        return o4 == 0 && pointOnSegment(bx, by, cx, cy, dx, dy);
    }

    /**
     * Fast floating filter with an exact BigDecimal fallback for ambiguous
     * cold-path polygon predicates. The fallback is adapted conceptually from
     * robust-orientation techniques documented by JTS; no JTS code or runtime
     * dependency is included.
     */
    private static int orientationSign(double ax, double ay,
                                       double bx, double by,
                                       double cx, double cy) {
        double abx = bx - ax;
        double aby = by - ay;
        double acx = cx - ax;
        double acy = cy - ay;
        double left = abx * acy;
        double right = aby * acx;
        double determinant = left - right;
        double magnitude = Math.abs(left) + Math.abs(right);
        double coordinateScale = maxAbs(ax, ay, bx, by, cx, cy);
        double differenceScale = maxAbs(abx, aby, acx, acy);
        double subtractionError = Math.ulp(Math.max(1.0, coordinateScale)) * 2.0;
        double subtractionContribution = subtractionError
                * (Math.abs(abx) + Math.abs(aby)
                + Math.abs(acx) + Math.abs(acy) + subtractionError * 2.0)
                * ORIENTATION_ERROR_FACTOR;
        double productContribution = magnitude == 0.0 ? 0.0
                : Math.ulp(magnitude) * ORIENTATION_ERROR_FACTOR;
        double errorBound = productContribution + subtractionContribution;
        if (differenceScale == 0.0) return 0;
        if (determinant > errorBound) return 1;
        if (determinant < -errorBound) return -1;

        BigDecimal exactAbx = new BigDecimal(bx).subtract(new BigDecimal(ax));
        BigDecimal exactAby = new BigDecimal(by).subtract(new BigDecimal(ay));
        BigDecimal exactAcx = new BigDecimal(cx).subtract(new BigDecimal(ax));
        BigDecimal exactAcy = new BigDecimal(cy).subtract(new BigDecimal(ay));
        return exactAbx.multiply(exactAcy)
                .subtract(exactAby.multiply(exactAcx)).signum();
    }

    private static boolean pointOnSegment(double px, double py,
                                          double ax, double ay,
                                          double bx, double by) {
        double tolerance = coordinateTolerance(px, py, ax, ay, bx, by);
        double dx = bx - ax;
        double dy = by - ay;
        double length = Math.hypot(dx, dy);
        if (length == 0.0) {
            double pointDx = px - ax;
            double pointDy = py - ay;
            return pointDx * pointDx + pointDy * pointDy <= tolerance * tolerance;
        }
        double cross = dx * (py - ay) - dy * (px - ax);
        if (Math.abs(cross) > tolerance * length) return false;
        return px >= Math.min(ax, bx) - tolerance
                && px <= Math.max(ax, bx) + tolerance
                && py >= Math.min(ay, by) - tolerance
                && py <= Math.max(ay, by) + tolerance;
    }

    // ---------------------------------------------------------------------
    // Primitive numeric helpers (allocation-free)
    // ---------------------------------------------------------------------

    private static double pointSegmentDistanceSquaredUnchecked(
            double px, double py, double ax, double ay, double bx, double by) {
        double abX = bx - ax;
        double abY = by - ay;
        double lengthSquared = abX * abX + abY * abY;
        if (lengthSquared == 0.0) {
            double dx = px - ax;
            double dy = py - ay;
            return dx * dx + dy * dy;
        }
        double projection = ((px - ax) * abX + (py - ay) * abY)
                / lengthSquared;
        projection = clamp(projection, 0.0, 1.0);
        double closestX = ax + projection * abX;
        double closestY = ay + projection * abY;
        double dx = px - closestX;
        double dy = py - closestY;
        return dx * dx + dy * dy;
    }

    private static double coordinateTolerance(double x1, double y1,
                                              double x2, double y2,
                                              double x3, double y3,
                                              double x4, double y4) {
        double scale = maxAbs(x1, y1, x2, y2, x3, y3, x4, y4);
        return Math.max(MIN_LINEAR_TOLERANCE,
                Math.ulp(Math.max(1.0, scale)) * 8.0);
    }

    private static double coordinateTolerance(double x1, double y1,
                                              double x2, double y2,
                                              double x3, double y3) {
        double scale = maxAbs(x1, y1, x2, y2, x3, y3);
        return Math.max(MIN_LINEAR_TOLERANCE,
                Math.ulp(Math.max(1.0, scale)) * 8.0);
    }

    private static double coordinateTolerance(double x1, double y1,
                                              double x2, double y2) {
        double scale = maxAbs(x1, y1, x2, y2);
        return Math.max(MIN_LINEAR_TOLERANCE,
                Math.ulp(Math.max(1.0, scale)) * 8.0);
    }

    private static boolean isWorldPoint(Vec2 point) {
        return point != null && isWorldPoint(point.x, point.y);
    }

    private static boolean isWorldPoint(double x, double y) {
        return Double.isFinite(x) && Double.isFinite(y)
                && Math.abs(x) <= MAX_ABS_WORLD_COORDINATE
                && Math.abs(y) <= MAX_ABS_WORLD_COORDINATE;
    }

    private static void requireWorldPoint(Vec2 point, String name) {
        if (!isWorldPoint(point)) {
            throw new IllegalArgumentException(name
                    + " must be non-null, finite and within World Space envelope");
        }
    }

    private static boolean isValidRadius(double radius) {
        return Double.isFinite(radius) && radius >= 0.0
                && radius <= MAX_ABS_WORLD_COORDINATE;
    }

    private static boolean isUnitTime(double time) {
        return Double.isFinite(time)
                && time >= -TIME_TOLERANCE
                && time <= 1.0 + TIME_TOLERANCE;
    }

    private static double maxAbs(double a, double b) {
        return Math.max(Math.abs(a), Math.abs(b));
    }

    private static double maxAbs(double a, double b, double c, double d) {
        return Math.max(maxAbs(a, b), maxAbs(c, d));
    }

    private static double maxAbs(double a, double b, double c,
                                 double d, double e, double f) {
        return Math.max(maxAbs(a, b, c, d), maxAbs(e, f));
    }

    private static double maxAbs(double a, double b, double c, double d,
                                 double e, double f, double g, double h) {
        return Math.max(maxAbs(a, b, c, d), maxAbs(e, f, g, h));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static double checkedFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " overflowed or became non-finite");
        }
        return value;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
    }

    private static List<Vec2> immutableCopy(List<Vec2> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return value < minimum ? minimum : (value > maximum ? maximum : value);
    }
}
