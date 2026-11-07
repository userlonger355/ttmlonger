package dev.ttmlonger;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Рисует линзу шейдером {@link GlassShader#SRC}. Нужен Android 13 (API 33): класс RuntimeShader есть только там,
 * поэтому всё, что его касается, лежит отдельно и вызывается только после проверки версии.
 */
@TargetApi(33)
final class AgslLens {
    private final RuntimeShader shader;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BitmapShader texShader;
    private Bitmap texBitmap;

    /** Бросает исключение, если шейдер не скомпилировался: вызывающий переходит на запасной рисунок. */
    AgslLens() {
        shader = new RuntimeShader(GlassShader.SRC);
    }

    void draw(Canvas c, Bitmap tex, GlassGeo.Frame f) {
        if (tex == null || tex.isRecycled()) return;
        if (texShader == null || texBitmap != tex) {
            texShader = new BitmapShader(tex, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            texShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            texBitmap = tex;
            shader.setInputShader("T", texShader);
        }
        shader.setFloatUniform("R", (float) f.rw, (float) f.rh);
        shader.setFloatUniform("N", (float) f.nw, (float) f.nh);
        shader.setFloatUniform("B", (float) f.bw, (float) f.bh);
        shader.setFloatUniform("L", (float) f.lx, (float) f.ly, (float) f.lw, (float) f.lh);
        shader.setFloatUniform("S", (float) f.s);
        shader.setFloatUniform("P", (float) f.p);
        shader.setFloatUniform("Q", (float) f.q);
        shader.setFloatUniform("W", (float) f.px);
        shader.setFloatUniform("Y", (float) f.y);
        shader.setFloatUniform("K", (float) f.k);
        shader.setFloatUniform("Z", (float) f.z);
        shader.setFloatUniform("D", (float) f.d);
        shader.setFloatUniform("I", (float) f.i);
        shader.setFloatUniform("SAT", (float) f.sat);
        shader.setFloatUniform("BRT", (float) f.brt);
        shader.setFloatUniform("BG", (float) f.bgR, (float) f.bgG, (float) f.bgB);
        paint.setShader(shader);
        c.drawRect(0f, 0f, (float) f.rw, (float) f.rh, paint);
    }

    void release() {
        texShader = null;
        texBitmap = null;
    }
}
