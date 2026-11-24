package com.neonmaze.app;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import com.neonmaze.core.DailyChallenge;
import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.ReplaySystem.ReplayMode;

import java.util.Map;

/** Mode selection surface. Eligibility is supplied by Core/Store, never guessed by the view. */
public final class ModesScreen {
    public interface Actions {
        void onCampaign();
        void onSelectLevel(@NonNull LevelDefinition level);
        void onStart(@NonNull LevelDefinition level, @NonNull ReplayMode mode,
                     String dailyChallengeId);
        void onRetry();
        void onBack();
    }

    private final Activity activity;
    private final UiKit ui;
    private final Catalog catalog;
    private final Map<String, LocalStore.LevelProgress> progress;
    private final DailyChallenge.Selection daily;
    private final String selectedLevelId;
    private final boolean ghostAvailable;
    private final boolean ghostCheckPending;
    private final boolean progressLoading;
    private final boolean progressFailed;
    private final boolean dailyLoading;
    private final boolean dailyFailed;
    private final Actions actions;

    public ModesScreen(@NonNull Activity activity, @NonNull UiKit ui,
                       @NonNull Catalog catalog,
                       Map<String, LocalStore.LevelProgress> progress,
                       DailyChallenge.Selection daily, String selectedLevelId,
                       boolean ghostAvailable, boolean ghostCheckPending,
                       boolean progressLoading, boolean progressFailed,
                       boolean dailyLoading, boolean dailyFailed, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.catalog = catalog;
        this.progress = progress;
        this.daily = daily;
        this.selectedLevelId = selectedLevelId;
        this.ghostAvailable = ghostAvailable;
        this.ghostCheckPending = ghostCheckPending;
        this.progressLoading = progressLoading;
        this.progressFailed = progressFailed;
        this.dailyLoading = dailyLoading;
        this.dailyFailed = dailyFailed;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_modes_eyebrow),
                activity.getString(R.string.modes_title),
                activity.getString(R.string.modes_subtitle));
        if (progressLoading || progressFailed) {
            LinearLayout notice = ui.card();
            ui.addText(notice, activity.getString(progressFailed ? R.string.home_store_error : R.string.home_loading),
                    12, progressFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, true, 0, 0);
            if (progressFailed) ui.addButton(notice, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addCard(page, notice, 0);
        }

        addModeCard(page, R.string.mode_campaign, R.string.mode_campaign_desc,
                R.string.mode_campaign_rewards, UiKit.MINT, UiKit.Icon.LEVELS,
                progressLoading || progressFailed ? null : actions::onCampaign,
                !progressLoading && !progressFailed);
        LevelDefinition selected = selectedLevel();
        String selectedName = selected == null ? "" : levelName(selected);
        ui.addSection(page, activity.getString(R.string.mode_choose_level, selectedName), null);
        LinearLayout levels = ui.row();
        for (int i = 0; i < catalog.size(); i++) {
            LevelDefinition level = catalog.get(i);
            LocalStore.LevelProgress stored = progress.get(level.levelId());
            boolean unlocked = !progressLoading && !progressFailed
                    && (stored == null ? i == 0 : stored.unlocked);
            TextChip chip = new TextChip(i + 1, unlocked,
                    level.levelId().equals(selectedLevelId));
            chip.setClick(unlocked ? () -> actions.onSelectLevel(level) : null);
            levels.addView(chip.view(), new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
            if (i < catalog.size() - 1) {
                View gap = new View(activity);
                levels.addView(gap, new LinearLayout.LayoutParams(ui.dp(5), 1));
            }
        }
        ui.addCard(page, levels, 0);

        addModeCard(page, R.string.mode_practice, R.string.mode_practice_desc,
                R.string.mode_pause_allowed, UiKit.VIOLET, UiKit.Icon.PLAY,
                selected == null || progressLoading || progressFailed ? null
                        : () -> actions.onStart(selected, ReplayMode.PRACTICE, null),
                selected != null && !progressLoading && !progressFailed);
        addModeCard(page, R.string.mode_time_attack, R.string.mode_time_attack_desc,
                R.string.mode_pause_locked, UiKit.AMBER, UiKit.Icon.CLOCK,
                selected == null || progressLoading || progressFailed ? null
                        : () -> actions.onStart(selected, ReplayMode.TIME_ATTACK, null),
                selected != null && !progressLoading && !progressFailed);
        addModeCard(page, R.string.mode_ghost, R.string.mode_ghost_desc,
                ghostCheckPending ? R.string.mode_ghost_loading
                        : ghostAvailable ? R.string.mode_start : R.string.mode_ghost_unavailable,
                UiKit.VIOLET, UiKit.Icon.TROPHY,
                selected == null || !ghostAvailable || ghostCheckPending
                        || progressLoading || progressFailed ? null
                        : () -> actions.onStart(selected, ReplayMode.GHOST_RACE, null),
                selected != null && (ghostAvailable || ghostCheckPending)
                        && !progressLoading && !progressFailed);
        addModeCard(page, R.string.mode_daily, R.string.mode_daily_desc,
                daily == null ? R.string.home_daily_unavailable
                        : dailyLoading ? R.string.home_daily_loading
                        : dailyFailed ? R.string.home_daily_load_error : R.string.mode_daily_date,
                UiKit.MINT, UiKit.Icon.STAR,
                daily == null || dailyLoading || dailyFailed
                        ? null : () -> actions.onStart(daily.level(),
                        ReplayMode.DAILY_CHALLENGE, daily.challengeId()),
                daily != null && !dailyLoading && !dailyFailed);
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    private void addModeCard(LinearLayout page, int titleId, int descId, int footId,
                             int accent, UiKit.Icon icon, Runnable action, boolean enabled) {
        LinearLayout card = ui.card();
        LinearLayout head = ui.row();
        View dot = new View(activity);
        dot.setBackground(ui.shape(accent, accent, 9, 0));
        head.addView(dot, new LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)));
        ImageView modeIcon = ui.icon(icon, accent, 22, null);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        iconParams.setMarginStart(ui.dp(9));
        head.addView(modeIcon, iconParams);
        android.widget.TextView title = ui.text(activity.getString(titleId), 17,
                enabled ? UiKit.TEXT_PRIMARY : UiKit.TEXT_SECONDARY, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(ui.dp(10));
        head.addView(title, titleParams);
        card.addView(head, ui.matchWrap());
        ui.addText(card, activity.getString(descId), 12, UiKit.TEXT_SECONDARY,
                false, 8, 0);
        String footer = footId == R.string.mode_daily_date && daily != null
                ? activity.getString(footId, daily.utcDate()) : activity.getString(footId);
        ui.addText(card, footer, 11, enabled ? accent : UiKit.TEXT_SECONDARY,
                true, 10, 0);
        ui.addButton(card, ui.button(activity.getString(R.string.mode_start),
                action == null ? UiKit.ButtonStyle.QUIET : UiKit.ButtonStyle.SECONDARY,
                action), true);
        ui.addCard(page, card, 10);
    }

    private LevelDefinition selectedLevel() {
        if (progressLoading || progressFailed) return null;
        if (selectedLevelId != null) {
            try {
                LevelDefinition level = catalog.byId(selectedLevelId);
                int index = catalog.all().indexOf(level);
                LocalStore.LevelProgress row = progress.get(level.levelId());
                if (row == null ? index == 0 : row.unlocked) return level;
            } catch (IllegalArgumentException ignored) { }
        }
        for (int i = 0; i < catalog.size(); i++) {
            LocalStore.LevelProgress row = progress.get(catalog.get(i).levelId());
            if (row == null ? i == 0 : row.unlocked) return catalog.get(i);
        }
        return catalog.get(0);
    }

    private String levelName(LevelDefinition level) {
        int id = activity.getResources().getIdentifier(level.displayNameKey(), "string",
                activity.getPackageName());
        return id == 0 ? level.levelId() : activity.getString(id);
    }

    private final class TextChip {
        private final android.widget.TextView text;
        TextChip(int number, boolean unlocked, boolean selected) {
            text = ui.text(unlocked ? ui.number(number) : "·", 14,
                    selected ? UiKit.BACKGROUND : unlocked ? UiKit.TEXT_PRIMARY : UiKit.TEXT_SECONDARY,
                    true);
            text.setGravity(android.view.Gravity.CENTER);
            text.setBackground(ui.shape(selected ? UiKit.MINT : UiKit.SURFACE_ELEVATED,
                    selected ? UiKit.MINT : UiKit.BORDER, 12, 1));
            text.setEnabled(unlocked);
            text.setContentDescription(activity.getString(unlocked
                    ? R.string.mode_level_available_description
                    : R.string.mode_level_locked_description, number));
        }
        View view() { text.setTag(this); return text; }
        void setClick(Runnable callback) {
            if (callback != null) text.setOnClickListener(v -> callback.run());
        }
    }
}
