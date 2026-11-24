package com.neonmaze.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Compact diegetic panel surface for the PRISM CIRCUIT gameplay HUD.
 * The clipped corners, inset rails and accent rail make HUD elements read as
 * instrument hardware rather than generic app cards. All geometry is cached
 * in onBoundsChange; draw() performs no allocation.
 */
final class GameHudPanelDrawable extends Drawable {

    private final int accent;
    private final boolean control;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path insetShape = new Path();
    private final RectF bounds = new RectF();
    private final RectF accentRect = new RectF();
    private int alpha = 255;
    @Nullable private ColorFilter colorFilter;

    GameHudPanelDrawable(int accent, boolean control) {
        this.accent = accent;
        this.control = control;
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.MITER);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeJoin(Paint.Join.MITER);
        accentPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect rect) {
        super.onBoundsChange(rect);
        bounds.set(rect);
        float width = bounds.width();
        float height = bounds.height();
        if (width <= 0f || height <= 0f) {
            fill.setShader(null);
            return;
        }
        float cut = Math.min(control ? 9f : 12f, Math.min(width, height) * 0.20f);
        float inset = Math.max(1.1f, Math.min(width, height) * 0.035f);

        shape.reset();
        shape.moveTo(bounds.left + cut, bounds.top);
        shape.lineTo(bounds.right - cut * 1.35f, bounds.top);
        shape.lineTo(bounds.right, bounds.top + cut * 1.35f);
        shape.lineTo(bounds.right, bounds.bottom - cut * 0.8f);
        shape.lineTo(bounds.right - cut * 0.8f, bounds.bottom);
        shape.lineTo(bounds.left + cut * 0.8f, bounds.bottom);
        shape.lineTo(bounds.left, bounds.bottom - cut * 0.8f);
        shape.lineTo(bounds.left, bounds.top + cut);
        shape.close();

        insetShape.reset();
        insetShape.moveTo(bounds.left + cut + inset, bounds.top + inset);
        insetShape.lineTo(bounds.right - cut * 1.35f - inset, bounds.top + inset);
        insetShape.lineTo(bounds.right - inset, bounds.top + cut * 1.35f + inset);
        insetShape.lineTo(bounds.right - inset, bounds.bottom - cut * 0.8f - inset);

        fill.setShader(new LinearGradient(bounds.left, bounds.top, bounds.left,
                bounds.bottom,
                new int[]{Color.rgb(24, 42, 62), Color.rgb(12, 23, 39),
                        Color.rgb(7, 15, 28)}, null, Shader.TileMode.CLAMP));
        float stroke = control ? 1.15f : 1.25f;
        outline.setStrokeWidth(stroke);
        inner.setStrokeWidth(0.8f);
        accentRect.set(bounds.left + cut + inset, bounds.top + inset,
                Math.min(bounds.right - inset, bounds.left + cut + inset + Math.max(9f, width * 0.18f)),
                bounds.top + inset + (control ? 1.6f : 2f));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (bounds.isEmpty()) return;
        fill.setAlpha(alpha);
        outline.setAlpha(Math.round(alpha * 0.78f));
        outline.setColor(Color.argb(188, Color.red(accent), Color.green(accent), Color.blue(accent)));
        inner.setAlpha(Math.round(alpha * 0.22f));
        inner.setColor(Color.WHITE);
        accentPaint.setAlpha(Math.round(alpha * (control ? 0.72f : 0.92f)));
        accentPaint.setColor(accent);
        fill.setColorFilter(colorFilter);
        outline.setColorFilter(colorFilter);
        inner.setColorFilter(colorFilter);
        accentPaint.setColorFilter(colorFilter);

        canvas.drawPath(shape, fill);
        canvas.drawPath(shape, outline);
        canvas.drawPath(insetShape, inner);
        canvas.drawRect(accentRect, accentPaint);

        // Two tiny calibration marks on the far edge complete the instrument-panel language.
        float markWidth = Math.max(2f, bounds.width() * 0.045f);
        float markY = bounds.bottom - Math.max(3f, bounds.height() * 0.17f);
        accentPaint.setAlpha(Math.round(alpha * 0.42f));
        canvas.drawRect(bounds.right - markWidth * 3.2f, markY,
                bounds.right - markWidth * 2.35f, markY + 1.1f, accentPaint);
        canvas.drawRect(bounds.right - markWidth * 1.75f, markY,
                bounds.right - markWidth * 0.9f, markY + 1.1f, accentPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
