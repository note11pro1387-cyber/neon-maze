package com.neonmaze.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.GameEngine.EngineState;
import com.neonmaze.core.GameEngine.GateRenderState;
import com.neonmaze.core.GeometryCollision.Vec2;
import com.neonmaze.core.LevelSystem.Gate;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.ObjectiveType;
import com.neonmaze.core.LevelSystem.Zone;
import com.neonmaze.core.LevelSystem.ZoneShape;
import com.neonmaze.core.ReplaySystem.GhostTimeline;
import com.neonmaze.core.ReplaySystem.ReplayMode;

import java.util.List;

/**
 * Canvas-only renderer for the NEON MAZE playfield.
 *
 * <p>All static geometry is prepared when a level is assigned or when the
 * shared viewport changes. Paints, Paths, RectF scratch space and the ghost
 * output buffer are reused. {@link #draw(Canvas, GameEngine, GhostTimeline,
 * TouchInputAdapter)} performs no explicit allocation, I/O, formatting,
 * geometry validation, or game-state mutation.</p>
 *
 * <p>Gameplay state is read from the authoritative GameEngine getters. This
 * avoids creating a defensive {@code Snapshot} (which intentionally copies
 * collections) on every display frame. Snapshot/event consumption belongs to
 * the Host/HUD layer.</p>
 */
public final class GameRenderer {

    private static final int COLOR_BACKGROUND = Color.rgb(8, 11, 20);
    private static final int COLOR_BOARD = Color.rgb(16, 24, 39);
    private static final int COLOR_MINT = Color.rgb(86, 230, 213);
    private static final int COLOR_MINT_CORE = Color.rgb(198, 255, 245);
    private static final int COLOR_AMBER = Color.rgb(255, 200, 107);
    private static final int COLOR_CORAL = Color.rgb(255, 107, 122);
    private static final int COLOR_VIOLET = Color.rgb(167, 139, 250);
    private static final int COLOR_NEUTRAL = Color.rgb(148, 163, 184);
    private static final int COLOR_WHITE = Color.rgb(245, 247, 251);

    private final TouchInputAdapter.Viewport viewport;

