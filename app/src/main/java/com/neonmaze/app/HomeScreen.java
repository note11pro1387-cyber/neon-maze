package com.neonmaze.app;

import android.app.Activity;
import android.widget.ImageView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.neonmaze.core.DailyChallenge;

/** Home dashboard: truthful local progress and one primary next action. */
public final class HomeScreen {
    public interface Actions {
        void onContinue();
        void onCampaign();
        void onModes();
        void onProfile();
        void onSettings();
        void onRetry();
        void onDaily();
    }

    private final Activity activity;
    private final UiKit ui;
    private final int completedLevels;
    private final int totalLevels;
    private final int totalAttempts;
    private final int totalCompletions;
    private final boolean hasAnyProgress;
    private final boolean allComplete;
    private final String playerName;
    private final boolean profileConfigured;
    private final boolean profileLoading;
    private final boolean profileLoadFailed;
    private final DailyChallenge.Selection daily;
    private final LocalStore.DailyRecord dailyRecord;
    private final boolean dataLoading;
    private final boolean storeError;
    private final boolean dailyLoading;
    private final boolean dailyLoadFailed;
    private final Actions actions;

    public HomeScreen(@NonNull Activity activity, @NonNull UiKit ui,
                      int completedLevels, int totalLevels, int totalAttempts, int totalCompletions,
                      boolean hasAnyProgress, boolean allComplete,
                      String playerName, boolean profileConfigured, boolean profileLoading,
                      boolean profileLoadFailed, DailyChallenge.Selection daily,
                      LocalStore.DailyRecord dailyRecord, boolean dataLoading, boolean storeError,
                      boolean dailyLoading, boolean dailyLoadFailed, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.completedLevels = completedLevels;
        this.totalLevels = Math.max(1, totalLevels);
        this.totalAttempts = totalAttempts;
        this.totalCompletions = totalCompletions;
        this.hasAnyProgress = hasAnyProgress;
        this.allComplete = allComplete;
        this.playerName = playerName;
        this.profileConfigured = profileConfigured;
        this.profileLoading = profileLoading;
        this.profileLoadFailed = profileLoadFailed;
        this.daily = daily;
        this.dailyRecord = dailyRecord;
        this.dataLoading = dataLoading;
        this.storeError = storeError;
        this.dailyLoading = dailyLoading;
        this.dailyLoadFailed = dailyLoadFailed;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.brand_eyebrow),
                activity.getString(R.string.home_title), activity.getString(R.string.home_subtitle));

        if (storeError) {
            LinearLayout notice = ui.card();
            ui.addText(notice, activity.getString(R.string.home_store_error),
                    12, UiKit.AMBER, true, 0, 0);
            ui.addButton(notice, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addCard(page, notice, 0);
        } else if (dataLoading) {
            LinearLayout notice = ui.card();
            ui.addText(notice, activity.getString(R.string.home_loading),
                    12, UiKit.TEXT_SECONDARY, false, 0, 0);
            ui.addCard(page, notice, 0);
        }

        LinearLayout hero = ui.card();
        hero.setBackground(ui.shape(UiKit.SURFACE_ELEVATED, UiKit.BORDER, 22, 1));
        ImageView optionalHeroArt = new ImageView(activity);
        optionalHeroArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        optionalHeroArt.setBackground(ui.shape(UiKit.SURFACE, UiKit.BORDER, 16, 0));
        optionalHeroArt.setClipToOutline(true);
        optionalHeroArt.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(optionalHeroArt, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(132)));
        ui.loadOptionalContentImage(optionalHeroArt, "ui/home-hero.webp",
                ui.dp(720), ui.dp(264));
        ui.addText(hero, playerName, 12, UiKit.MINT, true, 0, 0);
        ui.addGap(hero, 9);
        boolean progressReady = !dataLoading && !storeError;
        String heroState = !progressReady
                ? activity.getString(storeError ? R.string.home_store_error : R.string.home_loading)
                : activity.getString(allComplete ? R.string.home_all_complete
                        : hasAnyProgress ? R.string.home_returning : R.string.home_new_player);
        ui.addText(hero, heroState, 19, UiKit.TEXT_PRIMARY, true, 0, 0);
        if (progressReady) {
            String progressLabel = completedLevels == 0
                    ? activity.getString(R.string.home_new_player)
                    : activity.getString(R.string.home_levels_done, completedLevels, totalLevels);
            ui.addText(hero, progressLabel, 13, UiKit.TEXT_SECONDARY, false, 7, 0);
            LinearLayout progressTrack = ui.row();
            progressTrack.setBackground(ui.shape(UiKit.DEEP, UiKit.BORDER, 8, 1));
            View progress = new View(activity);
            progress.setBackground(ui.shape(UiKit.MINT, UiKit.MINT, 8, 0));
            int percent = Math.max(0, Math.min(100, Math.round(completedLevels * 100f / totalLevels)));
            progressTrack.addView(progress, new LinearLayout.LayoutParams(0, ui.dp(7), percent));
            View remaining = new View(activity);
            progressTrack.addView(remaining, new LinearLayout.LayoutParams(0, ui.dp(7), 100 - percent));
            LinearLayout.LayoutParams trackParams = ui.matchWrap();
            trackParams.topMargin = ui.dp(14);
            hero.addView(progressTrack, trackParams);
        }
        ui.addCard(page, hero, 0);

        if (profileLoading || profileLoadFailed) {
            LinearLayout profileState = ui.insetCard(UiKit.SURFACE, profileLoadFailed ? UiKit.AMBER : UiKit.BORDER);
            ui.addText(profileState, activity.getString(profileLoadFailed
                            ? R.string.profile_load_error : R.string.home_loading),
                    12, profileLoadFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, true, 0, 0);
            if (profileLoadFailed) ui.addButton(profileState, ui.button(
                    activity.getString(R.string.home_retry), UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addCard(page, profileState, 10);
        } else if (!profileConfigured) {
            LinearLayout setup = ui.insetCard(UiKit.SURFACE_ELEVATED, UiKit.VIOLET);
            ui.addText(setup, activity.getString(R.string.home_profile_setup_title),
                    14, UiKit.TEXT_PRIMARY, true, 0, 0);
            ui.addText(setup, activity.getString(R.string.home_profile_setup_desc),
                    12, UiKit.TEXT_SECONDARY, false, 5, 0);
            ui.addButton(setup, ui.button(activity.getString(R.string.home_profile_setup_action),
                    UiKit.ButtonStyle.SECONDARY, actions::onProfile), true);
            ui.addCard(page, setup, 12);
        }

        int primaryLabel = hasAnyProgress ? R.string.home_continue : R.string.home_play;
        ui.addButton(page, ui.button(activity.getString(primaryLabel), UiKit.ButtonStyle.PRIMARY,
                progressReady ? actions::onContinue : null, UiKit.Icon.PLAY), true);
        ui.addButton(page, ui.button(activity.getString(R.string.home_campaign),
                UiKit.ButtonStyle.SECONDARY, progressReady ? actions::onCampaign : null,
                UiKit.Icon.LEVELS), true);

        ui.addSection(page, activity.getString(R.string.home_daily), null);
        LinearLayout dailyCard = ui.card();
        if (daily == null) {
            ui.addText(dailyCard, activity.getString(R.string.home_daily_unavailable),
                    13, UiKit.TEXT_SECONDARY, false, 0, 0);
        } else {
            ui.addText(dailyCard, daily.utcDate(), 12, UiKit.VIOLET, true, 0, 0);
            if (dailyLoading) {
                ui.addText(dailyCard, activity.getString(R.string.home_daily_loading),
                        14, UiKit.TEXT_SECONDARY, true, 7, 0);
            } else if (dailyLoadFailed) {
                ui.addText(dailyCard, activity.getString(R.string.home_daily_load_error),
                        14, UiKit.AMBER, true, 7, 0);
            } else {
                ui.addText(dailyCard, dailyRecord == null
                                ? activity.getString(R.string.home_daily_ready)
                                : activity.getString(R.string.home_daily_done,
                                ui.formatTime(dailyRecord.bestTimeMs)),
                        14, UiKit.TEXT_PRIMARY, true, 7, 0);
            }
            ui.addText(dailyCard, activity.getString(R.string.mode_daily_desc),
                    12, UiKit.TEXT_SECONDARY, false, 6, 0);
            boolean dailyReady = !dailyLoading && !dailyLoadFailed;
            ui.addButton(dailyCard, ui.button(activity.getString(R.string.mode_start),
                    UiKit.ButtonStyle.SECONDARY, dailyReady ? actions::onDaily : null,
                    UiKit.Icon.STAR), true);
        }
        ui.addCard(page, dailyCard, 0);

        ui.addSection(page, activity.getString(R.string.home_progress), null);
        LinearLayout stats = ui.row();
        statCard(stats, activity.getString(R.string.profile_attempts),
                progressReady ? ui.number(totalAttempts) : "—");
        statCard(stats, activity.getString(R.string.profile_completions),
                progressReady ? ui.number(totalCompletions) : "—");
        LinearLayout.LayoutParams statsParams = ui.matchWrap();
        statsParams.topMargin = ui.dp(2);
        page.addView(stats, statsParams);

        ui.addSection(page, nullSafe(activity.getString(R.string.modes_title)), null);
        ui.addButton(page, ui.button(activity.getString(R.string.home_modes),
                UiKit.ButtonStyle.SECONDARY, actions::onModes, UiKit.Icon.PLAY), true);
        ui.addButton(page, ui.button(activity.getString(R.string.home_profile),
                UiKit.ButtonStyle.QUIET, actions::onProfile, UiKit.Icon.PROFILE), true);
        ui.addButton(page, ui.button(activity.getString(R.string.home_settings),
                UiKit.ButtonStyle.QUIET, actions::onSettings, UiKit.Icon.SETTINGS), true);
        return scroll;
    }

    private void statCard(LinearLayout parent, String label, String value) {
        LinearLayout card = ui.insetCard(UiKit.SURFACE, UiKit.BORDER);
        ui.addText(card, value, 22, UiKit.TEXT_PRIMARY, true, 0, 0);
        ui.addText(card, label, 11, UiKit.TEXT_SECONDARY, false, 3, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMarginEnd(ui.dp(8));
        parent.addView(card, p);
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }
}
