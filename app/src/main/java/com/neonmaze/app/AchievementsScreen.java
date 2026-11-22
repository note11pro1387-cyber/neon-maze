package com.neonmaze.app;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import com.neonmaze.core.ProgressRules;

import java.text.DateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Achievements and earned cosmetics are presented from Core definitions + LocalStore rows. */
public final class AchievementsScreen {
    public interface Actions { void onRetry(); void onBack(); }

    private final Activity activity;
    private final UiKit ui;
    private final List<LocalStore.Achievement> stored;
    private final Map<String, LocalStore.LevelProgress> progress;
    private final boolean loading;
    private final boolean loadFailed;
    private final Actions actions;

    public AchievementsScreen(@NonNull Activity activity, @NonNull UiKit ui,
                              List<LocalStore.Achievement> stored,
                              Map<String, LocalStore.LevelProgress> progress,
                              boolean loading, boolean loadFailed, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.stored = stored;
        this.progress = progress;
        this.loading = loading;
        this.loadFailed = loadFailed;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_achievements_eyebrow),
                activity.getString(R.string.achievements_title),
                activity.getString(R.string.achievements_subtitle));
        if (loading || loadFailed) {
            ui.addText(page, activity.getString(loadFailed ? R.string.achievements_load_error
                            : R.string.home_loading),
                    13, loadFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, true, 0, 0);
            if (loadFailed) ui.addButton(page, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                    UiKit.ButtonStyle.QUIET, actions::onBack), true);
            return scroll;
        }
        Map<String, LocalStore.Achievement> byId = new HashMap<>();
        if (stored != null) for (LocalStore.Achievement item : stored) {
            byId.put(item.achievementId, item);
        }
        List<ProgressRules.AchievementDef> definitions = ProgressRules.achievements();
        if (definitions.isEmpty()) {
            ui.addText(page, activity.getString(R.string.achievements_empty),
                    14, UiKit.TEXT_SECONDARY, false, 0, 0);
        }
        for (ProgressRules.AchievementDef definition : definitions) {
            LocalStore.Achievement row = byId.get(definition.id());
            boolean unlocked = row != null && row.unlockedAt != null;
            int progress = row == null ? 0 : Math.max(0, row.progressValue);
            LinearLayout card = ui.card();
            LinearLayout titleRow = ui.row();
            titleRow.addView(ui.icon(UiKit.Icon.TROPHY,
                    unlocked ? UiKit.MINT : UiKit.TEXT_SECONDARY, 20, null),
                    new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            android.widget.TextView title = ui.text(
                    activity.getString(achievementName(definition.id())),
                    16, unlocked ? UiKit.MINT : UiKit.TEXT_PRIMARY, true);
            LinearLayout.LayoutParams titleParams = ui.wrap();
            titleParams.setMarginStart(ui.dp(9));
            titleRow.addView(title, titleParams);
            card.addView(titleRow, ui.matchWrap());
            ui.addText(card, activity.getString(achievementDescription(definition.id())),
                    12, UiKit.TEXT_SECONDARY, false, 5, 0);
            LinearLayout status = ui.row();
            ui.addText(status, activity.getString(unlocked
                    ? R.string.achievement_unlocked : R.string.achievement_locked),
                    11, unlocked ? UiKit.MINT : UiKit.AMBER, true, 0, 0);
            if (!unlocked) {
                android.widget.TextView count = ui.text(activity.getString(
                        R.string.achievement_progress_value, Math.min(progress, definition.target()),
                        definition.target()), 11, UiKit.TEXT_SECONDARY, true);
                LinearLayout.LayoutParams p = ui.wrap();
                p.setMarginStart(ui.dp(10));
                status.addView(count, p);
            } else if (row != null && row.unlockedAt != null) {
                java.util.Locale locale = "en".equals(ui.context().getResources().getConfiguration().getLocales().get(0).getLanguage())
                        ? java.util.Locale.ENGLISH : new java.util.Locale("fa");
                String date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                        .format(new java.util.Date(row.unlockedAt));
                android.widget.TextView dateView = ui.text(date, 10, UiKit.TEXT_SECONDARY, false);
                LinearLayout.LayoutParams p = ui.wrap();
                p.setMarginStart(ui.dp(10));
                status.addView(dateView, p);
            }
            card.addView(status, ui.matchWrap());
            ui.addCard(page, card, 8);
        }

        int campaignCompletions = 0;
        if (progress != null) for (LocalStore.LevelProgress row : progress.values()) {
            campaignCompletions += Math.max(0, row.completions);
        }
        ui.addSection(page, activity.getString(R.string.achievements_cosmetics), null);
        for (ProgressRules.CosmeticDef cosmetic : ProgressRules.cosmetics()) {
            boolean unlocked = cosmetic.defaultUnlocked();
            if (!unlocked && !cosmetic.achievementId().isEmpty()) {
                LocalStore.Achievement row = byId.get(cosmetic.achievementId());
                unlocked = row != null && row.unlockedAt != null;
            } else if (!unlocked && cosmetic.completionsNeeded() > 0) {
                unlocked = campaignCompletions >= cosmetic.completionsNeeded();
            }
            LinearLayout card = ui.row();
            View glyph = new View(activity);
            int color = cosmetic.kind() == ProgressRules.COSMETIC_TRAIL ? UiKit.MINT
                    : cosmetic.kind() == ProgressRules.COSMETIC_FRAME ? UiKit.VIOLET : UiKit.AMBER;
            glyph.setBackground(ui.shape(unlocked ? color : UiKit.SURFACE_ELEVATED,
                    unlocked ? color : UiKit.BORDER, 10, 1));
            card.addView(glyph, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
            LinearLayout info = ui.column();
            info.setPaddingRelative(ui.dp(12), 0, 0, 0);
            ui.addText(info, activity.getString(cosmeticName(cosmetic.id())), 13,
                    unlocked ? UiKit.TEXT_PRIMARY : UiKit.TEXT_SECONDARY, true, 0, 0);
            ui.addText(info, activity.getString(unlocked
                            ? R.string.achievements_cosmetic_default
                            : R.string.achievements_cosmetic_locked),
                    10, unlocked ? UiKit.MINT : UiKit.TEXT_SECONDARY, false, 3, 0);
            card.addView(info, new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ui.addCard(page, card, 5);
        }
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    private static int achievementName(String id) {
        switch (id) {
            case ProgressRules.ACH_FIRST_TRACE: return R.string.achievement_first_trace_name;
            case ProgressRules.ACH_PRECISION_LEARNER: return R.string.achievement_precision_learner_name;
            case ProgressRules.ACH_GATE_RUNNER: return R.string.achievement_gate_runner_name;
            case ProgressRules.ACH_VAULT_FINDER: return R.string.achievement_vault_finder_name;
            case ProgressRules.ACH_CORE_COMPLETE: return R.string.achievement_core_complete_name;
            case ProgressRules.ACH_OBJECTIVE_HUNTER: return R.string.achievement_objective_hunter_name;
            case ProgressRules.ACH_PERSONAL_BEST: return R.string.achievement_personal_best_name;
            case ProgressRules.ACH_DAILY_SPARK: return R.string.achievement_daily_spark_name;
            default: return R.string.achievements_title;
        }
    }

    private static int achievementDescription(String id) {
        switch (id) {
            case ProgressRules.ACH_FIRST_TRACE: return R.string.achievement_first_trace_desc;
            case ProgressRules.ACH_PRECISION_LEARNER: return R.string.achievement_precision_learner_desc;
            case ProgressRules.ACH_GATE_RUNNER: return R.string.achievement_gate_runner_desc;
            case ProgressRules.ACH_VAULT_FINDER: return R.string.achievement_vault_finder_desc;
            case ProgressRules.ACH_CORE_COMPLETE: return R.string.achievement_core_complete_desc;
            case ProgressRules.ACH_OBJECTIVE_HUNTER: return R.string.achievement_objective_hunter_desc;
            case ProgressRules.ACH_PERSONAL_BEST: return R.string.achievement_personal_best_desc;
            case ProgressRules.ACH_DAILY_SPARK: return R.string.achievement_daily_spark_desc;
            default: return R.string.achievements_subtitle;
        }
    }

    private static int cosmeticName(String id) {
        switch (id) {
            case "trail.mint": return R.string.cosmetic_trail_mint;
            case "trail.violet": return R.string.cosmetic_trail_violet;
            case "trail.amber": return R.string.cosmetic_trail_amber;
            case "trail.coral": return R.string.cosmetic_trail_coral;
            case "trail.ice": return R.string.cosmetic_trail_ice;
            case "frame.none": return R.string.cosmetic_frame_none;
            case "frame.ring": return R.string.cosmetic_frame_ring;
            case "frame.hex": return R.string.cosmetic_frame_hex;
            case "frame.pulse": return R.string.cosmetic_frame_pulse;
            case "frame.prism": return R.string.cosmetic_frame_prism;
            case "emblem.first_trace": return R.string.cosmetic_emblem_first_trace;
            case "emblem.precision_learner": return R.string.cosmetic_emblem_precision_learner;
            case "emblem.gate_runner": return R.string.cosmetic_emblem_gate_runner;
            case "emblem.vault_finder": return R.string.cosmetic_emblem_vault_finder;
            case "emblem.core_complete": return R.string.cosmetic_emblem_core_complete;
            case "emblem.objective_hunter": return R.string.cosmetic_emblem_objective_hunter;
            case "emblem.personal_best": return R.string.cosmetic_emblem_personal_best;
            case "emblem.daily_spark": return R.string.cosmetic_emblem_daily_spark;
            default: return R.string.common_locked;
        }
    }
}
