package com.neonmaze.app;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.GeometryCollision.Vec2;

import java.util.List;

/** Interactive tutorial shell; the actual path is driven by GameEngine + GeometryCollision. */
public final class TutorialScreen {
    public interface Actions {
        void onStart();
        void onRetry();
        void onFinish();
        void onSkip();
        void onBack();
    }

    private final Activity activity;
    private final UiKit ui;
    private final Actions actions;

    public TutorialScreen(@NonNull Activity activity, @NonNull UiKit ui, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.actions = actions;
    }

    public View createIntro() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_tutorial_eyebrow),
                activity.getString(R.string.tutorial_title),
                activity.getString(R.string.tutorial_subtitle));
        String[] steps = {activity.getString(R.string.tutorial_step_start),
                activity.getString(R.string.tutorial_step_hold),
                activity.getString(R.string.tutorial_step_turn),
                activity.getString(R.string.tutorial_step_key),
                activity.getString(R.string.tutorial_step_gate),
                activity.getString(R.string.tutorial_step_goal)};
        LinearLayout card = ui.card();
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = ui.row();
            ui.addText(row, steps[i], 13, i == 0 ? UiKit.MINT : UiKit.TEXT_PRIMARY,
                    true, 0, 0);
            card.addView(row, ui.matchWrap());
            if (i < steps.length - 1) ui.addDivider(card, 8, 8);
        }
        ui.addText(card, activity.getString(R.string.tutorial_instructions),
                13, UiKit.TEXT_SECONDARY, false, 16, 0);
        ui.addCard(page, card, 0);
        ui.addButton(page, ui.button(activity.getString(R.string.tutorial_start),
                UiKit.ButtonStyle.PRIMARY, actions::onStart), true);
        ui.addButton(page, ui.button(activity.getString(R.string.tutorial_skip),
                UiKit.ButtonStyle.QUIET, actions::onSkip), true);
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    public View createRunning(@NonNull GameView gameView) {
        FrameLayout root = ui.frame();
        root.setBackgroundColor(UiKit.BACKGROUND);
        root.addView(gameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout overlay = ui.column();
        overlay.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        overlay.setBackground(ui.shape(0xEE101827, UiKit.BORDER, 15, 1));
        ui.addText(overlay, activity.getString(R.string.tutorial_active_hint),
                11, UiKit.TEXT_SECONDARY, true, 0, 0);

        android.widget.TextView stepView = ui.text(
                activity.getString(R.string.tutorial_step_start), 14, UiKit.MINT, true);
        stepView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams stepParams = ui.matchWrap();
        stepParams.topMargin = ui.dp(5);
        overlay.addView(stepView, stepParams);

        android.widget.TextView hintView = ui.text(
                activity.getString(R.string.tutorial_hint_start), 12,
                UiKit.TEXT_PRIMARY, false);
        hintView.setLineSpacing(ui.dp(2), 1f);
        hintView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams hintParams = ui.matchWrap();
        hintParams.topMargin = ui.dp(3);
        overlay.addView(hintView, hintParams);

        android.widget.Button exit = ui.button(activity.getString(R.string.tutorial_exit_button),
                UiKit.ButtonStyle.QUIET, actions::onSkip);
        ui.addButton(overlay, exit, false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        params.setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(14));
        root.addView(overlay, params);

        GameEngine engine = gameView.getEngine();
        if (engine != null) {
            final StepDetector detector = new StepDetector();
            final int[] shownStep = {0};
            final ViewTreeObserver.OnPreDrawListener observer = () -> {
                int detected = detector.detect(engine);
                if (detected > shownStep[0]) {
                    shownStep[0] = detected;
                    stepView.setText(stepLabelResource(detected));
                    hintView.setText(stepHintResource(detected));
                    stepView.setTextColor(detected >= 3 ? UiKit.AMBER
                            : detected >= 2 ? UiKit.VIOLET : UiKit.MINT);
                }
                return true;
            };
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                private boolean observerAttached;
                @Override public void onViewAttachedToWindow(View view) {
                    ViewTreeObserver tree = view.getViewTreeObserver();
                    if (tree.isAlive() && !observerAttached) {
                        tree.addOnPreDrawListener(observer);
                        observerAttached = true;
                    }
                }
                @Override public void onViewDetachedFromWindow(View view) {
                    ViewTreeObserver tree = view.getViewTreeObserver();
                    if (tree.isAlive() && observerAttached) tree.removeOnPreDrawListener(observer);
                    observerAttached = false;
                }
            });
        }
        return root;
    }

    /** Reads only newly appended samples plus the short tail window; no second simulation loop. */
    private static final class StepDetector {
        private int processedSamples;
        private boolean initialized;
        private boolean hasInitialDirection;
        private double originX;
        private double originY;
        private double initialDx;
        private double initialDy;

        int detect(GameEngine engine) {
            if (engine.getState() == GameEngine.EngineState.COMPLETED) return 5;
            if (engine.hasPassedGate("exit-gate")) return 4;
            if (engine.hasCollectedKey("exit-key")) return 3;
            if (engine.getState() != GameEngine.EngineState.RUNNING) return 0;
            List<Vec2> trail = engine.getTrail();
            if (trail == null || trail.size() < 2) return 0;

            if (!initialized || trail.size() < processedSamples) {
                initialized = true;
                processedSamples = 1;
                hasInitialDirection = false;
                Vec2 origin = trail.get(0);
                originX = origin.x();
                originY = origin.y();
            }

            // Capture the initial movement vector once, examining only samples not seen before.
            for (int i = processedSamples; i < trail.size(); i++) {
                Vec2 point = trail.get(i);
                double dx = point.x() - originX;
                double dy = point.y() - originY;
                if (!hasInitialDirection && dx * dx + dy * dy >= 28.0 * 28.0) {
                    double length = Math.hypot(dx, dy);
                    if (length >= 20.0) {
                        initialDx = dx / length;
                        initialDy = dy / length;
                        hasInitialDirection = true;
                    }
                }
            }
            processedSamples = trail.size();
            if (!hasInitialDirection) return 1;

            // Derive the current direction from the last 28 world units only.
            Vec2 end = trail.get(trail.size() - 1);
            double endX = end.x();
            double endY = end.y();
            double distance = 0.0;
            double anchorX = endX;
            double anchorY = endY;
            for (int i = trail.size() - 1; i > 0; i--) {
                Vec2 current = trail.get(i);
                Vec2 previous = trail.get(i - 1);
                double dx = previous.x() - current.x();
                double dy = previous.y() - current.y();
                double segment = Math.hypot(dx, dy);
                if (segment <= 1.0e-6) continue;
                double remaining = 28.0 - distance;
                if (segment >= remaining) {
                    double ratio = remaining / segment;
                    anchorX = current.x() + dx * ratio;
                    anchorY = current.y() + dy * ratio;
                    distance = 28.0;
                    break;
                }
                distance += segment;
                anchorX = previous.x();
                anchorY = previous.y();
            }
            if (distance < 20.0) return 1;

            double tailDx = endX - anchorX;
            double tailDy = endY - anchorY;
            double tailLength = Math.hypot(tailDx, tailDy);
            if (tailLength < 20.0) return 1;
            double cosine = (initialDx * tailDx + initialDy * tailDy) / tailLength;
            return cosine < Math.cos(Math.toRadians(22.0)) ? 2 : 1;
        }
    }

    private static int stepLabelResource(int step) {
        switch (step) {
            case 1: return R.string.tutorial_step_hold;
            case 2: return R.string.tutorial_step_turn;
            case 3: return R.string.tutorial_step_key;
            case 4: return R.string.tutorial_step_gate;
            case 5: return R.string.tutorial_step_goal;
            default: return R.string.tutorial_step_start;
        }
    }

    private static int stepHintResource(int step) {
        switch (step) {
            case 1: return R.string.tutorial_hint_hold;
            case 2: return R.string.tutorial_hint_turn;
            case 3: return R.string.tutorial_hint_key;
            case 4: return R.string.tutorial_hint_gate;
            case 5: return R.string.tutorial_hint_goal;
            default: return R.string.tutorial_hint_start;
        }
    }

    public View createOutcome(boolean completed, boolean failed) {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_tutorial_complete_eyebrow),
                activity.getString(completed ? R.string.tutorial_complete
                        : failed ? R.string.result_title_failure : R.string.result_title_interrupted),
                activity.getString(R.string.tutorial_subtitle));
        if (failed) {
            ui.addText(page, activity.getString(R.string.result_failed_path),
                    13, UiKit.CORAL, true, 0, 0);
            ui.addButton(page, ui.button(activity.getString(R.string.tutorial_retry),
                    UiKit.ButtonStyle.PRIMARY, actions::onRetry), true);
        } else if (completed) {
            LinearLayout completion = ui.card();
            ui.addText(completion, activity.getString(R.string.tutorial_step_goal),
                    15, UiKit.MINT, true, 0, 0);
            ui.addText(completion, activity.getString(R.string.tutorial_hint_goal),
                    12, UiKit.TEXT_SECONDARY, false, 6, 0);
            ui.addCard(page, completion, 0);
            ui.addButton(page, ui.button(activity.getString(R.string.tutorial_finish),
                    UiKit.ButtonStyle.PRIMARY, actions::onFinish), true);
            ui.addButton(page, ui.button(activity.getString(R.string.tutorial_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
        } else {
            ui.addButton(page, ui.button(activity.getString(R.string.tutorial_retry),
                    UiKit.ButtonStyle.PRIMARY, actions::onRetry), true);
        }
        ui.addButton(page, ui.button(activity.getString(R.string.tutorial_finish),
                UiKit.ButtonStyle.QUIET, actions::onFinish), true);
        return scroll;
    }
}
