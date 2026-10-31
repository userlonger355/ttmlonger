package dev.ttmlonger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Переключатель как в TikTok: плавная «пилюля» цвета акцента и белый кружок с тенью. */
final class ModSwitch extends View {
    interface Listener {
        void onChanged(boolean on);
    }

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean on;
    /** Положение от 0 (выключен) до 1 (включён); меняется плавно. */
    private float pos;
    private ValueAnimator anim;
    private Listener listener;

    ModSwitch(Context c, boolean initial) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        on = initial;
        pos = initial ? 1f : 0f;
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isOn() {
        return on;
    }

    /** Переключает с анимацией и сообщает слушателю. */
    void toggle() {
        on = !on;
        animateTo(on ? 1f : 0f);
        if (listener != null) listener.onChanged(on);
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(160);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(51 * d), Math.round(31 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();

        // Дорожка: серая в выключенном положении, цвет акцента во включённом.
        int off = Ui.DARK ? 0xFF3A3A3C : 0xFFD9D9DC;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(off, TtStyle.primary(), pos));
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, h / 2f, h / 2f, paint);

        // Кружок с мягкой тенью.
        float pad = 2f * d;
        float r = h / 2f - pad;
        float cx = h / 2f + (w - h) * pos;
        float cy = h / 2f;
        paint.setColor(0x33000000);
        c.drawCircle(cx, cy + 1.2f * d, r + 0.6f * d, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, r, paint);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
