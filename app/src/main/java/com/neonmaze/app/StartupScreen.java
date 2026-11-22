package com.neonmaze.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.text.NumberFormat;
import java.util.Locale;

/** Native first-launch/loading screen for bundled-resource validation and optional content install. */
public final class StartupScreen extends FrameLayout {
    public interface Actions {
        void onRetry();
        void onContinueOffline();
    }

    private final Activity activity;
    private final UiKit ui;
    private final TextView status;
    private final TextView detail;
    private final TextView percent;
    private final ProgressBar progress;
    private final Button retry;
    private final Button continueOffline;

    public StartupScreen(@NonNull Activity activity,
                         @NonNull LocalStore.Settings settings,
                         @NonNull Actions actions) {
        super(activity);
        this.activity = activity;
        this.ui = new UiKit(activity, settings);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{UiKit.BACKGROUND, UiKit.DEEP, Color.rgb(12, 23, 37)}));
        setFocusableInTouchMode(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(ui.dp(28), ui.dp(32), ui.dp(28), ui.dp(28));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(content, contentParams);

        TextView mark = new TextView(activity);
        mark.setText("NM");
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(UiKit.DEEP);
        mark.setTextSize(18);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable markBackground = new GradientDrawable();
        markBackground.setColor(UiKit.MINT);
        markBackground.setCornerRadius(ui.dp(17));
        mark.setBackground(markBackground);
        content.addView(mark, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));

        TextView brand = ui.text(activity.getString(R.string.app_name), 13,
                UiKit.TEXT_SECONDARY, true);
        brand.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        brandParams.topMargin = ui.dp(22);
        content.addView(brand, brandParams);

        TextView title = ui.text(activity.getString(R.string.startup_title), 26,
                UiKit.TEXT_PRIMARY, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = ui.dp(10);
        content.addView(title, titleParams);

        TextView subtitle = ui.text(activity.getString(R.string.startup_subtitle), 13,
                UiKit.TEXT_SECONDARY, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(ui.dp(3), 1f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = ui.dp(10);
        content.addView(subtitle, subtitleParams);

        LinearLayout progressCard = new LinearLayout(activity);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(16));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(UiKit.SURFACE);
        cardBackground.setCornerRadius(ui.dp(18));
        cardBackground.setStroke(ui.dp(1), UiKit.BORDER);
        progressCard.setBackground(cardBackground);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = ui.dp(34);
        content.addView(progressCard, cardParams);

        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        progressCard.addView(statusRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        status = ui.text(activity.getString(R.string.startup_checking_local), 14,
                UiKit.TEXT_PRIMARY, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(status, new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, 1f));

        percent = ui.text("0%", 12, UiKit.MINT, true);
        statusRow.addView(percent, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(UiKit.MINT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(UiKit.BORDER));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, ui.dp(7));
        progressParams.topMargin = ui.dp(16);
        progressCard.addView(progress, progressParams);

        detail = ui.text(activity.getString(R.string.startup_preparing_detail), 11,
                UiKit.TEXT_SECONDARY, false);
        detail.setGravity(Gravity.START);
        detail.setMaxLines(3);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = ui.dp(12);
        progressCard.addView(detail, detailParams);

        retry = ui.button(activity.getString(R.string.startup_retry),
                UiKit.ButtonStyle.PRIMARY, actions::onRetry);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, ui.dp(52));
        retryParams.topMargin = ui.dp(16);
        content.addView(retry, retryParams);
        retry.setVisibility(View.GONE);

        continueOffline = ui.button(activity.getString(R.string.startup_continue_offline),
                UiKit.ButtonStyle.SECONDARY, actions::onContinueOffline);
        LinearLayout.LayoutParams offlineParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, ui.dp(50));
        offlineParams.topMargin = ui.dp(9);
        content.addView(continueOffline, offlineParams);
        continueOffline.setVisibility(View.GONE);

        TextView privacy = ui.text(activity.getString(R.string.startup_local_privacy), 10,
                UiKit.TEXT_SECONDARY, false);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        privacyParams.topMargin = ui.dp(22);
        content.addView(privacy, privacyParams);
    }

    public void render(@NonNull StartupContentManager.Snapshot snapshot) {
        int textId;
        int detailId;
        switch (snapshot.state) {
            case PREPARING:
                textId = R.string.startup_checking_local;
                detailId = R.string.startup_preparing_detail;
                break;
            case CHECKING_REMOTE:
                textId = R.string.startup_checking_remote;
                detailId = R.string.startup_remote_detail;
                break;
            case DOWNLOADING:
                textId = R.string.startup_downloading;
                detailId = R.string.startup_download_detail;
                break;
            case VERIFYING:
                textId = R.string.startup_verifying;
                detailId = R.string.startup_verify_detail;
                break;
            case INSTALLING:
                textId = R.string.startup_installing;
                detailId = R.string.startup_install_detail;
                break;
            case READY:
                textId = R.string.startup_ready;
                detailId = R.string.startup_ready_detail;
                break;
            case READY_OFFLINE:
                textId = R.string.startup_offline_ready;
                detailId = R.string.startup_offline_detail;
                break;
            case FAILED:
            default:
                textId = R.string.startup_error;
                detailId = R.string.startup_error_detail;
                break;
        }
        status.setText(activity.getString(textId));
        if (snapshot.state == StartupContentManager.State.DOWNLOADING
                && snapshot.totalBytes > 0L) {
            detail.setText(activity.getString(R.string.startup_download_progress,
                    Formatter.formatShortFileSize(activity, snapshot.completedBytes),
                    Formatter.formatShortFileSize(activity, snapshot.totalBytes)));
        } else {
            detail.setText(activity.getString(detailId));
        }
        boolean indeterminate = snapshot.state == StartupContentManager.State.PREPARING
                || snapshot.state == StartupContentManager.State.CHECKING_REMOTE
                || snapshot.state == StartupContentManager.State.VERIFYING;
        progress.setIndeterminate(indeterminate);
        if (indeterminate) {
            percent.setText("…");
        } else {
            progress.setProgress(snapshot.progressPercent);
            percent.setText(NumberFormat.getPercentInstance(
                    ui.isRtl() ? new Locale("fa") : Locale.ENGLISH)
                    .format(snapshot.progressPercent / 100.0));
        }
        retry.setVisibility(snapshot.state == StartupContentManager.State.FAILED
                ? View.VISIBLE : View.GONE);
        continueOffline.setVisibility(snapshot.canContinueOffline
                && !snapshot.isReady() && snapshot.state != StartupContentManager.State.PREPARING
                ? View.VISIBLE : View.GONE);
        if (snapshot.state == StartupContentManager.State.FAILED) {
            status.setTextColor(UiKit.CORAL);
            percent.setText("!");
        } else {
            status.setTextColor(UiKit.TEXT_PRIMARY);
        }
    }
}
