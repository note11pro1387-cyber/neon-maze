package com.neonmaze.app;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

/** Retains one startup/content session across Activity configuration changes. */
public final class StartupSessionViewModel extends ViewModel {
    @Nullable private StartupContentManager manager;

    @NonNull
    public synchronized StartupContentManager getOrCreate(@NonNull Application application,
                                                           @Nullable String manifestUrl,
                                                           int versionCode) {
        if (manager == null) {
            manager = new StartupContentManager(application, manifestUrl, versionCode);
        }
        return manager;
    }

    @Override
    protected synchronized void onCleared() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        super.onCleared();
    }
}
