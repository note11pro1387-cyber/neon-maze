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
import com.neonmaze.core.LevelSystem.Gate;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.ObjectiveType;
import com.neonmaze.core.ReplaySystem.ReplayMode;

/**
 * Accessible native HUD for Gameplay. It renders no maze geometry and owns no
 * timer, gate, objective, or pause rules; all displayed state is read from
 * GameEngine and the immutable LevelDefinition.
 */
public final class GameHud extends FrameLayout {

    public interface Listener {
        void onPauseRequested();
    }

    private static final int COLOR_PANEL = Color.rgb(18, 28, 44);
    private static final int COLOR_TEXT = Color.rgb(245, 247, 251);
    private static final int COLOR_MINT = Color.rgb(86, 230, 213);
    private static final int COLOR_AMBER = Color.rgb(255, 200, 107);
    private static final int COLOR_CORAL = Color.rgb(255, 107, 122);
    private static final int COLOR_VIOLET = Color.rgb(167, 139, 250);
    private static final int COLOR_NEUTRAL = Color.rgb(148, 163, 184);

    private final float density;
    private final TextView timerView;
    private final TextView objectiveView;
    private final TextView gateView;
    private final ImageButton pauseButton;

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

    private long lastTimerSecond = Long.MIN_VALUE;
    private int lastCollectedObjectives = -1;
    private int lastCollectedOptional = -1;
    private boolean lastObjectiveBlocked;
    @Nullable private GateRenderState lastGateState;
    @Nullable private EngineState lastEngineState;

    public GameHud(@NonNull Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        timerView = new TextView(context);
        timerView.setTextColor(COLOR_TEXT);
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        timerView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        timerView.setFontFeatureSettings("tnum");
        timerView.setGravity(Gravity.CENTER);
        timerView.setSingleLine(true);
        timerView.setIncludeFontPadding(false);
        timerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        timerView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);

        objectiveView = createChip(context);
        objectiveView.setGravity(Gravity.CENTER);
        objectiveView.setVisibility(GONE);
        objectiveView.setMaxLines(2);
        objectiveView.setEllipsize(TextUtils.TruncateAt.END);
        objectiveView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        gateView = createChip(context);
        gateView.setGravity(Gravity.CENTER);
        gateView.setMaxLines(2);
        gateView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        gateView.setVisibility(GONE);

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

