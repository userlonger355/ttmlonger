package dev.ttmlonger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Выбор цвета: квадрат насыщенность/яркость сверху и полоса оттенка снизу. */
final class ColorPickerView extends View {
    interface Listener {
        void onColor(int rgb);
    }

    private static final float SV_RATIO = 0.56f;

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sv = new RectF();
    private final RectF bar = new RectF();
    private final float[] hsv = new float[]{140f, 1f, 0.78f};

    private Shader svShader;
    private Shader hueShader;
    private float shaderHue = -1f;
    private int drag; // 0 — нет, 1 — квадрат, 2 — полоса
    private Listener listener;

    ColorPickerView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setColor(int rgb, boolean notify) {
        Color.colorToHSV(0xFF000000 | rgb, hsv);
        shaderHue = -1f;
        invalidate();
        if (notify) fire();
    }

    int getColor() {
        return Color.HSVToColor(hsv) & 0xFFFFFF;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(w * SV_RATIO + 20 * d + 28 * d + 6 * d);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = 4f * d;
        float svBottom = w * SV_RATIO;
        sv.set(pad, pad, w - pad, svBottom);
        float top = svBottom + 20f * d;
        bar.set(pad, top, w - pad, top + 28f * d);
        hueShader = new LinearGradient(bar.left, 0, bar.right, 0, new int[]{
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000},
                null, Shader.TileMode.CLAMP);
        shaderHue = -1f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (shaderHue != hsv[0]) {
            int pure = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            Shader sat = new LinearGradient(sv.left, 0, sv.right, 0, 0xFFFFFFFF, pure, Shader.TileMode.CLAMP);
            Shader val = new LinearGradient(0, sv.top, 0, sv.bottom, 0xFFFFFFFF, 0xFF000000,
                    Shader.TileMode.CLAMP);
            svShader = new ComposeShader(val, sat, PorterDuff.Mode.MULTIPLY);
            shaderHue = hsv[0];
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(svShader);
        c.drawRoundRect(sv, 18f * d, 18f * d, paint);
        paint.setShader(hueShader);
        c.drawRoundRect(bar, 14f * d, 14f * d, paint);
        paint.setShader(null);

        // Бегунок квадрата.
        float x = sv.left + hsv[1] * sv.width();
        float y = sv.top + (1f - hsv[2]) * sv.height();
        paint.setColor(0xFF000000 | getColor());
        c.drawCircle(x, y, 10f * d, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * d);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, 11f * d, paint);

        // Бегунок полосы.
        float bx = bar.left + (hsv[0] / 360f) * bar.width();
        RectF pill = new RectF(bx - 6f * d, bar.top - 3f * d, bx + 6f * d, bar.bottom + 3f * d);
        c.drawRoundRect(pill, 6f * d, 6f * d, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (e.getY() <= sv.bottom + 10f * d) {
                drag = 1;
            } else if (e.getY() >= bar.top - 8f * d) {
                drag = 2;
            } else {
                drag = 0;
            }
            if (drag == 0) return false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            update(e.getX(), e.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && drag != 0) {
            update(e.getX(), e.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            drag = 0;
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void update(float x, float y) {
        if (drag == 1) {
            hsv[1] = clamp((x - sv.left) / sv.width());
            hsv[2] = 1f - clamp((y - sv.top) / sv.height());
        } else if (drag == 2) {
            hsv[0] = clamp((x - bar.left) / bar.width()) * 360f;
        }
        invalidate();
        fire();
    }

    private void fire() {
        if (listener != null) listener.onColor(getColor());
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
