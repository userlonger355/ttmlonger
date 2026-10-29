package dev.ttmlonger;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Значок «скачать» (стрелка в лоток), нарисованный линиями. Текстовый символ ⬇ на части телефонов
 * превращается в цветной эмодзи, поэтому рисуем сами и красим в цвет соседних кнопок.
 */
final class Glyph extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private Glyph(int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    static Drawable download(int color) {
        return new Glyph(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        float x = b.left + (b.width() - s) / 2f;
        float y = b.top + (b.height() - s) / 2f;
        paint.setStrokeWidth(Math.max(1.5f, s * 0.09f));

        path.reset();
        // стрелка вниз
        path.moveTo(x + s * 0.5f, y + s * 0.14f);
        path.lineTo(x + s * 0.5f, y + s * 0.64f);
        path.moveTo(x + s * 0.30f, y + s * 0.46f);
        path.lineTo(x + s * 0.5f, y + s * 0.66f);
        path.lineTo(x + s * 0.70f, y + s * 0.46f);
        // лоток
        path.moveTo(x + s * 0.18f, y + s * 0.62f);
        path.lineTo(x + s * 0.18f, y + s * 0.84f);
        path.lineTo(x + s * 0.82f, y + s * 0.84f);
        path.lineTo(x + s * 0.82f, y + s * 0.62f);
        c.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
