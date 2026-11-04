package dev.ttmlonger;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

/**
 * Бейджик вместо метки в тексте. Размер берётся из кисти текста при отрисовке, поэтому он подходит и к
 * мелкой подписи, и к крупному имени в профиле; по высоте бейджик стоит посередине строки.
 */
final class BadgeSpan extends ReplacementSpan {
    private final BadgeArt art;

    BadgeSpan(BadgeArt art) {
        this.art = art;
    }

    private static float side(Paint paint) {
        return paint.getTextSize() * 0.95f;
    }

    private static float gap(Paint paint) {
        return paint.getTextSize() * 0.2f;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return Math.round(gap(paint) + side(paint));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                     Paint paint) {
        float side = side(paint);
        Paint.FontMetrics m = paint.getFontMetrics();
        float cy = y + (m.ascent + m.descent) / 2f;
        float left = x + gap(paint);
        art.setBounds(Math.round(left), Math.round(cy - side / 2f), Math.round(left + side), Math.round(cy + side / 2f));
        art.draw(canvas);
    }
}
