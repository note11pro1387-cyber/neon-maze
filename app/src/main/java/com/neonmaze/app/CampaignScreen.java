package com.neonmaze.app;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.DifficultyProfile;
import com.neonmaze.core.LevelSystem.LevelDefinition;

import java.util.Map;

/** Catalog-driven Campaign map with the level briefing contained in this screen. */
public final class CampaignScreen {
    public interface Actions {
        void onSelectCampaignLevel(@NonNull LevelDefinition level);
        void onStartCampaignLevel(@NonNull LevelDefinition level);
        void onLockedLevel(@NonNull LevelDefinition level);
        void onRetry();
        void onBack();
    }

    private final Activity activity;
    private final UiKit ui;
    private final Catalog catalog;
    private final Map<String, LocalStore.LevelProgress> progress;
    private final String selectedLevelId;
    private final boolean progressLoading;
    private final boolean progressFailed;
    private final Actions actions;

    public CampaignScreen(@NonNull Activity activity, @NonNull UiKit ui,
                          @NonNull Catalog catalog,
                          Map<String, LocalStore.LevelProgress> progress,
                          String selectedLevelId, boolean progressLoading, boolean progressFailed, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.catalog = catalog;
        this.progress = progress;
        this.selectedLevelId = selectedLevelId;
        this.progressLoading = progressLoading;
        this.progressFailed = progressFailed;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_campaign_eyebrow),
                activity.getString(R.string.campaign_title),
                activity.getString(R.string.campaign_subtitle));
        if (progressLoading || progressFailed) {
            LinearLayout notice = ui.card();
            ui.addText(notice, activity.getString(progressFailed ? R.string.home_store_error : R.string.home_loading),
                    12, progressFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, true, 0, 0);
            if (progressFailed) ui.addButton(notice, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addCard(page, notice, 0);
        }
        for (int i = 0; i < catalog.size(); i++) addLevelNode(page, i, catalog.get(i));
        LevelDefinition selected = selectedLevel();
        if (selected != null) addBriefing(page, selected);
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    private void addLevelNode(LinearLayout page, int index, LevelDefinition level) {
        LocalStore.LevelProgress row = progress.get(level.levelId());
        boolean unlocked = !progressLoading && !progressFailed
                && (row == null ? index == 0 : row.unlocked);
        boolean completed = !progressLoading && !progressFailed && row != null && row.completed;
        boolean selected = level.levelId().equals(selectedLevelId);
        int foreground = completed ? UiKit.MINT : unlocked ? UiKit.TEXT_PRIMARY : UiKit.TEXT_SECONDARY;
        int background = selected ? UiKit.SURFACE_ELEVATED : UiKit.SURFACE;
        LinearLayout card = ui.row();
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        card.setBackground(ui.shape(background, selected ? UiKit.MINT : UiKit.BORDER, 16,
                selected ? 2 : 1));
        View node;
        if (unlocked) {
            TextView number = ui.text(completed ? "✓" : ui.number(index + 1),
                    18, foreground, true);
            number.setGravity(android.view.Gravity.CENTER);
            node = number;
        } else {
            node = ui.icon(UiKit.Icon.LOCK, UiKit.TEXT_SECONDARY, 21,
                    activity.getString(R.string.campaign_locked));
        }
        node.setBackground(ui.shape(UiKit.DEEP, unlocked ? UiKit.MINT : UiKit.BORDER, 28, 1));
        card.addView(node, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)));
        LinearLayout info = ui.column();
        info.setPaddingRelative(ui.dp(12), 0, ui.dp(8), 0);
        ui.addText(info, activity.getString(R.string.campaign_level, index + 1),
                10, UiKit.MINT, true, 0, 0);
        ui.addText(info, levelName(level), 15, foreground, true, 3, 0);
        ui.addText(info, levelDescription(level), 11,
                UiKit.TEXT_SECONDARY, false, 3, 0);
        card.addView(info, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout status = ui.column();
        status.setGravity(android.view.Gravity.CENTER);
        String stateLabel = progressLoading ? activity.getString(R.string.home_loading)
                : progressFailed ? activity.getString(R.string.home_store_error)
                : completed ? activity.getString(R.string.campaign_completed)
                : unlocked ? activity.getString(selected ? R.string.campaign_current
                : R.string.campaign_available) : activity.getString(R.string.campaign_locked);
        ui.addText(status, stateLabel,
                10, foreground, true, 0, 0);
        if (completed && row != null) {
            ui.addText(status, stars(row.stars), 12, UiKit.AMBER, true, 4, 0);
        }
        card.addView(status, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setContentDescription(activity.getString(R.string.campaign_level, index + 1)
                + ". " + levelName(level) + ". " + stateLabel
                + (completed && row != null ? ". " + activity.getString(
                R.string.campaign_stars_value, row.stars) : ""));
        card.setFocusable(true);
        card.setClickable(true);
        if (!progressLoading && !progressFailed) {
            card.setOnClickListener(v -> {
                if (unlocked) actions.onSelectCampaignLevel(level);
                else actions.onLockedLevel(level);
            });
        } else {
            card.setEnabled(false);
            card.setAlpha(.72f);
        }
        ui.addCard(page, card, 0);
        if (index < catalog.size() - 1) {
            View connector = new View(activity);
            connector.setBackgroundColor(UiKit.BORDER);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ui.dp(2), ui.dp(12));
            line.setMarginStart(ui.dp(34));
            page.addView(connector, line);
        }
    }

    private void addBriefing(LinearLayout page, LevelDefinition level) {
        int index = indexOf(level.levelId());
        LocalStore.LevelProgress stored = progress.get(level.levelId());
        boolean unlocked = !progressLoading && !progressFailed
                && (stored == null ? index == 0 : stored.unlocked);
        LinearLayout card = ui.card();
        ui.addText(card, activity.getString(R.string.campaign_briefing),
                11, UiKit.MINT, true, 0, 0);
        ui.addText(card, levelName(level), 20,
                UiKit.TEXT_PRIMARY, true, 6, 0);
        ui.addText(card, levelDescription(level), 13,
                UiKit.TEXT_SECONDARY, false, 5, 0);
        ui.addSection(card, activity.getString(R.string.campaign_objectives), null);
        DifficultyProfile metrics = level.difficultyProfile();
        ui.addText(card, activity.getString(R.string.campaign_mandatory) + "  "
                        + metrics.mandatoryObjectiveCount(), 13, UiKit.TEXT_PRIMARY, false, 0, 0);
        ui.addText(card, activity.getString(R.string.campaign_optional) + "  "
                        + metrics.optionalObjectiveCount(), 13, UiKit.TEXT_PRIMARY, false, 4, 0);
        ui.addText(card, activity.getString(R.string.campaign_turns_branches,
                        metrics.turnCount(), metrics.branchCount()),
                12, UiKit.TEXT_SECONDARY, false, 8, 0);
        if (metrics.timedGateCount() > 0 || metrics.keyLockedGateCount() > 0) {
            ui.addText(card, activity.getString(R.string.campaign_gate_summary,
                            metrics.timedGateCount(), metrics.keyLockedGateCount()),
                    12, UiKit.VIOLET, true, 5, 0);
        }
        ui.addDivider(card, 12, 12);
        ui.addText(card, activity.getString(R.string.campaign_par) + "  "
                        + ui.formatTime(level.parTimeMs()), 12, UiKit.AMBER, true, 0, 0);
        ui.addText(card, activity.getString(R.string.campaign_min_width) + "  "
                        + Math.round(metrics.minWidthWorldUnits()), 12,
                UiKit.TEXT_SECONDARY, false, 5, 0);
        if (stored != null && stored.bestTimeMs > 0L) {
            ui.addText(card, activity.getString(R.string.campaign_personal_best) + "  "
                            + ui.formatTime(stored.bestTimeMs),
                    12, UiKit.MINT, true, 5, 0);
        }
        if (!progressLoading && !progressFailed && !unlocked) {
            ui.addText(card, activity.getString(R.string.campaign_locked_detail),
                    12, UiKit.AMBER, false, 12, 0);
        }
        ui.addButton(card, ui.button(activity.getString(unlocked
                        ? R.string.campaign_start : R.string.campaign_locked),
                UiKit.ButtonStyle.PRIMARY, unlocked && !progressLoading && !progressFailed
                        ? () -> actions.onStartCampaignLevel(level) : null), true);
        ui.addCard(page, card, 20);
    }

    private String levelName(LevelDefinition level) {
        int id = activity.getResources().getIdentifier(level.displayNameKey(), "string",
                activity.getPackageName());
        return id == 0 ? level.levelId() : activity.getString(id);
    }

    private String levelDescription(LevelDefinition level) {
        String key = level.displayNameKey();
        key = key.endsWith("_name") ? key.substring(0, key.length() - 5) + "_desc" : key + "_desc";
        int id = activity.getResources().getIdentifier(key, "string", activity.getPackageName());
        return id == 0 ? activity.getString(R.string.campaign_subtitle) : activity.getString(id);
    }

    private LevelDefinition selectedLevel() {
        if (selectedLevelId == null) return null;
        try { return catalog.byId(selectedLevelId); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private int indexOf(String id) {
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).levelId().equals(id)) return i;
        }
        return 0;
    }

    private static String stars(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 3; i++) value.append(i < count ? "★" : "☆");
        return value.toString();
    }
}