        addView(timerView);
        addView(objectiveView);
        addView(gateView);
        addView(pauseButton);
        setVisibility(VISIBLE);
        layoutHud(getWidth(), getHeight());
    }

    private TextView createChip(Context context) {
        TextView view = new TextView(context);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        view.setBackground(chipBackground());
        view.setSingleLine(false);
        view.setIncludeFontPadding(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    private GradientDrawable chipBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), Color.argb(80, 148, 163, 184));
        return background;
    }

    private RippleDrawable createPauseBackground(boolean highContrast) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(highContrast ? Color.BLACK : COLOR_PANEL);
        content.setCornerRadius(dp(15));
        if (highContrast) content.setStroke(dp(2), Color.WHITE);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(15));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(52, 86, 230, 213)),
                content, mask);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setLevel(@NonNull LevelDefinition level) {
        this.level = level;
        lastTimerSecond = Long.MIN_VALUE;
        lastCollectedObjectives = -1;
        lastCollectedOptional = -1;
        lastGateState = null;
        lastEngineState = null;
        gateView.setVisibility(GONE);
        requestLayout();
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
        this.highContrast = highContrast;
        lastTimerSecond = Long.MIN_VALUE;
        lastCollectedObjectives = -1;
        lastCollectedOptional = -1;
        lastGateState = null;
        timerView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        objectiveView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        gateView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
        applyChipContrast(objectiveView);
        applyChipContrast(gateView);
        pauseButton.setBackground(createPauseBackground(highContrast));
        invalidate();
    }

    private void applyChipContrast(TextView chip) {
        if (chip.getBackground() instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) chip.getBackground();
            background.setColor(highContrast ? Color.BLACK : COLOR_PANEL);
            background.setStroke(dp(highContrast ? 2 : 1),
                    highContrast ? Color.WHITE : Color.argb(80, 148, 163, 184));
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
                timerView.setTextColor(highContrast ? Color.rgb(255, 90, 90) : COLOR_CORAL);
            } else {
                timerView.setTextColor(highContrast ? Color.WHITE : COLOR_TEXT);
            }
        }

        int collected = 0;
        int optionalCollected = 0;
        int optionalTotal = 0;
        int mandatoryCollected = 0;
        int mandatoryTotal = 0;
        Objective nextMandatory = null;
        for (Objective objective : level.objectives()) {
            boolean objectiveCollected = engine.isObjectiveCollected(objective.id());
            if (objective.type() == ObjectiveType.OPTIONAL) {
                optionalTotal++;
                if (objectiveCollected) optionalCollected++;
            } else {
                mandatoryTotal++;
                if (objectiveCollected) mandatoryCollected++;
                else if (nextMandatory == null) nextMandatory = objective;
            }
            if (objectiveCollected) collected++;
        }
        boolean blocked = engine.isObjectiveBlocked();
        if (!shortLandscape) objectiveView.setVisibility(VISIBLE);
        if (collected != lastCollectedObjectives
                || optionalCollected != lastCollectedOptional
                || blocked != lastObjectiveBlocked) {
            lastCollectedObjectives = collected;
            lastCollectedOptional = optionalCollected;
            lastObjectiveBlocked = blocked;
            String objectiveText;
            if (level.objectives().isEmpty()) {
                // Tutorial and intentionally objective-light exercises still have
                // a clear win condition; never present an empty mission as "no goal".
                objectiveText = getResources().getString(R.string.gameplay_no_objectives);
            } else {
                String primary;
                if (nextMandatory != null) {
                    String objectiveName = objectiveName(nextMandatory);
                    primary = getResources().getString(blocked
                                    ? R.string.gameplay_objective_blocked_named
                                    : R.string.gameplay_objective_primary,
                            objectiveName, mandatoryCollected + 1, mandatoryTotal);
                } else {
                    primary = getResources().getString(R.string.gameplay_objective_primary_done);
                }
                if (optionalTotal > 0) {
                    objectiveText = getResources().getString(R.string.gameplay_objectives_combined,
                            primary,
                            getResources().getString(R.string.gameplay_objectives_optional,
                                    optionalCollected, optionalTotal));
                } else {
                    objectiveText = primary;
                }
            }
            objectiveView.setText(objectiveText.trim());
            objectiveView.setTextColor(blocked
                    ? (highContrast ? Color.rgb(255, 90, 90) : COLOR_CORAL)
                    : (highContrast ? Color.WHITE : COLOR_TEXT));
        }

        GateRenderState gateState = selectGateState(engine, level);
        if (gateState != lastGateState) {
            lastGateState = gateState;
            if (gateState == null) {
                gateView.setVisibility(GONE);
                gateView.setText("");
            } else {
                gateView.setVisibility(VISIBLE);
                switch (gateState) {
                    case OPEN:
                        gateView.setText(R.string.gameplay_gate_open);
                        gateView.setTextColor(highContrast ? Color.rgb(0, 255, 190) : COLOR_MINT);
                        break;
                    case WARNING:
                        gateView.setText(R.string.gameplay_gate_warning);
                        gateView.setTextColor(highContrast ? Color.YELLOW : COLOR_AMBER);
                        break;
                    case LOCKED:
                        gateView.setText(R.string.gameplay_gate_locked);
                        gateView.setTextColor(highContrast ? Color.rgb(210, 185, 255) : COLOR_VIOLET);
                        break;
                    case CLOSED:
                    default:
                        gateView.setText(R.string.gameplay_gate_closed);
                        gateView.setTextColor(highContrast ? Color.WHITE : COLOR_NEUTRAL);
                        break;
                }
            }
        }

        EngineState state = engine.getState();
        boolean pauseAllowed = state == EngineState.RUNNING
                && mode != ReplayMode.TIME_ATTACK
                && mode != ReplayMode.GHOST_RACE
                && mode != ReplayMode.DAILY_CHALLENGE;
        pauseButton.setVisibility(pauseAllowed ? VISIBLE : GONE);
        if (shortLandscape) {
            objectiveView.setVisibility(INVISIBLE);
            gateView.setVisibility(INVISIBLE);
        }
        if (state != lastEngineState) lastEngineState = state;
    }

    private String objectiveName(Objective objective) {
        String key = objective.displayNameKey();
        if (key == null || key.isEmpty()) return objective.id();
        int resourceId = getResources().getIdentifier(
                key, "string", getContext().getPackageName());
        return resourceId == 0 ? objective.id() : getResources().getString(resourceId);
    }

    @Nullable
    private GateRenderState selectGateState(GameEngine engine, LevelDefinition level) {
        GateRenderState selected = null;
        int selectedPriority = -1;
        for (Gate gate : level.gates()) {
            GateRenderState state = engine.getGateRenderState(gate.id());
            int priority;
            switch (state) {
                case LOCKED: priority = 4; break;
                case CLOSED: priority = 3; break;
                case WARNING: priority = 2; break;
                case OPEN:
                default: priority = 1; break;
            }
            if (priority > selectedPriority) {
                selected = state;
                selectedPriority = priority;
            }
        }
        return selected;
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
        if (engine != null && level != null) update(engine, level);
    }

    private void layoutHud(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean landscape = width > height;
        shortLandscape = landscape && height / density < 480f;

        LayoutParams timerParams;
        LayoutParams pauseParams;
        LayoutParams objectiveParams;
        LayoutParams gateParams;
        if (!landscape) {
            timerParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(52),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            timerParams.topMargin = safeTop + dp(6);

            pauseParams = new LayoutParams(dp(48), dp(48),
                    Gravity.TOP | (leftHanded ? Gravity.LEFT : Gravity.RIGHT));
            if (leftHanded) pauseParams.leftMargin = safeLeft + dp(12);
            else pauseParams.rightMargin = safeRight + dp(12);
            pauseParams.topMargin = safeTop + dp(8);

            objectiveParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            objectiveParams.topMargin = safeTop + dp(56);

            gateParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            gateParams.topMargin = safeTop + dp(92);
        } else {
            int railWidth = dp(132);
            int railGravity = leftHanded ? Gravity.LEFT : Gravity.RIGHT;
            timerParams = new LayoutParams(railWidth, dp(56), Gravity.TOP | railGravity);
            timerParams.topMargin = safeTop + dp(12);
            if (leftHanded) timerParams.leftMargin = safeLeft + dp(6);
            else timerParams.rightMargin = safeRight + dp(6);

            pauseParams = new LayoutParams(dp(48), dp(48), Gravity.TOP | railGravity);
            pauseParams.topMargin = safeTop + dp(72);
            if (leftHanded) pauseParams.leftMargin = safeLeft + dp(42);
            else pauseParams.rightMargin = safeRight + dp(42);

            objectiveParams = new LayoutParams(railWidth, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | railGravity);
            objectiveParams.topMargin = safeTop + dp(130);
            if (leftHanded) objectiveParams.leftMargin = safeLeft + dp(6);
            else objectiveParams.rightMargin = safeRight + dp(6);

            gateParams = new LayoutParams(railWidth, LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | railGravity);
            gateParams.topMargin = safeTop + dp(182);
            if (leftHanded) gateParams.leftMargin = safeLeft + dp(6);
            else gateParams.rightMargin = safeRight + dp(6);
        }
        timerView.setLayoutParams(timerParams);
        pauseButton.setLayoutParams(pauseParams);
        objectiveView.setLayoutParams(objectiveParams);
        gateView.setLayoutParams(gateParams);

        if (shortLandscape) {
            objectiveView.setVisibility(INVISIBLE);
            gateView.setVisibility(INVISIBLE);
        } else if (engine != null) {
            objectiveView.setVisibility(VISIBLE);
            // Gate visibility is controlled by update() from the actual
            // GateRenderState, never merely by the presence of a Gate model.
        }
    }

    public int playfieldTopInsetPx(int width, int height) {
        return safeTop + (width > height ? dp(12) : dp(132));
    }

    public int playfieldLeftInsetPx(int width, int height) {
        return safeLeft + (width > height && leftHanded ? dp(148) : dp(10));
    }

    public int playfieldRightInsetPx(int width, int height) {
        return safeRight + (width > height && !leftHanded ? dp(148) : dp(10));
    }

    public int playfieldBottomInsetPx() {
        return safeBottom + dp(12);
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
}
