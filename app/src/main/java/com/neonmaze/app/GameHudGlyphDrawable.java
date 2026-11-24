package com.neonmaze.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Small semantic glyphs for the gameplay instrument HUD.
 * Shapes remain distinct without relying on color; draw() reuses all geometry.
 */
final class GameHudGlyphDrawable extends Drawable {
    static final int KIND_OBJECTIVE = 1;
    static final int KIND_GATE = 2;

    static final int STATE_NEUTRAL = 0;
    static final int STATE_OPEN = 1;
    static final int STATE_WARNING = 2;
    static final int STATE_LOCKED = 3;
    static final int STATE_CLOSED = 4;
    static final int STATE_BLOCKED = 5;
    static final int STATE_COMPLETE = 6;

    private final int kind;
    private final int intrinsicSize;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int state = STATE_NEUTRAL;
    private int accent = Color.WHITE;
    private int alpha = 255;
    @Nullable private ColorFilter colorFilter;

    GameHudGlyphDrawable(float density, int kind) {
        this.kind = kind;
        intrinsicSize = Math.max(14, Math.round(17f * density));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    void setAppearance(int state, int accent) {
        if (this.state == state && this.accent == accent) return;
        this.state = state;
        this.accent = accent;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float cx = (bounds.left + bounds.right) * 0.5f;
        float cy = (bounds.top + bounds.bottom) * 0.5f;
        float r = Math.min(bounds.width(), bounds.height()) * 0.34f;
        stroke.setColor(accent);
        stroke.setAlpha(alpha);
        fill.setColor(accent);
        fill.setAlpha(Math.round(alpha * 0.18f));
        stroke.setStrokeWidth(Math.max(1.25f, r * 0.22f));
        stroke.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);

        if (kind == KIND_OBJECTIVE) {
            drawObjective(canvas, cx, cy, r);
        } else {
            drawGate(canvas, cx, cy, r);
        }
    }

    private void drawObjective(Canvas canvas, float cx, float cy, float r) {
        if (state == STATE_BLOCKED) {
            path.reset();
            path.moveTo(cx, cy - r * 1.18f);
            path.lineTo(cx + r, cy + r * 0.9f);
            path.lineTo(cx - r, cy + r * 0.9f);
            path.close();
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
            canvas.drawLine(cx, cy - r * 0.42f, cx, cy + r * 0.28f, stroke);
            canvas.drawCircle(cx, cy + r * 0.62f, Math.max(0.8f, r * 0.09f), fill);
        } else if (state == STATE_COMPLETE) {
            canvas.drawCircle(cx, cy, r * 1.02f, stroke);
            path.reset();
            path.moveTo(cx - r * 0.55f, cy);
            path.lineTo(cx - r * 0.12f, cy + r * 0.42f);
            path.lineTo(cx + r * 0.64f, cy - r * 0.52f);
            canvas.drawPath(path, stroke);
        } else {
            path.reset();
            path.moveTo(cx, cy - r);
            path.lineTo(cx + r, cy);
            path.lineTo(cx, cy + r);
            path.lineTo(cx - r, cy);
            path.close();
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
            canvas.drawCircle(cx, cy, Math.max(0.8f, r * 0.16f), stroke);
        }
    }

    private void drawGate(Canvas canvas, float cx, float cy, float r) {
        float post = r * 0.58f;
        canvas.drawLine(cx - post, cy - r, cx - post, cy + r, stroke);
        canvas.drawLine(cx + post, cy - r, cx + post, cy + r, stroke);
        if (state == STATE_OPEN) {
            canvas.drawLine(cx - r * 0.28f, cy - r * 0.40f,
                    cx + r * 0.18f, cy, stroke);
            canvas.drawLine(cx + r * 0.18f, cy,
                    cx - r * 0.28f, cy + r * 0.40f, stroke);
            canvas.drawLine(cx + r * 0.30f, cy - r * 0.40f,
                    cx + r * 0.76f, cy, stroke);
            canvas.drawLine(cx + r * 0.76f, cy,
                    cx + r * 0.30f, cy + r * 0.40f, stroke);
        } else if (state == STATE_WARNING) {
            canvas.drawLine(cx, cy - r * 0.48f, cx, cy + r * 0.18f, stroke);
            canvas.drawCircle(cx, cy + r * 0.58f, Math.max(0.8f, r * 0.09f), fill);
        } else if (state == STATE_LOCKED) {
            canvas.drawLine(cx - r * 0.37f, cy - r * 0.08f,
                    cx - r * 0.37f, cy - r * 0.52f, stroke);
            canvas.drawLine(cx - r * 0.37f, cy - r * 0.52f,
                    cx + r * 0.37f, cy - r * 0.52f, stroke);
            canvas.drawLine(cx + r * 0.37f, cy - r * 0.52f,
                    cx + r * 0.37f, cy - r * 0.08f, stroke);
            canvas.drawRoundRect(cx - r * 0.62f, cy - r * 0.12f,
                    cx + r * 0.62f, cy + r * 0.82f, r * 0.12f, r * 0.12f, stroke);
        } else {
            canvas.drawLine(cx - r * 0.90f, cy - r * 0.25f,
                    cx + r * 0.90f, cy - r * 0.25f, stroke);
            canvas.drawLine(cx - r * 0.90f, cy + r * 0.25f,
                    cx + r * 0.90f, cy + r * 0.25f, stroke);
        }
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

    @Override
    public int getIntrinsicWidth() {
        return intrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicSize;
    }
}
