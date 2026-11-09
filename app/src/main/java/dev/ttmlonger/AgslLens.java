package dev.ttmlonger;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
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
    private BitmapShader bkShader;
    private Bitmap bkBitmap;
    /** Пока снимка ленты нет, вход G всё равно должен быть задан: подставляется одна прозрачная точка. */
    private Bitmap dummy;

    /** Бросает исключение, если шейдер не скомпилировался: вызывающий переходит на запасной рисунок. */
    AgslLens() {
        shader = new RuntimeShader(GlassShader.SRC);
    }

    /** bk — снимок полосы ленты над панелью (может быть null: тогда f.bk должен быть 0). */
    void draw(Canvas c, Bitmap tex, Bitmap bk, GlassGeo.Frame f) {
        if (!bind(tex, bk, f)) return;
        paint.setShader(shader);
        c.drawRect(0f, 0f, (float) f.rw, (float) f.rh, paint);
    }

    /**
     * Настоящий фон: вход G шейдера — запись того, что лежит под панелью (node), а не снимок. Шейдер вешается на узел
     * как эффект (RenderEffect), так что видеокарта сама подставляет в G картинку узла; blurPx > 0 размывает её до
     * шейдера. Узел рисуется туда же, где рисовался бы прямоугольник шейдера (холст уже сдвинут на начало холста
     * капсулы).
     */
    boolean drawLive(Canvas c, Bitmap tex, RenderNode node, double blurPx, GlassGeo.Frame f) {
        if (node == null || !node.hasDisplayList() || !c.isHardwareAccelerated()) return false;
        if (!bind(tex, null, f)) return false;
        shader.setFloatUniform("LV", 1f);
        RenderEffect e = RenderEffect.createRuntimeShaderEffect(shader, "G");
        if (blurPx > 0.4) {
            float r = (float) Math.min(blurPx, 100.0);
            e = RenderEffect.createChainEffect(e, RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        }
        node.setPosition(0, 0, (int) Math.round(f.rw), (int) Math.round(f.rh));
        node.setRenderEffect(e);
        c.drawRenderNode(node);
        return true;
    }

    /** Задаёт входы и uniform-переменные шейдера по кадру. */
    private boolean bind(Bitmap tex, Bitmap bk, GlassGeo.Frame f) {
        if (tex == null || tex.isRecycled()) return false;
        Bitmap g = bk != null && !bk.isRecycled() ? bk : null;
        if (g == null) {
            if (dummy == null) dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            g = dummy;
        }
        if (bkShader == null || bkBitmap != g) {
            bkShader = new BitmapShader(g, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bkShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            bkBitmap = g;
            shader.setInputShader("G", bkShader);
        }
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
        shader.setFloatUniform("ZM", (float) f.zm);
        shader.setFloatUniform("PS", (float) f.ps);
        shader.setFloatUniform("GA", (float) f.ga);
        shader.setFloatUniform("V", (float) f.v);
        shader.setFloatUniform("SC", (float) f.sc);
        shader.setFloatUniform("CY", (float) f.cy);
        shader.setFloatUniform("YC", (float) f.yc);
        shader.setFloatUniform("LT", (float) f.lt);
        shader.setFloatUniform("CX", (float) f.cx);
        shader.setFloatUniform("EZ", (float) f.ez);
        shader.setFloatUniform("LA", (float) f.la);
        shader.setFloatUniform("CA", (float) f.ca);
        shader.setFloatUniform("GS", (float) f.gsx, (float) f.gsy);
        shader.setFloatUniform("BK", (float) f.bk);
        shader.setFloatUniform("SB", (float) f.sb);
        shader.setFloatUniform("BM", (float) f.bm0, (float) f.bm1);
        shader.setFloatUniform("TN", (float) f.tnR, (float) f.tnG, (float) f.tnB, (float) f.tnA);
        shader.setFloatUniform("OP", (float) f.op);
        shader.setFloatUniform("PM", (float) f.pm);
        shader.setFloatUniform("PB", (float) f.pbR, (float) f.pbG, (float) f.pbB);
        shader.setFloatUniform("PP", (float) f.ppR, (float) f.ppG, (float) f.ppB);
        shader.setFloatUniform("PZ", (float) f.pz0, (float) f.pz1, (float) f.pz2, (float) f.pz3);
        shader.setFloatUniform("LV", 0f);
        return true;
    }

    void release() {
        texShader = null;
        texBitmap = null;
        bkShader = null;
        bkBitmap = null;
    }
}
