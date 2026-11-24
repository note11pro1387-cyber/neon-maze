package com.neonmaze.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.ReplaySystem;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.GhostTimeline;
import com.neonmaze.core.ReplaySystem.RunResult;
import com.neonmaze.core.ReplaySystem.TraceEvent;
import com.neonmaze.core.ReplaySystem.TraceSample;

import java.util.List;

/** Immutable Engine outcome presentation. Campaign rewards are never inferred by the view. */
public final class ResultScreen {
    public interface Actions {
        void onRetry();
        void onRetrySave();
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
    private final boolean saveRetryAvailable;
    private final boolean saveSucceeded;
    private final boolean newBest;
    private final long previousBestMs;
    private final String nextLevelId;
    @Nullable private final GhostTimeline ghostComparison;
    private final Actions actions;

    public ResultScreen(@NonNull Activity activity, @NonNull UiKit ui,
                        @NonNull LevelDefinition level,
                        @NonNull GameEngine.RunOutcome outcome,
                        boolean savePending, boolean saveFailed,
                        boolean saveRetryAvailable, boolean saveSucceeded, boolean newBest,
                        long previousBestMs, String nextLevelId, Actions actions) {
        this(activity, ui, level, outcome, savePending, saveFailed, saveRetryAvailable,
                saveSucceeded, newBest, previousBestMs, nextLevelId, null, actions);
    }

