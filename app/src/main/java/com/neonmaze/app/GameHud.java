package com.neonmaze.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.GameEngine;
import com.neonmaze.core.GameEngine.EngineState;
import com.neonmaze.core.GameEngine.GateRenderState;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.ObjectiveType;
import com.neonmaze.core.ReplaySystem.ReplayMode;

import java.util.Objects;

/**
 * Accessible native HUD for Gameplay. It renders no maze geometry and owns no
 * timer, gate, objective, or pause rules; all displayed state is read from
 * GameEngine and the immutable LevelDefinition.
 */
public final class GameHud extends FrameLayout {

    public interface Listener {
        void onPauseRequested();
        void onRestartRequested();
        void onExitRequested();
        void onPlayfieldInsetsChanged();
    }

    private static final int COLOR_TEXT = Color.rgb(246, 251, 255);
    private static final int COLOR_MINT = Color.rgb(63, 245, 222);
    private static final int COLOR_AMBER = Color.rgb(255, 196, 96);
    private static final int COLOR_CORAL = Color.rgb(255, 91, 126);
    private static final int COLOR_VIOLET = Color.rgb(174, 126, 255);
    private static final int COLOR_NEUTRAL = Color.rgb(123, 151, 177);

    private final float density;
    private final float fontScale;
    private int portraitPlayfieldTopInsetPx;
    private int landscapeRailInsetPx;
    private final TextView timerView;
    private final TextView levelTitleView;
    private final TextView objectiveView;
    private final ObjectiveProgressView objectiveProgressView;
    private final GameHudGlyphDrawable objectiveGlyph;
    private final TextView gateView;
    private final GameHudGlyphDrawable gateGlyph;
    private final TextView statusView;
    private final ImageButton pauseButton;
    private final ImageButton restartButton;
    private final ImageButton exitButton;

    @Nullable private Listener listener;
    @Nullable private LevelDefinition level;
    @Nullable private GameEngine engine;

    private int safeLeft;
    private int safeTop;
    private int safeRight;
    private int safeBottom;
    private boolean leftHanded;
    private boolean highContrast;
    private boolean shortLandscape;
    private int lastLoggedWidth = -1;
    private int lastLoggedHeight = -1;

    private long lastTimerSecond = Long.MIN_VALUE;
    private int lastCollectedObjectives = -1;
    private int lastCollectedOptional = -1;
    private boolean lastObjectiveBlocked;
    @Nullable private GateRenderState lastGateState;
    @Nullable private String lastGateId;
    @Nullable private EngineState lastEngineState;
    @Nullable private EngineState lastStatusState;

