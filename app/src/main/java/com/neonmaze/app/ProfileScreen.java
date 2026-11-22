package com.neonmaze.app;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Local-only player profile and editor state. */
public final class ProfileScreen {
    public interface Actions {
        void onEdit();
        void onSave(String name, String avatarAssetId, @Nullable String photoPath);
        void onSelectBuiltIn(@NonNull String avatarAssetId);
        void onChoosePhoto();
        void onUseBuiltIn();
        void onAchievements();
        void onRetry();
        void onBack();
    }

    private static final String[] AVATARS = {"orbit", "prism", "pulse", "nova"};
    private static final int[] AVATAR_LABELS = {R.string.profile_avatar_builtin_1,
            R.string.profile_avatar_builtin_2, R.string.profile_avatar_builtin_3,
            R.string.profile_avatar_builtin_4};

    private final Activity activity;
    private final UiKit ui;
    private final LocalStore.Profile profile;
    private final Map<String, LocalStore.LevelProgress> progress;
    private final List<LocalStore.Achievement> achievements;
    private final boolean editing;
    private final String pendingPhotoPath;
    private final int selectedAvatar;
    private final boolean profileLoading;
    private final boolean profileLoadFailed;
    private final boolean photoImportPending;
    private final boolean progressLoading;
    private final boolean progressFailed;
    private final boolean achievementsUnavailable;
    private final Actions actions;

