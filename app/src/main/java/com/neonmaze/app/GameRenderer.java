package com.neonmaze.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;

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

    // PRISM CIRCUIT art direction: a deep-space arena, machined obsidian lanes,
    // ion-lit wall bevels and jewel-like objectives. Collision geometry remains
    // the only authority for the board silhouette.
    private static final int COLOR_BACKGROUND = Color.rgb(5, 7, 18);
    private static final int COLOR_BOARD = Color.rgb(20, 48, 69);
    private static final int COLOR_MINT = Color.rgb(63, 245, 222);
    private static final int COLOR_MINT_CORE = Color.rgb(219, 255, 250);
    private static final int COLOR_AMBER = Color.rgb(255, 196, 96);
    private static final int COLOR_CORAL = Color.rgb(255, 91, 126);
    private static final int COLOR_VIOLET = Color.rgb(174, 126, 255);
    private static final int COLOR_NEUTRAL = Color.rgb(123, 151, 177);
    private static final int COLOR_WHITE = Color.rgb(246, 251, 255);
    private static final int COLOR_STEEL = Color.rgb(43, 67, 91);
    private static final int COLOR_VOID_BLUE = Color.rgb(14, 24, 48);

    private static final float[] STAR_X = { .035f, .092f, .147f, .208f, .273f, .326f,
            .388f, .451f, .514f, .578f, .639f, .702f, .766f, .824f, .893f, .956f,
            .061f, .183f, .297f, .418f, .543f, .668f, .781f, .914f };
    private static final float[] STAR_Y = { .082f, .241f, .126f, .337f, .071f, .191f,
            .292f, .103f, .226f, .057f, .319f, .148f, .267f, .091f, .211f, .354f,
            .481f, .572f, .423f, .658f, .514f, .734f, .447f, .612f };

    private final TouchInputAdapter.Viewport viewport;
    @Nullable private final Bitmap boardTextureBitmap;
    @Nullable private final BitmapShader boardTextureShader;

    private final Paint backdropPaint = paint(Paint.Style.FILL, COLOR_BACKGROUND, 255);
    private final Paint backdropAuraPaint = paint(Paint.Style.FILL, COLOR_VOID_BLUE, 255);
    private final Paint starPaint = paint(Paint.Style.FILL, COLOR_WHITE, 110);
    private final Paint boardShadowPaint = paint(Paint.Style.FILL, Color.BLACK, 205);
    private final Paint boardPaint = paint(Paint.Style.FILL, COLOR_BOARD, 255);
    private final Paint boardTexturePaint = paint(Paint.Style.FILL, Color.WHITE, 58);
    private final Matrix boardTextureMatrix = new Matrix();
    private final Paint boardAuraPaint = paint(Paint.Style.FILL, COLOR_MINT, 34);
    private final Paint wallShadowPaint = paint(Paint.Style.STROKE, Color.BLACK, 245);
    private final Paint wallBasePaint = paint(Paint.Style.STROKE, COLOR_STEEL, 255);
    private final Paint wallGlowPaint = paint(Paint.Style.STROKE, COLOR_MINT, 118);
    private final Paint wallCorePaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 225);
    private final Paint rivetPaint = paint(Paint.Style.STROKE, COLOR_MINT, 150);
    private final Paint rivetCorePaint = paint(Paint.Style.FILL, COLOR_WHITE, 215);
    private final Paint energyPulsePaint = paint(Paint.Style.FILL, COLOR_MINT, 190);
    private final Paint energyPulseGlowPaint = paint(Paint.Style.FILL, COLOR_MINT, 48);
    private final Paint floorDetailPaint = paint(Paint.Style.STROKE, COLOR_MINT, 23);
    private final Paint floorNodePaint = paint(Paint.Style.FILL, COLOR_MINT_CORE, 45);
    private final Paint gateGlowPaint = paint(Paint.Style.STROKE, COLOR_MINT, 90);
    private final Paint gateRailPaint = paint(Paint.Style.STROKE, COLOR_STEEL, 255);
    private final Paint gateEnergyPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 245);
    private final Paint gateSegmentPaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 190);
    private final Paint portalGlowPaint = paint(Paint.Style.FILL, COLOR_MINT, 60);
    private final Paint portalOrbitPaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 150);
    private final Paint objectiveGlowPaint = paint(Paint.Style.FILL, COLOR_VIOLET, 52);
    private final Paint objectiveOrbitPaint = paint(Paint.Style.STROKE, COLOR_VIOLET, 190);
    private final Paint trailShadowPaint = paint(Paint.Style.STROKE, Color.BLACK, 145);
    private final Paint trailMidPaint = paint(Paint.Style.STROKE, COLOR_MINT, 170);
    private final Paint markerRimPaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 215);
    private final Paint markerSpecularPaint = paint(Paint.Style.FILL, Color.WHITE, 235);
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
    private final Paint startPaint = paint(Paint.Style.FILL, COLOR_MINT, 120);
    private final Paint goalPaint = paint(Paint.Style.FILL, COLOR_AMBER, 132);
    private final Paint startRingPaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 245);
    private final Paint goalRingPaint = paint(Paint.Style.STROKE, COLOR_AMBER, 250);
    private final Paint trailGlowPaint = paint(Paint.Style.STROKE, COLOR_MINT, 70);
    private final Paint trailCorePaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 235);
    private final Paint markerGlowPaint = paint(Paint.Style.FILL, COLOR_MINT, 74);
    private final Paint markerCorePaint = paint(Paint.Style.FILL, COLOR_WHITE, 255);
    private final Paint markerFramePaint = paint(Paint.Style.STROKE, COLOR_VIOLET, 220);
    private final Paint markerEmblemPaint = paint(Paint.Style.STROKE, COLOR_BACKGROUND, 245);
    private final Path markerFramePath = new Path();
    private final Path markerEmblemPath = new Path();
    private String equippedTrailId = "trail.mint";
    private String equippedFrameId = "frame.none";
    private String equippedEmblemId = "";
    private final Paint touchHaloPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 135);
    private final Paint tetherPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 74);
    private final Paint ghostPaint = paint(Paint.Style.FILL, COLOR_VIOLET, 155);
    private final Paint blockedGoalPaint = paint(Paint.Style.STROKE, COLOR_AMBER, 240);
    private final Paint motionCuePaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 210);
    private final Paint terminalMotionPaint = paint(Paint.Style.STROKE, COLOR_MINT_CORE, 235);
    private final Paint boundaryWarningPaint = paint(Paint.Style.STROKE, COLOR_AMBER, 245);
    private final Paint boundaryWarningFillPaint = paint(Paint.Style.FILL, COLOR_AMBER, 245);
    private final Paint boundaryWarningGlyphPaint = paint(Paint.Style.FILL, COLOR_WHITE, 255);
    private final Paint objectiveGlyphPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 245);
    private final Paint gateGlyphPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 245);
    private final Paint startGlyphPaint = paint(Paint.Style.FILL, COLOR_WHITE, 250);
    private final Paint goalGlyphPaint = paint(Paint.Style.STROKE, COLOR_WHITE, 250);
    private final Path boundaryWarningGlyphPath = new Path();
    private final Path symbolPath = new Path();
    private final RectF symbolRect = new RectF();
    private final Paint gridPaint = paint(Paint.Style.STROKE, COLOR_NEUTRAL, 28);
    private final Paint debugPanelPaint = paint(Paint.Style.FILL, Color.BLACK, 205);
    private final Paint debugTextPaint = paint(Paint.Style.FILL, Color.rgb(153, 255, 220), 245);
    private final Paint zoneLabelFillPaint = paint(Paint.Style.FILL, Color.rgb(5, 13, 25), 224);
    private final Paint zoneLabelStrokePaint = paint(Paint.Style.STROKE, COLOR_MINT, 175);
    private final Paint zoneLabelTextPaint = paint(Paint.Style.FILL, COLOR_WHITE, 245);

    private final Path boundaryPath = new Path();
    private final Path trailPath = new Path();
    private final RectF debugPanelRect = new RectF();
    private final RectF fullScreenRect = new RectF();
    private final RectF boardScreenRect = new RectF();
    private final RectF markerOrbitRect = new RectF();
    private final RectF zoneLabelRect = new RectF();
    private final double[] ghostPosition = new double[2];
    private double boundaryPerimeterWorld;
    private double[] boundarySegmentLengths = new double[0];
    private long presentationTimeNanos;
    private int shaderWidth = -1;
    private int shaderHeight = -1;
    private int backdropAccentColor = Integer.MIN_VALUE;
    private int worldAccentColor = COLOR_MINT;
    private boolean reducedMotion;
    @NonNull private String startLabel = "START";
    @NonNull private String goalLabel = "EXIT";

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
    private boolean boundaryWarning;
    private int motionCueType = GameMotionSystem.CUE_NONE;
    private float motionCueProgress = 1f;
    private int terminalMotionType = GameMotionSystem.TERMINAL_NONE;
    private float terminalMotionProgress = 1f;
    private long debugDrawCount;
    private int lastDiagnosticRevision = Integer.MIN_VALUE;
    @Nullable private String lastDiagnosticState;
    @Nullable private String lastDebugOverlayState;
    private String debugLineOne = "";
    private String debugLineTwo = "";
    private String debugLineThree = "";

    public GameRenderer(@NonNull TouchInputAdapter.Viewport viewport,
                        @Nullable Bitmap boardTexture) {
        this.viewport = viewport;
        this.boardTextureBitmap = boardTexture;
        this.boardTextureShader = boardTexture != null && !boardTexture.isRecycled()
                ? new BitmapShader(boardTexture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                : null;
        if (boardTextureShader != null) boardTexturePaint.setShader(boardTextureShader);
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
        startRingPaint.setStrokeWidth(2.5f);
        goalRingPaint.setStrokeWidth(2.5f);
        motionCuePaint.setStrokeCap(Paint.Cap.ROUND);
        terminalMotionPaint.setStrokeCap(Paint.Cap.ROUND);
        boundaryWarningPaint.setStrokeCap(Paint.Cap.ROUND);
        objectiveGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        objectiveGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        gateGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        gateGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        goalGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        goalGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        wallShadowPaint.setStrokeJoin(Paint.Join.ROUND);
        wallBasePaint.setStrokeJoin(Paint.Join.ROUND);
        wallGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        wallCorePaint.setStrokeJoin(Paint.Join.ROUND);
        wallCorePaint.setStrokeCap(Paint.Cap.ROUND);
        gateGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        gateRailPaint.setStrokeCap(Paint.Cap.SQUARE);
        gateEnergyPaint.setStrokeCap(Paint.Cap.SQUARE);
        gateSegmentPaint.setStrokeCap(Paint.Cap.SQUARE);
        trailShadowPaint.setStrokeCap(Paint.Cap.ROUND);
        trailShadowPaint.setStrokeJoin(Paint.Join.ROUND);
        trailMidPaint.setStrokeCap(Paint.Cap.ROUND);
        trailMidPaint.setStrokeJoin(Paint.Join.ROUND);
        markerRimPaint.setStrokeWidth(1.5f);
        rivetPaint.setStrokeWidth(1.1f);
        floorDetailPaint.setStrokeWidth(1f);
        debugTextPaint.setTextSize(10.5f);
        zoneLabelTextPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-condensed", android.graphics.Typeface.BOLD));
        zoneLabelTextPaint.setTextAlign(Paint.Align.CENTER);
        zoneLabelStrokePaint.setStrokeWidth(0.9f);
    }

    /** Assigns a validated immutable level and builds all cold-path geometry. */
    public void setLevel(@NonNull LevelDefinition newLevel) {
        level = newLevel;
        worldAccentColor = levelAccent(newLevel.levelId());
        rebuildBackdropShaders(shaderWidth, shaderHeight);
        wallGlowPaint.setColor(highContrast ? Color.WHITE : worldAccentColor);
        rivetPaint.setColor(worldAccentColor);
        floorDetailPaint.setColor(worldAccentColor);
        boundaryOuterPaint.setColor(highContrast ? Color.WHITE : worldAccentColor);
        boundaryMidPaint.setColor(highContrast ? Color.WHITE : worldAccentColor);
        safeZoneGeometry = buildZoneGeometry(newLevel.safeZones());
        gateGeometry = buildGateGeometry(newLevel.gates());
        objectiveGeometry = buildObjectiveGeometry(newLevel.objectives());
        startGeometry = new ZoneGeometry(newLevel.startZone());
        goalGeometry = new ZoneGeometry(newLevel.goalZone());
        boundaryPerimeterWorld = 0.0;
        List<Vec2> boundary = newLevel.outerBoundary();
        boundarySegmentLengths = new double[boundary.size()];
        for (int i = 0; i < boundary.size(); i++) {
            Vec2 a = boundary.get(i);
            Vec2 b = boundary.get((i + 1) % boundary.size());
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segmentLength = Math.sqrt(dx * dx + dy * dy);
            boundarySegmentLengths[i] = segmentLength;
            boundaryPerimeterWorld += segmentLength;
        }
        trailPath.reset();
        trailSize = 0;
        viewportRevision = -1;
        rebuildViewportGeometry();
    }

    /** A per-sector light signature; semantic gameplay colors remain unchanged. */
    private static int levelAccent(@NonNull String levelId) {
        if (levelId.startsWith("level-02")) return COLOR_VIOLET;
        if (levelId.startsWith("level-03")) return COLOR_AMBER;
        if (levelId.startsWith("level-04")) return Color.rgb(111, 167, 255);
        if (levelId.startsWith("level-05")) return COLOR_CORAL;
        return COLOR_MINT;
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
        boundarySegmentLengths = new double[0];
        boundaryPerimeterWorld = 0.0;
        viewportRevision = -1;
    }

    public void setCosmeticLoadout(@NonNull LocalStore.CosmeticLoadout loadout) {
        equippedTrailId = loadout.trailId;
        equippedFrameId = loadout.frameId;
        equippedEmblemId = loadout.emblemId;
        int trail = "trail.violet".equals(equippedTrailId) ? COLOR_VIOLET
                : "trail.amber".equals(equippedTrailId) ? COLOR_AMBER
                : "trail.coral".equals(equippedTrailId) ? COLOR_CORAL
                : "trail.ice".equals(equippedTrailId) ? Color.rgb(164, 221, 255)
                : COLOR_MINT_CORE;
        int glow = "trail.violet".equals(equippedTrailId) ? COLOR_VIOLET
                : "trail.amber".equals(equippedTrailId) ? COLOR_AMBER
                : "trail.coral".equals(equippedTrailId) ? COLOR_CORAL
                : "trail.ice".equals(equippedTrailId) ? Color.rgb(100, 186, 245)
                : COLOR_MINT;
        trailCorePaint.setColor(trail);
        trailGlowPaint.setColor(glow);
        markerFramePaint.setColor("frame.hex".equals(equippedFrameId) ? COLOR_VIOLET
                : "frame.pulse".equals(equippedFrameId) ? COLOR_AMBER
                : "frame.prism".equals(equippedFrameId) ? Color.rgb(164, 221, 255)
                : COLOR_WHITE);
    }

    public void setZoneLabels(@NonNull String startLabel, @NonNull String goalLabel) {
        this.startLabel = startLabel;
        this.goalLabel = goalLabel;
    }

    public void setBoundaryWarning(boolean warning) {
        boundaryWarning = warning;
    }

    public void setMotionState(int cueType, float cueProgress,
                               int terminalType, float terminalProgress) {
        motionCueType = cueType;
        motionCueProgress = clamp01(cueProgress);
        terminalMotionType = terminalType;
        terminalMotionProgress = clamp01(terminalProgress);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public void setVisualOptions(boolean reducedEffects, boolean highContrast) {
        this.reducedEffects = reducedEffects;
        this.highContrast = highContrast;
        boardPaint.setColor(highContrast ? Color.rgb(18, 34, 48) : Color.WHITE);
        gridPaint.setAlpha(highContrast ? 54 : 28);
        boundaryOuterPaint.setColor(highContrast ? Color.WHITE : worldAccentColor);
        boundaryMidPaint.setColor(highContrast ? Color.WHITE : worldAccentColor);
        boundaryOuterPaint.setAlpha(highContrast ? 128 : 38);
        boundaryMidPaint.setAlpha(highContrast ? 220 : 92);
        boundaryCorePaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
        safeZonePaint.setAlpha(highContrast ? 64 : 22);
        gateOpenPaint.setColor(highContrast ? Color.WHITE : COLOR_MINT);
        gateWarningPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
        gateClosedPaint.setColor(highContrast ? Color.WHITE : COLOR_NEUTRAL);
        gateLockedPaint.setColor(highContrast ? Color.WHITE : COLOR_VIOLET);
        gateOpenPaint.setAlpha(highContrast ? 170 : 78);
        gateWarningPaint.setAlpha(highContrast ? 190 : 112);
        gateClosedPaint.setAlpha(highContrast ? 190 : 150);
        gateLockedPaint.setAlpha(highContrast ? 190 : 150);
        objectiveMandatoryPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
        objectiveOptionalPaint.setColor(highContrast ? Color.WHITE : COLOR_VIOLET);
        objectiveCollectedPaint.setColor(highContrast ? Color.WHITE : COLOR_MINT);
        objectiveMandatoryPaint.setAlpha(highContrast ? 255 : 245);
        objectiveOptionalPaint.setAlpha(highContrast ? 255 : 245);
        objectiveCollectedPaint.setAlpha(highContrast ? 255 : 225);
        startRingPaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
        goalRingPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
        trailCorePaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
        markerFramePaint.setColor(highContrast ? Color.WHITE : COLOR_VIOLET);
        gateBarrierPaint.setColor(highContrast ? Color.WHITE : COLOR_WHITE);
        wallCorePaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
        wallGlowPaint.setColor(highContrast ? Color.WHITE : worldAccentColor);
        wallGlowPaint.setAlpha(highContrast ? 190 : 118);
        floorDetailPaint.setColor(worldAccentColor);
        rivetPaint.setColor(worldAccentColor);
        floorDetailPaint.setAlpha(highContrast ? 0 : 23);
        boardTexturePaint.setAlpha(highContrast ? 0 : reducedEffects ? 28 : 66);
        if (highContrast) {
            boardPaint.setShader(null);
            boardAuraPaint.setAlpha(0);
        } else {
            boardAuraPaint.setAlpha(reducedEffects ? 0 : 34);
            if (level != null && viewport.isReady()) rebuildViewportGeometry();
        }
    }

    public void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    public void setPresentationTimeNanos(long timeNanos) {
        presentationTimeNanos = Math.max(0L, timeNanos);
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
        boardScreenRect.set(viewport.contentLeft(), viewport.contentTop(),
                viewport.contentRight(), viewport.contentBottom());
        updateBoardTextureMapping();
        float boardCenterX = boardScreenRect.centerX();
        float boardCenterY = boardScreenRect.centerY();
        float boardRadius = Math.max(boardScreenRect.width(), boardScreenRect.height()) * 0.78f;
        if (highContrast) {
            boardPaint.setShader(null);
            boardPaint.setColor(Color.rgb(18, 34, 48));
        } else {
            boardPaint.setColor(Color.WHITE);
            boardPaint.setShader(new LinearGradient(boardScreenRect.left, boardScreenRect.top,
                    boardScreenRect.right, boardScreenRect.bottom,
                    new int[]{Color.rgb(28, 66, 88), Color.rgb(14, 35, 55), Color.rgb(9, 23, 40)},
                    null, Shader.TileMode.CLAMP));
        }
        boardAuraPaint.setShader(new RadialGradient(boardCenterX, boardCenterY, boardRadius,
                new int[]{Color.argb(58, Color.red(worldAccentColor),
                        Color.green(worldAccentColor), Color.blue(worldAccentColor)),
                        Color.argb(18, 47, 113, 155), Color.argb(0, 47, 113, 155)},
                null, Shader.TileMode.CLAMP));
        rebuildTrailPath(null);
        viewportRevision = viewport.revision();
    }

    /** Maps the static surface texture to the current viewport outside the frame loop. */
    private void updateBoardTextureMapping() {
        if (boardTextureShader == null || boardTextureBitmap == null
                || boardTextureBitmap.isRecycled()) return;
        float sourceWidth = Math.max(1, boardTextureBitmap.getWidth());
        float sourceHeight = Math.max(1, boardTextureBitmap.getHeight());
        float scale = Math.max(boardScreenRect.width() / sourceWidth,
                boardScreenRect.height() / sourceHeight);
        float scaledWidth = sourceWidth * scale;
        float scaledHeight = sourceHeight * scale;
        float offsetX = boardScreenRect.left + (boardScreenRect.width() - scaledWidth) * 0.5f;
        float offsetY = boardScreenRect.top + (boardScreenRect.height() - scaledHeight) * 0.5f;
        boardTextureMatrix.setScale(scale, scale);
        boardTextureMatrix.postTranslate(offsetX, offsetY);
        boardTextureShader.setLocalMatrix(boardTextureMatrix);
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

    /**
     * Prepares size-dependent backdrop shaders outside the frame draw path.
     * Called by the host when the RenderSurface is measured or the Sector changes.
     */
    public void onSurfaceSizeChanged(int width, int height) {
        if (width <= 0 || height <= 0) return;
        rebuildBackdropShaders(width, height);
    }

    private void rebuildBackdropShaders(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (width == shaderWidth && height == shaderHeight
                && backdropAccentColor == worldAccentColor) return;
        shaderWidth = width;
        shaderHeight = height;
        backdropAccentColor = worldAccentColor;
        backdropPaint.setShader(new LinearGradient(0f, 0f, width, height,
                new int[]{Color.rgb(5, 7, 18), Color.rgb(10, 12, 31),
                        Color.rgb(7, 15, 29)}, null, Shader.TileMode.CLAMP));
        backdropAuraPaint.setShader(new RadialGradient(width * 0.50f, height * 0.43f,
                Math.max(width, height) * 0.72f,
                new int[]{Color.argb(58, Color.red(worldAccentColor),
                        Color.green(worldAccentColor), Color.blue(worldAccentColor)),
                        Color.argb(18, 19, 58, 86), Color.argb(0, 13, 23, 48)},
                null, Shader.TileMode.CLAMP));
    }

    /** Main render entry point. Must be called on the UI thread. */
    public void draw(@NonNull Canvas canvas,
                     @NonNull GameEngine engine,
                     @Nullable GhostTimeline ghost,
                     @Nullable TouchInputAdapter input) {
        drawBackdrop(canvas);
        if (BuildConfig.DEBUG) debugDrawCount++;
        if (level == null) {
            reportDiagnostic("LEVEL_MISSING");
            drawDebugOverlay(canvas, "LEVEL_MISSING");
            return;
        }
        if (!viewport.isReady()) {
            reportDiagnostic("VIEWPORT_NOT_READY");
            drawDebugOverlay(canvas, "VIEWPORT_NOT_READY");
            return;
        }
        if (viewportRevision != viewport.revision()) rebuildViewportGeometry();
        if (boundaryPath.isEmpty()) {
            reportDiagnostic("GEOMETRY_EMPTY");
            drawDebugOverlay(canvas, "GEOMETRY_EMPTY");
            return;
        }

        // The scene is layered like a physical game board: shadowed chassis,
        // luminous wall bevels, inlaid floor detail, then live gameplay actors.
        drawBoard(canvas);
        drawSafeZones(canvas);
        drawGates(canvas, engine);
        drawObjectives(canvas, engine);
        drawStartAndGoal(canvas);
        drawTrail(canvas, engine);
        drawGhost(canvas, engine, ghost);
        drawMarker(canvas, engine, input);
        drawTerminalMarker(canvas, engine);
        drawMotionEffects(canvas, engine);
        reportDiagnostic("RENDERER_ACTIVE");
        drawDebugOverlay(canvas, "RENDERER_ACTIVE");
    }

    private void drawBackdrop(Canvas canvas) {
        int width = Math.max(1, canvas.getWidth());
        int height = Math.max(1, canvas.getHeight());
        fullScreenRect.set(0f, 0f, width, height);
        canvas.drawRect(fullScreenRect, backdropPaint);
        canvas.drawRect(fullScreenRect, backdropAuraPaint);
        float phase = reducedMotion ? 0f : presentationTimeNanos * 0.000000001f;
        for (int i = 0; i < STAR_X.length; i++) {
            float twinkle = reducedMotion ? 0.72f
                    : 0.42f + 0.58f * (0.5f + 0.5f
                    * (float) Math.sin(phase * (0.72f + (i % 4) * 0.13f) + i * 1.71f));
            starPaint.setAlpha(Math.round(28f + twinkle * 86f));
            float radius = (i % 5 == 0 ? 1.65f : 0.9f) * (width / 420f);
            canvas.drawCircle(STAR_X[i] * width, STAR_Y[i] * height,
                    Math.max(0.65f, radius), starPaint);
        }
        starPaint.setAlpha(24);
        float ringRadius = Math.min(width, height) * 0.34f;
        canvas.drawCircle(width * 0.50f, height * 0.52f, ringRadius, starPaint);
        canvas.drawCircle(width * 0.50f, height * 0.52f, ringRadius * 1.08f, starPaint);
    }

    private void drawBoard(Canvas canvas) {
        if (boundaryPath.isEmpty()) return;
        float scale = (float) viewport.scale();
        float wallWidth = Math.max(2.2f, scale * 15.0f);
        canvas.save();
        canvas.translate(0f, Math.max(2f, scale * 13f));
        boardShadowPaint.setAlpha(195);
        canvas.drawPath(boundaryPath, boardShadowPaint);
        canvas.restore();

        // The region fill is the exact collision-authoritative polygon (holes
        // use EVEN_ODD); the heavy stroke is only its visible architectural wall.
        canvas.drawPath(boundaryPath, boardPaint);
        canvas.save();
        canvas.clipPath(boundaryPath);
        if (!highContrast && boardTextureShader != null) {
            boardTexturePaint.setAlpha(reducedEffects ? 28 : 66);
            canvas.drawRect(boardScreenRect, boardTexturePaint);
        }
        if (!highContrast && !reducedEffects) canvas.drawRect(boardScreenRect, boardAuraPaint);
        drawSubtleGrid(canvas);
        drawFloorDetails(canvas);
        canvas.restore();

        boundaryOuterPaint.setStrokeWidth(wallWidth * 2.35f);
        boundaryMidPaint.setStrokeWidth(wallWidth * 1.72f);
        canvas.drawPath(boundaryPath, boundaryOuterPaint);
        canvas.drawPath(boundaryPath, boundaryMidPaint);
        wallShadowPaint.setStrokeWidth(wallWidth * 1.72f);
        wallBasePaint.setStrokeWidth(wallWidth * 1.18f);
        wallGlowPaint.setStrokeWidth(Math.max(1.2f, wallWidth * 0.42f));
        wallCorePaint.setStrokeWidth(Math.max(1.0f, wallWidth * 0.105f));
        canvas.drawPath(boundaryPath, wallShadowPaint);
        canvas.drawPath(boundaryPath, wallBasePaint);
        if (!reducedEffects) canvas.drawPath(boundaryPath, wallGlowPaint);
        canvas.drawPath(boundaryPath, wallCorePaint);
        drawBoundaryHardware(canvas);
        drawEnergyPulses(canvas);
    }

    private void drawFloorDetails(Canvas canvas) {
        if (highContrast || reducedEffects) return;
        float scale = (float) viewport.scale();
        float left = viewport.contentLeft();
        float right = viewport.contentRight();
        float top = viewport.contentTop();
        float bottom = viewport.contentBottom();
        float spacing = Math.max(46f, scale * 150f);
        // A restrained circuit-board inlay; it is clipped to the real board,
        // never used to imply an additional route or collision surface.
        for (float y = top + spacing * 0.5f; y < bottom; y += spacing) {
            float x = left + spacing * 0.5f;
            while (x < right) {
                float length = spacing * 0.22f;
                canvas.drawLine(x, y, x + length, y, floorDetailPaint);
                canvas.drawLine(x + length, y, x + length, y + length * 0.42f,
                        floorDetailPaint);
                canvas.drawCircle(x + length, y + length * 0.42f,
                        Math.max(1f, scale * 2.0f), floorNodePaint);
                x += spacing * 2.0f;
            }
        }
    }

    private void drawBoundaryHardware(Canvas canvas) {
        if (highContrast || level == null) return;
        List<Vec2> points = level.outerBoundary();
        float scale = (float) viewport.scale();
        float radius = Math.max(2.1f, scale * 5.8f);
        rivetPaint.setStrokeWidth(Math.max(1f, radius * 0.28f));
        for (int i = 0; i < points.size(); i++) {
            Vec2 point = points.get(i);
            float x = viewport.worldToScreenX(point.x());
            float y = viewport.worldToScreenY(point.y());
            canvas.drawCircle(x, y, radius, rivetPaint);
            canvas.drawCircle(x, y, Math.max(0.9f, radius * 0.23f), rivetCorePaint);
        }
        for (List<Vec2> hole : level.holeBoundaries()) {
            for (int i = 0; i < hole.size(); i++) {
                Vec2 point = hole.get(i);
                float x = viewport.worldToScreenX(point.x());
                float y = viewport.worldToScreenY(point.y());
                canvas.drawCircle(x, y, radius * 0.72f, rivetPaint);
            }
        }
    }

    private void drawEnergyPulses(Canvas canvas) {
        if (reducedEffects || reducedMotion || boundaryPerimeterWorld <= 0.0 || level == null) return;
        double timeSeconds = presentationTimeNanos * 0.000000001;
        List<Vec2> points = level.outerBoundary();
        float scale = (float) viewport.scale();
        for (int pulse = 0; pulse < 3; pulse++) {
            double distance = (timeSeconds * 62.0
                    + boundaryPerimeterWorld * pulse / 3.0) % boundaryPerimeterWorld;
            for (int i = 0; i < points.size(); i++) {
                Vec2 a = points.get(i);
                Vec2 b = points.get((i + 1) % points.size());
                double dx = b.x() - a.x();
                double dy = b.y() - a.y();
                double length = i < boundarySegmentLengths.length
                        ? boundarySegmentLengths[i] : 0.0;
                if (distance <= length || i == points.size() - 1) {
                    double t = length <= 0.0001 ? 0.0 : Math.min(1.0, distance / length);
                    float x = viewport.worldToScreenX(a.x() + dx * t);
                    float y = viewport.worldToScreenY(a.y() + dy * t);
                    float r = Math.max(2f, scale * 5.2f);
                    energyPulseGlowPaint.setAlpha(52);
                    energyPulsePaint.setAlpha(205);
                    canvas.drawCircle(x, y, r * 2.4f, energyPulseGlowPaint);
                    canvas.drawCircle(x, y, r, energyPulsePaint);
                    break;
                }
                distance -= length;
            }
        }
    }

    private void drawSubtleGrid(Canvas canvas) {
        if (highContrast || boundaryPath.isEmpty()) return;
        float scale = (float) viewport.scale();
        float minor = Math.max(22f, scale * 62f);
        float major = minor * 4f;
        float left = viewport.contentLeft();
        float top = viewport.contentTop();
        float right = viewport.contentRight();
        float bottom = viewport.contentBottom();
        gridPaint.setAlpha(reducedEffects ? 16 : 33);
        for (float x = left; x <= right; x += minor) {
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }
        for (float y = top; y <= bottom; y += minor) {
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        floorDetailPaint.setAlpha(reducedEffects ? 0 : 30);
        Paint majorPaint = gridPaint;
        majorPaint.setAlpha(49);
        majorPaint.setStrokeWidth(Math.max(1f, scale * 1.15f));
        for (float x = left; x <= right; x += major) {
            canvas.drawLine(x, top, x, bottom, majorPaint);
        }
        for (float y = top; y <= bottom; y += major) {
            canvas.drawLine(left, y, right, y, majorPaint);
        }
        majorPaint.setAlpha(reducedEffects ? 16 : 33);
        majorPaint.setStrokeWidth(1f);
    }

    private void reportDiagnostic(@NonNull String state) {
        if (!BuildConfig.DEBUG) return;
        if (state.equals(lastDiagnosticState)
                && lastDiagnosticRevision == viewport.revision()) return;
        lastDiagnosticState = state;
        lastDiagnosticRevision = viewport.revision();
        String message = state + " draw=" + debugDrawCount
                + " viewportReady=" + viewport.isReady()
                + " revision=" + viewport.revision()
                + " scale=" + viewport.scale();
        if (level != null) {
            com.neonmaze.core.GeometryCollision.Aabb bounds = level.worldBounds();
            message += " level=" + level.levelId()
                    + " outerVertices=" + level.outerBoundary().size()
                    + " holes=" + level.holeBoundaries().size()
                    + " world=" + bounds.minX() + "," + bounds.minY()
                    + ".." + bounds.maxX() + "," + bounds.maxY();
            if (viewport.isReady()) {
                message += " origin=" + viewport.worldToScreenX(bounds.minX())
                        + "," + viewport.worldToScreenY(bounds.maxY());
            }
        }
        Log.d("NeonMazeRenderer", message);
    }

    private void drawDebugOverlay(@NonNull Canvas canvas, @NonNull String state) {
        if (!BuildConfig.DEBUG) return;
        if (!state.equals(lastDebugOverlayState) || debugDrawCount % 30L == 1L) {
            lastDebugOverlayState = state;
            debugLineOne = "DRAW " + debugDrawCount + " | " + state
                    + " | VP " + (viewport.isReady() ? "READY" : "WAIT")
                    + " r" + viewport.revision() + " s=" + viewport.scale();
            if (level == null) {
                debugLineTwo = "LEVEL missing | geometry unavailable";
                debugLineThree = "CONTENT unavailable";
            } else {
                com.neonmaze.core.GeometryCollision.Aabb bounds = level.worldBounds();
                debugLineTwo = level.levelId() + " | outer=" + level.outerBoundary().size()
                        + " | holes=" + level.holeBoundaries().size()
                        + " | world=" + bounds.minX() + "," + bounds.minY()
                        + ".." + bounds.maxX() + "," + bounds.maxY();
                debugLineThree = "content="
                        + viewport.contentLeft() + "," + viewport.contentTop() + ".."
                        + viewport.contentRight() + "," + viewport.contentBottom()
                        + " | origin=" + (viewport.isReady()
                        ? viewport.worldToScreenX(bounds.minX()) + ","
                        + viewport.worldToScreenY(bounds.maxY()) : "unavailable");
            }
        }
        float x = viewport.isReady() ? viewport.contentLeft() + 8f : 8f;
        float y = viewport.isReady() ? viewport.contentTop() + 18f : 18f;
        float width = Math.max(debugTextPaint.measureText(debugLineOne),
                Math.max(debugTextPaint.measureText(debugLineTwo),
                        debugTextPaint.measureText(debugLineThree))) + 12f;
        debugPanelRect.set(x - 4f, y - 13f, x + width, y + 39f);
        canvas.drawRoundRect(debugPanelRect, 4f, 4f, debugPanelPaint);
        canvas.drawText(debugLineOne, x + 2f, y, debugTextPaint);
        canvas.drawText(debugLineTwo, x + 2f, y + 15f, debugTextPaint);
        canvas.drawText(debugLineThree, x + 2f, y + 30f, debugTextPaint);
    }

    private void drawSafeZones(Canvas canvas) {
        if (safeZoneGeometry == null) return;
        for (ZoneGeometry geometry : safeZoneGeometry) geometry.draw(canvas, safeZonePaint);
    }

    private void drawGates(Canvas canvas, GameEngine engine) {
        if (gateGeometry == null) return;
        List<Gate> gates = level.gates();
        float scale = (float) viewport.scale();
        for (int i = 0; i < gates.size(); i++) {
            Gate gate = gates.get(i);
            GateRenderState state = engine.getGateRenderState(gate.id());
            Paint fill;
            int accent;
            switch (state) {
                case OPEN: fill = gateOpenPaint; accent = COLOR_MINT; break;
                case WARNING: fill = gateWarningPaint; accent = COLOR_AMBER; break;
                case LOCKED: fill = gateLockedPaint; accent = COLOR_VIOLET; break;
                case CLOSED:
                default: fill = gateClosedPaint; accent = COLOR_NEUTRAL; break;
            }
            gateGeometry[i].draw(canvas, fill);
            if (gate.hasBarrier()) {
                float x1 = viewport.worldToScreenX(gate.barrierStart().x());
                float y1 = viewport.worldToScreenY(gate.barrierStart().y());
                float x2 = viewport.worldToScreenX(gate.barrierEnd().x());
                float y2 = viewport.worldToScreenY(gate.barrierEnd().y());
                float dx = x2 - x1;
                float dy = y2 - y1;
                float length = Math.max(1f, (float) Math.sqrt(dx * dx + dy * dy));
                float nx = -dy / length;
                float ny = dx / length;
                float width = Math.max(2.4f, scale * 7.5f);
                gateGlowPaint.setColor(accent);
                gateGlowPaint.setAlpha(reducedEffects ? 0 : 76);
                gateGlowPaint.setStrokeWidth(width * 3.2f);
                gateRailPaint.setStrokeWidth(width * 1.55f);
                gateRailPaint.setColor(Color.rgb(16, 27, 43));
                gateEnergyPaint.setColor(highContrast ? Color.WHITE : accent);
                gateEnergyPaint.setStrokeWidth(Math.max(2f, width * 0.62f));
                gateSegmentPaint.setColor(highContrast ? Color.WHITE : COLOR_WHITE);
                gateSegmentPaint.setStrokeWidth(Math.max(1f, width * 0.12f));
                if (!reducedEffects) canvas.drawLine(x1, y1, x2, y2, gateGlowPaint);
                canvas.drawLine(x1, y1, x2, y2, gateRailPaint);
                if (state == GateRenderState.OPEN) {
                    // Open gates part the beam into two energized pylons.
                    canvas.drawLine(x1, y1, x1 + dx * 0.40f, y1 + dy * 0.40f, gateEnergyPaint);
                    canvas.drawLine(x1 + dx * 0.60f, y1 + dy * 0.60f, x2, y2, gateEnergyPaint);
                    gateSegmentPaint.setAlpha(135);
                } else {
                    canvas.drawLine(x1, y1, x2, y2, gateEnergyPaint);
                    gateSegmentPaint.setAlpha(190);
                }
                if (!reducedMotion && !reducedEffects && state != GateRenderState.OPEN) {
                    float travel = (presentationTimeNanos * 0.000000001f * 0.48f
                            + i * 0.31f) % 1f;
                    float energyX = x1 + dx * travel;
                    float energyY = y1 + dy * travel;
                    float pulseRadius = Math.max(1.8f, width * 0.34f);
                    energyPulseGlowPaint.setColor(accent);
                    energyPulseGlowPaint.setAlpha(state == GateRenderState.WARNING ? 84 : 54);
                    energyPulsePaint.setColor(Color.WHITE);
                    energyPulsePaint.setAlpha(235);
                    canvas.drawCircle(energyX, energyY, pulseRadius * 2.5f, energyPulseGlowPaint);
                    canvas.drawCircle(energyX, energyY, pulseRadius, energyPulsePaint);
                }
                // Mechanical bridge teeth make the barrier read as a gate, not a line.
                for (int tooth = 1; tooth <= 5; tooth++) {
                    float t = tooth / 6f;
                    float cx = x1 + dx * t;
                    float cy = y1 + dy * t;
                    float half = width * (state == GateRenderState.LOCKED ? 0.80f : 0.55f);
                    canvas.drawLine(cx - nx * half, cy - ny * half,
                            cx + nx * half, cy + ny * half, gateSegmentPaint);
                }
                float cap = Math.max(3f, width * 0.74f);
                canvas.drawCircle(x1, y1, cap, gateRailPaint);
                canvas.drawCircle(x2, y2, cap, gateRailPaint);
                canvas.drawCircle(x1, y1, Math.max(1.2f, cap * 0.28f), gateEnergyPaint);
                canvas.drawCircle(x2, y2, Math.max(1.2f, cap * 0.28f), gateEnergyPaint);
            }
            drawGateStateGlyph(canvas, gateGeometry[i], state);
        }
    }

    private void drawObjectives(Canvas canvas, GameEngine engine) {
        if (objectiveGeometry == null) return;
        List<Objective> objectives = level.objectives();
        float phase = reducedMotion ? 0f : presentationTimeNanos * 0.000000001f;
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            boolean collected = engine.isObjectiveCollected(objective.id());
            float x = objectiveGeometry[i].screenCenterX();
            float y = objectiveGeometry[i].screenCenterY();
            float radius = objectiveGeometry[i].glyphRadiusPx();
            int accent = collected ? COLOR_MINT
                    : objective.grantsKeyId() != null ? COLOR_VIOLET
                    : objective.type() == ObjectiveType.OPTIONAL ? COLOR_VIOLET : COLOR_AMBER;
            objectiveGlowPaint.setColor(accent);
            objectiveGlowPaint.setAlpha(reducedEffects ? 0 : collected ? 34 : 58);
            canvas.drawCircle(x, y, radius * 1.48f, objectiveGlowPaint);
            Paint outline = collected ? objectiveCollectedPaint
                    : objective.type() == ObjectiveType.OPTIONAL
                    ? objectiveOptionalPaint : objectiveMandatoryPaint;
            objectiveGeometry[i].draw(canvas, outline);
            if (!collected) {
                float orbit = radius * (1.26f + (reducedMotion ? 0f
                        : 0.04f * (float) Math.sin(phase * 2.2f + i)));
                objectiveOrbitPaint.setColor(accent);
                objectiveOrbitPaint.setAlpha(highContrast ? 255 : 115);
                objectiveOrbitPaint.setStrokeWidth(Math.max(1f, radius * 0.055f));
                canvas.drawCircle(x, y, orbit, objectiveOrbitPaint);
                if (!reducedEffects && !reducedMotion) {
                    float dotAngle = phase * 0.92f + i * 1.8f;
                    canvas.drawCircle(x + (float) Math.cos(dotAngle) * orbit,
                            y + (float) Math.sin(dotAngle) * orbit,
                            Math.max(1.2f, radius * 0.095f), objectiveGlowPaint);
                }
            }
            drawObjectiveGlyph(canvas, objectiveGeometry[i], objective, collected);
        }
    }

    private void drawStartAndGoal(Canvas canvas) {
        float phase = reducedMotion ? 0f : presentationTimeNanos * 0.000000001f;
        float pulse = reducedMotion ? 1f : 0.5f + 0.5f * (float) Math.sin(phase * 2.0f);
        float ringWidth = Math.max(2f, (float) (viewport.scale() * 5.5));
        startRingPaint.setStrokeWidth(ringWidth);
        goalRingPaint.setStrokeWidth(ringWidth);
        if (startGeometry != null) {
            float x = startGeometry.screenCenterX();
            float y = startGeometry.screenCenterY();
            float radius = startGeometry.glyphRadiusPx();
            portalGlowPaint.setColor(COLOR_MINT);
            portalGlowPaint.setAlpha(reducedEffects ? 0 : Math.round(24f + pulse * 28f));
            canvas.drawCircle(x, y, radius * 1.52f, portalGlowPaint);
            startGeometry.draw(canvas, startPaint);
            startGeometry.draw(canvas, startRingPaint);
            drawStartGlyph(canvas, startGeometry);
            drawZoneLabel(canvas, x, y - radius * 1.66f, radius, startLabel, COLOR_MINT);
            portalOrbitPaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
            portalOrbitPaint.setAlpha(175);
            portalOrbitPaint.setStrokeWidth(Math.max(1.2f, ringWidth * 0.28f));
            markerOrbitRect.set(x - radius * 1.24f, y - radius * 1.24f,
                    x + radius * 1.24f, y + radius * 1.24f);
            canvas.drawArc(markerOrbitRect, reducedMotion ? 25f : phase * 28f,
                    82f, false, portalOrbitPaint);
        }
        if (goalGeometry != null) {
            float x = goalGeometry.screenCenterX();
            float y = goalGeometry.screenCenterY();
            float radius = goalGeometry.glyphRadiusPx();
            portalGlowPaint.setColor(COLOR_AMBER);
            portalGlowPaint.setAlpha(reducedEffects ? 0 : Math.round(24f + pulse * 38f));
            canvas.drawCircle(x, y, radius * 1.60f, portalGlowPaint);
            goalGeometry.draw(canvas, goalPaint);
            goalGeometry.draw(canvas, goalRingPaint);
            drawGoalGlyph(canvas, goalGeometry);
            drawZoneLabel(canvas, x, y - radius * 1.72f, radius, goalLabel, COLOR_AMBER);
            portalOrbitPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
            portalOrbitPaint.setAlpha(180);
            portalOrbitPaint.setStrokeWidth(Math.max(1.2f, ringWidth * 0.26f));
            markerOrbitRect.set(x - radius * 1.30f, y - radius * 1.30f,
                    x + radius * 1.30f, y + radius * 1.30f);
            canvas.drawArc(markerOrbitRect, reducedMotion ? 205f : -phase * 24f,
                    74f, false, portalOrbitPaint);
        }
    }

    private void drawZoneLabel(Canvas canvas, float centerX, float centerY,
                               float radius, String label, int accent) {
        if (label == null || label.isEmpty()) return;
        float textSize = Math.max(7.5f, Math.min(10.5f, radius * 0.55f));
        zoneLabelTextPaint.setTextSize(textSize);
        zoneLabelTextPaint.setColor(highContrast ? Color.WHITE : COLOR_WHITE);
        zoneLabelFillPaint.setAlpha(highContrast ? 245 : 218);
        zoneLabelStrokePaint.setColor(highContrast ? Color.WHITE : accent);
        zoneLabelStrokePaint.setAlpha(220);
        float textWidth = zoneLabelTextPaint.measureText(label);
        float horizontalPad = Math.max(4f, textSize * 0.52f);
        float verticalPad = Math.max(2.2f, textSize * 0.30f);
        float height = textSize + verticalPad * 2f;
        zoneLabelRect.set(centerX - textWidth * 0.5f - horizontalPad,
                centerY - height * 0.5f,
                centerX + textWidth * 0.5f + horizontalPad,
                centerY + height * 0.5f);
        float corner = Math.max(2f, height * 0.22f);
        canvas.drawRoundRect(zoneLabelRect, corner, corner, zoneLabelFillPaint);
        canvas.drawRoundRect(zoneLabelRect, corner, corner, zoneLabelStrokePaint);
        canvas.drawText(label, centerX, centerY + textSize * 0.34f, zoneLabelTextPaint);
    }

    private void drawStartGlyph(Canvas canvas, ZoneGeometry geometry) {
        float x = geometry.screenCenterX();
        float y = geometry.screenCenterY();
        float radius = Math.max(2.8f, geometry.glyphRadiusPx() * 0.26f);
        startGlyphPaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
        goalGlyphPaint.setColor(highContrast ? Color.WHITE : COLOR_MINT_CORE);
        goalGlyphPaint.setStrokeWidth(Math.max(1.5f, radius * 0.28f));
        canvas.drawCircle(x, y, radius * 0.70f, startGlyphPaint);
        canvas.drawCircle(x, y, radius * 1.22f, goalGlyphPaint);
        float arm = Math.max(3.2f, geometry.glyphRadiusPx() * 0.60f);
        canvas.drawLine(x - arm, y, x - radius * 1.65f, y, goalGlyphPaint);
        canvas.drawLine(x + radius * 1.65f, y, x + arm, y, goalGlyphPaint);
        canvas.drawLine(x, y - arm, x, y - radius * 1.65f, goalGlyphPaint);
        canvas.drawLine(x, y + radius * 1.65f, x, y + arm, goalGlyphPaint);
        symbolPath.reset();
        polygon(symbolPath, x, y, Math.max(2f, radius * 1.78f), 4,
                (float) (-Math.PI / 4.0));
        canvas.drawPath(symbolPath, goalGlyphPaint);
    }

    private void drawGoalGlyph(Canvas canvas, ZoneGeometry geometry) {
        float x = geometry.screenCenterX();
        float y = geometry.screenCenterY();
        float radius = Math.max(3.2f, geometry.glyphRadiusPx() * 0.52f);
        goalGlyphPaint.setColor(highContrast ? Color.WHITE : COLOR_WHITE);
        startGlyphPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
        goalGlyphPaint.setStrokeWidth(Math.max(1.5f, radius * 0.18f));
        symbolPath.reset();
        polygon(symbolPath, x, y, radius, 4, (float) (Math.PI / 4.0));
        canvas.drawPath(symbolPath, goalGlyphPaint);
        canvas.drawCircle(x, y, Math.max(1.8f, radius * 0.48f), startGlyphPaint);
        canvas.drawCircle(x, y, Math.max(1f, radius * 0.17f), goalGlyphPaint);
        float tick = radius * 1.32f;
        canvas.drawLine(x - tick, y, x - radius * 0.88f, y, goalGlyphPaint);
        canvas.drawLine(x + radius * 0.88f, y, x + tick, y, goalGlyphPaint);
        canvas.drawLine(x, y - tick, x, y - radius * 0.88f, goalGlyphPaint);
        canvas.drawLine(x, y + radius * 0.88f, x, y + tick, goalGlyphPaint);
    }

    private void drawObjectiveGlyph(Canvas canvas, ZoneGeometry geometry,
                                    Objective objective, boolean collected) {
        float x = geometry.screenCenterX();
        float y = geometry.screenCenterY();
        float radius = Math.max(2.6f, geometry.glyphRadiusPx() * 0.30f);
        objectiveGlyphPaint.setColor(highContrast ? Color.WHITE
                : collected ? COLOR_MINT_CORE
                : objective.grantsKeyId() != null ? COLOR_VIOLET
                : objective.type() == ObjectiveType.OPTIONAL ? COLOR_VIOLET : COLOR_AMBER);
        objectiveGlyphPaint.setStrokeWidth(Math.max(1.35f, radius * 0.28f));
        symbolPath.reset();
        if (collected) {
            symbolPath.moveTo(x - radius * 0.85f, y);
            symbolPath.lineTo(x - radius * 0.18f, y + radius * 0.64f);
            symbolPath.lineTo(x + radius, y - radius * 0.76f);
            canvas.drawPath(symbolPath, objectiveGlyphPaint);
        } else if (objective.grantsKeyId() != null) {
            canvas.drawCircle(x - radius * 0.52f, y, radius * 0.43f, objectiveGlyphPaint);
            canvas.drawLine(x - radius * 0.10f, y, x + radius, y, objectiveGlyphPaint);
            canvas.drawLine(x + radius * 0.50f, y, x + radius * 0.50f,
                    y + radius * 0.42f, objectiveGlyphPaint);
            canvas.drawLine(x + radius * 0.88f, y, x + radius * 0.88f,
                    y + radius * 0.30f, objectiveGlyphPaint);
        } else if (objective.type() == ObjectiveType.OPTIONAL) {
            canvas.drawCircle(x, y, radius * 0.62f, objectiveGlyphPaint);
            canvas.drawCircle(x, y, Math.max(1f, radius * 0.14f), objectiveGlyphPaint);
        } else {
            symbolPath.moveTo(x, y - radius);
            symbolPath.lineTo(x + radius, y);
            symbolPath.lineTo(x, y + radius);
            symbolPath.lineTo(x - radius, y);
            symbolPath.close();
            canvas.drawPath(symbolPath, objectiveGlyphPaint);
        }
    }

    private void drawGateStateGlyph(Canvas canvas, ZoneGeometry geometry,
                                   GateRenderState state) {
        float x = geometry.screenCenterX();
        float y = geometry.screenCenterY();
        float radius = Math.max(3f, geometry.glyphRadiusPx() * 0.25f);
        gateGlyphPaint.setColor(highContrast ? Color.WHITE
                : state == GateRenderState.WARNING ? COLOR_AMBER
                : state == GateRenderState.LOCKED ? COLOR_VIOLET : COLOR_WHITE);
        gateGlyphPaint.setStrokeWidth(Math.max(1.5f, radius * 0.27f));
        if (state == GateRenderState.LOCKED) {
            symbolRect.set(x - radius * 0.62f, y - radius * 0.08f,
                    x + radius * 0.62f, y + radius * 0.86f);
            canvas.drawRoundRect(symbolRect, radius * 0.12f, radius * 0.12f, gateGlyphPaint);
            symbolRect.set(x - radius * 0.40f, y - radius * 0.78f,
                    x + radius * 0.40f, y + radius * 0.12f);
            canvas.drawArc(symbolRect, 180f, 180f, false, gateGlyphPaint);
        } else if (state == GateRenderState.WARNING) {
            canvas.drawLine(x, y - radius, x, y + radius * 0.36f, gateGlyphPaint);
            canvas.drawCircle(x, y + radius * 0.78f,
                    Math.max(1f, radius * 0.13f), startGlyphPaint);
        } else if (state == GateRenderState.OPEN) {
            canvas.drawLine(x - radius, y - radius * 0.55f,
                    x + radius * 0.25f, y, gateGlyphPaint);
            canvas.drawLine(x + radius * 0.25f, y,
                    x - radius, y + radius * 0.55f, gateGlyphPaint);
            canvas.drawLine(x + radius * 0.30f, y - radius * 0.55f,
                    x + radius, y, gateGlyphPaint);
            canvas.drawLine(x + radius, y,
                    x + radius * 0.30f, y + radius * 0.55f, gateGlyphPaint);
        } else {
            canvas.drawLine(x - radius, y - radius * 0.35f,
                    x + radius, y - radius * 0.35f, gateGlyphPaint);
            canvas.drawLine(x - radius, y + radius * 0.35f,
                    x + radius, y + radius * 0.35f, gateGlyphPaint);
        }
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
        float stroke = Math.max(2.2f, (float) (viewport.scale() * 8.0));
        trailShadowPaint.setStrokeWidth(stroke * 2.8f);
        trailGlowPaint.setStrokeWidth(reducedEffects ? stroke : stroke * 3.0f);
        trailMidPaint.setStrokeWidth(stroke * 1.5f);
        trailCorePaint.setStrokeWidth(stroke * 0.72f);
        canvas.drawPath(trailPath, trailShadowPaint);
        if (!reducedEffects) canvas.drawPath(trailPath, trailGlowPaint);
        canvas.drawPath(trailPath, trailMidPaint);
        canvas.drawPath(trailPath, trailCorePaint);
        if (currentSize > 1) {
            Vec2 tip = trail.get(currentSize - 1);
            float x = viewport.worldToScreenX(tip.x());
            float y = viewport.worldToScreenY(tip.y());
            float bead = Math.max(1.5f, stroke * 0.44f);
            energyPulseGlowPaint.setAlpha(reducedEffects ? 0 : 72);
            energyPulsePaint.setAlpha(220);
            canvas.drawCircle(x, y, bead * 2.8f, energyPulseGlowPaint);
            canvas.drawCircle(x, y, bead, energyPulsePaint);
        }
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
        ghostPaint.setAlpha(92);
        canvas.drawCircle(x, y, radius * 1.8f, ghostPaint);
        canvas.drawCircle(x, y, radius, ghostPaint);
        markerRimPaint.setColor(COLOR_VIOLET);
        markerRimPaint.setAlpha(220);
        markerRimPaint.setStrokeWidth(Math.max(1.4f, radius * 0.16f));
        canvas.drawCircle(x, y, radius * 1.32f, markerRimPaint);
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
            float phase = reducedMotion ? 0f : presentationTimeNanos * 0.000000001f;
            if (!reducedEffects) {
                markerGlowPaint.setAlpha(88);
                canvas.drawCircle(x, y, radius * 2.55f, markerGlowPaint);
                markerOrbitRect.set(x - radius * 1.78f, y - radius * 1.78f,
                        x + radius * 1.78f, y + radius * 1.78f);
                markerRimPaint.setColor(COLOR_MINT_CORE);
                markerRimPaint.setAlpha(155);
                markerRimPaint.setStrokeWidth(Math.max(1f, radius * 0.105f));
                canvas.drawArc(markerOrbitRect, reducedMotion ? -35f : phase * 82f,
                        126f, false, markerRimPaint);
                canvas.drawArc(markerOrbitRect, reducedMotion ? 145f : -phase * 66f + 180f,
                        62f, false, markerRimPaint);
            }
            markerGlowPaint.setAlpha(70);
            canvas.drawCircle(x, y + radius * 0.12f, radius * 1.12f, markerGlowPaint);
            markerCorePaint.setColor(Color.rgb(224, 255, 250));
            canvas.drawCircle(x, y, radius * 0.90f, markerCorePaint);
            markerRimPaint.setColor(Color.WHITE);
            markerRimPaint.setAlpha(235);
            markerRimPaint.setStrokeWidth(Math.max(1f, radius * 0.105f));
            canvas.drawCircle(x, y, radius * 0.94f, markerRimPaint);
            markerSpecularPaint.setAlpha(220);
            canvas.drawCircle(x - radius * 0.27f, y - radius * 0.30f,
                    Math.max(1f, radius * 0.22f), markerSpecularPaint);
            markerCorePaint.setColor(COLOR_WHITE);
            if (!"frame.none".equals(equippedFrameId)) {
                markerFramePaint.setStrokeWidth(Math.max(1.5f, radius * .16f));
                markerFramePath.reset();
                if ("frame.hex".equals(equippedFrameId)) {
                    for (int i = 0; i < 6; i++) {
                        double angle = Math.PI / 3.0 * i - Math.PI / 6.0;
                        float px = x + (float) Math.cos(angle) * radius * 1.75f;
                        float py = y + (float) Math.sin(angle) * radius * 1.75f;
                        if (i == 0) markerFramePath.moveTo(px, py); else markerFramePath.lineTo(px, py);
                    }
                    markerFramePath.close();
                    canvas.drawPath(markerFramePath, markerFramePaint);
                } else {
                    float frameScale = "frame.pulse".equals(equippedFrameId) ? 2.05f
                            : "frame.prism".equals(equippedFrameId) ? 1.85f : 1.62f;
                    canvas.drawCircle(x, y, radius * frameScale, markerFramePaint);
                    if ("frame.prism".equals(equippedFrameId)) {
                        markerFramePath.moveTo(x, y - radius * 2.2f);
                        markerFramePath.lineTo(x + radius * 2.2f, y);
                        markerFramePath.lineTo(x, y + radius * 2.2f);
                        markerFramePath.lineTo(x - radius * 2.2f, y);
                        markerFramePath.close();
                        canvas.drawPath(markerFramePath, markerFramePaint);
                    }
                }
            }
            drawMarkerEmblem(canvas, x, y, radius);
        }
    }

    private void drawMarkerEmblem(Canvas canvas, float x, float y, float radius) {
        if (equippedEmblemId == null || equippedEmblemId.isEmpty()) return;
        float r = Math.max(1.5f, radius * .58f);
        markerEmblemPaint.setStrokeWidth(Math.max(1.0f, radius * .13f));
        markerEmblemPath.reset();
        if (equippedEmblemId.endsWith("precision_learner")) {
            polygon(markerEmblemPath, x, y, r, 6, (float) (-Math.PI / 6.0));
        } else if (equippedEmblemId.endsWith("gate_runner")) {
            markerEmblemPath.moveTo(x - r, y - r * .65f);
            markerEmblemPath.lineTo(x, y + r * .2f);
            markerEmblemPath.lineTo(x + r, y - r * .65f);
            markerEmblemPath.moveTo(x - r, y + r * .35f);
            markerEmblemPath.lineTo(x, y + r * 1.2f);
            markerEmblemPath.lineTo(x + r, y + r * .35f);
        } else if (equippedEmblemId.endsWith("vault_finder")) {
            polygon(markerEmblemPath, x, y, r, 4, (float) (Math.PI / 4.0));
        } else if (equippedEmblemId.endsWith("core_complete")) {
            canvas.drawCircle(x, y, r * .8f, markerEmblemPaint);
            return;
        } else if (equippedEmblemId.endsWith("objective_hunter")) {
            markerEmblemPath.moveTo(x - r, y); markerEmblemPath.lineTo(x + r, y);
            markerEmblemPath.moveTo(x, y - r); markerEmblemPath.lineTo(x, y + r);
        } else if (equippedEmblemId.endsWith("personal_best")) {
            markerEmblemPath.moveTo(x - r, y - r * .7f);
            markerEmblemPath.lineTo(x - r * .45f, y + r * .65f);
            markerEmblemPath.lineTo(x + r * .45f, y + r * .65f);
            markerEmblemPath.lineTo(x + r, y - r * .7f);
            markerEmblemPath.lineTo(x + r * .7f, y + r);
            markerEmblemPath.lineTo(x - r * .7f, y + r);
            markerEmblemPath.close();
        } else {
            // First trace / Daily Spark use a compact five-point star.
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2.0 + i * Math.PI / 5.0;
                float pr = i % 2 == 0 ? r : r * .43f;
                float px = x + (float) Math.cos(angle) * pr;
                float py = y + (float) Math.sin(angle) * pr;
                if (i == 0) markerEmblemPath.moveTo(px, py); else markerEmblemPath.lineTo(px, py);
            }
            markerEmblemPath.close();
        }
        canvas.drawPath(markerEmblemPath, markerEmblemPaint);
    }

    private static void polygon(Path path, float cx, float cy, float radius,
                                int sides, float rotation) {
        for (int i = 0; i < sides; i++) {
            double angle = rotation + (2.0 * Math.PI * i / sides);
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
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


    /**
     * Draws short-lived, presentation-only feedback at the authoritative marker.
     * All Paint instances are reused and all coordinates are derived from the
     * shared viewport; this path does not allocate or query collision geometry.
     */
    private void drawMotionEffects(Canvas canvas, GameEngine engine) {
        Vec2 marker = engine.getMarkerPosition();
        if (marker == null) return;
        float x = viewport.worldToScreenX(marker.x());
        float y = viewport.worldToScreenY(marker.y());
        float markerRadius = Math.max(4f, (float) (level.playerRadius() * viewport.scale()));
        float phase = reducedMotion ? 0f : presentationTimeNanos * 0.000000001f;

        if (boundaryWarning) {
            float warningRadius = markerRadius * 2.45f;
            boundaryWarningPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
            boundaryWarningFillPaint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
            boundaryWarningPaint.setStrokeWidth(Math.max(2f, markerRadius * 0.19f));
            if (!reducedEffects) {
                boundaryWarningPaint.setAlpha(125);
                canvas.drawCircle(x, y, warningRadius * 1.28f, boundaryWarningPaint);
            }
            boundaryWarningPaint.setAlpha(245);
            canvas.drawCircle(x, y, warningRadius, boundaryWarningPaint);
            float glyphRadius = Math.max(4f, markerRadius * 0.62f);
            boundaryWarningGlyphPaint.setColor(highContrast ? Color.BLACK : COLOR_BACKGROUND);
            boundaryWarningGlyphPath.reset();
            boundaryWarningGlyphPath.moveTo(x, y - glyphRadius * 1.25f);
            boundaryWarningGlyphPath.lineTo(x + glyphRadius, y + glyphRadius * 0.85f);
            boundaryWarningGlyphPath.lineTo(x - glyphRadius, y + glyphRadius * 0.85f);
            boundaryWarningGlyphPath.close();
            canvas.drawPath(boundaryWarningGlyphPath, boundaryWarningFillPaint);
            canvas.drawPath(boundaryWarningGlyphPath, boundaryWarningPaint);
            canvas.drawCircle(x, y + glyphRadius * 0.24f,
                    Math.max(1f, glyphRadius * 0.12f), boundaryWarningGlyphPaint);
            canvas.drawLine(x, y - glyphRadius * 0.55f,
                    x, y + glyphRadius * 0.02f, boundaryWarningGlyphPaint);
        }

        if (motionCueType != GameMotionSystem.CUE_NONE && motionCueProgress < 1f) {
            int color;
            switch (motionCueType) {
                case GameMotionSystem.CUE_KEY: color = COLOR_VIOLET; break;
                case GameMotionSystem.CUE_GATE: color = COLOR_AMBER; break;
                case GameMotionSystem.CUE_BLOCKED: color = COLOR_CORAL; break;
                case GameMotionSystem.CUE_PAUSE: color = COLOR_NEUTRAL; break;
                default: color = COLOR_MINT_CORE; break;
            }
            float eased = motionCueProgress * motionCueProgress
                    * (3f - 2f * motionCueProgress);
            float radius = markerRadius * (1.65f + eased * 2.25f);
            motionCuePaint.setColor(color);
            motionCuePaint.setAlpha(Math.round((1f - motionCueProgress) * 205f));
            motionCuePaint.setStrokeWidth(Math.max(1.5f, markerRadius * 0.16f));
            canvas.drawCircle(x, y, radius, motionCuePaint);
            if (!reducedEffects) {
                float outer = markerRadius * (2.2f + eased * 3.6f);
                motionCuePaint.setAlpha(Math.round((1f - motionCueProgress) * 98f));
                canvas.drawCircle(x, y, outer, motionCuePaint);
                if (motionCueType == GameMotionSystem.CUE_KEY
                        || motionCueType == GameMotionSystem.CUE_OBJECTIVE) {
                    symbolPath.reset();
                    polygon(symbolPath, x, y, markerRadius * (1.4f + eased * 1.8f),
                            4, phase * 0.9f);
                    motionCuePaint.setAlpha(Math.round((1f - motionCueProgress) * 135f));
                    canvas.drawPath(symbolPath, motionCuePaint);
                }
                float particleDistance = markerRadius * (1.35f + eased * 2.35f);
                float particleSize = Math.max(1.1f, markerRadius * 0.12f);
                energyPulsePaint.setColor(color);
                energyPulsePaint.setAlpha(Math.round((1f - motionCueProgress) * 220f));
                for (int particle = 0; particle < 8; particle++) {
                    float angle = phase * 2.1f + particle * (float) (Math.PI / 4.0);
                    float spread = particleDistance * (0.84f + (particle % 3) * 0.12f);
                    float px = x + (float) Math.cos(angle) * spread;
                    float py = y + (float) Math.sin(angle) * spread;
                    canvas.drawCircle(px, py, particleSize * (particle % 2 == 0 ? 1.25f : 0.78f),
                            energyPulsePaint);
                }
            }
        }

        if (terminalMotionType != GameMotionSystem.TERMINAL_NONE
                && terminalMotionProgress < 1f) {
            boolean success = terminalMotionType == GameMotionSystem.TERMINAL_SUCCESS;
            int color = success ? COLOR_MINT_CORE : COLOR_CORAL;
            float eased = terminalMotionProgress * terminalMotionProgress
                    * (3f - 2f * terminalMotionProgress);
            float radius = markerRadius * (1.9f + eased * 4.0f);
            terminalMotionPaint.setColor(color);
            terminalMotionPaint.setStrokeWidth(Math.max(2f, markerRadius * 0.22f));
            terminalMotionPaint.setAlpha(Math.round((1f - terminalMotionProgress) * 220f));
            canvas.drawCircle(x, y, radius, terminalMotionPaint);
            if (!reducedEffects) {
                float secondProgress = Math.min(1f, terminalMotionProgress + 0.18f);
                float secondEased = secondProgress * secondProgress
                        * (3f - 2f * secondProgress);
                terminalMotionPaint.setAlpha(Math.round((1f - secondProgress) * 112f));
                canvas.drawCircle(x, y, markerRadius * (1.55f + secondEased * 3.1f),
                        terminalMotionPaint);
                // Success blooms into a four-point prism; failure resolves to a crisp break mark.
                symbolPath.reset();
                if (success) {
                    polygon(symbolPath, x, y, markerRadius * (1.6f + eased * 1.2f),
                            4, (float) (Math.PI / 4.0));
                    terminalMotionPaint.setStyle(Paint.Style.STROKE);
                    terminalMotionPaint.setAlpha(Math.round((1f - terminalMotionProgress) * 170f));
                    canvas.drawPath(symbolPath, terminalMotionPaint);
                    for (int ray = 0; ray < 8; ray++) {
                        float angle = (float) (ray * Math.PI / 4.0 - Math.PI / 8.0);
                        float inner = markerRadius * (1.18f + eased * 0.50f);
                        float outer = markerRadius * (2.30f + eased * 2.20f);
                        terminalMotionPaint.setAlpha(Math.round((1f - terminalMotionProgress)
                                * (ray % 2 == 0 ? 190f : 122f)));
                        canvas.drawLine(x + (float) Math.cos(angle) * inner,
                                y + (float) Math.sin(angle) * inner,
                                x + (float) Math.cos(angle) * outer,
                                y + (float) Math.sin(angle) * outer, terminalMotionPaint);
                    }
                    terminalMotionPaint.setStyle(Paint.Style.STROKE);
                } else {
                    float arm = markerRadius * (0.8f + eased * 1.4f);
                    canvas.drawLine(x - arm, y - arm, x + arm, y + arm, terminalMotionPaint);
                    canvas.drawLine(x - arm, y + arm, x + arm, y - arm, terminalMotionPaint);
                }
            }
        }
    }

    private final class ZoneGeometry {
        private final Zone zone;
        private final Path path = new Path();
        private final float centerWorldX;
        private final float centerWorldY;
        private final float glyphRadiusWorld;

        private ZoneGeometry(Zone zone) {
            this.zone = zone;
            Vec2 centroid = zone.centroid();
            centerWorldX = (float) centroid.x();
            centerWorldY = (float) centroid.y();
            if (zone.shape() == ZoneShape.CIRCLE) {
                glyphRadiusWorld = (float) zone.radius();
            } else {
                com.neonmaze.core.GeometryCollision.Aabb bounds = zone.bounds();
                glyphRadiusWorld = (float) (Math.min(bounds.maxX() - bounds.minX(),
                        bounds.maxY() - bounds.minY()) * 0.32);
            }
        }

        private float screenCenterX() { return viewport.worldToScreenX(centerWorldX); }
        private float screenCenterY() { return viewport.worldToScreenY(centerWorldY); }
        private float glyphRadiusPx() { return Math.max(2.5f, glyphRadiusWorld * (float) viewport.scale()); }

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
