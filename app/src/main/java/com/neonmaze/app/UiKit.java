package com.neonmaze.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.text.NumberFormat;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;

/**
 * NEON MAZE — Signal Atlas design system.
 * Centralizes visual tokens and reusable native View primitives. It deliberately
 * avoids a permanent render loop, expensive default glows, and hard-coded
 * per-screen component styling.
 */
public final class UiKit {
    public static final int BACKGROUND = Color.rgb(8, 11, 20);
    public static final int SURFACE = Color.rgb(16, 24, 39);
    public static final int SURFACE_ELEVATED = Color.rgb(23, 35, 52);
    public static final int TEXT_PRIMARY = Color.rgb(245, 247, 251);
    public static final int TEXT_SECONDARY = Color.rgb(154, 167, 183);
    public static final int MINT = Color.rgb(86, 230, 213);
    public static final int VIOLET = Color.rgb(167, 139, 250);
    public static final int AMBER = Color.rgb(255, 200, 107);
    public static final int CORAL = Color.rgb(255, 107, 122);
    public static final int BORDER = Color.rgb(37, 50, 71);
    public static final int DEEP = Color.rgb(11, 17, 29);

    public enum ButtonStyle { PRIMARY, SECONDARY, QUIET, DANGER }

    /** The 18 semantic icons in project design-source/icons-master.svg. */
    public enum Icon {
        PLAY, LEVELS, PROFILE, TUTORIAL, SETTINGS, PAUSE, RESTART, BACK, HOME,
        CLOCK, STAR, LOCK, SOUND, HAPTICS, AVATAR, SUCCESS, FAILURE, TROPHY
    }

    private static final Map<Icon, List<IconLayer>> ICON_PATHS = new EnumMap<>(Icon.class);