    private final Paint boardPaint = paint(Paint.Style.FILL, COLOR_BOARD, 255);
    private final Paint boundaryOuterPaint = paint(Paint.Style.STROKE, COLOR_MINT, 48);
    private final Paint boundaryMidPaint = paint(Paint.Style.STROKE, COLOR_MINT, 132);
    private final Paint boundaryCorePaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 255);
    private final Paint safeZonePaint = paint(Paint.Style.FILL, COLOR_MINT, 22);
    private final Paint gateOpenPaint = paint(Paint.Style.FILL, COLOR_MINT, 78);
    private final Paint gateWarningPaint = paint(Paint.Style.FILL, COLOR_AMBER, 112);
    private final Paint gateClosedPaint = paint(Paint.Style.FILL, COLOR_NEUTRAL, 150);
    private final Paint gateLockedPaint = paint(Paint.Style.FILL, COLOR_VIOLET, 150);
    private final Paint gateBarrierPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 235);
    private final Paint objectiveMandatoryPaint = paint(Paint.Style.STROKE, COLOR_AMBER, 245);
    private final Paint objectiveOptionalPaint = paint(Paint.Style.STROKE, COLOR_VIOLET, 245);
    private final Paint objectiveCollectedPaint = paint(Paint.Style.FILL, COLOR_MINT, 225);
    private final Paint startPaint = paint(Paint.Style.FILL, COLOR_MINT, 82);
    private final Paint goalPaint = paint(Paint.Style.FILL, COLOR_AMBER, 96);
    private final Paint trailGlowPaint = paint(Paint.Style.STROKE, COLOR_MINT, 70);
    private final Paint trailCorePaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 235);
    private final Paint markerGlowPaint = paint(Paint.Style.FILL, COLOR_MINT, 74);
    private final Paint markerCorePaint = paint(Paint.Style.FILL, COLOR_WHITE, 255);
    private final Paint touchHaloPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 135);
    private final Paint tetherPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 74);
    private final Paint ghostPaint = paint(Paint.Style.FILL, COLOR_VIOLET, 155);
    private final Paint blockedGoalPaint = paint(Paint.Style.STROKE, COLOR_AMBER, 240);
    private final Paint gridPaint = paint(Paint.Style.STROKE, COLOR_NEUTRAL, 12);

    private final Path boundaryPath = new Path();
    private final Path trailPath = new Path();
    private final double[] ghostPosition = new double[2];

    @Nullable private LevelDefinition level;
    @Nullable private ZoneGeometry[] safeZoneGeometry;
    @Nullable private ZoneGeometry[] gateGeometry;
    @Nullable private ZoneGeometry[] objectiveGeometry;
    @Nullable private ZoneGeometry startGeometry;
    @Nullable private ZoneGeometry goalGeometry;

    private int viewportRevision = -1;
    private int trailSize = 0;
    private boolean reducedEffects;
    private boolean highContrast;

    public GameRenderer(@NonNull TouchInputAdapter.Viewport viewport) {
        this.viewport = viewport;
        configureStrokePaints();
    }

    private static Paint paint(Paint.Style style, int color, int alpha) {
        Paint value = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        value.setStyle(style);
        value.setColor(color);
        value.setAlpha(alpha);
        value.setStrokeCap(Paint.Cap.ROUND);
        value.setStrokeJoin(Paint.Join.ROUND);
        return value;
    }

    private void configureStrokePaints() {
        boundaryOuterPaint.setStrokeJoin(Paint.Join.ROUND);
        boundaryMidPaint.setStrokeJoin(Paint.Join.ROUND);
        boundaryCorePaint.setStrokeJoin(Paint.Join.ROUND);
        trailGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        trailCorePaint.setStrokeCap(Paint.Cap.ROUND);
        trailCorePaint.setStrokeJoin(Paint.Join.ROUND);
        gateBarrierPaint.setStrokeCap(Paint.Cap.SQUARE);
        objectiveMandatoryPaint.setStrokeWidth(2.5f);
        objectiveOptionalPaint.setStrokeWidth(2.5f);
        touchHaloPaint.setStrokeWidth(1.5f);
        blockedGoalPaint.setStrokeWidth(2.5f);
    }

    /** Assigns a validated immutable level and builds all cold-path geometry. */
    public void setLevel(@NonNull LevelDefinition newLevel) {
        level = newLevel;
        safeZoneGeometry = buildZoneGeometry(newLevel.safeZones());
        gateGeometry = buildGateGeometry(newLevel.gates());
        objectiveGeometry = buildObjectiveGeometry(newLevel.objectives());
        startGeometry = new ZoneGeometry(newLevel.startZone());
        goalGeometry = new ZoneGeometry(newLevel.goalZone());
        trailPath.reset();
        trailSize = 0;
        viewportRevision = -1;
        rebuildViewportGeometry();
    }

    public void clearLevel() {
        level = null;
        safeZoneGeometry = null;
        gateGeometry = null;
        objectiveGeometry = null;
        startGeometry = null;
        goalGeometry = null;
        boundaryPath.reset();
        trailPath.reset();
        trailSize = 0;
        viewportRevision = -1;
    }

    public void setVisualOptions(boolean reducedEffects, boolean highContrast) {
        this.reducedEffects = reducedEffects;
        this.highContrast = highContrast;
    }

    private ZoneGeometry[] buildZoneGeometry(List<Zone> zones) {
        ZoneGeometry[] result = new ZoneGeometry[zones.size()];
        for (int i = 0; i < zones.size(); i++) result[i] = new ZoneGeometry(zones.get(i));
        return result;
    }

    private ZoneGeometry[] buildGateGeometry(List<Gate> gates) {
        ZoneGeometry[] result = new ZoneGeometry[gates.size()];
        for (int i = 0; i < gates.size(); i++) result[i] = new ZoneGeometry(gates.get(i).passage());
        return result;
    }

    private ZoneGeometry[] buildObjectiveGeometry(List<Objective> objectives) {
        ZoneGeometry[] result = new ZoneGeometry[objectives.size()];
        for (int i = 0; i < objectives.size(); i++) {
            result[i] = new ZoneGeometry(objectives.get(i).zone());
        }
        return result;
    }

    /** Called after the shared viewport is recomputed on a size/inset change. */
    public void onViewportChanged() {
        rebuildViewportGeometry();
    }

    private void rebuildViewportGeometry() {
        if (level == null || !viewport.isReady()) return;
        boundaryPath.reset();
        boundaryPath.setFillType(Path.FillType.EVEN_ODD);
        appendPolygon(boundaryPath, level.outerBoundary());
        for (List<Vec2> hole : level.holeBoundaries()) appendPolygon(boundaryPath, hole);
        rebuildZoneArray(safeZoneGeometry);
        rebuildZoneArray(gateGeometry);
        rebuildZoneArray(objectiveGeometry);
        if (startGeometry != null) startGeometry.rebuild(viewport);
        if (goalGeometry != null) goalGeometry.rebuild(viewport);
        rebuildTrailPath(null);
        viewportRevision = viewport.revision();
    }

    private void rebuildZoneArray(@Nullable ZoneGeometry[] zones) {
        if (zones == null) return;
        for (ZoneGeometry zone : zones) zone.rebuild(viewport);
    }

    private void appendPolygon(Path path, List<Vec2> polygon) {
        if (polygon.isEmpty()) return;
        Vec2 first = polygon.get(0);
        path.moveTo(viewport.worldToScreenX(first.x()), viewport.worldToScreenY(first.y()));
        for (int i = 1; i < polygon.size(); i++) {
            Vec2 point = polygon.get(i);
            path.lineTo(viewport.worldToScreenX(point.x()), viewport.worldToScreenY(point.y()));
        }
        path.close();
    }

    /** Main render entry point. Must be called on the UI thread. */
    public void draw(@NonNull Canvas canvas,
                     @NonNull GameEngine engine,
                     @Nullable GhostTimeline ghost,
                     @Nullable TouchInputAdapter input) {
        canvas.drawColor(COLOR_BACKGROUND);
        if (level == null || !viewport.isReady()) return;
        if (viewportRevision != viewport.revision()) rebuildViewportGeometry();

        drawSubtleGrid(canvas);
        drawBoard(canvas);
        drawSafeZones(canvas);
        drawGates(canvas, engine);
        drawObjectives(canvas, engine);
        drawStartAndGoal(canvas);
        drawTrail(canvas, engine);
        drawGhost(canvas, engine, ghost);
        drawMarker(canvas, engine, input);
        drawTerminalMarker(canvas, engine);
    }

    private void drawSubtleGrid(Canvas canvas) {
        if (reducedEffects) return;
        float spacing = Math.max(24f, (float) (viewport.scale() * 100.0));
        float left = viewport.contentLeft();
        float top = viewport.contentTop();
        float right = viewport.contentRight();
        float bottom = viewport.contentBottom();
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        for (float x = left; x <= right; x += spacing) canvas.drawLine(x, top, x, bottom, gridPaint);
        for (float y = top; y <= bottom; y += spacing) canvas.drawLine(left, y, right, y, gridPaint);
        canvas.restore();
    }

    private void drawBoard(Canvas canvas) {
        if (boundaryPath.isEmpty()) return;
        canvas.drawPath(boundaryPath, boardPaint);
        float baseStroke = Math.max(1.15f, (float) (viewport.scale() * 12.0));
        boundaryOuterPaint.setStrokeWidth(reducedEffects ? baseStroke : baseStroke * 2.8f);
        boundaryMidPaint.setStrokeWidth(reducedEffects ? baseStroke : baseStroke * 1.55f);
        boundaryCorePaint.setStrokeWidth(highContrast ? Math.max(2f, baseStroke * 0.85f)
                : Math.max(1f, baseStroke * 0.58f));
        if (!reducedEffects) canvas.drawPath(boundaryPath, boundaryOuterPaint);
        if (!reducedEffects) canvas.drawPath(boundaryPath, boundaryMidPaint);
        canvas.drawPath(boundaryPath, boundaryCorePaint);
    }

    private void drawSafeZones(Canvas canvas) {
        if (safeZoneGeometry == null) return;
        for (ZoneGeometry geometry : safeZoneGeometry) geometry.draw(canvas, safeZonePaint);
    }

    private void drawGates(Canvas canvas, GameEngine engine) {
        if (gateGeometry == null) return;
        List<Gate> gates = level.gates();
        for (int i = 0; i < gates.size(); i++) {
            Gate gate = gates.get(i);
            GateRenderState state = engine.getGateRenderState(gate.id());
            Paint fill;
            switch (state) {
                case OPEN: fill = gateOpenPaint; break;
                case WARNING: fill = gateWarningPaint; break;
                case LOCKED: fill = gateLockedPaint; break;
                case CLOSED:
                default: fill = gateClosedPaint; break;
            }
            gateGeometry[i].draw(canvas, fill);
            if (gate.hasBarrier()) {
                float x1 = viewport.worldToScreenX(gate.barrierStart().x());
                float y1 = viewport.worldToScreenY(gate.barrierStart().y());
                float x2 = viewport.worldToScreenX(gate.barrierEnd().x());
                float y2 = viewport.worldToScreenY(gate.barrierEnd().y());
                float width = Math.max(2f, (float) (viewport.scale() * 7.0));
                gateBarrierPaint.setStrokeWidth(width);
                gateBarrierPaint.setColor(state == GateRenderState.LOCKED
                        ? COLOR_VIOLET : state == GateRenderState.WARNING
                        ? COLOR_AMBER : state == GateRenderState.OPEN
                        ? COLOR_MINT_CORE : highContrast ? Color.WHITE : COLOR_NEUTRAL);
                canvas.drawLine(x1, y1, x2, y2, gateBarrierPaint);
            }
        }
    }

    private void drawObjectives(Canvas canvas, GameEngine engine) {
        if (objectiveGeometry == null) return;
        List<Objective> objectives = level.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            boolean collected = engine.isObjectiveCollected(objective.id());
            Paint paint;
            if (collected) {
                paint = objectiveCollectedPaint;
            } else {
                paint = objective.type() == ObjectiveType.OPTIONAL
                        ? objectiveOptionalPaint : objectiveMandatoryPaint;
            }
            objectiveGeometry[i].draw(canvas, paint);
        }
    }

    private void drawStartAndGoal(Canvas canvas) {
        if (startGeometry != null) startGeometry.draw(canvas, startPaint);
        if (goalGeometry != null) goalGeometry.draw(canvas, goalPaint);
    }

    private void drawTrail(Canvas canvas, GameEngine engine) {
        List<Vec2> trail = engine.getTrail();
        int currentSize = trail.size();
        if (currentSize < trailSize) rebuildTrailPath(trail);
        else if (currentSize > trailSize) {
            if (trailSize == 0 && currentSize > 0) {
                Vec2 first = trail.get(0);
                trailPath.moveTo(viewport.worldToScreenX(first.x()),
                        viewport.worldToScreenY(first.y()));
                trailSize = 1;
            }
            for (int i = trailSize; i < currentSize; i++) {
                Vec2 point = trail.get(i);
                trailPath.lineTo(viewport.worldToScreenX(point.x()),
                        viewport.worldToScreenY(point.y()));
            }
            trailSize = currentSize;
        }
        if (trailPath.isEmpty()) return;
        float stroke = Math.max(2f, (float) (viewport.scale() * 8.0));
        trailGlowPaint.setStrokeWidth(reducedEffects ? stroke : stroke * 2.4f);
        trailCorePaint.setStrokeWidth(stroke);
        if (!reducedEffects) canvas.drawPath(trailPath, trailGlowPaint);
        canvas.drawPath(trailPath, trailCorePaint);
    }

    private void rebuildTrailPath(@Nullable List<Vec2> trail) {
        trailPath.reset();
        trailSize = 0;
        if (trail == null) return;
        if (trail.isEmpty()) return;
        Vec2 first = trail.get(0);
        trailPath.moveTo(viewport.worldToScreenX(first.x()), viewport.worldToScreenY(first.y()));
        for (int i = 1; i < trail.size(); i++) {
            Vec2 point = trail.get(i);
            trailPath.lineTo(viewport.worldToScreenX(point.x()), viewport.worldToScreenY(point.y()));
        }
        trailSize = trail.size();
    }

    private void drawGhost(Canvas canvas, GameEngine engine, @Nullable GhostTimeline ghost) {
        if (ghost == null || engine.getMode() != ReplayMode.GHOST_RACE) return;
        EngineState state = engine.getState();
        if (state != EngineState.READY && state != EngineState.RUNNING
                && state != EngineState.COMPLETED) return;
        long activeMs = state == EngineState.READY ? 0L : engine.getActiveMs();
        if (activeMs > ghost.durationMs()) return;
        ghost.positionAt(activeMs, ghostPosition);
        float x = viewport.worldToScreenX(ghostPosition[0]);
        float y = viewport.worldToScreenY(ghostPosition[1]);
        float radius = Math.max(3f, (float) (level.playerRadius() * viewport.scale()));
        canvas.drawCircle(x, y, radius, ghostPaint);
    }

    private void drawMarker(Canvas canvas, GameEngine engine,
                            @Nullable TouchInputAdapter input) {
        Vec2 marker = engine.getMarkerPosition();
        if (input != null && input.hasPointerPosition()) {
            float tx = input.lastScreenX();
            float ty = input.lastScreenY();
            float haloRadius = Math.max(8f, (float) (level.playerRadius()
                    * 1.6 * viewport.scale()));
            touchHaloPaint.setStrokeWidth(Math.max(1.5f, haloRadius * 0.09f));
            canvas.drawCircle(tx, ty, haloRadius, touchHaloPaint);
            if (marker != null) {
                float mx = viewport.worldToScreenX(marker.x());
                float my = viewport.worldToScreenY(marker.y());
                if (Math.abs(tx - mx) > 2f || Math.abs(ty - my) > 2f) {
                    tetherPaint.setStrokeWidth(Math.max(1f, haloRadius * 0.06f));
                    canvas.drawLine(tx, ty, mx, my, tetherPaint);
                }
            }
        }
        if (marker != null) {
            float x = viewport.worldToScreenX(marker.x());
            float y = viewport.worldToScreenY(marker.y());
            float radius = Math.max(3f, (float) (level.playerRadius() * viewport.scale()));
            if (!reducedEffects) canvas.drawCircle(x, y, radius * 2.15f, markerGlowPaint);
            canvas.drawCircle(x, y, radius, markerCorePaint);
        }
    }

    private void drawTerminalMarker(Canvas canvas, GameEngine engine) {
        if (engine.getState() != EngineState.FAILED
                || engine.getFailureReason() == GameEngine.FailureReason.NONE) return;
        Vec2 marker = engine.getMarkerPosition();
        if (marker == null) return;
        float x = viewport.worldToScreenX(marker.x());
        float y = viewport.worldToScreenY(marker.y());
        float radius = Math.max(8f, (float) (level.playerRadius() * 1.7 * viewport.scale()));
        blockedGoalPaint.setColor(COLOR_CORAL);
        canvas.drawCircle(x, y, radius, blockedGoalPaint);
        canvas.drawLine(x - radius * 0.55f, y - radius * 0.55f,
                x + radius * 0.55f, y + radius * 0.55f, blockedGoalPaint);
        canvas.drawLine(x - radius * 0.55f, y + radius * 0.55f,
                x + radius * 0.55f, y - radius * 0.55f, blockedGoalPaint);
    }

    private final class ZoneGeometry {
        private final Zone zone;
        private final Path path = new Path();

        private ZoneGeometry(Zone zone) {
            this.zone = zone;
        }

        private void rebuild(TouchInputAdapter.Viewport viewport) {
            path.reset();
            if (zone.shape() == ZoneShape.POLYGON) appendPolygon(path, zone.polygon());
        }

        private void draw(Canvas canvas, Paint paint) {
            if (zone.shape() == ZoneShape.CIRCLE) {
                float x = viewport.worldToScreenX(zone.cx());
                float y = viewport.worldToScreenY(zone.cy());
                float radius = (float) (zone.radius() * viewport.scale());
                canvas.drawCircle(x, y, radius, paint);
            } else if (!path.isEmpty()) {
                canvas.drawPath(path, paint);
            }
        }
    }
}
