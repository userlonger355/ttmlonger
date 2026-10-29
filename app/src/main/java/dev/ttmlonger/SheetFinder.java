package dev.ttmlonger;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Узнаёт лист стикера по устройству, а не по имени классов (они обфусцированы): крупная картинка и
 * под ней ряд из двух-трёх кнопок-надписей («Поделиться», «Сохранить»). Чистый обход view, без
 * хуков: работает, даже если методы TikTok не нашлись.
 */
final class SheetFinder {
    /** Метка на ряду, в который мод уже добавил свою кнопку. */
    static final int TAG_PLACED = 0x7ff0ad05;

    private SheetFinder() {
    }

    static final class Hit {
        ViewGroup row;
        ImageView picture;
        /** Ряд — горизонтальный LinearLayout: в него кнопка встаёт третьей. */
        boolean linear;
        /** Кнопка мода уже стоит в этом ряду. */
        boolean placed;
        /** Высота кнопок ряда, пиксели. */
        int tall;
    }

    static final class Scan {
        Hit hit;
        /** В окне есть крупная картинка. */
        boolean bigPicture;
        /** Подходящий ряд найден, но ещё не измерен: нужно прийти позже. */
        boolean waiting;
        /** Сколько рядов-кнопок попалось (для диагностики). */
        int rows;
    }

    /**
     * @param picFraction какую долю ширины окна должна занимать картинка листа (аватар над
     *                    кнопками «Подписаться»/«Написать» не должен сойти за стикер)
     */
    static Scan scan(View root, float picFraction) {
        Scan s = new Scan();
        if (root == null) return s;
        float d = root.getResources().getDisplayMetrics().density;
        int rootW = root.getWidth() > 0 ? root.getWidth() : root.getResources().getDisplayMetrics().widthPixels;
        int minPic = Math.max(Math.round(96 * d), Math.round(rootW * picFraction));
        List<ViewGroup> rows = new ArrayList<>();
        walk(root, s, rows, Math.round(64 * d), 0, new int[]{3500});
        s.rows = rows.size();

        int[] loc = new int[2];
        int bestTop = Integer.MIN_VALUE;
        for (ViewGroup row : rows) {
            if (row.getTag(TAG_PLACED) != null) {
                Hit h = new Hit();
                h.row = row;
                h.placed = true;
                s.hit = h;
                return s;
            }
            View first = firstVisible(row);
            if (first == null) continue;
            if (first.getHeight() <= 0 || first.getWidth() <= 0) {
                s.waiting = true;
                continue;
            }
            ImageView pic = pictureAbove(row, minPic);
            if (pic == null) continue;
            row.getLocationOnScreen(loc);
            if (loc[1] > bestTop) {
                bestTop = loc[1];
                Hit h = new Hit();
                h.row = row;
                h.picture = pic;
                h.linear = row instanceof LinearLayout
                        && ((LinearLayout) row).getOrientation() == LinearLayout.HORIZONTAL;
                h.tall = first.getHeight();
                s.hit = h;
            }
        }
        return s;
    }