    private final Context context;
    private final LocalStore.Settings settings;
    private final ContentAssetResolver contentAssets;
    private final boolean rtl;
    private static volatile Typeface persianRegularTypeface;
    private static volatile Typeface persianBoldTypeface;
    private static volatile boolean persianTypefaceLoaded;
    private static final Handler CONTENT_IMAGE_MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService CONTENT_IMAGE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "neon-maze-content-images");
        thread.setDaemon(true);
        return thread;
    });

    public UiKit(@NonNull Context context, @NonNull LocalStore.Settings settings) {
        this(context, settings, new ContentAssetResolver(context.getAssets(), null));
    }

    public UiKit(@NonNull Context context, @NonNull LocalStore.Settings settings,
                 @NonNull ContentAssetResolver contentAssets) {
        this.context = context;
        this.settings = settings;
        this.contentAssets = contentAssets;
        this.rtl = "fa".equals(settings.language);
    }

    public Context context() { return context; }
    /** Resolver for optional, non-executable assets installed with a content pack. */
    public ContentAssetResolver contentAssets() { return contentAssets; }

    /** Opens an optional content asset; callers must close the returned stream. */
    @Nullable
    public InputStream openOptionalContentAsset(@NonNull String logicalPath) {
        return contentAssets.openOptional(logicalPath);
    }

    /** Opens a required content asset or throws when neither pack nor APK provides it. */
    @NonNull
    public InputStream openContentAsset(@NonNull String logicalPath) throws IOException {
        return contentAssets.open(logicalPath);
    }

    /**
     * Loads an optional content-pack image without file I/O or bitmap decoding
     * on the UI thread. Missing images keep the target view collapsed.
     */
    public void loadOptionalContentImage(@NonNull ImageView target,
                                         @NonNull String logicalPath,
                                         int maxWidthPx, int maxHeightPx) {
        final Object requestToken = new Object();
        target.setTag(requestToken);
        target.setVisibility(View.GONE);
        WeakReference<ImageView> targetReference = new WeakReference<>(target);
        int safeWidth = Math.max(1, maxWidthPx);
        int safeHeight = Math.max(1, maxHeightPx);
        CONTENT_IMAGE_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = contentAssets.open(logicalPath)) {
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    BitmapFactory.Options decode = new BitmapFactory.Options();
                    decode.inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight,
                            safeWidth, safeHeight);
                    decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    try (InputStream input = contentAssets.open(logicalPath)) {
                        bitmap = BitmapFactory.decodeStream(input, null, decode);
                    }
                }
            } catch (IOException | RuntimeException unavailable) {
                // Optional artwork must never block or fail the playable game.
            }
            Bitmap decoded = bitmap;
            CONTENT_IMAGE_MAIN.post(() -> {
                ImageView image = targetReference.get();
                if (image == null || image.getTag() != requestToken) {
                    if (decoded != null && !decoded.isRecycled()) decoded.recycle();
                    return;
                }
                if (decoded != null) {
                    image.setImageBitmap(decoded);
                    image.setVisibility(View.VISIBLE);
                } else {
                    image.setVisibility(View.GONE);
                }
            });
        });
    }

    private static int calculateSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int sample = 1;
        while (width / (sample * 2L) >= reqWidth
                || height / (sample * 2L) >= reqHeight) {
            sample *= 2;
        }
        return sample;
    }
    public boolean isRtl() { return rtl; }
    public boolean isReducedMotion() { return settings.reducedMotion; }
    public boolean isHighContrast() { return settings.highContrast; }

    /**
     * Loads required Persian weights on the startup worker before the main UI
     * is created. The loading screen uses the system fallback and never causes
     * font-file I/O on the main thread.
     */
    public static boolean preloadPersianTypefaces(@NonNull Context context) {
        if (!persianTypefaceLoaded) {
            synchronized (UiKit.class) {
                if (!persianTypefaceLoaded) {
                    try {
                        Typeface regular = Typeface.createFromAsset(
                                context.getAssets(), "fonts/Vazirmatn-Regular.ttf");
                        Typeface bold = Typeface.createFromAsset(
                                context.getAssets(), "fonts/Vazirmatn-Bold.ttf");
                        persianRegularTypeface = regular;
                        persianBoldTypeface = bold;
                        persianTypefaceLoaded = true;
                    } catch (RuntimeException missingAsset) {
                        // Do not cache failure: an explicit startup retry must be
                        // allowed to retry loading after assets have been repaired.
                        persianRegularTypeface = null;
                        persianBoldTypeface = null;
                        persianTypefaceLoaded = false;
                    }
                }
            }
        }
        return persianRegularTypeface != null && persianBoldTypeface != null;
    }

    /** Uses cached static Persian weights; before bootstrap, returns a no-I/O system fallback. */
    public Typeface typeface(boolean bold) {
        if (rtl && persianTypefaceLoaded) {
            Typeface selected = bold ? persianBoldTypeface : persianRegularTypeface;
            if (selected != null) return selected;
        }
        return Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    public int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, context.getResources().getDisplayMetrics()));
    }

    public float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                value, context.getResources().getDisplayMetrics());
    }

    public LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        return view;
    }

    public LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        return view;
    }

    public ScrollView scrollPage() {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout content = column();
        content.setPaddingRelative(dp(20), dp(18), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setTag(content);
        return scroll;
    }

    public LinearLayout pageContent(ScrollView scroll) {
        Object tag = scroll.getTag();
        if (!(tag instanceof LinearLayout)) throw new IllegalArgumentException("not a UiKit page");
        return (LinearLayout) tag;
    }

    public void addPageHeader(@NonNull LinearLayout page, String eyebrow,
                              String title, String subtitle) {
        LinearLayout brandRow = row();
        TextView mark = text("NM", 12, BACKGROUND, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(shape(MINT, MINT, 10, 0));
        brandRow.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout brandText = column();
        brandText.setPaddingRelative(dp(10), 0, 0, 0);
        TextView brand = text("NEON MAZE", 12, TEXT_PRIMARY, true);
        TextView descriptor = text("SIGNAL ATLAS  /  PRECISION PLAY", 9, TEXT_SECONDARY, true);
        brandText.addView(brand);
        brandText.addView(descriptor);
        brandRow.addView(brandText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(brandRow, matchWrap());
        if (!TextUtils.isEmpty(eyebrow)) {
            TextView small = text(eyebrow.toUpperCase(Locale.ROOT), 10, MINT, true);
            LinearLayout.LayoutParams p = wrap();
            p.topMargin = dp(28);
            page.addView(small, p);
        } else {
            spacer(page, 22);
        }
        TextView heading = text(title, 26, TEXT_PRIMARY, true);
        heading.setLetterSpacing(0.005f);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(5);
        page.addView(heading, titleParams);
        if (!TextUtils.isEmpty(subtitle)) {
            TextView detail = text(subtitle, 14, TEXT_SECONDARY, false);
            detail.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams detailParams = wrap();
            detailParams.topMargin = dp(8);
            detailParams.bottomMargin = dp(20);
            page.addView(detail, detailParams);
        } else {
            spacer(page, 18);
        }
    }

    public TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(accessibleColor(color));
        view.setTypeface(typeface(bold));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(dp(2), 1f);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    public TextView mono(String value, int sizeSp, int color, boolean bold) {
        TextView view = text(value, sizeSp, color, bold);
        view.setTypeface(Typeface.create("monospace", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (Build.VERSION.SDK_INT >= 21) view.setFontFeatureSettings("tnum");
        return view;
    }

    public Button button(String label, ButtonStyle style, Runnable action) {
        return button(label, style, action, null);
    }

    public Button button(String label, ButtonStyle style, Runnable action, @Nullable Icon icon) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(typeface(true));
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setMinWidth(dp(48));
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        button.setEnabled(action != null);
        button.setFocusable(true);
        int bg;
        int fg;
        int border;
        switch (style) {
            case PRIMARY: bg = MINT; fg = BACKGROUND; border = MINT; break;
            case DANGER: bg = Color.rgb(54, 24, 37); fg = CORAL; border = CORAL; break;
            case QUIET: bg = Color.TRANSPARENT; fg = TEXT_SECONDARY; border = Color.TRANSPARENT; break;
            default: bg = SURFACE_ELEVATED; fg = TEXT_PRIMARY; border = BORDER; break;
        }
        button.setTextColor(accessibleColor(fg));
        if (icon != null) {
            Drawable iconDrawable = new IconDrawable(icon, accessibleColor(fg),
                    style == ButtonStyle.PRIMARY ? accessibleColor(fg) : accessibleColor(TEXT_SECONDARY));
            int iconSize = dp(19);
            iconDrawable.setBounds(0, 0, iconSize, iconSize);
            button.setCompoundDrawablesRelative(iconDrawable, null, null, null);
            button.setCompoundDrawablePadding(dp(9));
        }
        GradientDrawable base = shape(bg, border, 16, settings.highContrast ? 2 : 1);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                style == ButtonStyle.PRIMARY ? 0x557FFFFF : 0x33FFFFFF), base, null));
        if (action == null) button.setAlpha(.48f);
        button.setStateListAnimator(null);
        button.setOnClickListener(action == null ? null : v -> action.run());
        button.setContentDescription(label);
        return button;
    }

    /** Creates a reusable, source-faithful 24x24 outline icon. A null label marks it decorative. */
    public ImageView icon(Icon icon, int color, int sizeDp, @Nullable String contentDescription) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(new IconDrawable(icon, accessibleColor(color),
                accessibleColor(TEXT_SECONDARY)));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setLayoutParams(new ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)));
        if (TextUtils.isEmpty(contentDescription)) {
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            view.setContentDescription(contentDescription);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        return view;
    }

    /**
     * Native Android Path geometry derived from the 18-symbol SVG master.
     * SVG remains a design source; no SVG/path-data parser runs at runtime.
     */
    private static List<IconLayer> iconLayers(Icon icon) {
        synchronized (ICON_PATHS) {
            List<IconLayer> cached = ICON_PATHS.get(icon);
            if (cached != null) return cached;
            ArrayList<IconLayer> layers = new ArrayList<>();
            Path p;
            switch (icon) {
                case PLAY:
                    p = new Path();
                    p.moveTo(8.0f, 5.8f);
                    p.cubicTo(8.0f, 5.0f, 8.9f, 4.5f, 9.6f, 4.9f);
                    p.lineTo(18.7f, 11.0f);
                    p.cubicTo(19.4f, 11.5f, 19.4f, 12.5f, 18.7f, 13.0f);
                    p.lineTo(9.6f, 19.1f);
                    p.cubicTo(8.9f, 19.6f, 8.0f, 19.1f, 8.0f, 18.2f);
                    p.close();
                    addLayer(layers, p, false, 1.8f);
                    break;

                case LEVELS:
                    p = new Path();
                    p.moveTo(4.5f, 6.5f);
                    p.lineTo(12.0f, 3.8f);
                    p.lineTo(19.5f, 6.5f);
                    p.lineTo(19.5f, 17.5f);
                    p.lineTo(12.0f, 20.2f);
                    p.lineTo(4.5f, 17.5f);
                    p.close();
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(4.8f, 6.7f);
                    p.lineTo(12.0f, 9.3f);
                    p.lineTo(19.2f, 6.7f);
                    p.moveTo(12.0f, 9.4f);
                    p.lineTo(12.0f, 19.7f);
                    p.moveTo(8.0f, 8.1f);
                    p.lineTo(15.6f, 5.3f);
                    addLayer(layers, p, true, 1.6f);
                    p = new Path(); p.addCircle(12.0f, 13.5f, 1.2f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    break;

                case PROFILE:
                    p = new Path(); p.addCircle(12.0f, 8.0f, 3.4f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(4.8f, 20.0f);
                    p.cubicTo(5.5f, 16.5f, 8.0f, 14.5f, 12.0f, 14.5f);
                    p.cubicTo(16.0f, 14.5f, 18.5f, 16.5f, 19.2f, 20.0f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(7.0f, 20.0f);
                    p.lineTo(17.0f, 20.0f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case TUTORIAL:
                    p = new Path();
                    p.moveTo(5.0f, 5.5f);
                    p.cubicTo(7.7f, 4.7f, 10.1f, 5.2f, 12.0f, 6.7f);
                    p.cubicTo(13.9f, 5.2f, 16.3f, 4.7f, 19.0f, 5.5f);
                    p.lineTo(19.0f, 18.5f);
                    p.cubicTo(16.3f, 17.7f, 13.9f, 18.2f, 12.0f, 19.7f);
                    p.cubicTo(10.1f, 18.2f, 7.7f, 17.7f, 5.0f, 18.5f);
                    p.close();
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(12.0f, 6.7f);
                    p.lineTo(12.0f, 19.7f);
                    p.moveTo(8.0f, 9.0f);
                    p.lineTo(10.0f, 9.0f);
                    p.moveTo(14.0f, 9.0f);
                    p.lineTo(16.0f, 9.0f);
                    p.moveTo(8.0f, 12.0f);
                    p.lineTo(10.0f, 12.0f);
                    p.moveTo(14.0f, 12.0f);
                    p.lineTo(16.0f, 12.0f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case SETTINGS:
                    p = new Path();
                    p.moveTo(10.0f, 3.8f);
                    p.lineTo(10.5f, 2.8f);
                    p.lineTo(13.5f, 2.8f);
                    p.lineTo(14.0f, 3.8f);
                    p.lineTo(15.0f, 5.6f);
                    p.lineTo(16.8f, 5.9f);
                    p.lineTo(17.9f, 5.4f);
                    p.lineTo(20.0f, 7.5f);
                    p.lineTo(19.5f, 8.6f);
                    p.lineTo(19.8f, 10.4f);
                    p.lineTo(21.6f, 11.4f);
                    p.lineTo(22.5f, 11.9f);
                    p.lineTo(22.5f, 14.9f);
                    p.lineTo(21.6f, 15.4f);
                    p.lineTo(19.8f, 16.4f);
                    p.lineTo(19.5f, 18.2f);
                    p.lineTo(20.0f, 19.3f);
                    p.lineTo(17.9f, 21.4f);
                    p.lineTo(16.8f, 20.9f);
                    p.lineTo(15.0f, 21.2f);
                    p.lineTo(14.0f, 23.0f);
                    p.lineTo(13.5f, 23.9f);
                    p.lineTo(10.5f, 23.9f);
                    p.lineTo(10.0f, 23.0f);
                    p.lineTo(9.0f, 21.2f);
                    p.lineTo(7.2f, 20.9f);
                    p.lineTo(6.1f, 21.4f);
                    p.lineTo(4.0f, 18.2f);
                    p.lineTo(4.5f, 17.1f);
                    p.lineTo(4.2f, 15.3f);
                    p.lineTo(2.4f, 14.3f);
                    p.lineTo(1.5f, 13.8f);
                    p.lineTo(1.5f, 10.8f);
                    p.lineTo(2.4f, 10.3f);
                    p.lineTo(4.2f, 9.3f);
                    p.lineTo(4.5f, 7.5f);
                    p.lineTo(4.0f, 6.4f);
                    p.lineTo(6.1f, 4.3f);
                    p.lineTo(7.2f, 4.8f);
                    p.lineTo(9.0f, 4.5f);
                    p.close();
                    Matrix transform = new Matrix();
                    transform.setValues(new float[]{1.05f, 0f, -0.6f, 0f, 1.05f, -1.2f, 0f, 0f, 1f});
                    p.transform(transform);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path(); p.addCircle(12.0f, 12.0f, 3.2f, Path.Direction.CW);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case PAUSE:
                    p = new Path();
                    p.moveTo(8.2f, 5.5f);
                    p.lineTo(8.2f, 18.5f);
                    p.moveTo(15.8f, 5.5f);
                    p.lineTo(15.8f, 18.5f);
                    addLayer(layers, p, false, 1.8f);
                    break;

                case RESTART:
                    p = new Path();
                    p.moveTo(5.3f, 9.1f);
                    p.cubicTo(6.6903f, 5.8927f, 10.1836f, 4.1474f, 13.5831f, 4.9617f);
                    p.cubicTo(16.9827f, 5.776f, 19.3062f, 8.9144f, 19.0926f, 12.4036f);
                    p.cubicTo(18.8789f, 15.8928f, 16.1899f, 18.7244f, 12.7164f, 19.1179f);
                    p.cubicTo(9.2429f, 19.5113f, 5.9887f, 17.353f, 5.0f, 14.0f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(5.3f, 4.8f);
                    p.lineTo(5.3f, 9.6f);
                    p.lineTo(10.1f, 9.6f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(12.0f, 7.8f);
                    p.lineTo(12.0f, 12.3f);
                    p.lineTo(15.0f, 14.0f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case BACK:
                    p = new Path();
                    p.moveTo(19.0f, 12.0f);
                    p.lineTo(5.0f, 12.0f);
                    p.moveTo(11.0f, 6.0f);
                    p.lineTo(5.0f, 12.0f);
                    p.lineTo(11.0f, 18.0f);
                    addLayer(layers, p, false, 1.8f);
                    break;

                case HOME:
                    p = new Path();
                    p.moveTo(3.8f, 10.5f);
                    p.lineTo(12.0f, 3.7f);
                    p.lineTo(20.2f, 10.5f);
                    p.lineTo(20.2f, 19.5f);
                    p.lineTo(14.0f, 19.5f);
                    p.lineTo(14.0f, 13.8f);
                    p.lineTo(10.0f, 13.8f);
                    p.lineTo(10.0f, 19.5f);
                    p.lineTo(3.8f, 19.5f);
                    p.close();
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(8.4f, 19.5f);
                    p.lineTo(8.4f, 13.8f);
                    p.lineTo(15.6f, 13.8f);
                    p.lineTo(15.6f, 19.5f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case CLOCK:
                    p = new Path(); p.addCircle(12.0f, 12.0f, 8.6f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(12.0f, 6.6f);
                    p.lineTo(12.0f, 12.4f);
                    p.lineTo(16.0f, 14.5f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(8.0f, 2.8f);
                    p.lineTo(16.0f, 2.8f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case STAR:
                    p = new Path();
                    p.moveTo(12.0f, 3.1f);
                    p.lineTo(14.7f, 8.8f);
                    p.lineTo(20.9f, 9.6f);
                    p.lineTo(16.4f, 13.9f);
                    p.lineTo(17.5f, 20.1f);
                    p.lineTo(12.0f, 17.1f);
                    p.lineTo(6.5f, 20.1f);
                    p.lineTo(7.6f, 13.9f);
                    p.lineTo(3.1f, 9.6f);
                    p.lineTo(9.3f, 8.8f);
                    p.close();
                    addLayer(layers, p, false, 1.8f);
                    break;

                case LOCK:
                    p = new Path(); p.addRoundRect(new RectF(5.2f, 10.2f, 18.8f, 20.4f), 2.0f, 2.0f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(8.0f, 10.2f);
                    p.lineTo(8.0f, 7.5f);
                    p.cubicTo(8.0f, 5.2909f, 9.7909f, 3.5f, 12.0f, 3.5f);
                    p.cubicTo(14.2091f, 3.5f, 16.0f, 5.2909f, 16.0f, 7.5f);
                    p.lineTo(16.0f, 10.2f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path(); p.addCircle(12.0f, 15.2f, 1.1f, Path.Direction.CW);
                    addLayer(layers, p, true, 1.6f);
                    p = new Path();
                    p.moveTo(12.0f, 16.3f);
                    p.lineTo(12.0f, 17.7f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case SOUND:
                    p = new Path();
                    p.moveTo(4.0f, 9.5f);
                    p.lineTo(7.4f, 9.5f);
                    p.lineTo(11.9f, 5.7f);
                    p.lineTo(11.9f, 18.7f);
                    p.lineTo(7.4f, 14.9f);
                    p.lineTo(4.0f, 14.9f);
                    p.close();
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(15.0f, 9.0f);
                    p.cubicTo(16.5279f, 10.7082f, 16.5279f, 13.2918f, 15.0f, 15.0f);
                    p.moveTo(17.5f, 6.5f);
                    p.cubicTo(20.4207f, 9.585f, 20.4207f, 14.415f, 17.5f, 17.5f);
                    addLayer(layers, p, false, 1.8f);
                    break;

                case HAPTICS:
                    p = new Path(); p.addRoundRect(new RectF(8.0f, 4.2f, 16.0f, 19.8f), 2.0f, 2.0f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(4.8f, 8.2f);
                    p.cubicTo(3.3f, 10.4f, 3.3f, 13.6f, 4.8f, 15.8f);
                    p.moveTo(19.2f, 8.2f);
                    p.cubicTo(20.7f, 10.4f, 20.7f, 13.6f, 19.2f, 15.8f);
                    p.moveTo(2.5f, 5.7f);
                    p.cubicTo(0.4f, 9.3f, 0.4f, 14.5f, 2.5f, 18.1f);
                    p.moveTo(21.5f, 5.7f);
                    p.cubicTo(23.6f, 9.3f, 23.6f, 14.5f, 21.5f, 18.1f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case AVATAR:
                    p = new Path(); p.addCircle(12.0f, 8.0f, 3.1f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(5.0f, 20.0f);
                    p.cubicTo(5.7f, 16.8f, 8.1f, 14.9f, 12.0f, 14.9f);
                    p.cubicTo(15.9f, 14.9f, 18.3f, 16.8f, 19.0f, 20.0f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(4.2f, 4.3f);
                    p.lineTo(6.2f, 4.3f);
                    p.moveTo(17.8f, 4.3f);
                    p.lineTo(19.8f, 4.3f);
                    p.moveTo(4.2f, 19.8f);
                    p.lineTo(6.2f, 19.8f);
                    p.moveTo(17.8f, 19.8f);
                    p.lineTo(19.8f, 19.8f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case SUCCESS:
                    p = new Path(); p.addCircle(12.0f, 12.0f, 8.8f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(7.8f, 12.2f);
                    p.lineTo(10.6f, 15.0f);
                    p.lineTo(16.4f, 9.0f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(8.2f, 3.9f);
                    p.lineTo(9.0f, 2.8f);
                    p.moveTo(15.8f, 3.9f);
                    p.lineTo(15.0f, 2.8f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case FAILURE:
                    p = new Path(); p.addCircle(12.0f, 12.0f, 8.8f, Path.Direction.CW);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(9.0f, 9.0f);
                    p.lineTo(15.0f, 15.0f);
                    p.moveTo(15.0f, 9.0f);
                    p.lineTo(9.0f, 15.0f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(12.0f, 2.8f);
                    p.lineTo(12.0f, 4.3f);
                    addLayer(layers, p, true, 1.6f);
                    break;

                case TROPHY:
                    p = new Path();
                    p.moveTo(7.0f, 4.0f);
                    p.lineTo(17.0f, 4.0f);
                    p.lineTo(17.0f, 9.2f);
                    p.cubicTo(17.0f, 11.9614f, 14.7614f, 14.2f, 12.0f, 14.2f);
                    p.cubicTo(9.2386f, 14.2f, 7.0f, 11.9614f, 7.0f, 9.2f);
                    p.close();
                    p.moveTo(12.0f, 14.2f);
                    p.lineTo(12.0f, 19.0f);
                    p.moveTo(8.5f, 20.0f);
                    p.lineTo(15.5f, 20.0f);
                    addLayer(layers, p, false, 1.8f);
                    p = new Path();
                    p.moveTo(7.0f, 6.0f);
                    p.lineTo(4.2f, 6.0f);
                    p.lineTo(4.2f, 8.2f);
                    p.cubicTo(4.4064f, 9.8484f, 5.8399f, 11.0648f, 7.5f, 11.0f);
                    p.moveTo(17.0f, 6.0f);
                    p.lineTo(19.8f, 6.0f);
                    p.lineTo(19.8f, 8.2f);
                    p.cubicTo(19.5936f, 9.8484f, 18.1601f, 11.0648f, 16.5f, 11.0f);
                    addLayer(layers, p, true, 1.6f);
                    break;
                default: throw new IllegalArgumentException("Unknown icon " + icon);
            }
            List<IconLayer> immutable = java.util.Collections.unmodifiableList(layers);
            ICON_PATHS.put(icon, immutable);
            return immutable;
        }
    }

    private static void addLayer(List<IconLayer> layers, Path path, boolean muted, float strokeWidth) {
        layers.add(new IconLayer(path, muted, strokeWidth));
    }

    private static final class IconLayer {
        final Path path;
        final boolean muted;
        final float strokeWidth;
        IconLayer(Path path, boolean muted, float strokeWidth) {
            this.path = path;
            this.muted = muted;
            this.strokeWidth = strokeWidth;
        }
    }

    private final class IconDrawable extends Drawable {
        private final List<IconLayer> layers;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int primary;
        private final int secondary;
        private int alpha = 255;
        IconDrawable(Icon icon, int primary, int secondary) {
            this.layers = iconLayers(icon);
            this.primary = primary;
            this.secondary = secondary;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float side = Math.min(bounds.width(), bounds.height());
            float scale = side / 24f;
            float left = bounds.left + (bounds.width() - side) * .5f;
            float top = bounds.top + (bounds.height() - side) * .5f;
            int save = canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            for (IconLayer layer : layers) {
                paint.setStrokeWidth(layer.strokeWidth);
                int base = layer.muted ? secondary : primary;
                paint.setColor((base & 0x00FFFFFF) | ((Color.alpha(base) * alpha / 255) << 24));
                canvas.drawPath(layer.path, paint);
            }
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) {
            this.alpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }
        @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return dp(24); }
        @Override public int getIntrinsicHeight() { return dp(24); }
    }

    public LinearLayout card() {
        LinearLayout card = column();
        card.setPaddingRelative(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(shape(SURFACE, BORDER, 18, 1));
        card.setClipToPadding(false);
        return card;
    }

    public LinearLayout insetCard(int background, int stroke) {
        LinearLayout card = column();
        card.setPaddingRelative(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(shape(background, stroke, 14, stroke == Color.TRANSPARENT ? 0 : 1));
        return card;
    }

    public TextView badge(String label, int foreground, int background) {
        TextView badge = text(label, 11, foreground, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPaddingRelative(dp(10), dp(5), dp(10), dp(5));
        badge.setBackground(shape(background, background, 20, 0));
        badge.setContentDescription(label);
        return badge;
    }

    public View divider() {
        View divider = new View(context);
        divider.setBackgroundColor(accessibleColor(BORDER));
        return divider;
    }

    public void addSection(LinearLayout parent, String title, String hint) {
        LinearLayout row = row();
        TextView heading = text(title, 17, TEXT_PRIMARY, true);
        row.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!TextUtils.isEmpty(hint)) row.addView(text(hint, 11, TEXT_SECONDARY, false));
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(22);
        p.bottomMargin = dp(10);
        parent.addView(row, p);
    }

    public void spacer(LinearLayout parent, int heightDp) {
        View spacer = new View(context);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    public void addGap(LinearLayout parent, int heightDp) { spacer(parent, heightDp); }

    public void addButton(LinearLayout parent, Button button, boolean fullWidth) {
        LinearLayout.LayoutParams p = fullWidth
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        p.topMargin = dp(8);
        parent.addView(button, p);
    }

    public void addText(LinearLayout parent, String value, int size, int color,
                        boolean bold, int topMarginDp) {
        addText(parent, value, size, color, bold, topMarginDp, 0);
    }

    /** Adds a localized text block with explicit vertical spacing. */
    public void addText(LinearLayout parent, String value, int size, int color,
                        boolean bold, int topMarginDp, int bottomMarginDp) {
        TextView view = text(value, size, color, bold);
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(topMarginDp);
        p.bottomMargin = dp(bottomMarginDp);
        parent.addView(view, p);
    }

    public void addCard(LinearLayout parent, View card, int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMarginDp);
        parent.addView(card, p);
    }

    public void addDivider(LinearLayout parent, int topMarginDp, int bottomMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        p.topMargin = dp(topMarginDp);
        p.bottomMargin = dp(bottomMarginDp);
        parent.addView(divider(), p);
    }

    public GradientDrawable shape(int color, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(accessibleColor(color));
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(settings.highContrast ? Math.max(2, strokeDp) : strokeDp),
                accessibleColor(strokeColor));
        return drawable;
    }

    public FrameLayout frame() {
        FrameLayout frame = new FrameLayout(context);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        return frame;
    }

    public LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public String formatTime(long millis) {
        if (millis <= 0L) return "—";
        long totalSeconds = millis / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long centis = (millis % 1000L) / 10L;
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, centis);
    }

    public String number(int value) {
        NumberFormat format = NumberFormat.getIntegerInstance(rtl ? new Locale("fa") : Locale.US);
        format.setGroupingUsed(false);
        return format.format(Math.max(0, value));
    }

    public void applyPageWidth(View view) {
        view.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
    }

    public int semanticColor(boolean success, boolean warning, boolean failure) {
        if (failure) return accessibleColor(CORAL);
        if (warning) return accessibleColor(AMBER);
        return accessibleColor(success ? MINT : TEXT_SECONDARY);
    }

    private int accessibleColor(int color) {
        if (!settings.highContrast) return color;
        if (color == TEXT_SECONDARY) return Color.rgb(211, 222, 237);
        if (color == BORDER) return Color.rgb(88, 108, 135);
        if (color == MINT) return Color.rgb(126, 255, 239);
        if (color == VIOLET) return Color.rgb(198, 181, 255);
        if (color == AMBER) return Color.rgb(255, 220, 151);
        if (color == CORAL) return Color.rgb(255, 145, 158);
        return color;
    }
}
