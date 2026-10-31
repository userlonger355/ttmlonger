package dev.ttmlonger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Кружок выбора цвета: стандартный (палитра), готовый цвет или свой цвет («+»). */
final class Swatch extends View {
    static final int RESET = 0;
    static final int COLOR = 1;
    static final int CUSTOM = 2;

    private final float d;
    private final int kind;
    private final int rgb;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    Swatch(Context c, int kind, int rgb) {
        super(c);
        this.d = c.getResources().getDisplayMetrics().density;
        this.kind = kind;
        this.rgb = rgb & 0xFFFFFF;
        setClickable(true);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Math.round(54 * d);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float cx = w / 2f;
        float cy = getHeight() / 2f;
        float r = w / 2f - 3f * d;
        int fill = 0xFF000000 | rgb;

        if (kind == RESET) {
            // Стандартный цвет TikTok: красный круг со стрелкой «вернуть».
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TtStyle.RED);
            c.drawCircle(cx, cy, isSelected() ? r - 7f * d : r, paint);
            Drawable ic = Icons.of(Icons.REFRESH, 0xFFFFFFFF);
            int h = Math.round(22f * d);
            ic.setBounds(Math.round(cx) - h / 2, Math.round(cy) - h / 2, Math.round(cx) + h / 2, Math.round(cy) + h / 2);
            ic.draw(c);
            if (isSelected()) ring(c, cx, cy, r, TtStyle.RED);
            return;
        }

        if (kind == COLOR) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            c.drawCircle(cx, cy, isSelected() ? r - 7f * d : r, paint);
            if (isSelected()) ring(c, cx, cy, r, fill);
            return;
        }

        // CUSTOM: радужное кольцо с плюсом; если выбран свой цвет — внутри он.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f * d);
        paint.setShader(new SweepGradient(cx, cy, new int[]{
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}, null));
        c.drawCircle(cx, cy, r - 2f * d, paint);
        paint.setShader(null);
        if (isSelected()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            c.drawCircle(cx, cy, r - 10f * d, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.INK);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(34f * d);
            c.drawText("+", cx, cy + 12f * d, paint);
        }
    }

    private void ring(Canvas c, float cx, float cy, float r, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * d);
        paint.setColor(color);
        c.drawCircle(cx, cy, r - 1.5f * d, paint);
    }
}
