package com.neonmaze.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.neonmaze.core.LevelSystem.Catalog;

import java.util.Locale;

/** Single Android host and composition root for NEON MAZE. */
public final class MainActivity extends ComponentActivity {
    private static final String STATE_SCREEN = "neon_maze.screen";

    @Nullable private LocalStore store;
    @Nullable private FeedbackSystem feedback;
    @Nullable private ScreenUI screenUI;
    @Nullable private Catalog catalog;
    @Nullable private ContentAssetResolver contentAssets;
    @Nullable private Handler mainHandler;
    @Nullable private ActivityResultLauncher<PickVisualMediaRequest> photoPicker;
    @Nullable private StartupContentManager contentManager;
    @Nullable private StartupScreen startupScreen;
    @Nullable private StartupSessionViewModel startupSession;
    @Nullable private String restoreRoute;
    private boolean gameUiCreated;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(localizedBase(newBase));
    }

    private static Context localizedBase(Context base) {
        String language = base.getSharedPreferences("neon_maze_settings", Context.MODE_PRIVATE)
                .getString("language", "fa");
        Locale locale = new Locale("en".equals(language) ? "en" : "fa");
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        mainHandler = new Handler(Looper.getMainLooper());
        store = new LocalStore(this, runnable -> mainHandler.post(runnable));
        LocalStore.Settings settings = store.loadSettings();
        feedback = new FeedbackSystem(this, settings);
        registerPhotoPicker();
        if (savedInstanceState != null) {
            restoreRoute = savedInstanceState.getString(STATE_SCREEN, "HOME");
        }

        startupSession = new ViewModelProvider(this).get(StartupSessionViewModel.class);
        contentManager = startupSession.getOrCreate(getApplication(),
                BuildConfig.CONTENT_MANIFEST_URL, BuildConfig.VERSION_CODE);
        startupScreen = new StartupScreen(this, settings, new StartupScreen.Actions() {
            @Override public void onRetry() {
                if (contentManager != null) contentManager.retry();
            }
            @Override public void onContinueOffline() {
                if (contentManager != null) contentManager.continueOffline();
            }
        });
        setInsetContent(startupScreen);
        contentManager.attachListener(this::onStartupStateChanged);
        contentManager.start();
    }

    private void onStartupStateChanged(@NonNull StartupContentManager.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        if (snapshot.isReady()) {
            if (!gameUiCreated) showGameUi();
            return;
        }
        if (startupScreen != null) startupScreen.render(snapshot);
    }

    private void showGameUi() {
        if (gameUiCreated || store == null || feedback == null || contentManager == null) return;
        catalog = contentManager.getValidatedCatalog();
        if (catalog == null) {
            gameUiCreated = false;
            if (startupScreen != null) {
                startupScreen.render(new StartupContentManager.Snapshot(
                        StartupContentManager.State.FAILED, 0, 0, 0, false,
                        "Validated campaign catalog is unavailable"));
            }
            return;
        }
        ContentPackInstaller.InstalledPack activePack = contentManager.getActivePack();
        contentAssets = new ContentAssetResolver(getAssets(), activePack);
        gameUiCreated = true;

        screenUI = new ScreenUI(this, catalog, store, feedback, contentAssets,
                this::launchPhotoPicker, this::recreate);
        setInsetContent(screenUI);
        if (restoreRoute != null) {
            screenUI.restoreSafeRoute(restoreRoute);
            restoreRoute = null;
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (screenUI == null || !screenUI.onBackPressed()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        startupScreen = null;
    }

    private void configureWindow() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightNavigationBars(false);
    }

    private void registerPhotoPicker() {
        photoPicker = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    if (screenUI != null) screenUI.onPhotoPicked(uri);
                });
    }

    private void launchPhotoPicker() {
        if (photoPicker == null) return;
        PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build();
        photoPicker.launch(request);
    }

    private void setInsetContent(@NonNull View content) {
        FrameHost host = new FrameHost(this);
        host.setBackgroundColor(UiKit.BACKGROUND);
        host.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(host);
        ViewCompat.setOnApplyWindowInsetsListener(host, (view, windowInsets) -> {
            Insets system = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(system.left, system.top, system.right,
                    Math.max(system.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(host);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (screenUI != null) outState.putString(STATE_SCREEN, screenUI.currentRouteName());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        if (screenUI != null) screenUI.onHostPaused();
        else if (feedback != null) feedback.pauseAll();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screenUI != null) screenUI.onHostResumed();
        else if (feedback != null) feedback.resumeAll();
    }

    @Override
    protected void onDestroy() {
        if (screenUI != null) screenUI.destroy();
        contentAssets = null;
        if (feedback != null) feedback.release();
        if (store != null) store.shutdown();
        if (contentManager != null) contentManager.detachListener();
        super.onDestroy();
    }

    private static final class FrameHost extends android.widget.FrameLayout {
        FrameHost(@NonNull Context context) { super(context); setClipToPadding(false); }
    }
}
