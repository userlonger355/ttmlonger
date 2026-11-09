package dev.ttmlonger;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

/**
 * Настоящий фон под стеклом: запись того, что лежит под панелью (0.1.0z). Нужен Android 13: запись хранится в
 * RenderNode, а шейдер видеокарта подставляет как эффект (RenderEffect) узла, см. {@link AgslLens#drawLive}.
 *
 * <p>Что записывается. Панель стоит в цепочке видов окна: корень окна → … → родитель панели → панель. Сама панель в
 * запись не попадает (иначе она рисовала бы сама себя), а попадает всё, что рисуется под ней: фон каждого предка и
 * соседи панели, стоящие раньше неё (страница TikTok обычно лежит именно там). Виды, стоящие после панели, рисуются
 * поверх неё в окне, поэтому и в записи их нет. Каждый сосед рисуется обычным View.draw на аппаратном холсте записи:
 * внутри он подставляет в запись свои готовые списки команд (в том числе TextureView с видео), так что запись стоит
 * дёшево, а картинка обновляется сама, пока видеокарта рисует кадры.
 *
 * <p>Запись перекладывается перед каждым кадром (OnPreDraw) и никогда внутри рисования: внутри оно ломало бы
 * списки команд самих видов (повторный вход в запись).
 */
@TargetApi(33)
final class LiveBackdrop {
    private final RenderNode node = new RenderNode("ttmlonger-backdrop");
    private final Matrix toBar = new Matrix();
    private final Matrix inv = new Matrix();
    private final Matrix tmp = new Matrix();
    private final ArrayList<View> chain = new ArrayList<>();
    private String note = "ещё не записывалось";
    private int records;
    private int fails;
    private long lastNs;
    private long maxNs;
    private int siblings;

    LiveBackdrop() {
        node.setClipToBounds(true);
    }

    RenderNode node() {
        return node;
    }

    /** Записан ли хотя бы один кадр. */
    boolean ready() {
        return records > 0 && node.hasDisplayList();
    }

    int fails() {
        return fails;
    }

    String status() {
        return note + ", записей " + records + (records > 0 ? ", последняя " + (lastNs / 1000) + " мкс, самая долгая "
                + (maxNs / 1000) + " мкс, соседей под панелью " + siblings : "");
    }

    /**
     * Записывает то, что лежит под холстом капсулы. barOx, barOy — начало холста капсулы в координатах панели
     * (capL, capT − pad); w, h — размер холста.
     *
     * @return записано ли
     */
    boolean record(View bar, int barOx, int barOy, int w, int h) {
        if (w <= 0 || h <= 0) {
            note = "холст капсулы пуст";
            return false;
        }
        long t0 = System.nanoTime();
        chain.clear();
        View v = bar;
        for (int i = 0; i < 40 && v != null; i++) {
            chain.add(v);
            Object p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        int n = chain.size();
        if (n < 2) {
            note = "у панели нет предков";
            return false;
        }
        // сдвиг и преобразование каждого предка в окно: ребёнок → родитель = перенос (left − scroll) после матрицы ребёнка
        toBar.reset();
        for (int k = n - 2; k >= 0; k--) {
            View c = chain.get(k);
            ViewGroup g = (ViewGroup) chain.get(k + 1);
            step(tmp, g, c);
            toBar.preConcat(tmp);
        }
        if (!toBar.invert(inv)) {
            note = "преобразование панели вырождено";
            return false;
        }
        node.setPosition(0, 0, w, h);
        RecordingCanvas rc = node.beginRecording(w, h);
        int sib = 0;
        try {
            // окно → холст капсулы: начало холста лежит в (barOx, barOy) панели, а панель — по toBar в окне
            rc.translate(-barOx, -barOy);
            rc.concat(inv);
            for (int k = n - 1; k >= 1; k--) {
                ViewGroup g = (ViewGroup) chain.get(k);
                View next = chain.get(k - 1);
                Drawable bgd = g.getBackground();
                if (bgd != null) {
                    bgd.setBounds(0, 0, g.getWidth(), g.getHeight());
                    bgd.draw(rc);
                }
                int cnt = g.getChildCount();
                for (int i = 0; i < cnt; i++) {
                    View c = g.getChildAt(i);
                    if (c == next) break;
                    if (c.getVisibility() != View.VISIBLE) continue;
                    drawSibling(rc, g, c);
                    sib++;
                }
                step(tmp, g, next);
                rc.concat(tmp);
            }
        } finally {
            node.endRecording();
        }
        siblings = sib;
        records++;
        lastNs = System.nanoTime() - t0;
        maxNs = Math.max(maxNs, lastNs);
        note = "работает";
        return true;
    }

    /** Преобразование «ребёнок → родитель»: сначала матрица ребёнка (поворот, масштаб, сдвиг-свойства), потом позиция. */
    private static void step(Matrix out, ViewGroup g, View c) {
        out.reset();
        out.setTranslate(c.getLeft() - g.getScrollX(), c.getTop() - g.getScrollY());
        Matrix m = c.getMatrix();
        if (m != null && !m.isIdentity()) out.preConcat(m);
    }

    private void drawSibling(Canvas rc, ViewGroup g, View c) {
        int save = rc.save();
        try {
            rc.translate(c.getLeft() - g.getScrollX(), c.getTop() - g.getScrollY());
            Matrix m = c.getMatrix();
            if (m != null && !m.isIdentity()) rc.concat(m);
            float a = c.getAlpha();
            if (a <= 0.01f) return;
            if (a < 0.99f) rc.saveLayerAlpha(0f, 0f, c.getWidth(), c.getHeight(), Math.round(a * 255f));
            c.draw(rc);
        } finally {
            rc.restoreToCount(save);
        }
    }

    /** Запись не удалась: считаем сбои и помним причину (после нескольких подряд сессия уходит на запасной фон). */
    void failed(Throwable t) {
        fails++;
        note = "сбой записи: " + t.getClass().getSimpleName();
        FlagStore.log(t);
    }

    void ok() {
        fails = 0;
    }

    void release() {
        try {
            node.setRenderEffect(null);
            node.discardDisplayList();
        } catch (Throwable ignored) {
            // узел уже не нужен
        }
        records = 0;
        chain.clear();
    }
}
