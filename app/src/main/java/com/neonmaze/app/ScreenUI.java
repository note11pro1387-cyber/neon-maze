package com.neonmaze.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.neonmaze.core.DailyChallenge;
import com.neonmaze.core.GameEngine;
import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.LevelValidator;
import com.neonmaze.core.ProgressRules;
import com.neonmaze.core.ReplaySystem;
import com.neonmaze.core.ReplaySystem.GhostTimeline;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.RunResult;
import com.neonmaze.core.levels.Level01;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Navigation host and application-facing command coordinator. Page layouts live in their own classes. */
public final class ScreenUI extends FrameLayout {
    private enum Route { HOME, CAMPAIGN, MODES, PROFILE, SETTINGS, TUTORIAL, ACHIEVEMENTS, GAME, RESULT }

    private final Activity activity;
    private final Catalog catalog;
    private final LocalStore store;
    private final FeedbackSystem feedback;
    private final ContentAssetResolver contentAssets;
    private final Runnable requestPhotoPicker;
    private final Runnable recreateActivity;
    private final ArrayList<Route> backStack = new ArrayList<>();

    private LocalStore.Settings settings;
    private Map<String, LocalStore.LevelProgress> levelProgress = new HashMap<>();
    private List<LocalStore.Achievement> achievementRows = new ArrayList<>();
    @Nullable private LocalStore.Profile profile;
    @Nullable private DailyChallenge.Selection dailySelection;
    @Nullable private LocalStore.DailyRecord dailyRecord;
    @Nullable private String selectedLevelId;
    @Nullable private String pendingPhotoPath;
    private int selectedAvatar;
    private boolean profileEditing;
    private boolean photoImportPending;
    private long photoImportGeneration;
    private boolean dataLoading = true;
    private boolean storeError;
    private boolean profileLoading = true;
    private boolean profileLoadFailed;
    private boolean achievementsLoading = true;
    private boolean achievementsLoadFailed;
    private boolean dailyLoading = true;
    private boolean dailyLoadFailed;
    private boolean ghostCheckPending;
    private boolean ghostAvailable;
    @Nullable private GhostTimeline ghostTimeline;
    @Nullable private GameView gameView;
    @Nullable private GameEngine gameEngine;
    @Nullable private LevelDefinition activeLevel;
    @Nullable private ReplayMode activeMode;
    @Nullable private String activeDailyId;
    @Nullable private GameEngine.RunOutcome pendingOutcome;
    @Nullable private GameEngine.RunOutcome resultOutcome;
    @Nullable private LocalStore.RecordRunResult runSaveResult;
    private boolean resultSavePending;
    private boolean resultSaveFailed;
    private boolean resultNewBest;
    private long resultPreviousBestMs;
    private boolean hostPaused;
    private boolean tutorialNeedsResumeRender;
    private boolean disposed;
    private boolean discardOutcome;
    private boolean transitionPending;
    private boolean tutorialRunning;
    private boolean tutorialComplete;
    private boolean tutorialFailed;
    private long routeGeneration;
    private long ghostQueryGeneration;
    private long progressQueryGeneration;
    private long profileQueryGeneration;
    private long achievementQueryGeneration;
    private long dailyQueryGeneration;

    public ScreenUI(@NonNull Context context,
                    @NonNull Catalog catalog,
                    @NonNull LocalStore store,
                    @NonNull FeedbackSystem feedback,
                    @NonNull ContentAssetResolver contentAssets,
                    @NonNull Runnable requestPhotoPicker,
                    @NonNull Runnable recreateActivity) {
        super(context);
        if (!(context instanceof Activity)) {
            throw new IllegalArgumentException("ScreenUI requires an Activity context");
        }
        this.activity = (Activity) context;
        this.catalog = catalog;
        this.store = store;
        this.feedback = feedback;
        this.contentAssets = contentAssets;
        this.requestPhotoPicker = requestPhotoPicker;
        this.recreateActivity = recreateActivity;
        this.settings = store.loadSettings();
        this.dailySelection = DailyChallenge.forTimestamp(
                System.currentTimeMillis(), ReplaySystem.GAME_RULES_VERSION, catalog);
        // Progress is asynchronous: do not select or expose a level until its saved unlock state is read.
        this.selectedLevelId = null;
        setBackgroundColor(UiKit.BACKGROUND);
        setClipToPadding(false);
        setClipChildren(false);
        backStack.add(Route.HOME);
        refreshData();
        render();
        refreshDailyRecord();
    }

    // ------------------------------------------------------------------
    // Navigation state
    // ------------------------------------------------------------------

    public String currentRouteName() {
        Route route = currentRoute();
        return route == Route.GAME || route == Route.RESULT ? "HOME" : route.name();
    }

    public void restoreSafeRoute(@Nullable String routeName) {
        if (routeName == null) return;
        Route route;
        try { route = Route.valueOf(routeName); }
        catch (IllegalArgumentException ignored) { route = Route.HOME; }
        if (route == Route.GAME || route == Route.RESULT || route == Route.TUTORIAL) {
            // A live Engine is intentionally never reconstructed from a Bundle.
            route = Route.HOME;
        }
        backStack.clear();
        backStack.add(Route.HOME);
        if (route != Route.HOME) backStack.add(route);
        render();
    }

    public boolean onBackPressed() {
        Route route = currentRoute();
        if (route == Route.GAME) {
            requestGameExitOrPause();
            return true;
        }
        if (route == Route.TUTORIAL && tutorialRunning) {
            showTutorialExitConfirmation();
            return true;
        }
        if (route == Route.PROFILE && profileEditing) {
            cancelProfileEdit();
            return true;
        }
        if (backStack.size() > 1) {
            backStack.remove(backStack.size() - 1);
            if (currentRoute() == Route.PROFILE) profileEditing = false;
            transitionPending = true;
            render();
            return true;
        }
        return false;
    }

    private Route currentRoute() {
        return backStack.isEmpty() ? Route.HOME : backStack.get(backStack.size() - 1);
    }

