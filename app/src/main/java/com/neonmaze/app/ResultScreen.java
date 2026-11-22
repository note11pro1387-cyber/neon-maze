package com.neonmaze.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.ReplaySystem;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.RunResult;
import com.neonmaze.core.ReplaySystem.TraceEvent;
import com.neonmaze.core.ReplaySystem.TraceSample;

import java.util.List;

/** Immutable Engine outcome presentation. Campaign rewards are never inferred by the view. */
public final class ResultScreen {
    public interface Actions {
        void onRetry();
        void onNext();
        void onCampaign();
        void onHome();
    }

    private final Activity activity;
    private final UiKit ui;
    private final LevelDefinition level;
    private final GameEngine.RunOutcome outcome;
    private final boolean savePending;
    private final boolean saveFailed;
    private final boolean newBest;
    private final long previousBestMs;
    private final String nextLevelId;
    private final Actions actions;

    public ResultScreen(@NonNull Activity activity, @NonNull UiKit ui,
                        @NonNull LevelDefinition level,
                        @NonNull GameEngine.RunOutcome outcome,
                        boolean savePending, boolean saveFailed, boolean newBest,
                        long previousBestMs, String nextLevelId, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.level = level;
        this.outcome = outcome;
        this.savePending = savePending;
        this.saveFailed = saveFailed;
        this.newBest = newBest;
        this.previousBestMs = Math.max(0L, previousBestMs);
        this.nextLevelId = nextLevelId;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        boolean completed = outcome.result() == RunResult.COMPLETED;
        boolean interrupted = outcome.result() == RunResult.INTERRUPTED;
        int title = completed ? R.string.result_title_success
                : interrupted ? R.string.result_title_interrupted : R.string.result_title_failure;
        int subtitle = completed ? R.string.result_subtitle_success
                : interrupted ? R.string.result_subtitle_interrupted : R.string.result_subtitle_failure;
        int accent = completed ? UiKit.MINT : interrupted ? UiKit.AMBER : UiKit.CORAL;
        ui.addPageHeader(page, activity.getString(R.string.screen_result_eyebrow), activity.getString(title),
                activity.getString(subtitle));
        LinearLayout summary = ui.card();
        summary.setBackground(ui.shape(UiKit.SURFACE_ELEVATED, accent, 20, 1));
        LinearLayout resultHeading = ui.row();
        UiKit.Icon outcomeIcon = completed ? UiKit.Icon.SUCCESS
                : interrupted ? UiKit.Icon.CLOCK : UiKit.Icon.FAILURE;
        resultHeading.addView(ui.icon(outcomeIcon, accent, 22, null),
                new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        android.widget.TextView heading = ui.text(levelTitle(), 13, accent, true);
        LinearLayout.LayoutParams headingParams = ui.wrap();
        headingParams.setMarginStart(ui.dp(8));
        resultHeading.addView(heading, headingParams);
        summary.addView(resultHeading, ui.matchWrap());
        ui.addText(summary, ui.formatTime(outcome.elapsedMs()), 32,
                UiKit.TEXT_PRIMARY, true, 8, 0);
        ui.addText(summary, activity.getString(R.string.result_elapsed),
                11, UiKit.TEXT_SECONDARY, false, 0, 0);
        ui.addCard(page, summary, 0);

        boolean campaign = outcome.mode() == ReplayMode.CAMPAIGN;
        if (campaign && completed) {
            LinearLayout rewards = ui.card();
            ui.addText(rewards, activity.getString(R.string.result_stars)
                    + "  " + stars(outcome.stars()), 16, UiKit.AMBER, true, 0, 0);
            ui.addText(rewards, activity.getString(R.string.result_score)
                    + "  " + ui.number(outcome.score()), 13, UiKit.TEXT_PRIMARY, false, 8, 0);
            if (outcome.assisted()) {
                ui.addText(rewards, activity.getString(R.string.result_assisted),
                        11, UiKit.AMBER, false, 8, 0);
            }
            if (newBest) {
                ui.addText(rewards, activity.getString(R.string.result_new_best),
                        12, UiKit.MINT, true, 8, 0);
                if (previousBestMs > 0L && outcome.elapsedMs() > 0L
                        && previousBestMs > outcome.elapsedMs()) {
                    ui.addText(rewards, activity.getString(R.string.result_best_improvement,
                                    ui.formatTime(previousBestMs - outcome.elapsedMs())),
                            11, UiKit.MINT, false, 4, 0);
                }
            }
            ui.addCard(page, rewards, 12);
        } else if (outcome.assisted()) {
            ui.addText(page, activity.getString(R.string.result_assisted),
                    12, UiKit.AMBER, false, 12, 0);
        }

        LinearLayout details = ui.card();
        metric(details, R.string.result_par, ui.formatTime(level.parTimeMs()));
        metric(details, R.string.result_objectives,
                outcome.collectedObjectiveIds().size() + " / " + level.objectives().size());
        if (!completed && !interrupted) {
            ui.addText(details, activity.getString(failureString(outcome.failureReason())),
                    13, UiKit.CORAL, true, 10, 0);
        }
        ui.addCard(page, details, 12);

        if (savePending) {
            ui.addText(page, activity.getString(R.string.result_save_pending),
                    12, UiKit.AMBER, false, 12, 0);
        } else if (saveFailed) {
            ui.addText(page, activity.getString(R.string.result_save_error),
                    12, UiKit.CORAL, false, 12, 0);
        }

        View pathReview = buildPathReview();
        if (pathReview != null) {
            ui.addSection(page, activity.getString(R.string.result_path_review), null);
            ui.addCard(page, pathReview, 0);
        } else if (!completed && !interrupted) {
            ui.addText(page, activity.getString(R.string.result_path_unavailable),
                    11, UiKit.TEXT_SECONDARY, false, 12, 0);
        }

        if (completed && campaign && nextLevelId != null) {
            ui.addButton(page, ui.button(activity.getString(R.string.result_next),
                    UiKit.ButtonStyle.PRIMARY, actions::onNext), true);
        }
        if (!interrupted) {
            ui.addButton(page, ui.button(activity.getString(R.string.result_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
        }
        ui.addButton(page, ui.button(activity.getString(R.string.result_campaign),
                UiKit.ButtonStyle.QUIET, actions::onCampaign), true);
        ui.addButton(page, ui.button(activity.getString(R.string.result_home),
                UiKit.ButtonStyle.QUIET, actions::onHome), true);
        return scroll;
    }

    private void metric(LinearLayout target, int labelId, String value) {
        LinearLayout row = ui.row();
        ui.addText(row, activity.getString(labelId), 12, UiKit.TEXT_SECONDARY, false, 0, 0);
        android.widget.TextView valueView = ui.text(value, 13, UiKit.TEXT_PRIMARY, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMarginStart(ui.dp(8));
        row.addView(valueView, p);
        target.addView(row, ui.matchWrap());
    }

    @Nullable
    private View buildPathReview() {
        ReplayTrace trace = outcome.trace();
        if (trace == null || trace.result() != RunResult.FAILED) return null;
        long failureTime = -1L;
        for (TraceEvent event : trace.events()) {
            if ("FAIL".equals(event.type())) { failureTime = event.tMs(); break; }
        }
        if (failureTime < 0L) return null;
        ReplaySystem.PathReview review = ReplaySystem.PathReview.from(trace,
                failureTime, 3_000L, 1_500L);
        if (review == null || review.window().size() < 2) return null;
        return new ReviewMiniMap(activity, ui, level, review.window());
    }

    private String levelTitle() {
        int[] names = {R.string.level_01_name, R.string.level_02_name,
                R.string.level_03_name, R.string.level_04_name, R.string.level_05_name};
        // IDs are a stable ordered Campaign contract; Tutorial is handled separately.
        String id = level.levelId();
        if (id.endsWith("awakening")) return activity.getString(names[0]);
        if (id.endsWith("precision-core")) return activity.getString(names[1]);
        if (id.endsWith("pulse-reactor")) return activity.getString(names[2]);
        if (id.endsWith("energy-vault")) return activity.getString(names[3]);
        if (id.endsWith("the-core")) return activity.getString(names[4]);
        return id;
    }

    private static int failureString(GameEngine.FailureReason reason) {
        if (reason == GameEngine.FailureReason.PATH_BREACH) return R.string.result_failed_path;
        if (reason == GameEngine.FailureReason.TIME_EXPIRED) return R.string.result_failed_time;
        return R.string.result_failed_incomplete;
    }

    private static String stars(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 3; i++) value.append(i < count ? "★" : "☆");
        return value.toString();
    }

    /** Small, cached failure trace preview; it never changes collision geometry. */
    private static final class ReviewMiniMap extends View {
        private final UiKit ui;
        private final LevelDefinition level;
        private final List<TraceSample> samples;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outerPath = new Path();
        private final Path holesPath = new Path();
        private final Path startPath = new Path();
        private final Path goalPath = new Path();
        private final Path safePath = new Path();
        private final Path tracePath = new Path();
        private float contentLeft;
        private float contentTop;
        private double scale = 1.0;

        ReviewMiniMap(Activity activity, UiKit ui, LevelDefinition level,
                      List<TraceSample> samples) {
            super(activity);
            this.ui = ui;
            this.level = level;
            this.samples = samples;
            setContentDescription(activity.getString(R.string.result_path_review));
            setFocusable(true);
            setMinimumHeight(ui.dp(184));
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            rebuildPaths(width, height);
        }

        private void rebuildPaths(int width, int height) {
            outerPath.reset();
            holesPath.reset();
            startPath.reset();
            goalPath.reset();
            safePath.reset();
            tracePath.reset();
            com.neonmaze.core.GeometryCollision.Aabb bounds = level.worldBounds();
            float pad = ui.dp(18);
            float availableWidth = Math.max(1f, width - pad * 2f);
            float availableHeight = Math.max(1f, height - pad * 2f);
            double sx = availableWidth / (bounds.maxX() - bounds.minX());
            double sy = availableHeight / (bounds.maxY() - bounds.minY());
            scale = Math.min(sx, sy);
            double drawnWidth = (bounds.maxX() - bounds.minX()) * scale;
            double drawnHeight = (bounds.maxY() - bounds.minY()) * scale;
            contentLeft = pad + (float) ((availableWidth - drawnWidth) * .5);
            contentTop = pad + (float) ((availableHeight - drawnHeight) * .5);

            appendPolygon(outerPath, level.outerBoundary(), bounds);
            for (List<com.neonmaze.core.GeometryCollision.Vec2> hole : level.holeBoundaries()) {
                appendPolygon(holesPath, hole, bounds);
            }
            appendZone(startPath, level.startZone(), bounds);
            appendZone(goalPath, level.goalZone(), bounds);
            for (com.neonmaze.core.LevelSystem.Zone safe : level.safeZones()) {
                appendZone(safePath, safe, bounds);
            }
            TraceSample previous = null;
            for (TraceSample sample : samples) {
                float x = screenX(sample.x(), bounds);
                float y = screenY(sample.y(), bounds);
                if (previous == null) tracePath.moveTo(x, y);
                else tracePath.lineTo(x, y);
                previous = sample;
            }
        }

        private void appendPolygon(Path target,
                                   List<com.neonmaze.core.GeometryCollision.Vec2> points,
                                   com.neonmaze.core.GeometryCollision.Aabb bounds) {
            if (points == null || points.size() < 3) return;
            for (int i = 0; i < points.size(); i++) {
                com.neonmaze.core.GeometryCollision.Vec2 point = points.get(i);
                float x = screenX(point.x(), bounds);
                float y = screenY(point.y(), bounds);
                if (i == 0) target.moveTo(x, y); else target.lineTo(x, y);
            }
            target.close();
        }

        private void appendZone(Path target, com.neonmaze.core.LevelSystem.Zone zone,
                                com.neonmaze.core.GeometryCollision.Aabb bounds) {
            if (zone.shape() == com.neonmaze.core.LevelSystem.ZoneShape.CIRCLE) {
                target.addCircle(screenX(zone.cx(), bounds), screenY(zone.cy(), bounds),
                        (float) (zone.radius() * scale), Path.Direction.CW);
            } else {
                appendPolygon(target, zone.polygon(), bounds);
            }
        }

        private float screenX(double worldX,
                              com.neonmaze.core.GeometryCollision.Aabb bounds) {
            return contentLeft + (float) ((worldX - bounds.minX()) * scale);
        }

        private float screenY(double worldY,
                              com.neonmaze.core.GeometryCollision.Aabb bounds) {
            return contentTop + (float) ((worldY - bounds.minY()) * scale);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(UiKit.SURFACE_ELEVATED);
            canvas.drawPath(outerPath, paint);

            paint.setColor(UiKit.BACKGROUND);
            canvas.drawPath(holesPath, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.5f));
            paint.setColor(UiKit.BORDER);
            canvas.drawPath(outerPath, paint);
            canvas.drawPath(holesPath, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(2f));
            paint.setColor(UiKit.MINT);
            canvas.drawPath(startPath, paint);
            paint.setColor(UiKit.VIOLET);
            canvas.drawPath(goalPath, paint);
            paint.setColor(UiKit.AMBER);
            paint.setStrokeWidth(ui.dp(1.5f));
            canvas.drawPath(safePath, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(3f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(UiKit.MINT);
            canvas.drawPath(tracePath, paint);

            if (!samples.isEmpty()) {
                TraceSample last = samples.get(samples.size() - 1);
                com.neonmaze.core.GeometryCollision.Aabb bounds = level.worldBounds();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(UiKit.CORAL);
                canvas.drawCircle(screenX(last.x(), bounds), screenY(last.y(), bounds),
                        ui.dp(5), paint);
            }
        }
    }
}