    private static void walk(View v, Scan s, List<ViewGroup> rows, int bigPic, int depth, int[] budget) {
        if (v == null || depth > 45 || --budget[0] < 0 || v.getVisibility() != View.VISIBLE
                || Diag.OWN_TAG.equals(v.getTag())) return;
        if (v instanceof ImageView && v.getWidth() >= bigPic && v.getHeight() >= bigPic) s.bigPicture = true;
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        if (g.getTag(TAG_PLACED) != null || isButtonRow(g)) rows.add(g);
        for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), s, rows, bigPic, depth + 1, budget);
    }

    /** Две или три видимые кнопки-надписи и больше ничего. */
    static boolean isButtonRow(ViewGroup g) {
        int visible = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (!(c instanceof TextView) || !(c.isClickable() || c.hasOnClickListeners())) return false;
            CharSequence t = ((TextView) c).getText();
            if (t == null) return false;
            int n = t.toString().trim().length();
            if (n == 0 || n > 30) return false;
            visible++;
        }
        return visible >= 2 && visible <= 3;
    }

    private static View firstVisible(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) return c;
        }
        return null;
    }

    /** Самая большая картинка среди «соседей» ряда (до трёх уровней вверх), лежащая над ним. */
    private static ImageView pictureAbove(View row, int minPic) {
        int[] rl = new int[2];
        row.getLocationOnScreen(rl);
        int slack = Math.round(8 * row.getResources().getDisplayMetrics().density);
        View child = row;
        for (int up = 0; up < 3 && child.getParent() instanceof ViewGroup; up++) {
            ViewGroup parent = (ViewGroup) child.getParent();
            ImageView best = null;
            int[] pl = new int[2];
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c == child) continue;
                ImageView pic = biggestImage(c, minPic, 0);
                if (pic == null) continue;
                pic.getLocationOnScreen(pl);
                if (pl[1] + pic.getHeight() > rl[1] + slack) continue;
                if (best == null || area(pic) > area(best)) best = pic;
            }
            if (best != null) return best;
            child = parent;
        }
        return null;
    }

    private static ImageView biggestImage(View v, int minPic, int depth) {
        if (v == null || depth > 7 || v.getVisibility() != View.VISIBLE || Diag.OWN_TAG.equals(v.getTag())) return null;
        if (v instanceof ImageView) {
            return v.getWidth() >= minPic && v.getHeight() >= minPic ? (ImageView) v : null;
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        ImageView best = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            ImageView p = biggestImage(g.getChildAt(i), minPic, depth + 1);
            if (p != null && (best == null || area(p) > area(best))) best = p;
        }
        return best;
    }

    private static long area(View v) {
        return (long) v.getWidth() * v.getHeight();
    }

    // ------------------------------------------------------------ для диагностики

    /** Подписи нажимаемых надписей в окне, через «|». */
    static String labels(View root) {
        StringBuilder b = new StringBuilder();
        labels(root, b, 0, new int[]{2000});
        return b.length() == 0 ? "-" : b.toString();
    }

    private static void labels(View v, StringBuilder b, int depth, int[] budget) {
        if (v == null || depth > 40 || --budget[0] < 0 || b.length() > 160 || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView && v.isClickable()) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                if (b.length() > 0) b.append(" | ");
                b.append(t.length() > 24 ? t.subSequence(0, 24) : t);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) labels(g.getChildAt(i), b, depth + 1, budget);
        }
    }

    /**
     * Схема окна: класс, размер, нажимаемость и подписи. По ней видно, как устроен лист, если мод
     * его не узнал. Личных данных там нет, кроме подписей на кнопках.
     */
    static String outline(View root, int maxLines) {
        StringBuilder b = new StringBuilder();
        outline(root, b, 0, new int[]{maxLines, 3000});
        return b.toString();
    }

    private static void outline(View v, StringBuilder b, int depth, int[] budget) {
        if (v == null || budget[0] <= 0 || --budget[1] < 0 || depth > 18 || v.getVisibility() != View.VISIBLE
                || Diag.OWN_TAG.equals(v.getTag())) return;
        budget[0]--;
        for (int i = 0; i < depth && i < 18; i++) b.append(' ');
        b.append(shortName(v)).append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v.isClickable()) b.append(" click");
        if (v instanceof ImageView && ((ImageView) v).getDrawable() != null) b.append(" img");
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                b.append(" '").append(t.length() > 24 ? t.subSequence(0, 24) : t).append('\'');
            }
        }
        b.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) outline(g.getChildAt(i), b, depth + 1, budget);
        }
    }

    /** Имя класса; у обфусцированных добавляется ближайший класс Android, чтобы было понятно, что это. */
    static String shortName(View v) {
        Class<?> c = v.getClass();
        String n = c.getName();
        if (n.startsWith("android.")) return n.substring(n.lastIndexOf('.') + 1);
        String base = null;
        for (Class<?> k = c.getSuperclass(); k != null; k = k.getSuperclass()) {
            String kn = k.getName();
            if (kn.startsWith("android.")) {
                base = kn.substring(kn.lastIndexOf('.') + 1);
                break;
            }
        }
        String simple = n.substring(n.lastIndexOf('.') + 1);
        return base == null ? simple : simple + "(" + base + ")";
    }
}