    private void navigate(Route route) {
        if (currentRoute() == route) return;
        backStack.add(route);
        routeGeneration++;
        transitionPending = true;
        render();
    }

    private void replace(Route route) {
        if (backStack.isEmpty()) backStack.add(Route.HOME);
        else backStack.set(backStack.size() - 1, route);
        routeGeneration++;
        transitionPending = true;
        render();
    }

    private void goHome() {
        if (profileEditing) cancelProfileEdit();
        backStack.clear();
        backStack.add(Route.HOME);
        routeGeneration++;
        transitionPending = true;
        render();
    }

    private void goCampaign() {
        if (profileEditing) cancelProfileEdit();
        backStack.clear();
        backStack.add(Route.HOME);
        backStack.add(Route.CAMPAIGN);
        if (selectedLevelId == null && !dataLoading && !storeError) selectedLevelId = findContinueLevelId();
        transitionPending = true;
        render();
    }

    // ------------------------------------------------------------------
    // Screen composition — this class owns routing, not page layout.
    // ------------------------------------------------------------------

    private void render() {
        for (int i = 0; i < getChildCount(); i++) {
            View old = getChildAt(i);
            old.animate().cancel();
            old.setAlpha(1f);
        }
        removeAllViews();
        boolean animateTransition = transitionPending && !settings.reducedMotion;
        transitionPending = false;
        UiKit ui = new UiKit(activity, settings, contentAssets);
        View screen;
        switch (currentRoute()) {
            case CAMPAIGN:
                screen = new CampaignScreen(activity, ui, catalog, levelProgress,
                        selectedLevelId, dataLoading, storeError, new CampaignScreen.Actions() {
                    @Override public void onSelectCampaignLevel(LevelDefinition level) {
                        selectedLevelId = level.levelId();
                        render();
                    }
                    @Override public void onStartCampaignLevel(LevelDefinition level) {
                        selectedLevelId = level.levelId();
                        startRun(level, ReplayMode.CAMPAIGN, null, null);
                    }
                    @Override public void onLockedLevel(LevelDefinition level) {
                        toast(R.string.campaign_locked_detail);
                    }
                    @Override public void onRetry() { refreshData(); }
                    @Override public void onBack() { onBackPressed(); }
                }).create();
                break;
            case MODES:
                screen = new ModesScreen(activity, ui, catalog, levelProgress,
                        dailySelection, selectedLevelId, ghostAvailable, ghostCheckPending,
                        dataLoading, storeError, dailyLoading, dailyLoadFailed,
                        new ModesScreen.Actions() {
                    @Override public void onCampaign() { goCampaign(); }
                    @Override public void onSelectLevel(LevelDefinition level) {
                        selectedLevelId = level.levelId();
                        ghostAvailable = false;
                        ghostTimeline = null;
                        refreshGhostAvailability();
                        render();
                    }
                    @Override public void onStart(LevelDefinition level, ReplayMode mode,
                                                  String dailyId) {
                        if (mode == ReplayMode.DAILY_CHALLENGE) {
                            refreshDailySelectionIfNeeded();
                            DailyChallenge.Selection current = dailySelection;
                            if (current != null) {
                                startRun(current.level(), mode, current.challengeId(), null);
                            }
                            return;
                        }
                        selectedLevelId = level.levelId();
                        if (mode == ReplayMode.GHOST_RACE) {
                            loadGhostAndStart(level);
                        } else {
                            startRun(level, mode, dailyId, null);
                        }
                    }
                    @Override public void onRetry() { refreshData(); refreshDailyRecord(); }
                    @Override public void onBack() { onBackPressed(); }
                }).create();
                break;
            case PROFILE:
                screen = new ProfileScreen(activity, ui, profile, levelProgress,
                        achievementRows, profileEditing, pendingPhotoPath, selectedAvatar,
                        profileLoading, profileLoadFailed, photoImportPending, dataLoading, storeError,
                        achievementsLoading || achievementsLoadFailed,
                        new ProfileScreen.Actions() {
                    @Override public void onEdit() {
                        if (profileLoading || profileLoadFailed) { toast(R.string.nav_loading); return; }
                        profileEditing = true; render();
                    }
                    @Override public void onRetry() { refreshData(); }
                    @Override public void onSave(String name, String avatarId, String photoPath) {
                        saveProfile(name, avatarId, photoPath);
                    }
                    @Override public void onSelectBuiltIn(String avatarId) {
                        if (profileLoading || profileLoadFailed || photoImportPending) return;
                        selectedAvatar = avatarIndex(avatarId);
                        if (pendingPhotoPath != null) discardPhoto(pendingPhotoPath);
                        pendingPhotoPath = null;
                        if (profile != null) {
                            profile = new LocalStore.Profile(profile.id, profile.displayName, 0,
                                    normalizeAvatarId(avatarId), null,
                                    profile.createdAt, profile.updatedAt);
                        }
                        render();
                    }
                    @Override public void onChoosePhoto() {
                        if (!profileLoading && !profileLoadFailed && !photoImportPending) requestPhotoPicker.run();
                    }
                    @Override public void onUseBuiltIn() {
                        if (!profileLoading && !profileLoadFailed && !photoImportPending) clearPendingPhotoAndUseBuiltIn();
                    }
                    @Override public void onAchievements() { navigate(Route.ACHIEVEMENTS); }
                    @Override public void onBack() {
                        if (profileEditing) cancelProfileEdit(); else onBackPressed();
                    }
                }).create();
                break;
            case SETTINGS:
                screen = new SettingsScreen(activity, ui, settings,
                        new SettingsScreen.Actions() {
                    @Override public void onSettingsChanged(LocalStore.Settings value) {
                        applySettings(value);
                    }
                    @Override public void onVolumePreview(int volumePercent) {
                        feedback.previewVolumePercent(volumePercent);
                    }
                    @Override public void onResetProgress() { confirmReset(ResetKind.PROGRESS); }
                    @Override public void onResetProfile() { confirmReset(ResetKind.PROFILE); }
                    @Override public void onResetAll() { confirmReset(ResetKind.ALL); }
                    @Override public void onBack() { onBackPressed(); }
                }).create();
                break;
            case TUTORIAL:
                TutorialScreen tutorial = new TutorialScreen(activity, ui,
                        new TutorialScreen.Actions() {
                    @Override public void onStart() { startTutorial(); }
                    @Override public void onRetry() { startTutorial(); }
                    @Override public void onFinish() { finishTutorial(); }
                    @Override public void onSkip() { showTutorialExitConfirmation(); }
                    @Override public void onBack() { onBackPressed(); }
                });
                if (tutorialRunning && gameView != null) screen = tutorial.createRunning(gameView);
                else if (tutorialComplete || tutorialFailed) {
                    screen = tutorial.createOutcome(tutorialComplete, tutorialFailed);
                } else screen = tutorial.createIntro();
                break;
            case ACHIEVEMENTS:
                screen = new AchievementsScreen(activity, ui, achievementRows, levelProgress,
                        achievementsLoading, achievementsLoadFailed,
                        new AchievementsScreen.Actions() {
                    @Override public void onRetry() { refreshAchievements(); }
                    @Override public void onBack() { onBackPressed(); }
                }).create();
                break;
            case GAME:
                screen = gameView == null ? new View(activity) : gameView;
                screen.setBackgroundColor(UiKit.BACKGROUND);
                break;
            case RESULT:
                screen = buildResultScreen(ui);
                break;
            case HOME:
            default:
                screen = buildHomeScreen(ui);
                break;
        }
        if (screen.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) screen.getParent()).removeView(screen);
        }
        screen.setAlpha(animateTransition ? 0f : 1f);
        addView(screen, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (animateTransition) {
            screen.animate().alpha(1f).setDuration(180L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setListener(null);
        }
        applySettingsToGame();
    }

    private View buildHomeScreen(UiKit ui) {
        int completed = 0, attempts = 0, completions = 0;
        boolean any = false;
        for (LocalStore.LevelProgress row : levelProgress.values()) {
            if (row.completed) completed++;
            attempts += row.attempts;
            completions += row.completions;
            any |= row.attempts > 0 || row.completions > 0;
        }
        boolean allComplete = completed >= catalog.size();
        String name = profile == null || profile.displayName == null
                || profile.displayName.trim().isEmpty()
                ? activity.getString(R.string.profile_default_name) : profile.displayName;
        return new HomeScreen(activity, ui, completed, attempts, completions,
                any, allComplete, name,
                profile != null && profile.displayName != null && !profile.displayName.trim().isEmpty(),
                profileLoading, profileLoadFailed, dailySelection, dailyRecord,
                dataLoading, storeError, dailyLoading, dailyLoadFailed,
                new HomeScreen.Actions() {
            @Override public void onContinue() { goCampaign(); }
            @Override public void onCampaign() { goCampaign(); }
            @Override public void onModes() { navigate(Route.MODES); }
            @Override public void onProfile() {
                if (profileLoading || profileLoadFailed) { toast(R.string.nav_loading); return; }
                profileEditing = profile == null || profile.displayName == null
                        || profile.displayName.trim().isEmpty();
                navigate(Route.PROFILE);
            }
            @Override public void onSettings() { navigate(Route.SETTINGS); }
            @Override public void onRetry() { refreshData(); refreshDailyRecord(); }
            @Override public void onDaily() {
                refreshDailySelectionIfNeeded();
                if (dailySelection != null) startRun(dailySelection.level(),
                        ReplayMode.DAILY_CHALLENGE, dailySelection.challengeId(), null);
            }
        }).create();
    }

    private View buildResultScreen(UiKit ui) {
        if (resultOutcome == null || activeLevel == null) {
            return errorPage(ui, R.string.nav_error);
        }
        String nextId = null;
        if (resultOutcome.result() == RunResult.COMPLETED
                && resultOutcome.mode() == ReplayMode.CAMPAIGN) {
            String candidate = ProgressRules.nextLevelId(catalog, activeLevel.levelId());
            if (candidate != null && !candidate.isEmpty() && runSaveResult != null
                    && runSaveResult.progressSnapshot.isLevelUnlocked(candidate)) {
                nextId = candidate;
            }
        }
        final String next = nextId;
        return new ResultScreen(activity, ui, activeLevel, resultOutcome,
                resultSavePending, resultSaveFailed, resultNewBest, resultPreviousBestMs, next,
                new ResultScreen.Actions() {
            @Override public void onRetry() {
                if (activeLevel != null && activeMode != null) {
                    replace(Route.GAME);
                    startRun(activeLevel, activeMode, activeDailyId, ghostTimeline);
                }
            }
            @Override public void onNext() {
                if (next != null) {
                    selectedLevelId = next;
                    LevelDefinition nextLevel = catalog.byId(next);
                    startRun(nextLevel, ReplayMode.CAMPAIGN, null, null);
                }
            }
            @Override public void onCampaign() { goCampaign(); }
            @Override public void onHome() { goHome(); }
        }).create();
    }

    private View errorPage(UiKit ui, int messageId) {
        android.widget.LinearLayout page = ui.column();
        page.setPadding(ui.dp(24), ui.dp(32), ui.dp(24), ui.dp(32));
        page.setGravity(android.view.Gravity.CENTER);
        ui.addText(page, activity.getString(messageId), 17, UiKit.TEXT_PRIMARY,
                true, 0, 0);
        ui.addButton(page, ui.button(activity.getString(R.string.common_retry),
                UiKit.ButtonStyle.PRIMARY, this::goHome), true);
        return page;
    }

    // ------------------------------------------------------------------
    // Gameplay / Engine lifecycle
    // ------------------------------------------------------------------

    private void startRun(@NonNull LevelDefinition level, @NonNull ReplayMode mode,
                          @Nullable String dailyId, @Nullable GhostTimeline ghost) {
        if (mode == ReplayMode.DAILY_CHALLENGE) {
            if (dailyLoading || dailyLoadFailed || dailySelection == null
                    || dailyId == null || !dailySelection.challengeId().equals(dailyId)
                    || !dailySelection.level().levelId().equals(level.levelId())) {
                toast(dailyLoadFailed ? R.string.home_daily_load_error : R.string.home_daily_loading);
                return;
            }
        } else if (mode != ReplayMode.TUTORIAL) {
            if (dataLoading || storeError) {
                toast(storeError ? R.string.home_store_error : R.string.nav_loading);
                return;
            }
            if (!isUnlocked(level.levelId())) {
                toast(R.string.campaign_locked_detail);
                return;
            }
        }
        try {
            LevelValidator.requireValid(level);
            GameEngine engine = new GameEngine(level, mode, System.currentTimeMillis());
            GameView view = new GameView(activity);
            view.setLeftHanded(settings.leftHandedUi);
            view.setVisualOptions(settings.reducedEffects, settings.highContrast);
            view.setEngine(engine, level, feedback);
            if (ghost != null) view.setGhost(ghost);
            attachGameListener(view, level, mode, dailyId);
            releaseGameView();
            this.activeLevel = level;
            this.activeMode = mode;
            this.activeDailyId = dailyId;
            this.gameEngine = engine;
            this.gameView = view;
            this.ghostTimeline = ghost;
            this.resultOutcome = null;
            this.runSaveResult = null;
            this.resultSavePending = false;
            this.resultSaveFailed = false;
            this.resultNewBest = false;
            this.resultPreviousBestMs = 0L;
            this.pendingOutcome = null;
            this.discardOutcome = false;
            this.tutorialRunning = mode == ReplayMode.TUTORIAL;
            this.tutorialComplete = false;
            this.tutorialFailed = false;
            if (mode == ReplayMode.TUTORIAL) replace(Route.TUTORIAL);
            else if (currentRoute() == Route.RESULT || currentRoute() == Route.GAME) replace(Route.GAME);
            else navigate(Route.GAME);
        } catch (RuntimeException invalidRun) {
            toast(R.string.common_error_catalog);
        }
    }

    private void attachGameListener(@NonNull GameView view, @NonNull LevelDefinition level,
                                    @NonNull ReplayMode mode, @Nullable String dailyId) {
        view.setListener(new GameView.Listener() {
            @Override public void onEngineStateChanged(GameEngine.EngineState previous,
                                                        GameEngine.EngineState current) {
                // The Engine owns gameplay transitions; the host reacts only to terminal outcomes.
            }
            @Override public void onRunOutcome(GameEngine.RunOutcome outcome) {
                handleRunOutcome(level, mode, dailyId, outcome);
            }
            @Override public void onPauseRequested() { showPauseDialog(); }
        });
    }

    /** Detaches a terminal/replaced GameView so it cannot retain Activity or frame callbacks. */
    private void releaseGameView() {
        GameView previous = gameView;
        gameView = null;
        if (previous != null) {
            previous.setListener(null);
            previous.onHostPaused();
        }
    }

    private void handleRunOutcome(LevelDefinition level, ReplayMode mode, String dailyId,
                                  GameEngine.RunOutcome outcome) {
        if (discardOutcome) return;
        releaseGameView();
        if (mode == ReplayMode.TUTORIAL) {
            tutorialRunning = false;
            tutorialComplete = outcome.result() == RunResult.COMPLETED;
            tutorialFailed = outcome.result() == RunResult.FAILED;
            if (outcome.result() == RunResult.INTERRUPTED) {
                // Lifecycle interruption is not a failed lesson. Keep the Tutorial
                // route and return to its intro once the Activity is foregrounded.
                tutorialComplete = false;
                tutorialFailed = false;
                tutorialNeedsResumeRender = hostPaused;
                if (!hostPaused) render();
            } else if (!hostPaused) {
                render();
            }
            return;
        }
        if (outcome.result() == RunResult.INTERRUPTED) {
            pendingOutcome = outcome;
            if (!hostPaused) showInterruptedOutcome();
            return;
        }
        this.resultOutcome = outcome;
        LocalStore.LevelProgress previous = levelProgress.get(level.levelId());
        this.resultPreviousBestMs = previous == null ? 0L : Math.max(0L, previous.bestTimeMs);
        this.resultNewBest = isNewBestFor(outcome, level, levelProgress);
        this.resultSavePending = shouldPersist(mode, outcome);
        this.resultSaveFailed = false;
        this.runSaveResult = null;
        if (!resultSavePending) {
            openResultRoute();
            return;
        }
        String runId = UUID.randomUUID().toString();
        LocalStore.RunRecordInput input;
        try {
            input = LocalStore.RunRecordInput.fromOutcome(catalog, runId,
                    level, outcome, null, dailyId);
        } catch (RuntimeException invalidOutcome) {
            resultSavePending = false;
            resultSaveFailed = true;
            openResultRoute();
            return;
        }
        openResultRoute();
        store.recordRun(input, new LocalStore.Callback<LocalStore.RecordRunResult>() {
            @Override public void onResult(LocalStore.RecordRunResult result) {
                if (destroyed()) return;
                runSaveResult = result;
                resultSavePending = false;
                resultSaveFailed = false;
                refreshData();
                renderIfShowingResult();
            }
            @Override public void onError(Exception error) {
                if (destroyed()) return;
                resultSavePending = false;
                resultSaveFailed = true;
                renderIfShowingResult();
            }
        });
    }

    private static boolean shouldPersist(ReplayMode mode, GameEngine.RunOutcome outcome) {
        if (outcome.result() == RunResult.INTERRUPTED) return false;
        return mode != ReplayMode.PRACTICE && mode != ReplayMode.TUTORIAL;
    }

    private void openResultRoute() {
        if (currentRoute() == Route.GAME) replace(Route.RESULT);
        else {
            if (backStack.contains(Route.GAME)) backStack.remove(Route.GAME);
            if (currentRoute() == Route.RESULT) render();
            else navigate(Route.RESULT);
        }
    }

    private void showInterruptedOutcome() {
        if (pendingOutcome == null || activeLevel == null) return;
        resultOutcome = pendingOutcome;
        pendingOutcome = null;
        resultSavePending = false;
        resultSaveFailed = false;
        resultNewBest = false;
        resultPreviousBestMs = 0L;
        runSaveResult = null;
        openResultRoute();
    }

    private void renderIfShowingResult() {
        if (currentRoute() == Route.RESULT) render();
    }

    private boolean isNewBestFor(GameEngine.RunOutcome outcome, LevelDefinition level,
                                 Map<String, LocalStore.LevelProgress> previousProgress) {
        if (outcome.mode() != ReplayMode.CAMPAIGN
                || outcome.result() != RunResult.COMPLETED || outcome.assisted()) return false;
        LocalStore.LevelProgress previous = previousProgress.get(level.levelId());
        return previous == null || previous.bestTimeMs <= 0L
                || outcome.elapsedMs() < previous.bestTimeMs;
    }

    private void showPauseDialog() {
        if (gameEngine == null || gameEngine.getState() != GameEngine.EngineState.PAUSED) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.gameplay_pause)
                .setMessage(R.string.game_pause_instruction)
                .setPositiveButton(R.string.game_resume_touch, (dialog, which) -> {
                    toast(R.string.game_resume_touch);
                })
                .setNeutralButton(R.string.game_restart, (dialog, which) -> restartCurrentRun())
                .setNegativeButton(R.string.game_exit, (dialog, which) -> confirmGameExit())
                .show();
    }

    private void requestGameExitOrPause() {
        if (gameEngine == null || gameView == null) { goHome(); return; }
        ReplayMode mode = gameEngine.getMode();
        boolean pauseAllowed = mode == ReplayMode.CAMPAIGN || mode == ReplayMode.PRACTICE;
        if (pauseAllowed && gameEngine.getState() == GameEngine.EngineState.RUNNING) {
            gameView.onPauseRequested();
        } else if (gameEngine.getState() == GameEngine.EngineState.PAUSED) {
            showPauseDialog();
        } else {
            confirmGameExit();
        }
    }

    private void restartCurrentRun() {
        if (activeLevel == null || activeMode == null || gameView == null) return;
        GameEngine next = new GameEngine(activeLevel, activeMode, System.currentTimeMillis());
        GameView view = gameView;
        view.setListener(null); // Ignore the intentional INTERRUPTED result from replacing the old Engine.
        gameEngine = next;
        view.setEngine(next, activeLevel, feedback);
        if (activeMode == ReplayMode.GHOST_RACE && ghostTimeline != null) {
            view.setGhost(ghostTimeline);
        }
        attachGameListener(view, activeLevel, activeMode, activeDailyId);
        render();
    }

    private void confirmGameExit() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.game_exit_title)
                .setMessage(R.string.game_exit_confirm)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.game_exit, (dialog, which) -> {
                    discardOutcome = true;
                    if (gameView != null) gameView.setListener(null);
                    if (gameEngine != null) gameEngine.interrupt("user-exit");
                    if (gameView != null) gameView.onInputChanged();
                    gameView = null;
                    gameEngine = null;
                    if (backStack.contains(Route.GAME)) backStack.remove(Route.GAME);
                    if (backStack.isEmpty()) backStack.add(Route.HOME);
                    render();
                }).show();
    }

    private void applySettingsToGame() {
        if (gameView != null) {
            gameView.setLeftHanded(settings.leftHandedUi);
            gameView.setVisualOptions(settings.reducedEffects, settings.highContrast);
        }
    }

    public void onHostPaused() {
        hostPaused = true;
        if (gameView != null && (currentRoute() == Route.GAME
                || (currentRoute() == Route.TUTORIAL && tutorialRunning))) {
            gameView.onHostPaused();
        }
        feedback.pauseAll();
    }

    public void onHostResumed() {
        hostPaused = false;
        feedback.resumeAll();
        refreshDailySelectionIfNeeded();
        if (gameView != null) gameView.onHostResumed();
        if (pendingOutcome != null) showInterruptedOutcome();
        if (tutorialNeedsResumeRender) {
            tutorialNeedsResumeRender = false;
            render();
        }
    }

    // ------------------------------------------------------------------
    // Tutorial — same engine and collision as the real campaign.
    // ------------------------------------------------------------------

    private void startTutorial() {
        try {
            LevelDefinition base = Level01.build();
            LevelDefinition tutorialLevel = new LevelDefinition.Builder()
                    .levelId("tutorial-00")
                    .levelRevision(base.levelRevision())
                    .displayNameKey("tutorial_level")
                    .worldBounds(base.worldBounds())
                    .outerBoundary(base.outerBoundary())
                    .holeBoundaries(base.holeBoundaries())
                    .startZone(base.startZone())
                    .goalZone(base.goalZone())
                    .playerRadius(base.playerRadius())
                    .safetyMargin(base.safetyMargin())
                    .routeGraph(base.routeGraph())
                    .objectives(Collections.emptyList())
                    .gates(Collections.emptyList())
                    .safeZones(Collections.emptyList())
                    .parTimeMs(base.parTimeMs())
                    .timeAttackLimitMs(base.timeAttackLimitMs())
                    .build();
            LevelValidator.requireValid(tutorialLevel);
            startRun(tutorialLevel, ReplayMode.TUTORIAL, null, null);
        } catch (RuntimeException invalidTutorial) {
            tutorialRunning = false;
            tutorialFailed = true;
            render();
        }
    }

    private void finishTutorial() {
        tutorialRunning = false;
        tutorialComplete = false;
        tutorialFailed = false;
        releaseGameView();
        gameEngine = null;
        goHome();
    }

    private void showTutorialExitConfirmation() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.tutorial_title)
                .setMessage(R.string.tutorial_exit_confirm)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.game_exit, (dialog, which) -> {
                    discardOutcome = true;
                    if (gameView != null) gameView.setListener(null);
                    if (gameEngine != null) gameEngine.interrupt("tutorial-exit");
                    if (gameView != null) gameView.onInputChanged();
                    tutorialRunning = false;
                    tutorialComplete = false;
                    tutorialFailed = false;
                    releaseGameView();
                    gameEngine = null;
                    if (currentRoute() == Route.TUTORIAL) replace(Route.HOME);
                    else goHome();
                }).show();
    }

    // ------------------------------------------------------------------
    // Local profile / photo avatar.
    // ------------------------------------------------------------------

    public void onPhotoPicked(@Nullable Uri uri) {
        if (profileLoading || profileLoadFailed) { toast(R.string.nav_loading); return; }
        if (uri == null) return; // Picker cancellation preserves the previous avatar.
        final long request = ++photoImportGeneration;
        photoImportPending = true;
        renderIfCurrent(Route.PROFILE);
        store.savePhotoAvatar(uri, new LocalStore.Callback<String>() {
            @Override public void onResult(String photoPath) {
                if (destroyed() || request != photoImportGeneration) {
                    // A cancelled/stale import must not leave an orphaned private image.
                    discardPhoto(photoPath);
                    return;
                }
                photoImportPending = false;
                if (pendingPhotoPath != null && !pendingPhotoPath.equals(photoPath)) {
                    discardPhoto(pendingPhotoPath);
                }
                pendingPhotoPath = photoPath;
                profileEditing = true;
                renderIfCurrent(Route.PROFILE);
                toast(R.string.profile_photo_saved);
            }
            @Override public void onError(Exception error) {
                if (destroyed() || request != photoImportGeneration) return;
                photoImportPending = false;
                renderIfCurrent(Route.PROFILE);
                toast(R.string.profile_photo_error);
            }
        });
    }

    private void saveProfile(String name, String avatarId, @Nullable String photoPath) {
        if (profileLoading || profileLoadFailed) { toast(R.string.nav_loading); return; }
        if (photoImportPending) { toast(R.string.profile_photo_importing); return; }
        String normalizedName = name == null ? "" : java.text.Normalizer.normalize(
                name.trim(), java.text.Normalizer.Form.NFC);
        int codePoints = normalizedName.codePointCount(0, normalizedName.length());
        boolean hasControl = normalizedName.codePoints().anyMatch(cp -> Character.isISOControl(cp));
        if (codePoints < 2 || codePoints > 20 || hasControl) {
            toast(R.string.profile_name_error);
            return;
        }
        String effectivePhotoPath = photoPath;
        if (effectivePhotoPath == null && profile != null && profile.avatarType == 1) {
            // Editing only the display name must not silently discard an existing photo.
            effectivePhotoPath = profile.photoPath;
        }
        int avatarType = effectivePhotoPath == null ? 0 : 1;
        String assetId = avatarType == 0 ? normalizeAvatarId(avatarId) : null;
        store.saveProfile(normalizedName, avatarType, assetId, effectivePhotoPath,
                new LocalStore.Callback<Void>() {
            @Override public void onResult(Void ignored) {
                if (destroyed()) return;
                pendingPhotoPath = null;
                profileEditing = false;
                reloadProfile();
                render();
                toast(R.string.profile_saved);
            }
            @Override public void onError(Exception error) {
                if (destroyed()) return;
                toast(R.string.profile_save_error);
            }
        });
    }

    private void clearPendingPhotoAndUseBuiltIn() {
        if (photoImportPending) return;
        String old = pendingPhotoPath;
        pendingPhotoPath = null;
        if (old != null) discardPhoto(old);
        if (profile != null && profile.avatarType == 1) {
            profile = new LocalStore.Profile(profile.id, profile.displayName, 0,
                    normalizeAvatarId(avatarIdForIndex(selectedAvatar)), null,
                    profile.createdAt, profile.updatedAt);
        }
        render();
    }

    private void cancelProfileEdit() {
        // Invalidate any in-flight import; its callback will delete the copied file.
        photoImportGeneration++;
        photoImportPending = false;
        if (pendingPhotoPath != null) discardPhoto(pendingPhotoPath);
        pendingPhotoPath = null;
        profileEditing = false;
        reloadProfile();
        render();
    }

    private void discardPhoto(String path) {
        store.discardPhotoAvatar(path, new LocalStore.Callback<Void>() {
            @Override public void onResult(Void ignored) { }
            @Override public void onError(Exception error) { /* old/current photo is protected by LocalStore */ }
        });
    }

    private void reloadProfile() {
        final long request = ++profileQueryGeneration;
        profileLoading = true;
        profileLoadFailed = false;
        renderIfCurrent(Route.PROFILE);
        store.loadProfile(new LocalStore.Callback<LocalStore.Profile>() {
            @Override public void onResult(LocalStore.Profile value) {
                if (!destroyed() && request == profileQueryGeneration) {
                    profile = value;
                    profileLoading = false;
                    profileLoadFailed = false;
                    if (value != null && value.avatarType == 0 && value.avatarAssetId != null) {
                        selectedAvatar = avatarIndex(value.avatarAssetId);
                    }
                    renderIfCurrent(Route.PROFILE);
                    renderIfCurrent(Route.HOME);
                }
            }
            @Override public void onError(Exception error) {
                if (!destroyed() && request == profileQueryGeneration) {
                    profileLoading = false;
                    profileLoadFailed = true;
                    renderIfCurrent(Route.PROFILE);
                    renderIfCurrent(Route.HOME);
                }
            }
        });
    }

    private static String avatarIdForIndex(int index) {
        String[] ids = {"orbit", "prism", "pulse", "nova"};
        return ids[Math.max(0, Math.min(ids.length - 1, index))];
    }

    private static String normalizeAvatarId(String value) {
        if ("orbit".equals(value) || "prism".equals(value)
                || "pulse".equals(value) || "nova".equals(value)) return value;
        return "orbit";
    }

    private static int avatarIndex(String value) {
        String[] ids = {"orbit", "prism", "pulse", "nova"};
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(value)) return i;
        return 0;
    }

    // ------------------------------------------------------------------
    // Settings / destructive confirmations.
    // ------------------------------------------------------------------

    private void applySettings(LocalStore.Settings value) {
        store.saveSettings(value);
        LocalStore.Settings previous = settings;
        boolean languageChanged = !previous.language.equals(value.language);
        boolean visualSettingsChanged = languageChanged
                || previous.reducedMotion != value.reducedMotion
                || previous.reducedEffects != value.reducedEffects
                || previous.highContrast != value.highContrast
                || previous.leftHandedUi != value.leftHandedUi;
        settings = value;
        feedback.setSettings(value);
        applySettingsToGame();
        if (languageChanged) recreateActivity.run();
        else if (visualSettingsChanged) render();
        // Sound, haptics and SFX volume are applied in-place. Rebuilding the
        // Settings screen for each TalkBack/keyboard SeekBar increment would
        // steal accessibility focus and make precise adjustment frustrating.
    }

    private enum ResetKind { PROGRESS, PROFILE, ALL }

    private void confirmReset(ResetKind kind) {
        int title = kind == ResetKind.PROGRESS ? R.string.settings_reset_progress
                : kind == ResetKind.PROFILE ? R.string.settings_reset_profile
                : R.string.settings_reset_all;
        int message = kind == ResetKind.PROGRESS ? R.string.settings_reset_progress_confirm
                : kind == ResetKind.PROFILE ? R.string.settings_reset_profile_confirm
                : R.string.settings_reset_all_confirm;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(R.string.common_cancel, null)
                .setPositiveButton(R.string.settings_confirm, (dialog, which) -> performReset(kind))
                .show();
    }

    private void performReset(ResetKind kind) {
        LocalStore.Callback<Void> callback = new LocalStore.Callback<Void>() {
            @Override public void onResult(Void ignored) {
                if (destroyed()) return;
                if (kind == ResetKind.ALL) {
                    settings = store.loadSettings();
                    feedback.setSettings(settings);
                    profile = null;
                    levelProgress.clear();
                    achievementRows.clear();
                    dailyRecord = null;
                    recreateActivity.run();
                } else if (kind == ResetKind.PROGRESS) {
                    levelProgress.clear();
                    achievementRows.clear();
                    dailyRecord = null;
                    refreshData();
                    render();
                } else {
                    profile = null;
                    reloadProfile();
                    render();
                }
                toast(R.string.settings_reset_done);
            }
            @Override public void onError(Exception error) {
                if (!destroyed()) toast(R.string.nav_error);
            }
        };
        if (kind == ResetKind.PROGRESS) store.resetProgress(callback);
        else if (kind == ResetKind.PROFILE) store.resetProfile(callback);
        else store.resetAll(callback);
    }

    // ------------------------------------------------------------------
    // Daily challenge / Ghost eligibility.
    // ------------------------------------------------------------------

    /** Re-evaluates the UTC challenge after a resume or a late-day user action. */
    private void refreshDailySelectionIfNeeded() {
        DailyChallenge.Selection current = DailyChallenge.forTimestamp(
                System.currentTimeMillis(), ReplaySystem.GAME_RULES_VERSION, catalog);
        if (dailySelection != null && dailySelection.utcDate().equals(current.utcDate())
                && dailySelection.challengeId().equals(current.challengeId())) return;
        dailySelection = current;
        dailyRecord = null;
        refreshDailyRecord();
    }

    private void refreshDailyRecord() {
        DailyChallenge.Selection selection = dailySelection;
        final long request = ++dailyQueryGeneration;
        if (selection == null) { dailyLoading = false; return; }
        dailyLoading = true;
        dailyLoadFailed = false;
        renderIfCurrent(Route.HOME);
        renderIfCurrent(Route.MODES);
        store.loadDailyRecord(selection.utcDate(), new LocalStore.Callback<LocalStore.DailyRecord>() {
            @Override public void onResult(LocalStore.DailyRecord value) {
                if (!destroyed() && request == dailyQueryGeneration && dailySelection != null
                        && dailySelection.utcDate().equals(selection.utcDate())) {
                    dailyRecord = value;
                    dailyLoading = false;
                    dailyLoadFailed = false;
                    renderIfCurrent(Route.HOME);
                    renderIfCurrent(Route.MODES);
                }
            }
            @Override public void onError(Exception error) {
                if (!destroyed() && request == dailyQueryGeneration && dailySelection != null
                        && dailySelection.utcDate().equals(selection.utcDate())) {
                    dailyRecord = null;
                    dailyLoading = false;
                    dailyLoadFailed = true;
                    renderIfCurrent(Route.HOME);
                    renderIfCurrent(Route.MODES);
                }
            }
        });
    }

    private void refreshGhostAvailability() {
        LevelDefinition level = selectedLevel();
        if (level == null) return;
        ghostCheckPending = true;
        ghostAvailable = false;
        ghostTimeline = null;
        queryGhost(level, false);
    }

    private void loadGhostAndStart(LevelDefinition level) {
        ghostCheckPending = true;
        toast(R.string.nav_loading);
        queryGhost(level, true);
    }

    private void queryGhost(LevelDefinition level, boolean startWhenReady) {
        final long routeRequest = routeGeneration;
        final long queryRequest = ++ghostQueryGeneration;
        store.loadBestReplay(level.levelId(), ReplayMode.CAMPAIGN.name(),
                level.levelRevision(), level.geometryHash(), new LocalStore.Callback<byte[]>() {
            @Override public void onResult(byte[] blob) {
                if (destroyed() || queryRequest != ghostQueryGeneration) return;
                GhostTimeline timeline = null;
                try {
                    if (blob != null) {
                        ReplayTrace trace = ReplaySystem.ReplayCodec.decode(blob);
                        timeline = GhostTimeline.from(trace, level);
                    }
                } catch (RuntimeException corrupt) {
                    timeline = null;
                }
                // Ignore an older selection's response; a query for a different level may follow.
                if (!level.levelId().equals(selectedLevelId)) return;
                if (startWhenReady && (routeRequest != routeGeneration || currentRoute() != Route.MODES)) {
                    ghostCheckPending = false;
                    renderIfCurrent(Route.MODES);
                    return;
                }
                ghostCheckPending = false;
                ghostTimeline = timeline;
                ghostAvailable = timeline != null;
                if (startWhenReady) {
                    if (timeline == null) toast(R.string.mode_ghost_unavailable);
                    else startRun(level, ReplayMode.GHOST_RACE, null, timeline);
                } else renderIfCurrent(Route.MODES);
            }
            @Override public void onError(Exception error) {
                if (destroyed() || queryRequest != ghostQueryGeneration
                        || !level.levelId().equals(selectedLevelId)) return;
                if (startWhenReady && (routeRequest != routeGeneration || currentRoute() != Route.MODES)) {
                    ghostCheckPending = false;
                    renderIfCurrent(Route.MODES);
                    return;
                }
                ghostCheckPending = false;
                ghostTimeline = null;
                ghostAvailable = false;
                if (startWhenReady) toast(R.string.mode_ghost_unavailable);
                else renderIfCurrent(Route.MODES);
            }
        });
    }

    // ------------------------------------------------------------------
    // Persistence reads and view refresh.
    // ------------------------------------------------------------------

    private void refreshData() {
        final long request = ++progressQueryGeneration;
        dataLoading = true;
        storeError = false;
        renderIfNotGameplay();
        store.loadAllLevelProgress(new LocalStore.Callback<Map<String, LocalStore.LevelProgress>>() {
            @Override public void onResult(Map<String, LocalStore.LevelProgress> value) {
                if (destroyed() || request != progressQueryGeneration) return;
                levelProgress = value == null ? new HashMap<>() : new HashMap<>(value);
                dataLoading = false;
                storeError = false;
                if (selectedLevelId == null || !isUnlocked(selectedLevelId)) {
                    selectedLevelId = findContinueLevelId();
                }
                renderIfNotGameplay();
                refreshGhostAvailability();
            }
            @Override public void onError(Exception error) {
                if (destroyed() || request != progressQueryGeneration) return;
                dataLoading = false;
                storeError = true;
                renderIfNotGameplay();
            }
        });
        refreshAchievements();
        reloadProfile();
    }

    private void refreshAchievements() {
        final long request = ++achievementQueryGeneration;
        achievementsLoading = true;
        achievementsLoadFailed = false;
        renderIfCurrent(Route.ACHIEVEMENTS);
        store.loadAchievements(new LocalStore.Callback<List<LocalStore.Achievement>>() {
            @Override public void onResult(List<LocalStore.Achievement> value) {
                if (destroyed() || request != achievementQueryGeneration) return;
                achievementRows = value == null ? new ArrayList<>() : new ArrayList<>(value);
                achievementsLoading = false;
                achievementsLoadFailed = false;
                renderIfCurrent(Route.ACHIEVEMENTS);
                renderIfCurrent(Route.PROFILE);
            }
            @Override public void onError(Exception error) {
                if (destroyed() || request != achievementQueryGeneration) return;
                achievementsLoading = false;
                achievementsLoadFailed = true;
                renderIfCurrent(Route.ACHIEVEMENTS);
            }
        });
    }

    private void renderIfNotGameplay() {
        if (!isAttachedToWindow()) return;
        Route current = currentRoute();
        if (current != Route.GAME && current != Route.TUTORIAL) render();
    }

    private void renderIfCurrent(Route route) {
        if (isAttachedToWindow() && currentRoute() == route) render();
    }

    private String findContinueLevelId() {
        for (int i = 0; i < catalog.size(); i++) {
            LevelDefinition level = catalog.get(i);
            LocalStore.LevelProgress row = levelProgress.get(level.levelId());
            boolean unlocked = row == null ? i == 0 : row.unlocked;
            if (unlocked && (row == null || !row.completed)) return level.levelId();
        }
        return catalog.get(catalog.size() - 1).levelId();
    }

    private boolean isUnlocked(String id) {
        for (int i = 0; i < catalog.size(); i++) {
            LevelDefinition level = catalog.get(i);
            if (!level.levelId().equals(id)) continue;
            LocalStore.LevelProgress row = levelProgress.get(id);
            return row == null ? i == 0 : row.unlocked;
        }
        return false;
    }

    @Nullable private LevelDefinition selectedLevel() {
        if (selectedLevelId != null) {
            try { return catalog.byId(selectedLevelId); }
            catch (IllegalArgumentException ignored) { }
        }
        return catalog.get(0);
    }

    private void toast(int stringId) {
        Toast.makeText(activity, stringId, Toast.LENGTH_SHORT).show();
    }

    private boolean destroyed() { return disposed || activity.isFinishing() || activity.isDestroyed(); }

    // ------------------------------------------------------------------
    // Cleanup.
    // ------------------------------------------------------------------

    public void destroy() {
        if (disposed) return;
        disposed = true;
        routeGeneration++;
        ghostQueryGeneration++;
        progressQueryGeneration++;
        profileQueryGeneration++;
        achievementQueryGeneration++;
        dailyQueryGeneration++;
        photoImportGeneration++;
        transitionPending = false;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().cancel();
        if (gameView != null) {
            GameView previous = gameView;
            gameView = null;
            previous.setListener(null);
            if (gameEngine != null && (gameEngine.getState() == GameEngine.EngineState.RUNNING
                    || gameEngine.getState() == GameEngine.EngineState.PAUSED)) {
                gameEngine.interrupt("activity-destroyed");
            }
            previous.onHostPaused();
            gameEngine = null;
        }
        if (pendingPhotoPath != null) discardPhoto(pendingPhotoPath);
        pendingPhotoPath = null;
    }
}