    public ProfileScreen(@NonNull Activity activity, @NonNull UiKit ui,
                         @Nullable LocalStore.Profile profile,
                         Map<String, LocalStore.LevelProgress> progress,
                         List<LocalStore.Achievement> achievements,
                         boolean editing, String pendingPhotoPath,
                         int selectedAvatar, boolean profileLoading, boolean profileLoadFailed,
                         boolean photoImportPending, boolean progressLoading, boolean progressFailed,
                         boolean achievementsUnavailable, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.profile = profile;
        this.progress = progress;
        this.achievements = achievements;
        this.editing = editing;
        this.pendingPhotoPath = pendingPhotoPath;
        this.selectedAvatar = Math.max(0, Math.min(AVATARS.length - 1, selectedAvatar));
        this.profileLoading = profileLoading;
        this.profileLoadFailed = profileLoadFailed;
        this.photoImportPending = photoImportPending;
        this.progressLoading = progressLoading;
        this.progressFailed = progressFailed;
        this.achievementsUnavailable = achievementsUnavailable;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_profile_eyebrow),
                activity.getString(R.string.profile_title),
                activity.getString(R.string.profile_subtitle));
        if (profileLoading || profileLoadFailed) {
            LinearLayout notice = ui.card();
            ui.addText(notice, activity.getString(profileLoadFailed
                            ? R.string.profile_load_error : R.string.home_loading),
                    13, profileLoadFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, true, 0, 0);
            if (profileLoadFailed) ui.addButton(notice, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addCard(page, notice, 0);
        } else if (editing) addEditor(page); else addProfile(page);
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    private void addProfile(LinearLayout page) {
        LinearLayout identity = ui.card();
        String name = profile == null || profile.displayName == null
                || profile.displayName.trim().isEmpty()
                ? activity.getString(R.string.profile_default_name) : profile.displayName;
        ImageView avatar = avatarView();
        identity.addView(avatar, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(84)));
        ui.addText(identity, name, 21, UiKit.TEXT_PRIMARY, true, 14, 0);
        ui.addText(identity, activity.getString(R.string.profile_local_only),
                12, UiKit.TEXT_SECONDARY, false, 5, 0);
        ui.addButton(identity, ui.button(activity.getString(R.string.profile_edit),
                UiKit.ButtonStyle.PRIMARY, actions::onEdit), true);
        ui.addCard(page, identity, 0);

        ui.addSection(page, activity.getString(R.string.profile_stats), null);
        if (progressLoading || progressFailed) {
            ui.addText(page, activity.getString(progressFailed
                            ? R.string.home_store_error : R.string.home_loading),
                    12, progressFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, false, 0, 0);
            if (progressFailed) ui.addButton(page, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
        } else {
            int attempts = 0, completions = 0;
            long bestTimeMs = Long.MAX_VALUE;
            for (LocalStore.LevelProgress row : progress.values()) {
                attempts += row.attempts;
                completions += row.completions;
                if (row.bestTimeMs > 0L) bestTimeMs = Math.min(bestTimeMs, row.bestTimeMs);
            }
            LinearLayout stats = ui.row();
            stat(stats, R.string.profile_attempts, attempts);
            stat(stats, R.string.profile_completions, completions);
            stat(stats, R.string.profile_achievements, achievementsUnavailable ? -1 : unlockedCount());
            page.addView(stats, ui.matchWrap());
            if (bestTimeMs != Long.MAX_VALUE) {
                ui.addText(page, activity.getString(R.string.profile_best_value,
                        ui.formatTime(bestTimeMs), activity.getString(R.string.profile_best)),
                        12, UiKit.TEXT_SECONDARY, false, 14, 0);
            } else {
                ui.addText(page, activity.getString(R.string.profile_best_none),
                        12, UiKit.TEXT_SECONDARY, false, 14, 0);
            }
        }
        ui.addButton(page, ui.button(activity.getString(R.string.profile_achievements),
                UiKit.ButtonStyle.SECONDARY, actions::onAchievements), true);
    }

    private void addEditor(LinearLayout page) {
        LinearLayout card = ui.card();
        ui.addText(card, activity.getString(R.string.profile_name),
                13, UiKit.TEXT_PRIMARY, true, 0, 0);
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setContentDescription(activity.getString(R.string.profile_name));
        name.setTextSize(15);
        name.setTypeface(ui.typeface(false));
        name.setTextColor(UiKit.TEXT_PRIMARY);
        name.setHintTextColor(UiKit.TEXT_SECONDARY);
        name.setHint(R.string.profile_name_hint);
        String currentName = profile == null ? "" : profile.displayName;
        name.setText(currentName == null ? "" : currentName);
        name.setSelectAllOnFocus(true);
        name.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        name.setBackground(ui.shape(UiKit.DEEP, UiKit.BORDER, 12, 1));
        LinearLayout.LayoutParams fieldParams = ui.matchWrap();
        fieldParams.topMargin = ui.dp(8);
        card.addView(name, fieldParams);

        ui.addSection(card, activity.getString(R.string.profile_avatar), null);
        LinearLayout avatarRow = ui.row();
        ImageView avatar = avatarView();
        avatarRow.addView(avatar, new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)));
        LinearLayout avatarInfo = ui.column();
        avatarInfo.setPaddingRelative(ui.dp(12), 0, 0, 0);
        ui.addText(avatarInfo, activity.getString(R.string.profile_avatar_preview),
                11, UiKit.TEXT_SECONDARY, false, 0, 0);
        boolean photoSelected = pendingPhotoPath != null
                || (profile != null && profile.avatarType == 1);
        ui.addText(avatarInfo, activity.getString(photoSelected
                        ? R.string.profile_avatar_photo_label : AVATAR_LABELS[selectedAvatar]),
                15, UiKit.TEXT_PRIMARY, true, 4, 0);
        avatarRow.addView(avatarInfo, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(avatarRow, ui.matchWrap());

        LinearLayout avatarChoices = ui.column();
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout choiceRow = ui.row();
            for (int column = 0; column < 2; column++) {
                int index = rowIndex * 2 + column;
                android.widget.Button choice = ui.button(
                        activity.getString(AVATAR_LABELS[index]),
                        index == selectedAvatar ? UiKit.ButtonStyle.PRIMARY
                                : UiKit.ButtonStyle.SECONDARY,
                        photoImportPending ? null : () -> actions.onSelectBuiltIn(AVATARS[index]));
                LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(0,
                        ui.dp(48), 1f);
                if (column == 1) choiceParams.setMarginStart(ui.dp(8));
                choiceRow.addView(choice, choiceParams);
            }
            LinearLayout.LayoutParams rowParams = ui.matchWrap();
            if (rowIndex > 0) rowParams.topMargin = ui.dp(7);
            avatarChoices.addView(choiceRow, rowParams);
        }
        LinearLayout.LayoutParams choiceContainerParams = ui.matchWrap();
        choiceContainerParams.topMargin = ui.dp(10);
        card.addView(avatarChoices, choiceContainerParams);
        ui.addButton(card, ui.button(activity.getString(R.string.profile_photo),
                UiKit.ButtonStyle.SECONDARY, photoImportPending ? null : actions::onChoosePhoto), true);
        if (photoImportPending) {
            ui.addText(card, activity.getString(R.string.profile_photo_importing),
                    11, UiKit.AMBER, true, 5, 0);
        }
        if (pendingPhotoPath != null || (profile != null && profile.avatarType == 1)) {
            if (pendingPhotoPath != null) {
                ui.addText(card, activity.getString(R.string.profile_photo_saved),
                        11, UiKit.MINT, false, 5, 0);
            }
            ui.addButton(card, ui.button(activity.getString(R.string.profile_remove_photo),
                    UiKit.ButtonStyle.QUIET, photoImportPending ? null : actions::onUseBuiltIn), true);
        }
        ui.addButton(card, ui.button(activity.getString(R.string.profile_save),
                UiKit.ButtonStyle.PRIMARY, photoImportPending ? null : () -> {
                    String value = name.getText() == null ? "" : name.getText().toString().trim();
                    if (!validName(value)) {
                        name.setError(activity.getString(R.string.profile_name_error));
                        name.requestFocus();
                        return;
                    }
                    String avatarId = AVATARS[selectedAvatar];
                    actions.onSave(value, avatarId, pendingPhotoPath);
                }), true);
        ui.addButton(card, ui.button(activity.getString(R.string.profile_cancel),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        ui.addCard(page, card, 0);
    }

    private ImageView avatarView() {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(ui.shape(UiKit.SURFACE_ELEVATED, UiKit.MINT, 48, 1));
        image.setClipToOutline(true);
        String path = pendingPhotoPath != null ? pendingPhotoPath
                : profile != null && profile.avatarType == 1 ? profile.photoPath : null;
        if (path != null && new File(path).isFile()) {
            android.graphics.Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap != null) image.setImageBitmap(bitmap);
            else image.setImageDrawable(new InitialAvatar(activity, ui, selectedAvatar));
        } else {
            image.setImageDrawable(new InitialAvatar(activity, ui, selectedAvatar));
        }
        image.setContentDescription(activity.getString(R.string.profile_avatar));
        return image;
    }

    private void stat(LinearLayout parent, int labelId, int value) {
        LinearLayout card = ui.insetCard(UiKit.SURFACE, UiKit.BORDER);
        ui.addText(card, value < 0 ? "—" : ui.number(value), 20, UiKit.TEXT_PRIMARY, true, 0, 0);
        ui.addText(card, activity.getString(labelId), 10, UiKit.TEXT_SECONDARY, false, 3, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMarginEnd(ui.dp(6));
        parent.addView(card, p);
    }

    private int unlockedCount() {
        int count = 0;
        if (achievements != null) for (LocalStore.Achievement item : achievements) {
            if (item.unlockedAt != null) count++;
        }
        return count;
    }

    private static boolean validName(String value) {
        if (value == null) return false;
        String normalized = java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFC);
        int count = normalized.codePointCount(0, normalized.length());
        if (count < 2 || count > 20) return false;
        return normalized.codePoints().noneMatch(cp -> Character.isISOControl(cp) || cp == 0xFFFD);
    }

    private static final class InitialAvatar extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(3);
        private final UiKit ui;
        private final int avatarIndex;
        InitialAvatar(Activity activity, UiKit ui, int avatarIndex) {
            this.ui = ui;
            this.avatarIndex = Math.max(0, Math.min(3, avatarIndex));
        }
        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float radius = Math.min(b.width(), b.height()) * .48f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(UiKit.DEEP);
            canvas.drawCircle(cx, cy, radius, paint);
            int accent = avatarIndex == 1 ? UiKit.VIOLET
                    : avatarIndex == 2 ? UiKit.AMBER
                    : avatarIndex == 3 ? UiKit.CORAL : UiKit.MINT;
            paint.setColor(accent);
            paint.setStrokeWidth(Math.max(2f, b.width() * .035f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            if (avatarIndex == 0) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawCircle(cx, cy, radius * .66f, paint);
                canvas.drawCircle(cx, cy, radius * .28f, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx + radius * .46f, cy - radius * .32f, radius * .10f, paint);
            } else if (avatarIndex == 1) {
                android.graphics.Path prism = new android.graphics.Path();
                prism.moveTo(cx, cy - radius * .7f);
                prism.lineTo(cx + radius * .6f, cy - radius * .05f);
                prism.lineTo(cx, cy + radius * .7f);
                prism.lineTo(cx - radius * .6f, cy - radius * .05f);
                prism.close();
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawPath(prism, paint);
                canvas.drawLine(cx, cy - radius * .7f, cx, cy + radius * .7f, paint);
                canvas.drawLine(cx - radius * .6f, cy - radius * .05f,
                        cx + radius * .6f, cy - radius * .05f, paint);
            } else if (avatarIndex == 2) {
                android.graphics.Path pulse = new android.graphics.Path();
                pulse.moveTo(cx - radius * .72f, cy);
                pulse.lineTo(cx - radius * .28f, cy);
                pulse.lineTo(cx - radius * .08f, cy - radius * .46f);
                pulse.lineTo(cx + radius * .16f, cy + radius * .42f);
                pulse.lineTo(cx + radius * .34f, cy);
                pulse.lineTo(cx + radius * .72f, cy);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawPath(pulse, paint);
            } else {
                android.graphics.Path star = new android.graphics.Path();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2d + i * Math.PI / 5d;
                    float r = radius * (i % 2 == 0 ? .7f : .31f);
                    float x = cx + (float) Math.cos(angle) * r;
                    float y = cy + (float) Math.sin(angle) * r;
                    if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
                }
                star.close();
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawPath(star, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawCircle(cx, cy, radius * .11f, paint);
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