    public GameHud(@NonNull Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        fontScale = Math.max(1f, context.getResources().getConfiguration().fontScale);
        portraitPlayfieldTopInsetPx = dp(190);
        landscapeRailInsetPx = dp(128);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        timerView = new TextView(context);
        timerView.setTextColor(COLOR_TEXT);
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        timerView.setMinWidth(dp(112));
        timerView.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                android.graphics.Typeface.BOLD));
        timerView.setFontFeatureSettings("tnum");
        timerView.setGravity(Gravity.CENTER);
        timerView.setPadding(dp(15), dp(5), dp(15), dp(5));
        timerView.setBackground(timerBackground(COLOR_MINT));
        timerView.setElevation(dp(7));
        timerView.setLetterSpacing(0.075f);
        timerView.setSingleLine(true);
        timerView.setIncludeFontPadding(false);
        timerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        timerView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);

        levelTitleView = new TextView(context);
        levelTitleView.setTextColor(Color.rgb(157, 187, 212));
        levelTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        levelTitleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        levelTitleView.setLetterSpacing(0.105f);
        levelTitleView.setGravity(Gravity.CENTER);
        levelTitleView.setSingleLine(true);
        levelTitleView.setEllipsize(TextUtils.TruncateAt.END);
        levelTitleView.setIncludeFontPadding(false);
        levelTitleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        levelTitleView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);

        objectiveView = createChip(context);
        objectiveGlyph = new GameHudGlyphDrawable(context.getResources().getDisplayMetrics().density,
                GameHudGlyphDrawable.KIND_OBJECTIVE);
        objectiveView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                objectiveGlyph, null, null, null);
        objectiveView.setCompoundDrawablePadding(dp(6));
        objectiveGlyph.setAppearance(GameHudGlyphDrawable.STATE_NEUTRAL,
                highContrast ? Color.WHITE : COLOR_AMBER);
        objectiveView.setGravity(Gravity.CENTER);
        objectiveView.setVisibility(GONE);
        objectiveView.setMaxLines(2);
        objectiveView.setEllipsize(TextUtils.TruncateAt.END);
        objectiveView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        objectiveProgressView = new ObjectiveProgressView(context);
        objectiveProgressView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        objectiveProgressView.setVisibility(GONE);

        gateView = createChip(context);
        gateGlyph = new GameHudGlyphDrawable(context.getResources().getDisplayMetrics().density,
                GameHudGlyphDrawable.KIND_GATE);
        gateView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                gateGlyph, null, null, null);
        gateView.setCompoundDrawablePadding(dp(6));
        gateGlyph.setAppearance(GameHudGlyphDrawable.STATE_NEUTRAL,
                highContrast ? Color.WHITE : COLOR_NEUTRAL);
        gateView.setGravity(Gravity.CENTER);
        gateView.setMaxLines(2);
        gateView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        gateView.setVisibility(GONE);

        statusView = new TextView(context);
        statusView.setTextColor(COLOR_TEXT);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(14), dp(9), dp(14), dp(9));
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setMaxWidth(dp(320));
        statusView.setIncludeFontPadding(false);
        statusView.setBackground(statusBackground(COLOR_MINT));
        statusView.setElevation(dp(4));
        statusView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusView.setVisibility(GONE);

        pauseButton = new ImageButton(context);
        pauseButton.setImageDrawable(new PauseGlyphDrawable());
        pauseButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        pauseButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        pauseButton.setBackground(createPauseBackground(false));
        pauseButton.setMinimumWidth(dp(48));
        pauseButton.setMinimumHeight(dp(48));
        pauseButton.setContentDescription(context.getString(R.string.gameplay_pause));
        pauseButton.setFocusable(true);
        pauseButton.setClickable(true);
        pauseButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null) current.onPauseRequested();
        });
        pauseButton.setVisibility(GONE);

        restartButton = new ImageButton(context);
        restartButton.setImageDrawable(new RestartGlyphDrawable());
        restartButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        restartButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        restartButton.setBackground(createRestartBackground(false));
        restartButton.setMinimumWidth(dp(48));
        restartButton.setMinimumHeight(dp(48));
        restartButton.setContentDescription(context.getString(R.string.gameplay_restart));
        restartButton.setFocusable(true);
        restartButton.setClickable(true);
        restartButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null) current.onRestartRequested();
        });
        restartButton.setVisibility(GONE);

        exitButton = new ImageButton(context);
        exitButton.setImageDrawable(new ExitGlyphDrawable());
        exitButton.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        exitButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        exitButton.setBackground(createExitBackground(false));
        exitButton.setMinimumWidth(dp(48));
        exitButton.setMinimumHeight(dp(48));
        exitButton.setContentDescription(context.getString(R.string.game_exit));
        exitButton.setFocusable(true);
        exitButton.setClickable(true);
        exitButton.setOnClickListener(view -> {
            Listener current = listener;
            if (current != null) current.onExitRequested();
        });
        exitButton.setVisibility(VISIBLE);

        addView(timerView);
        addView(levelTitleView);
        addView(objectiveView);
        addView(objectiveProgressView);
        addView(gateView);
        addView(statusView);
        addView(pauseButton);
        addView(restartButton);
        addView(exitButton);
        setVisibility(VISIBLE);
        layoutHud(getWidth(), getHeight());
    }

    private TextView createChip(Context context) {
        TextView view = new TextView(context);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        view.setLetterSpacing(0.01f);
        view.setPadding(dp(11), dp(8), dp(11), dp(8));
        view.setBackground(chipBackground());
        view.setSingleLine(false);
        view.setIncludeFontPadding(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    private Drawable timerBackground(int accent) {
        return new GameHudPanelDrawable(highContrast ? Color.WHITE : accent, false);
    }

    private Drawable statusBackground(int accent) {
        return new GameHudPanelDrawable(highContrast ? Color.WHITE : accent, false);
    }

    private void setChipAccent(TextView view, int accent) {
        view.setBackground(new GameHudPanelDrawable(highContrast ? Color.WHITE : accent, false));
    }

    private Drawable chipBackground() {
        return new GameHudPanelDrawable(COLOR_NEUTRAL, false);
    }

    private RippleDrawable createPauseBackground(boolean highContrast) {
        Drawable content = new GameHudPanelDrawable(highContrast ? Color.WHITE : COLOR_MINT, true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(8));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(72, 63, 245, 222)),
                content, mask);
    }

    private RippleDrawable createRestartBackground(boolean highContrast) {
        Drawable content = new GameHudPanelDrawable(highContrast ? Color.WHITE : COLOR_AMBER, true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(8));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(82, 255, 196, 96)),
                content, mask);
    }

    private RippleDrawable createExitBackground(boolean highContrast) {
        Drawable content = new GameHudPanelDrawable(highContrast ? Color.WHITE : COLOR_CORAL, true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(8));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(84, 255, 91, 126)),
                content, mask);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private int scaledDp(float value, float scale) {
        return dp(value * scale);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setLevel(@NonNull LevelDefinition level) {
        this.level = level;
        int levelNumber = levelNumber(level.levelId());
        String displayName = getResources().getString(levelNameResource(level.displayNameKey()));
        levelTitleView.setText(getResources().getString(R.string.gameplay_sector_label,
                levelNumber, displayName));
        levelTitleView.setContentDescription(levelTitleView.getText());
        lastTimerSecond = Long.MIN_VALUE;
        lastCollectedObjectives = -1;
        lastCollectedOptional = -1;
        lastGateState = null;
        lastGateId = null;
        lastEngineState = null;
        lastStatusState = null;
        statusView.setVisibility(GONE);
        gateView.setVisibility(GONE);
        objectiveProgressView.setVisibility(level.objectives().isEmpty() ? GONE : VISIBLE);
        objectiveProgressView.setProgress(0, 0, 0, 0);
        requestLayout();
    }

    private static int levelNumber(String levelId) {
        if (levelId != null && levelId.startsWith("level-")) {
            try {
                int separator = levelId.indexOf('-', 6);
                String number = separator < 0 ? levelId.substring(6)
                        : levelId.substring(6, separator);
                return Math.max(1, Math.min(5, Integer.parseInt(number)));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static int levelNameResource(String key) {
        if ("level_01_name".equals(key)) return R.string.level_01_name;
        if ("level_02_name".equals(key)) return R.string.level_02_name;
        if ("level_03_name".equals(key)) return R.string.level_03_name;
        if ("level_04_name".equals(key)) return R.string.level_04_name;
        if ("level_05_name".equals(key)) return R.string.level_05_name;
        return R.string.level_01_name;
    }

    public void setSafeInsets(int left, int top, int right, int bottom) {
        safeLeft = Math.max(0, left);
        safeTop = Math.max(0, top);
        safeRight = Math.max(0, right);
        safeBottom = Math.max(0, bottom);
        layoutHud(getWidth(), getHeight());
        requestLayout();
        if (engine != null && level != null) update(engine, level);
    }

    public void setLeftHanded(boolean leftHanded) {
        if (this.leftHanded == leftHanded) return;
        this.leftHanded = leftHanded;
        layoutHud(getWidth(), getHeight());
        requestLayout();
        if (engine != null && level != null) update(engine, level);
    }

    public void setHighContrast(boolean highContrast) {
        if (this.highContrast == highContrast) return;
        long timerSecond = lastTimerSecond;
        this.highContrast = highContrast;
        lastTimerSecond = Long.MIN_VALUE;
        lastCollectedObjectives = -1;
        lastCollectedOptional = -1;
        lastGateState = null;
        lastGateId = null;
        boolean timed = engine != null
                && (engine.getMode() == ReplayMode.TIME_ATTACK
                || engine.getMode() == ReplayMode.DAILY_CHALLENGE);
        boolean criticalTimer = timed && timerSecond >= 0L && timerSecond <= 10L;
        timerView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        timerView.setBackground(timerBackground(criticalTimer ? COLOR_CORAL : COLOR_MINT));
        objectiveView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        gateView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        statusView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        setChipAccent(objectiveView, lastObjectiveBlocked ? COLOR_CORAL : COLOR_MINT);
        setChipAccent(gateView, gateAccent(lastGateState));
        objectiveGlyph.setAppearance(lastObjectiveBlocked
                        ? GameHudGlyphDrawable.STATE_BLOCKED
                        : GameHudGlyphDrawable.STATE_NEUTRAL,
                highContrast ? Color.WHITE : lastObjectiveBlocked ? COLOR_CORAL : COLOR_AMBER);
        gateGlyph.setAppearance(gateGlyphState(lastGateState),
                highContrast ? Color.WHITE : gateAccent(lastGateState));
        objectiveProgressView.invalidate();
        if (lastStatusState != null) applyStatusState(lastStatusState);
        pauseButton.setBackground(createPauseBackground(highContrast));
        restartButton.setBackground(createRestartBackground(highContrast));
        exitButton.setBackground(createExitBackground(highContrast));
        invalidate();
    }

    private int gateAccent(@Nullable GateRenderState state) {
        if (state == null) return COLOR_NEUTRAL;
        switch (state) {
            case OPEN: return COLOR_MINT;
            case WARNING: return COLOR_AMBER;
            case LOCKED: return COLOR_VIOLET;
            case CLOSED:
            default: return COLOR_NEUTRAL;
        }
    }

    private int gateGlyphState(@Nullable GateRenderState state) {
        if (state == null) return GameHudGlyphDrawable.STATE_NEUTRAL;
        switch (state) {
            case OPEN: return GameHudGlyphDrawable.STATE_OPEN;
            case WARNING: return GameHudGlyphDrawable.STATE_WARNING;
            case LOCKED: return GameHudGlyphDrawable.STATE_LOCKED;
            case CLOSED:
            default: return GameHudGlyphDrawable.STATE_CLOSED;
        }
    }

    /** Updates text only when the displayed second or gameplay status changes. */
    public void update(@NonNull GameEngine engine, @NonNull LevelDefinition level) {
        this.engine = engine;
        if (this.level != level) setLevel(level);

        ReplayMode mode = engine.getMode();
        boolean timed = mode == ReplayMode.TIME_ATTACK || mode == ReplayMode.DAILY_CHALLENGE;
        long displayedMs = engine.getActiveMs();
        if (timed) displayedMs = Math.max(0L, level.timeAttackLimitMs() - displayedMs);
        long displayedSecond = displayedMs / 1000L;
        if (displayedSecond != lastTimerSecond) {
            lastTimerSecond = displayedSecond;
            String time = formatTime(displayedSecond);
            timerView.setText(time);
            timerView.setContentDescription(getResources().getString(
                    timed ? R.string.gameplay_remaining_time : R.string.gameplay_elapsed_time,
                    time));
            if (timed && displayedSecond <= 10L) {
                timerView.setTextColor(highContrast ? Color.WHITE : COLOR_CORAL);
                timerView.setBackground(timerBackground(COLOR_CORAL));
            } else {
                timerView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
                timerView.setBackground(timerBackground(COLOR_MINT));
            }
        }

        int collected = 0;
        int mandatoryCollected = 0;
        int mandatoryTotal = 0;
        int optionalCollected = 0;
        int optionalTotal = 0;
        for (Objective objective : level.objectives()) {
            if (objective.type() == ObjectiveType.OPTIONAL) optionalTotal++;
            else mandatoryTotal++;
            if (engine.isObjectiveCollected(objective.id())) {
                collected++;
                if (objective.type() == ObjectiveType.OPTIONAL) optionalCollected++;
                else mandatoryCollected++;
            }
        }
        objectiveProgressView.setProgress(mandatoryCollected, mandatoryTotal,
                optionalCollected, optionalTotal);
        boolean hasProgress = mandatoryTotal > 0 || optionalTotal > 0;
        if (!shortLandscape) objectiveProgressView.setVisibility(hasProgress ? VISIBLE : GONE);
        boolean blocked = engine.isObjectiveBlocked();
        boolean showObjectives = !level.objectives().isEmpty() || blocked;
        if (!shortLandscape) objectiveView.setVisibility(showObjectives ? VISIBLE : GONE);
        if (collected != lastCollectedObjectives
                || optionalCollected != lastCollectedOptional
                || blocked != lastObjectiveBlocked) {
            lastCollectedObjectives = collected;
            lastCollectedOptional = optionalCollected;
            lastObjectiveBlocked = blocked;
            String objectiveText;
            if (blocked) {
                objectiveText = getResources().getString(R.string.gameplay_objective_blocked);
            } else if (level.objectives().isEmpty()) {
                objectiveText = getResources().getString(R.string.gameplay_no_objectives);
            } else if (optionalTotal > 0) {
                objectiveText = getResources().getString(R.string.gameplay_objectives_combined,
                        getResources().getString(R.string.gameplay_objectives_summary,
                                collected, level.objectives().size()),
                        getResources().getString(R.string.gameplay_objectives_optional,
                                optionalCollected, optionalTotal));
            } else {
                objectiveText = getResources().getString(R.string.gameplay_objectives_summary,
                        collected, level.objectives().size());
            }
            objectiveView.setText(objectiveText.trim());
            objectiveView.setTextColor(blocked
                    ? (highContrast ? Color.rgb(255, 90, 90) : COLOR_CORAL)
                    : (highContrast ? Color.WHITE : COLOR_TEXT));
            setChipAccent(objectiveView, blocked ? COLOR_CORAL : COLOR_MINT);
            boolean allMandatoryDone = mandatoryTotal == 0 || mandatoryCollected >= mandatoryTotal;
            boolean allOptionalDone = optionalTotal == 0 || optionalCollected >= optionalTotal;
            int objectiveState = blocked ? GameHudGlyphDrawable.STATE_BLOCKED
                    : allMandatoryDone && allOptionalDone
                    ? GameHudGlyphDrawable.STATE_COMPLETE
                    : GameHudGlyphDrawable.STATE_NEUTRAL;
            objectiveGlyph.setAppearance(objectiveState, highContrast ? Color.WHITE
                    : blocked ? COLOR_CORAL
                    : objectiveState == GameHudGlyphDrawable.STATE_COMPLETE
                    ? COLOR_MINT : COLOR_AMBER);
        }

        String gateId = engine.getRelevantGateId();
        GateRenderState gateState = gateId == null ? null : engine.getGateRenderState(gateId);
        if (!Objects.equals(gateId, lastGateId) || gateState != lastGateState) {
            lastGateId = gateId;
            lastGateState = gateState;
            if (gateId == null || gateState == null) {
                gateView.setVisibility(GONE);
                gateView.setText("");
            } else {
                gateView.setVisibility(VISIBLE);
                int statusText;
                switch (gateState) {
                    case OPEN:
                        statusText = R.string.gameplay_gate_open;
                        gateView.setTextColor(highContrast ? Color.rgb(0, 255, 190) : COLOR_MINT);
                        setChipAccent(gateView, COLOR_MINT);
                        break;
                    case WARNING:
                        statusText = R.string.gameplay_gate_warning;
                        gateView.setTextColor(highContrast ? Color.YELLOW : COLOR_AMBER);
                        setChipAccent(gateView, COLOR_AMBER);
                        break;
                    case LOCKED:
                        statusText = R.string.gameplay_gate_locked;
                        gateView.setTextColor(highContrast ? Color.rgb(210, 185, 255) : COLOR_VIOLET);
                        setChipAccent(gateView, COLOR_VIOLET);
                        break;
                    case CLOSED:
                    default:
                        statusText = R.string.gameplay_gate_closed;
                        gateView.setTextColor(highContrast ? Color.WHITE : COLOR_NEUTRAL);
                        setChipAccent(gateView, COLOR_NEUTRAL);
                        break;
                }
                gateView.setText(getResources().getString(R.string.gameplay_gate_state,
                        gateLabel(gateId, level), getResources().getString(statusText)));
                gateGlyph.setAppearance(gateGlyphState(gateState),
                        highContrast ? Color.WHITE : gateAccent(gateState));
            }
        }

        EngineState state = engine.getState();
        boolean pauseAllowed = state == EngineState.RUNNING
                && mode != ReplayMode.TIME_ATTACK
                && mode != ReplayMode.GHOST_RACE
                && mode != ReplayMode.DAILY_CHALLENGE;
        pauseButton.setVisibility(pauseAllowed ? VISIBLE : GONE);
        restartButton.setVisibility(state == EngineState.PAUSED ? VISIBLE : GONE);
        boolean exitAllowed = state == EngineState.READY
                || state == EngineState.RUNNING || state == EngineState.PAUSED;
        exitButton.setVisibility(exitAllowed ? VISIBLE : GONE);
        if (state != lastStatusState) applyStatusState(state);
        if (shortLandscape) {
            objectiveView.setVisibility(INVISIBLE);
            objectiveProgressView.setVisibility(INVISIBLE);
            gateView.setVisibility(INVISIBLE);
        }
        if (state != lastEngineState) lastEngineState = state;
    }


    private void applyStatusState(EngineState state) {
        lastStatusState = state;
        int textId;
        int accent;
        boolean visible;
        switch (state) {
            case READY:
                textId = R.string.gameplay_ready_hint;
                accent = COLOR_MINT;
                visible = true;
                break;
            case PAUSED:
                textId = R.string.game_pause_instruction;
                accent = COLOR_AMBER;
                visible = true;
                break;
            case COMPLETED:
                textId = R.string.gameplay_success_hint;
                accent = COLOR_MINT;
                visible = true;
                break;
            case FAILED:
                textId = R.string.gameplay_failure_hint;
                accent = COLOR_CORAL;
                visible = true;
                break;
            case INTERRUPTED:
                textId = R.string.game_accessibility_state_interrupted;
                accent = COLOR_NEUTRAL;
                visible = true;
                break;
            case RUNNING:
            default:
                textId = 0;
                accent = COLOR_MINT;
                visible = false;
                break;
        }
        if (visible) {
            statusView.setText(textId);
            statusView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
            statusView.setBackground(statusBackground(accent));
            statusView.setContentDescription(getResources().getString(textId));
            statusView.setVisibility(VISIBLE);
        } else {
            statusView.setVisibility(GONE);
            statusView.setContentDescription(null);
        }
    }

    private String gateLabel(String gateId, LevelDefinition level) {
        if ("timed-gate".equals(gateId)) {
            return getResources().getString(R.string.gameplay_gate_timed_name);
        }
        if ("exit-gate".equals(gateId)) {
            return getResources().getString(R.string.gameplay_gate_exit_name);
        }
        for (int i = 0; i < level.gates().size(); i++) {
            if (gateId.equals(level.gates().get(i).id())) {
                return getResources().getString(R.string.gameplay_gate_generic, i + 1);
            }
        }
        return getResources().getString(R.string.gameplay_gate_generic, 1);
    }

    private String formatTime(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder builder = new StringBuilder(8);
        if (minutes < 10L) builder.append('0');
        builder.append(minutes).append(':');
        if (seconds < 10L) builder.append('0');
        builder.append(seconds);
        return builder.toString();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutHud(width, height);
        if (BuildConfig.DEBUG && (width != lastLoggedWidth || height != lastLoggedHeight)) {
            lastLoggedWidth = width;
            lastLoggedHeight = height;
            Log.d("NeonMazeHud", "size=" + width + "x" + height
                    + " density=" + density + " leftHanded=" + leftHanded
                    + " shortLandscape=" + shortLandscape
                    + " insets=" + safeLeft + "," + safeTop + ","
                    + safeRight + "," + safeBottom);
        }
        if (engine != null && level != null) update(engine, level);
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onPlayfieldInsetsChanged();
    }

    private void layoutHud(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean landscape = width > height;
        float textScale = Math.min(2f, fontScale);
        // Font Scale alone does not force compact mode: a tall landscape device
        // can host a larger, readable rail. Compact mode is based on physical
        // available height, while the rail itself expands with system text.
        shortLandscape = landscape && height / density < 520f;

        LayoutParams timerParams;
        LayoutParams titleParams;
        LayoutParams pauseParams;
        LayoutParams restartParams;
        LayoutParams exitParams;
        LayoutParams objectiveParams;
        LayoutParams progressParams;
        LayoutParams gateParams;
        LayoutParams statusParams;
        if (!landscape) {
            int usableWidth = Math.max(dp(240), width - safeLeft - safeRight - dp(24));
            boolean stackedStatus = textScale >= 1.35f;
            int columnWidth = Math.max(dp(108), Math.min(dp(176),
                    (usableWidth - dp(8)) / 2));
            int timerHeight = scaledDp(48f, textScale);
            int timerWidth = dp(Math.min(172f, 116f + (textScale - 1f) * 52f));
            int chipHeight = scaledDp(42f, textScale) + dp(2f);
            int timerTop = safeTop + dp(4);
            int titleTop = timerTop + timerHeight + dp(2);
            int titleHeight = scaledDp(17f, textScale);
            int chipTop = titleTop + titleHeight + dp(5);

            timerParams = new LayoutParams(timerWidth, timerHeight,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            timerParams.topMargin = timerTop;

            titleParams = new LayoutParams(Math.min(usableWidth, dp(320)), titleHeight,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            titleParams.topMargin = titleTop;

            pauseParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.LEFT : Gravity.RIGHT));
            if (leftHanded) pauseParams.leftMargin = safeLeft + dp(8);
            else pauseParams.rightMargin = safeRight + dp(8);
            pauseParams.topMargin = timerTop;

            restartParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.LEFT : Gravity.RIGHT));
            if (leftHanded) restartParams.leftMargin = safeLeft + dp(8);
            else restartParams.rightMargin = safeRight + dp(8);
            restartParams.topMargin = timerTop;

            exitParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.RIGHT : Gravity.LEFT));
            if (leftHanded) exitParams.rightMargin = safeRight + dp(8);
            else exitParams.leftMargin = safeLeft + dp(8);
            exitParams.topMargin = timerTop;

            if (stackedStatus) {
                objectiveParams = new LayoutParams(usableWidth, chipHeight,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                objectiveParams.leftMargin = safeLeft + dp(8);
                objectiveParams.rightMargin = safeRight + dp(8);
                objectiveParams.topMargin = chipTop;
                gateParams = new LayoutParams(usableWidth, chipHeight,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                gateParams.leftMargin = safeLeft + dp(8);
                gateParams.rightMargin = safeRight + dp(8);
                gateParams.topMargin = chipTop + chipHeight + dp(4);
            } else {
                objectiveParams = new LayoutParams(columnWidth, chipHeight,
                        Gravity.TOP | Gravity.START);
                objectiveParams.leftMargin = safeLeft + dp(8);
                objectiveParams.rightMargin = safeRight + dp(8);
                objectiveParams.topMargin = chipTop;
                gateParams = new LayoutParams(columnWidth, chipHeight,
                        Gravity.TOP | Gravity.END);
                gateParams.leftMargin = safeLeft + dp(8);
                gateParams.rightMargin = safeRight + dp(8);
                gateParams.topMargin = chipTop;
            }

            int afterChipTop = stackedStatus ? chipTop + chipHeight * 2 + dp(9)
                    : chipTop + chipHeight + dp(5);
            int progressHeight = scaledDp(9f, textScale);
            progressParams = new LayoutParams(Math.min(dp(240), usableWidth - dp(48)),
                    progressHeight, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            progressParams.topMargin = afterChipTop;

            int statusTop = afterChipTop + progressHeight + dp(7);
            statusParams = new LayoutParams(Math.min(dp(310), usableWidth),
                    LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            statusParams.topMargin = statusTop;
            portraitPlayfieldTopInsetPx = statusTop + scaledDp(47f, textScale) + dp(2);
        } else {
            int railWidth = scaledDp(116f, textScale);
            landscapeRailInsetPx = railWidth + dp(12);
            int railGravity = leftHanded ? Gravity.LEFT : Gravity.RIGHT;
            int railOuterMargin = leftHanded ? safeLeft : safeRight;
            int titleTop = safeTop + dp(3);
            int titleHeight = scaledDp(18f, textScale);
            int timerTop = titleTop + titleHeight + dp(3);
            int timerHeight = scaledDp(46f, textScale);
            int controlTop = timerTop + timerHeight + dp(8);
            int objectiveTop = controlTop + dp(58);
            int chipHeight = scaledDp(42f, textScale) + dp(2);
            int progressTop = objectiveTop + chipHeight + dp(6);
            int gateTop = progressTop + dp(20);
            // In a short display the objective/gate chips are hidden, but the
            // status banner must still sit below the scaled control targets.
            // A fixed 136dp offset overlapped Pause/Exit at large system fonts.
            int statusTop = shortLandscape ? controlTop + dp(58)
                    : gateTop + chipHeight + dp(12);

            titleParams = new LayoutParams(railWidth, titleHeight, Gravity.TOP | railGravity);
            titleParams.topMargin = titleTop;
            if (leftHanded) titleParams.leftMargin = railOuterMargin + dp(4);
            else titleParams.rightMargin = railOuterMargin + dp(4);

            timerParams = new LayoutParams(railWidth, timerHeight, Gravity.TOP | railGravity);
            timerParams.topMargin = timerTop;
            if (leftHanded) timerParams.leftMargin = railOuterMargin + dp(4);
            else timerParams.rightMargin = railOuterMargin + dp(4);

            pauseParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.LEFT : Gravity.RIGHT));
            restartParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.LEFT : Gravity.RIGHT));
            exitParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.LEFT : Gravity.RIGHT));
            if (leftHanded) {
                exitParams.leftMargin = railOuterMargin + dp(4);
                pauseParams.leftMargin = railOuterMargin + dp(64);
                restartParams.leftMargin = railOuterMargin + dp(64);
            } else {
                exitParams.rightMargin = railOuterMargin + dp(4);
                pauseParams.rightMargin = railOuterMargin + dp(64);
                restartParams.rightMargin = railOuterMargin + dp(64);
            }
            exitParams.topMargin = controlTop;
            pauseParams.topMargin = controlTop;
            restartParams.topMargin = controlTop;

            objectiveParams = new LayoutParams(railWidth, chipHeight, Gravity.TOP | railGravity);
            objectiveParams.topMargin = objectiveTop;
            gateParams = new LayoutParams(railWidth, chipHeight, Gravity.TOP | railGravity);
            gateParams.topMargin = gateTop;
            progressParams = new LayoutParams(railWidth - dp(12), dp(9), Gravity.TOP | railGravity);
            progressParams.topMargin = progressTop;
            statusParams = new LayoutParams(railWidth, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | railGravity);
            statusParams.topMargin = statusTop;
        }

        timerView.setLayoutParams(timerParams);
        levelTitleView.setLayoutParams(titleParams);
        pauseButton.setLayoutParams(pauseParams);
        restartButton.setLayoutParams(restartParams);
        exitButton.setLayoutParams(exitParams);
        objectiveView.setLayoutParams(objectiveParams);
        objectiveProgressView.setLayoutParams(progressParams);
        gateView.setLayoutParams(gateParams);
        statusView.setLayoutParams(statusParams);

        if (shortLandscape) {
            objectiveView.setVisibility(INVISIBLE);
            objectiveProgressView.setVisibility(INVISIBLE);
            gateView.setVisibility(INVISIBLE);
        } else if (engine != null && level != null) {
            objectiveView.setVisibility(!level.objectives().isEmpty()
                    || engine.isObjectiveBlocked() ? VISIBLE : GONE);
            objectiveProgressView.setVisibility(level.objectives().isEmpty() ? GONE : VISIBLE);
            gateView.setVisibility(lastGateState == null ? GONE : VISIBLE);
        }
    }

    public int playfieldTopInsetPx(int width, int height) {
        return width > height ? safeTop + dp(54)
                : Math.max(safeTop + dp(188), portraitPlayfieldTopInsetPx);
    }

    public int playfieldLeftInsetPx(int width, int height) {
        return safeLeft + (width > height && leftHanded ? landscapeRailInsetPx : dp(8));
    }

    public int playfieldRightInsetPx(int width, int height) {
        return safeRight + (width > height && !leftHanded ? landscapeRailInsetPx : dp(8));
    }

    public int playfieldBottomInsetPx() {
        return safeBottom + dp(8);
    }

    /**
     * A compact, segmented objective rail. Mandatory objectives use mint cells;
     * optional objectives use violet cells on a second lane. It is decorative
     * because the adjacent HUD text already provides the accessible summary.
     */
    private final class ObjectiveProgressView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cell = new RectF();
        private int mandatoryDone = -1;
        private int mandatoryTotal = -1;
        private int optionalDone = -1;
        private int optionalTotal = -1;

        ObjectiveProgressView(Context context) {
            super(context);
            trackPaint.setStyle(Paint.Style.FILL);
            trackPaint.setColor(Color.rgb(8, 17, 30));
            activePaint.setStyle(Paint.Style.FILL);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(dp(0.8f));
            setWillNotDraw(false);
        }

        void setProgress(int mandatoryDone, int mandatoryTotal,
                         int optionalDone, int optionalTotal) {
            mandatoryDone = Math.max(0, Math.min(mandatoryTotal, mandatoryDone));
            optionalDone = Math.max(0, Math.min(optionalTotal, optionalDone));
            if (this.mandatoryDone == mandatoryDone
                    && this.mandatoryTotal == mandatoryTotal
                    && this.optionalDone == optionalDone
                    && this.optionalTotal == optionalTotal) return;
            this.mandatoryDone = mandatoryDone;
            this.mandatoryTotal = mandatoryTotal;
            this.optionalDone = optionalDone;
            this.optionalTotal = optionalTotal;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            if (width <= 0f) return;
            float laneHeight = dp(3.5f);
            float gap = dp(3f);
            if (mandatoryTotal > 0) {
                drawLane(canvas, width, 0f, laneHeight, mandatoryTotal,
                        mandatoryDone, highContrast ? Color.WHITE : COLOR_MINT, gap);
            }
            if (optionalTotal > 0) {
                float y = mandatoryTotal > 0 ? dp(7f) : dp(3.5f);
                drawLane(canvas, width, y, laneHeight * 0.72f, optionalTotal,
                        optionalDone, highContrast ? Color.WHITE : COLOR_VIOLET, gap);
            }
        }

        private void drawLane(Canvas canvas, float width, float top, float height,
                              int total, int completed, int accent, float gap) {
            if (total <= 0) return;
            int visibleCells = Math.min(total, 12);
            float cellWidth = (width - gap * (visibleCells - 1)) / visibleCells;
            float radius = Math.min(height * 0.5f, dp(2f));
            activePaint.setColor(accent);
            outlinePaint.setColor(accent);
            for (int index = 0; index < visibleCells; index++) {
                float left = index * (cellWidth + gap);
                cell.set(left, top, left + cellWidth, top + height);
                canvas.drawRoundRect(cell, radius, radius, trackPaint);
                if (index < completed || (total > visibleCells && index < completed * visibleCells / total)) {
                    canvas.drawRoundRect(cell, radius, radius, activePaint);
                } else {
                    outlinePaint.setAlpha(highContrast ? 220 : 82);
                    canvas.drawRoundRect(cell, radius, radius, outlinePaint);
                }
            }
        }
    }

    private final class PauseGlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF firstBar = new RectF();
        private final RectF secondBar = new RectF();

        PauseGlyphDrawable() {
            paint.setColor(COLOR_TEXT);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            float barWidth = width * 0.18f;
            float gap = width * 0.13f;
            float barHeight = height * 0.52f;
            float centerX = bounds.exactCenterX();
            float centerY = bounds.exactCenterY();
            firstBar.set(centerX - gap - barWidth, centerY - barHeight * 0.5f,
                    centerX - gap, centerY + barHeight * 0.5f);
            secondBar.set(centerX + gap, centerY - barHeight * 0.5f,
                    centerX + gap + barWidth, centerY + barHeight * 0.5f);
            canvas.drawRoundRect(firstBar, barWidth * 0.2f, barWidth * 0.2f, paint);
            canvas.drawRoundRect(secondBar, barWidth * 0.2f, barWidth * 0.2f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }


    private final class RestartGlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcBounds = new RectF();

        RestartGlyphDrawable() {
            paint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float radius = Math.min(bounds.width(), bounds.height()) * 0.27f;
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setColor(highContrast ? Color.WHITE : COLOR_AMBER);
            canvas.drawArc(arcBounds, -48f, 286f, false, paint);
            float tipX = cx + radius * 0.72f;
            float tipY = cy - radius * 0.70f;
            canvas.drawLine(tipX, tipY, tipX + dp(5), tipY - dp(1), paint);
            canvas.drawLine(tipX, tipY, tipX - dp(1), tipY + dp(5), paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** High-contrast door-and-arrow glyph for the persistent gameplay Exit control. */
    private final class ExitGlyphDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF door = new RectF();

        ExitGlyphDrawable() {
            paint.setColor(highContrast ? Color.WHITE : COLOR_CORAL);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float centerX = bounds.exactCenterX();
            float centerY = bounds.exactCenterY();
            float halfHeight = bounds.height() * 0.27f;
            door.set(centerX - bounds.width() * 0.23f, centerY - halfHeight,
                    centerX + bounds.width() * 0.04f, centerY + halfHeight);
            paint.setColor(highContrast ? Color.WHITE : COLOR_CORAL);
            canvas.drawRoundRect(door, dp(2), dp(2), paint);
            float startX = centerX - bounds.width() * 0.08f;
            float endX = centerX + bounds.width() * 0.27f;
            canvas.drawLine(startX, centerY, endX, centerY, paint);
            canvas.drawLine(endX - dp(5), centerY - dp(5), endX, centerY, paint);
            canvas.drawLine(endX - dp(5), centerY + dp(5), endX, centerY, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
