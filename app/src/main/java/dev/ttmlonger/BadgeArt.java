package dev.ttmlonger;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Рисунок бейджика: описание из {@link BadgeSet}, заливка цветом бейджика, белая обводка (галочка, блик). */
final class BadgeArt extends Drawable implements Icons.Sink {
    private final BadgeSet.Def def;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Canvas canvas;
    private float k = 1f;

    BadgeArt(BadgeSet.Def def) {
        this.def = def;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(def.color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        if (b.isEmpty()) return;
        int save = c.save();
        c.translate(b.left, b.top);
        k = Math.min(b.width(), b.height()) / 24f;
        stroke.setStrokeWidth(2.2f * k);
        canvas = c;
        path.reset();
        Icons.parseText(def.art, -1, this);
        c.restoreToCount(save);
    }

    @Override
    public void move(float x, float y) {
        path.moveTo(x * k, y * k);
    }

    @Override
    public void line(float x, float y) {
        path.lineTo(x * k, y * k);
    }

    @Override
    public void cubic(float x1, float y1, float x2, float y2, float x, float y) {
        path.cubicTo(x1 * k, y1 * k, x2 * k, y2 * k, x * k, y * k);
    }

    @Override
    public void close() {
        path.close();
    }

    @Override
    public void flush(boolean f) {
        canvas.drawPath(path, f ? fill : stroke);
        path.reset();
    }

    @Override
    public void circle(float cx, float cy, float r, boolean f) {
        canvas.drawCircle(cx * k, cy * k, r * k, f ? fill : stroke);
    }

    @Override
    public void roundRect(float x, float y, float w, float h, float r) {
        rect.set(x * k, y * k, (x + w) * k, (y + h) * k);
        canvas.drawRoundRect(rect, r * k, r * k, stroke);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return -1;
    }
}
