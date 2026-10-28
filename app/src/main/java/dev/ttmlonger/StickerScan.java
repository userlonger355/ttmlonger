package dev.ttmlonger;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Поиск картинок на экране: всё, что похоже на стикер, во всех окнах приложения (переписка,
 * комментарии, лист стикера). Не зависит от хуков на нажатия: просто обходит view и для каждой
 * достаточно крупной картинки берёт адрес (обходом её полей) и снимок. Результат показывает
 * экран «Стикеры», откуда любую найденную картинку можно сохранить.
 */
final class StickerScan {
    static final int MAX_ITEMS = 30;
    private static final int MAX_VIEWS = 8000;
    private static final int MIN_DP = 56;
    private static final int THUMB_PX = 192;

    private static volatile String lastSummary = "экран «Стикеры» ещё не открывали";

    private StickerScan() {
    }

    static String lastSummary() {
        return lastSummary;
    }

    static final class Cand {
        String url;
        Bitmap thumb;
        /** Снимок в полном размере, только если адреса нет. */
        Bitmap shot;
        int w;
        int h;
        int y;
        int x;
        String cls;
    }

    static final class Result {
        final List<Cand> items = new ArrayList<>();
        int windows;
        int images;
        final List<String> big = new ArrayList<>();

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("окон ").append(windows).append(", картинок ").append(images)
                    .append(", подходящих ").append(items.size());
            if (!big.isEmpty()) sb.append(", крупные: ").append(join(big, 6));
            return sb.toString();
        }

        private static String join(List<String> l, int max) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size() && i < max; i++) {
                if (i > 0) sb.append("; ");
                sb.append(l.get(i));
            }
            return sb.toString();
        }
    }

    private static final class Ctx {
        final Result r = new Result();
        final Set<String> seenUrls = new HashSet<>();
        int budget = MAX_VIEWS;
        int minPx;
        int screenW;
        int screenH;
    }

    static Result scan(Activity act) {
        Ctx c = new Ctx();
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        c.minPx = Math.round(MIN_DP * dm.density);
        c.screenW = dm.widthPixels;
        c.screenH = dm.heightPixels;

        List<View> roots = new ArrayList<>(StickerSaver.windows());
        View own = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (own != null && !roots.contains(own)) roots.add(0, own);
        // Верхние окна раньше: лист стикера важнее того, что под ним.
        Collections.reverse(roots);
        for (View root : roots) {
            if (root == null || Screen.isOwnDecor(root)) continue;
            c.r.windows++;
            walk(root, c, 0);
        }
        Collections.sort(c.r.items, new Comparator<Cand>() {
            @Override
            public int compare(Cand a, Cand b) {
                return a.y != b.y ? Integer.compare(a.y, b.y) : Integer.compare(a.x, b.x);
            }
        });
        lastSummary = c.r.summary();
        return c.r;
    }

    private static void walk(View v, Ctx c, int depth) {
        if (v == null || depth > 60 || --c.budget < 0 || v.getVisibility() != View.VISIBLE) return;
        if (Diag.OWN_TAG.equals(v.getTag())) return;
        if (v instanceof ImageView) {
            image((ImageView) v, c);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), c, depth + 1);
        }
    }

    private static void image(ImageView iv, Ctx c) {
        try {
            c.r.images++;
            int w = iv.getWidth();
            int h = iv.getHeight();
            if (w < c.minPx || h < c.minPx || !iv.isShown() || iv.getDrawable() == null) return;
            // Обложки и фоны на весь экран: не стикеры.
            if (w >= c.screenW * 0.9f && h >= c.screenH * 0.4f) return;
            if (c.r.big.size() < 12) c.r.big.add(iv.getClass().getSimpleName() + " " + w + "x" + h);
            if (c.r.items.size() >= MAX_ITEMS) return;

            String url = StickerSaver.urlOf(iv);
            if (url != null && !c.seenUrls.add(url)) return;
            Bitmap shot = StickerSaver.bitmapOf(iv);
            if (shot == null && url == null) return;

            Cand cand = new Cand();
            cand.url = url;
            cand.w = w;
            cand.h = h;
            cand.cls = iv.getClass().getName();
            int[] loc = new int[2];
            iv.getLocationOnScreen(loc);
            cand.x = loc[0];
            cand.y = loc[1];
            if (shot != null) {
                Bitmap soft = software(shot);
                cand.thumb = thumb(soft);
                if (url == null) cand.shot = soft;
            }
            c.r.items.add(cand);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static Bitmap software(Bitmap b) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && b.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
            return copy != null ? copy : b;
        }
        return b;
    }

    private static Bitmap thumb(Bitmap b) {
        int max = Math.max(b.getWidth(), b.getHeight());
        if (max <= THUMB_PX) return b;
        float k = THUMB_PX / (float) max;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                Math.max(1, Math.round(b.getHeight() * k)), true);
    }
}
