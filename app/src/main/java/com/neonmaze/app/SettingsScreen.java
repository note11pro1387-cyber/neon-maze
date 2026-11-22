package com.neonmaze.app;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;

import androidx.annotation.NonNull;

/** Settings UI reflects exactly the persisted LocalStore.Settings contract. */
public final class SettingsScreen {
    public interface Actions {
        void onSettingsChanged(@NonNull LocalStore.Settings settings);
        void onVolumePreview(int volumePercent);
        void onResetProgress();
        void onResetProfile();
        void onResetAll();
        void onBack();
    }

    private final Activity activity;
    private final UiKit ui;
    private LocalStore.Settings settings;
    private final Actions actions;

    public SettingsScreen(@NonNull Activity activity, @NonNull UiKit ui,
                          @NonNull LocalStore.Settings settings, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.settings = settings;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_settings_eyebrow),
                activity.getString(R.string.settings_title),
                activity.getString(R.string.settings_subtitle));

        LinearLayout audio = ui.card();
        ui.addSection(audio, activity.getString(R.string.settings_audio), null);
        addToggle(audio, R.string.settings_sound, settings.soundEnabled, UiKit.Icon.SOUND,
                value -> update(value, settings.hapticsEnabled, settings.reducedMotion,
                        settings.reducedEffects, settings.highContrast, settings.language,
                        settings.leftHandedUi, settings.volumePercent));
        addToggle(audio, R.string.settings_haptics, settings.hapticsEnabled, UiKit.Icon.HAPTICS,
                value -> update(settings.soundEnabled, value, settings.reducedMotion,
                        settings.reducedEffects, settings.highContrast, settings.language,
                        settings.leftHandedUi, settings.volumePercent));
        addVolumeControl(audio);
        ui.addCard(page, audio, 0);

        LinearLayout access = ui.card();
        ui.addSection(access, activity.getString(R.string.settings_accessibility), null);
        addToggle(access, R.string.settings_reduced_motion, settings.reducedMotion, null,
                value -> update(settings.soundEnabled, settings.hapticsEnabled, value,
                        settings.reducedEffects, settings.highContrast, settings.language,
                        settings.leftHandedUi, settings.volumePercent));
        addToggle(access, R.string.settings_reduced_effects, settings.reducedEffects, null,
                value -> update(settings.soundEnabled, settings.hapticsEnabled,
                        settings.reducedMotion, value, settings.highContrast, settings.language,
                        settings.leftHandedUi, settings.volumePercent));
        addToggle(access, R.string.settings_high_contrast, settings.highContrast, null,
                value -> update(settings.soundEnabled, settings.hapticsEnabled,
                        settings.reducedMotion, settings.reducedEffects, value,
                        settings.language, settings.leftHandedUi, settings.volumePercent));
        addToggle(access, R.string.settings_left_handed, settings.leftHandedUi, null,
                value -> update(settings.soundEnabled, settings.hapticsEnabled,
                        settings.reducedMotion, settings.reducedEffects, settings.highContrast,
                        settings.language, value, settings.volumePercent));
        ui.addText(access, activity.getString(R.string.settings_font_scale_note),
                11, UiKit.TEXT_SECONDARY, false, 12, 0);
        ui.addCard(page, access, 12);

        LinearLayout language = ui.card();
        ui.addSection(language, activity.getString(R.string.settings_language), null);
        LinearLayout languageButtons = ui.row();
        languageButtons.addView(ui.button(activity.getString(R.string.settings_language_fa_label), "fa".equals(settings.language)
                        ? UiKit.ButtonStyle.PRIMARY : UiKit.ButtonStyle.SECONDARY,
                () -> update(settings.soundEnabled, settings.hapticsEnabled,
                        settings.reducedMotion, settings.reducedEffects, settings.highContrast,
                        "fa", settings.leftHandedUi, settings.volumePercent)), new LinearLayout.LayoutParams(0,
                ui.dp(52), 1f));
        View gap = new View(activity);
        languageButtons.addView(gap, new LinearLayout.LayoutParams(ui.dp(8), 1));
        languageButtons.addView(ui.button(activity.getString(R.string.settings_language_en_label), "en".equals(settings.language)
                        ? UiKit.ButtonStyle.PRIMARY : UiKit.ButtonStyle.SECONDARY,
                () -> update(settings.soundEnabled, settings.hapticsEnabled,
                        settings.reducedMotion, settings.reducedEffects, settings.highContrast,
                        "en", settings.leftHandedUi, settings.volumePercent)), new LinearLayout.LayoutParams(0,
                ui.dp(52), 1f));
        language.addView(languageButtons, ui.matchWrap());
        ui.addCard(page, language, 12);

        LinearLayout reset = ui.card();
        ui.addSection(reset, activity.getString(R.string.settings_data), null);
        ui.addText(reset, activity.getString(R.string.settings_reset_progress_desc),
                12, UiKit.TEXT_SECONDARY, false, 0, 0);
        ui.addButton(reset, ui.button(activity.getString(R.string.settings_reset_progress),
                UiKit.ButtonStyle.DANGER, actions::onResetProgress), true);
        ui.addText(reset, activity.getString(R.string.settings_reset_profile_desc),
                12, UiKit.TEXT_SECONDARY, false, 14, 0);
        ui.addButton(reset, ui.button(activity.getString(R.string.settings_reset_profile),
                UiKit.ButtonStyle.DANGER, actions::onResetProfile), true);
        ui.addText(reset, activity.getString(R.string.settings_reset_all_desc),
                12, UiKit.TEXT_SECONDARY, false, 14, 0);
        ui.addButton(reset, ui.button(activity.getString(R.string.settings_reset_all),
                UiKit.ButtonStyle.DANGER, actions::onResetAll), true);
        ui.addCard(page, reset, 12);
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    private void addVolumeControl(LinearLayout parent) {
        LinearLayout block = ui.column();
        block.setPadding(0, ui.dp(10), 0, ui.dp(6));
        LinearLayout heading = ui.row();
        android.widget.TextView label = ui.text(activity.getString(R.string.settings_volume),
                13, UiKit.TEXT_PRIMARY, true);
        heading.addView(label, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.TextView value = ui.mono(activity.getString(
                R.string.settings_volume_value, settings.volumePercent), 11,
                UiKit.MINT, true);
        value.setGravity(android.view.Gravity.CENTER);
        heading.addView(value, ui.wrap());
        block.addView(heading, ui.matchWrap());

        SeekBar slider = new SeekBar(activity);
        slider.setId(View.generateViewId());
        slider.setMax(100);
        slider.setProgress(settings.volumePercent);
        slider.setContentDescription(activity.getString(R.string.settings_volume_accessibility,
                settings.volumePercent));
        slider.setMinimumHeight(ui.dp(48));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.MINT));
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.MINT));
        }
        label.setLabelFor(slider.getId());
        final boolean[] tracking = {false};
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int lastCommitted = settings.volumePercent;
            @Override public void onStartTrackingTouch(SeekBar seekBar) { tracking[0] = true; }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(activity.getString(R.string.settings_volume_value, progress));
                seekBar.setContentDescription(activity.getString(
                        R.string.settings_volume_accessibility, progress));
                if (fromUser) {
                    actions.onVolumePreview(progress);
                    // Keyboard and accessibility adjustments may not open a touch-tracking session.
                    if (!tracking[0] && progress != lastCommitted) {
                        commitVolume(progress);
                        lastCommitted = progress;
                    }
                }
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                tracking[0] = false;
                int progress = seekBar.getProgress();
                if (progress != lastCommitted) {
                    commitVolume(progress);
                    lastCommitted = progress;
                }
            }
            private void commitVolume(int progress) {
                update(settings.soundEnabled, settings.hapticsEnabled,
                        settings.reducedMotion, settings.reducedEffects, settings.highContrast,
                        settings.language, settings.leftHandedUi, progress);
            }
        });
        block.addView(slider, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));
        parent.addView(block, ui.matchWrap());
        View divider = ui.divider();
        parent.addView(divider, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)));
    }

    private void addToggle(LinearLayout parent, int labelId, boolean checked,
                           UiKit.Icon icon, java.util.function.Consumer<Boolean> change) {
        LinearLayout row = ui.row();
        row.setPadding(0, ui.dp(5), 0, ui.dp(5));
        if (icon != null) {
            row.addView(ui.icon(icon, UiKit.TEXT_SECONDARY, 22, null),
                    new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            View gap = new View(activity);
            row.addView(gap, new LinearLayout.LayoutParams(ui.dp(10), 1));
        }
        android.widget.TextView label = ui.text(activity.getString(labelId), 14,
                UiKit.TEXT_PRIMARY, false);
        row.addView(label, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(activity);
        toggle.setId(View.generateViewId());
        label.setLabelFor(toggle.getId());
        toggle.setChecked(checked);
        toggle.setContentDescription(activity.getString(labelId));
        toggle.setOnCheckedChangeListener((button, value) -> change.accept(value));
        row.addView(toggle, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)));
        parent.addView(row, ui.matchWrap());
        View divider = ui.divider();
        parent.addView(divider, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)));
    }

    private void update(boolean sound, boolean haptics, boolean reducedMotion,
                        boolean reducedEffects, boolean highContrast, String language,
                        boolean leftHanded, int volumePercent) {
        // Keep the screen's snapshot current. Non-visual controls (sound,
        // haptics and volume) do not rebuild this screen, so later edits must
        // not restore values captured when the screen was first created.
        LocalStore.Settings next = new LocalStore.Settings(sound, haptics, reducedMotion,
                reducedEffects, highContrast, language, leftHanded, volumePercent);
        settings = next;
        actions.onSettingsChanged(next);
    }
}
