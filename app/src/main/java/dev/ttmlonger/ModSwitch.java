package dev.ttmlonger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Переключатель в стиле экрана мода: цветная дорожка, белый кружок и галочка. */
final class ModSwitch extends View {
    interface Listener {
        void onChanged(boolean on);
    }

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path tick = new Path();
    private boolean on;
    private Listener listener;

    ModSwitch(Context c, boolean initial) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        on = initial;
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                on = !on;
                invalidate();
                if (listener != null) listener.onChanged(on);
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isOn() {
        return on;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(52 * d), Math.round(32 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        int accent = Theme.accent();

        // Дорожка.
        if (on) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, h / 2f, h / 2f, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * d);
            paint.setColor(0xFF8A8A8A);
            rect.set(1.25f * d, 1.25f * d, w - 1.25f * d, h - 1.25f * d);
            c.drawRoundRect(rect, h / 2f, h / 2f, paint);
        }

        // Кружок.
        float r = on ? 13f * d : 9f * d;
        float cx = on ? w - h / 2f : h / 2f;
        float cy = h / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? 0xFFFFFFFF : 0xFF8A8A8A);
        c.drawCircle(cx, cy, r, paint);

        // Галочка.
        if (on) {
            tick.reset();
            tick.moveTo(cx - 5f * d, cy + 0.5f * d);
            tick.lineTo(cx - 1.5f * d, cy + 4f * d);
            tick.lineTo(cx + 5.5f * d, cy - 4f * d);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(accent);
            c.drawPath(tick, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
    }
}