    public ResultScreen(@NonNull Activity activity, @NonNull UiKit ui,
                        @NonNull LevelDefinition level,
                        @NonNull GameEngine.RunOutcome outcome,
                        boolean savePending, boolean saveFailed,
                        boolean saveRetryAvailable, boolean saveSucceeded, boolean newBest,
                        long previousBestMs, String nextLevelId,
                        @Nullable GhostTimeline ghostComparison, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.level = level;
        this.outcome = outcome;
        this.savePending = savePending;
        this.saveFailed = saveFailed;
        this.saveRetryAvailable = saveRetryAvailable;
        this.saveSucceeded = saveSucceeded;
        this.newBest = newBest;
        this.previousBestMs = Math.max(0L, previousBestMs);
        this.nextLevelId = nextLevelId;
        this.ghostComparison = ghostComparison;
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
            ui.addText(page, activity.getString(saveRetryAvailable
                            ? R.string.result_save_error : R.string.result_save_unavailable),
                    12, UiKit.CORAL, false, 12, 0);
            if (saveRetryAvailable) {
                ui.addButton(page, ui.button(activity.getString(R.string.result_save_retry),
                        UiKit.ButtonStyle.PRIMARY, actions::onRetrySave), true);
            }
        } else if (saveSucceeded) {
            ui.addText(page, activity.getString(R.string.result_save_success),
                    12, UiKit.MINT, false, 12, 0);
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
        ui.addButton(page, ui.button(activity.getString(interrupted
                        ? R.string.result_restart_interrupted : R.string.result_retry),
                UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
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
        if (trace == null) return null;
        if (trace.checkCompatibility(level) != ReplaySystem.Compatibility.AVAILABLE) {
            TextView unavailable = ui.text(
                    activity.getString(R.string.result_replay_incompatible),
                    12, UiKit.AMBER, false);
            unavailable.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            unavailable.setContentDescription(activity.getString(R.string.result_replay_incompatible));
            return unavailable;
        }
        List<TraceSample> samples = boundedReviewSamples(trace.samples(), 1_400);
        long failureTime = -1L;
        if (trace.result() == RunResult.FAILED) {
            for (TraceEvent event : trace.events()) {
                if ("FAIL".equals(event.type())) { failureTime = event.tMs(); break; }
            }
            if (failureTime >= 0L) {
                ReplaySystem.PathReview review = ReplaySystem.PathReview.from(trace,
                        failureTime, 3_000L, 1_500L);
                if (review != null && review.window().size() >= 2)
                    samples = boundedReviewSamples(review.window(), 1_400);
            }
        }
        if (samples == null || samples.size() < 2) return null;
        return new ReplayPlaybackPanel(activity, ui, level, samples, failureTime,
                outcome.mode() == ReplayMode.GHOST_RACE ? ghostComparison : null);
    }

    /** At most 1,400 visual samples are kept by the UI; stored Replay remains untouched. */
    private static List<TraceSample> boundedReviewSamples(List<TraceSample> source, int maximum) {
        if (source.size() <= maximum) return source;
        java.util.ArrayList<TraceSample> result = new java.util.ArrayList<>(maximum);
        int last = source.size() - 1;
        for (int i = 0; i < maximum; i++) {
            int index = (int) Math.round((double) i * last / (maximum - 1));
            TraceSample sample = source.get(index);
            if (result.isEmpty() || result.get(result.size() - 1).tMs() != sample.tMs()) {
                result.add(sample);
            }
        }
        if (result.get(result.size() - 1) != source.get(last)) result.add(source.get(last));
        return java.util.Collections.unmodifiableList(result);
    }

    private String levelTitle() {
        int id = activity.getResources().getIdentifier(level.displayNameKey(), "string",
                activity.getPackageName());
        return id == 0 ? level.levelId() : activity.getString(id);
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

    /** Lifecycle-bound trace player with deterministic scrub/speed and no physics replay. */
    private static final class ReplayPlaybackPanel extends LinearLayout
            implements View.OnAttachStateChangeListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<TraceSample> samples;
        private final long startMs;
        private final long endMs;
        private final ReviewMiniMap map;
        private final SeekBar seekBar;
        private final TextView positionLabel;
        private final TextView speedLabel;
        private final android.widget.Button playButton;
        private final double[] speeds = {0.5d, 1.0d, 2.0d};
        private int speedIndex = 1;
        private long currentMs;
        private long lastFrameUptime;
        private boolean playing;
        private boolean attached;
        private boolean updatingSeek;

        private final Runnable tick;

        private final Activity activity;
        private final UiKit ui;

        ReplayPlaybackPanel(Activity activity, UiKit ui, LevelDefinition level,
                            List<TraceSample> samples, long failureTime,
                            @Nullable GhostTimeline ghostComparison) {
            super(activity);
            this.activity = activity;
            this.ui = ui;
            this.samples = samples;
            this.startMs = samples.get(0).tMs();
            this.endMs = samples.get(samples.size() - 1).tMs();
            this.currentMs = startMs;
            setOrientation(VERTICAL);
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(8));
            map = new ReviewMiniMap(activity, ui, level, samples, failureTime, ghostComparison);
            addView(map, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(184)));
            if (ghostComparison != null) {
                TextView legend = ui.text(activity.getString(R.string.replay_ghost_legend),
                        10, UiKit.VIOLET, true);
                addView(legend, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }

            LinearLayout playbackRow = ui.row();
            playButton = ui.button(activity.getString(R.string.replay_play),
                    UiKit.ButtonStyle.SECONDARY, this::togglePlayback);
            tick = this::tickPlaybackFrame;
            playbackRow.addView(playButton, new LayoutParams(0, ui.dp(48), 1f));
            android.widget.Button speedButton = ui.button(
                    activity.getString(R.string.replay_speed, speedText()),
                    UiKit.ButtonStyle.QUIET, () -> cycleSpeed());
            playbackRow.addView(speedButton, new LayoutParams(0, ui.dp(48), 1f));
            playbackRow.setTag(speedButton);
            addView(playbackRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            seekBar = new SeekBar(activity);
            seekBar.setMax(1000);
            seekBar.setContentDescription(activity.getString(R.string.replay_position,
                    ui.formatTime(startMs), ui.formatTime(endMs)));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser || updatingSeek) return;
                    long target = startMs + Math.round((endMs - startMs) * (progress / 1000d));
                    setPosition(target);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    pausePlayback();
                }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            addView(seekBar, new LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(48)));
            LinearLayout footer = ui.row();
            positionLabel = ui.text("", 10, UiKit.TEXT_SECONDARY, true);
            speedLabel = ui.text(activity.getString(R.string.replay_speed, speedText()),
                    10, UiKit.TEXT_SECONDARY, false);
            footer.addView(positionLabel, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            footer.addView(speedLabel, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            addView(footer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            speedButton.setOnClickListener(v -> {
                cycleSpeed();
                speedButton.setText(activity.getString(R.string.replay_speed, speedText()));
            });
            setPosition(startMs);
            addOnAttachStateChangeListener(this);
        }

        private String activityLabel(int id) { return activity.getString(id); }

        private String speedText() {
            return speeds[speedIndex] == 1.0d ? "1×"
                    : speeds[speedIndex] == 0.5d ? "0.5×" : "2×";
        }

        private void cycleSpeed() {
            speedIndex = (speedIndex + 1) % speeds.length;
            speedLabel.setText(activity.getString(R.string.replay_speed, speedText()));
        }

        private void togglePlayback() {
            if (playing) pausePlayback();
            else {
                if (currentMs >= endMs) setPosition(startMs);
                playing = true;
                lastFrameUptime = SystemClock.uptimeMillis();
                playButton.setText(activityLabel(R.string.replay_pause));
                handler.removeCallbacks(tick);
                if (attached) handler.postDelayed(tick, 33L);
            }
        }

        private void tickPlaybackFrame() {
            if (!attached || !playing) return;
            long now = SystemClock.uptimeMillis();
            long delta = Math.max(0L, Math.min(100L, now - lastFrameUptime));
            lastFrameUptime = now;
            long advance = Math.round(delta * speeds[speedIndex]);
            setPosition(Math.min(endMs, currentMs + advance));
            if (currentMs >= endMs) {
                playing = false;
                playButton.setText(activityLabel(R.string.replay_play));
            } else {
                handler.postDelayed(tick, 33L);
            }
        }

        private void pausePlayback() {
            playing = false;
            handler.removeCallbacks(tick);
            playButton.setText(activityLabel(R.string.replay_play));
        }

        private void setPosition(long position) {
            currentMs = Math.max(startMs, Math.min(endMs, position));
            map.setPlaybackTime(currentMs);
            positionLabel.setText(activity.getString(R.string.replay_position,
                    ui.formatTime(currentMs), ui.formatTime(endMs)));
            updatingSeek = true;
            int progress = endMs <= startMs ? 0 : (int) Math.round(
                    (currentMs - startMs) * 1000d / (endMs - startMs));
            seekBar.setProgress(Math.max(0, Math.min(1000, progress)));
            seekBar.setContentDescription(activity.getString(R.string.replay_position,
                    ui.formatTime(currentMs), ui.formatTime(endMs)));
            updatingSeek = false;
        }

        @Override public void onViewAttachedToWindow(View view) {
            attached = true;
            if (playing) {
                lastFrameUptime = SystemClock.uptimeMillis();
                handler.removeCallbacks(tick);
                handler.postDelayed(tick, 33L);
            }
        }

        @Override public void onViewDetachedFromWindow(View view) {
            attached = false;
            handler.removeCallbacks(tick);
        }
    }

    /** Cached level geometry with a playback cursor; it never replays physics/collision. */
    private static final class ReviewMiniMap extends View {
        private final UiKit ui;
        private final LevelDefinition level;
        private final List<TraceSample> samples;
        private final long failureTime;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outerPath = new Path();
        private final Path holesPath = new Path();
        private final Path startPath = new Path();
        private final Path goalPath = new Path();
        private final Path safePath = new Path();
        private final Path tracePath = new Path();
        private final Path playedPath = new Path();
        private final Path ghostPath = new Path();
        @Nullable private final GhostTimeline ghostComparison;
        private final double[] ghostScratch = new double[2];
        private final float[] failurePointScratch = new float[2];
        private final float[] markerPointScratch = new float[2];
        private float contentLeft;
        private float contentTop;
        private double scale = 1.0;
        private long playbackTime;
        private float markerX;
        private float markerY;
        private float failureX;
        private float failureY;
        private boolean hasFailurePoint;

        ReviewMiniMap(Activity activity, UiKit ui, LevelDefinition level,
                      List<TraceSample> samples, long failureTime,
                      @Nullable GhostTimeline ghostComparison) {
            super(activity);
            this.ui = ui;
            this.level = level;
            this.samples = samples;
            this.failureTime = failureTime;
            this.ghostComparison = ghostComparison;
            this.playbackTime = samples.get(0).tMs();
            setContentDescription(activity.getString(R.string.result_path_review));
            setFocusable(true);
            setMinimumHeight(ui.dp(184));
        }

        void setPlaybackTime(long timeMs) {
            playbackTime = Math.max(samples.get(0).tMs(),
                    Math.min(samples.get(samples.size() - 1).tMs(), timeMs));
            rebuildPlayedPath();
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            rebuildPaths(width, height);
        }

        private void rebuildPaths(int width, int height) {
            outerPath.reset(); holesPath.reset(); startPath.reset(); goalPath.reset();
            safePath.reset(); tracePath.reset(); playedPath.reset(); ghostPath.reset();
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
            for (List<com.neonmaze.core.GeometryCollision.Vec2> hole : level.holeBoundaries())
                appendPolygon(holesPath, hole, bounds);
            appendZone(startPath, level.startZone(), bounds);
            appendZone(goalPath, level.goalZone(), bounds);
            for (com.neonmaze.core.LevelSystem.Zone safe : level.safeZones())
                appendZone(safePath, safe, bounds);
            boolean firstSample = true;
            for (TraceSample sample : samples) {
                float sampleX = screenX(sample.x(), bounds);
                float sampleY = screenY(sample.y(), bounds);
                if (firstSample) { tracePath.moveTo(sampleX, sampleY); firstSample = false; }
                else tracePath.lineTo(sampleX, sampleY);
            }
            if (ghostComparison != null) {
                long duration = ghostComparison.durationMs();
                int points = (int) Math.max(2L, Math.min(480L, duration / 40L + 1L));
                for (int i = 0; i < points; i++) {
                    long t = points <= 1 ? 0L : Math.round((double) duration * i / (points - 1));
                    ghostComparison.positionAt(t, ghostScratch);
                    float gx = screenX(ghostScratch[0], bounds);
                    float gy = screenY(ghostScratch[1], bounds);
                    if (i == 0) ghostPath.moveTo(gx, gy); else ghostPath.lineTo(gx, gy);
                }
            }
            hasFailurePoint = failureTime >= samples.get(0).tMs()
                    && failureTime <= samples.get(samples.size() - 1).tMs();
            if (hasFailurePoint) {
                positionAt(failureTime, bounds, failurePointScratch);
                failureX = failurePointScratch[0]; failureY = failurePointScratch[1];
            }
            rebuildPlayedPath();
        }

        private void rebuildPlayedPath() {
            playedPath.reset();
            if (getWidth() <= 0 || samples.isEmpty()) return;
            com.neonmaze.core.GeometryCollision.Aabb bounds = level.worldBounds();
            boolean started = false;
            for (int i = 0; i < samples.size(); i++) {
                TraceSample sample = samples.get(i);
                if (sample.tMs() > playbackTime) break;
                float x = screenX(sample.x(), bounds), y = screenY(sample.y(), bounds);
                if (!started) { playedPath.moveTo(x, y); started = true; }
                else playedPath.lineTo(x, y);
            }
            positionAt(playbackTime, bounds, markerPointScratch);
            markerX = markerPointScratch[0]; markerY = markerPointScratch[1];
            if (started) playedPath.lineTo(markerX, markerY);
            if (!started) playedPath.moveTo(markerX, markerY);
        }

        private void positionAt(long timeMs,
                com.neonmaze.core.GeometryCollision.Aabb bounds, float[] output) {
            TraceSample a = samples.get(0), b = samples.get(samples.size() - 1);
            for (int i = 1; i < samples.size(); i++) {
                TraceSample candidate = samples.get(i);
                if (candidate.tMs() >= timeMs) { a = samples.get(i - 1); b = candidate; break; }
            }
            double span = b.tMs() - a.tMs();
            double alpha = span <= 0L ? 0d : (timeMs - a.tMs()) / span;
            double x = a.x() + (b.x() - a.x()) * alpha;
            double y = a.y() + (b.y() - a.y()) * alpha;
            output[0] = screenX(x, bounds);
            output[1] = screenY(y, bounds);
        }

        private void appendPolygon(Path target,
                                   List<com.neonmaze.core.GeometryCollision.Vec2> points,
                                   com.neonmaze.core.GeometryCollision.Aabb bounds) {
            if (points == null || points.size() < 3) return;
            for (int i = 0; i < points.size(); i++) {
                com.neonmaze.core.GeometryCollision.Vec2 point = points.get(i);
                float x = screenX(point.x(), bounds), y = screenY(point.y(), bounds);
                if (i == 0) target.moveTo(x, y); else target.lineTo(x, y);
            }
            target.close();
        }

        private void appendZone(Path target, com.neonmaze.core.LevelSystem.Zone zone,
                                com.neonmaze.core.GeometryCollision.Aabb bounds) {
            if (zone.shape() == com.neonmaze.core.LevelSystem.ZoneShape.CIRCLE) {
                target.addCircle(screenX(zone.cx(), bounds), screenY(zone.cy(), bounds),
                        (float) (zone.radius() * scale), Path.Direction.CW);
            } else appendPolygon(target, zone.polygon(), bounds);
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
            paint.setColor(UiKit.SURFACE_ELEVATED); canvas.drawPath(outerPath, paint);
            paint.setColor(UiKit.BACKGROUND); canvas.drawPath(holesPath, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(1.5f));
            paint.setColor(UiKit.BORDER); canvas.drawPath(outerPath, paint); canvas.drawPath(holesPath, paint);
            paint.setStrokeWidth(ui.dp(2f)); paint.setColor(UiKit.MINT); canvas.drawPath(startPath, paint);
            paint.setColor(UiKit.VIOLET); canvas.drawPath(goalPath, paint);
            paint.setColor(UiKit.AMBER); paint.setStrokeWidth(ui.dp(1.5f)); canvas.drawPath(safePath, paint);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(ui.dp(2.5f)); paint.setColor(UiKit.TEXT_SECONDARY); paint.setAlpha(90);
            canvas.drawPath(tracePath, paint);
            if (ghostComparison != null) {
                paint.setAlpha(175); paint.setStrokeWidth(ui.dp(2.2f)); paint.setColor(UiKit.VIOLET);
                canvas.drawPath(ghostPath, paint);
            }
            paint.setAlpha(255); paint.setStrokeWidth(ui.dp(3.2f)); paint.setColor(UiKit.MINT);
            canvas.drawPath(playedPath, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(UiKit.MINT);
            canvas.drawCircle(markerX, markerY, ui.dp(5), paint);
            if (hasFailurePoint) {
                paint.setColor(UiKit.CORAL);
                canvas.drawCircle(failureX, failureY, ui.dp(5), paint);
            }
        }
    }

}
