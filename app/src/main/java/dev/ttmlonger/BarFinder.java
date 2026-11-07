package dev.ttmlonger;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Поиск нижней панели вкладок TikTok («Главная, Друзья, +, Входящие, Профиль»).
 *
 * <p>Классы и номера ресурсов TikTok обфусцированы и меняются от сборки к сборке, поэтому панель опознаётся
 * по виду: широкий ряд у нижнего края экрана из 4–5 равномерно стоящих вкладок, у которых есть подписи. Лучше
 * всего подходит самый вложенный ряд. Всё решение о «похоже / не похоже» вынесено в чистые функции
 * ({@link #score}, {@link #pickSelected}, {@link #createIndex}), чтобы проверять их без телефона.
 */
final class BarFinder {
    private static final int NODE_BUDGET = 6000;
    private static final int MAX_DEPTH = 48;

    private BarFinder() {
    }

    /** Найденная панель. */
    static final class Found {
        ViewGroup bar;
        final ArrayList<View> tabs = new ArrayList<>();
        /** Номер вкладки «+» (создать) среди tabs или -1. */
        int create = -1;
        int score;
        String how = "";
    }

    // ------------------------------------------------------------ чистые функции

    /**
     * Оценка кандидата: 0 — не панель. barW×barH — размер ряда, barBottom — нижний край ряда от верха окна,
     * decorW×decorH — размер окна, w и cx — ширины и центры вкладок слева направо, labelled — у скольких есть
     * подпись, nameHit — в имени класса или ресурса есть tab/bottom/nav, dp — плотность.
     */
    static int score(int barW, int barH, int barBottom, int decorW, int decorH, int[] w, int[] cx, int labelled,
                     boolean nameHit, float dp) {
        if (decorW <= 0 || decorH <= 0 || dp <= 0f || w == null || cx == null || w.length != cx.length) return 0;
        int n = w.length;
        if (n < 4 || n > 5) return 0;
        // у настоящей панели есть подписи под значками; без них нужна подсказка в имени класса или ресурса
        if (labelled < 3 && !nameHit) return 0;
        if (barW < decorW * 0.80f || barW > decorW * 1.02f) return 0;
        if (barH < 36f * dp || barH > 140f * dp) return 0;
        if (barBottom < decorH * 0.80f) return 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            if (w[i] < decorW * 0.08f || w[i] > decorW * 0.34f) return 0;
            sum += w[i];
            if (i > 0 && cx[i] <= cx[i - 1]) return 0;
        }
        if (sum > barW * 1.03f) return 0;
        // шаги между центрами примерно равны (середина может быть шире из-за кнопки «+»)
        int minGap = Integer.MAX_VALUE;
        int maxGap = 0;
        for (int i = 1; i < n; i++) {
            int g = cx[i] - cx[i - 1];
            minGap = Math.min(minGap, g);
            maxGap = Math.max(maxGap, g);
        }
        if (minGap <= 0 || maxGap > minGap * 1.7f) return 0;
        int s = 10 + (n == 5 ? 3 : 2) + Math.min(Math.max(labelled, 0), 4) + (nameHit ? 2 : 0);
        return s;
    }

    /** Номер кнопки «+» среди n вкладок: единственная без подписи при остальных с подписью, иначе середина из пяти. */
    static int createIndex(boolean[] labelled) {
        if (labelled == null) return -1;
        int n = labelled.length;
        int without = -1;
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (!labelled[i]) {
                without = i;
                count++;
            }
        }
        if (count == 1 && n >= 4) return without;
        return n == 5 ? 2 : -1;
    }

    private static double lum(int c) {
        return (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255));
    }

    /**
     * Какая вкладка выбрана. eligible — номера вкладок без «+», sel — стоит ли у вкладки состояние «выбрана»,
     * color — цвет подписи (или 0, если подписи нет), alpha — прозрачность значка. Возвращает номер из eligible
     * или -1, если по признакам не понять:
     * 1) ровно одна вкладка в состоянии «выбрана»;
     * 2) цвет подписи одной вкладки заметно отличается от остальных, которые одинаковы между собой;
     * 3) то же по прозрачности значка.
     */
    static int pickSelected(int[] eligible, boolean[] sel, int[] color, float[] alpha) {
        if (eligible == null || eligible.length < 2) return -1;
        int found = -1;
        int count = 0;
        for (int e : eligible) {
            if (sel != null && e < sel.length && sel[e]) {
                found = e;
                count++;
            }
        }
        if (count == 1) return found;
        if (color != null) {
            int r = outlier(eligible, color, 40.0, 14.0);
            if (r >= 0) return r;
        }
        if (alpha != null) {
            double[] v = new double[eligible.length];
            for (int i = 0; i < eligible.length; i++) {
                int e = eligible[i];
                v[i] = e < alpha.length ? alpha[e] * 255.0 : 255.0;
            }
            int i = outlierOf(v, 40.0, 10.0);
            if (i >= 0) return eligible[i];
        }
        return -1;
    }

    private static int outlier(int[] eligible, int[] color, double gap, double same) {
        double[] v = new double[eligible.length];
        for (int i = 0; i < eligible.length; i++) {
            int e = eligible[i];
            if (e >= color.length || color[e] == 0) return -1;
            v[i] = lum(color[e]);
        }
        int i = outlierOf(v, gap, same);
        return i < 0 ? -1 : eligible[i];
    }

    /** Индекс единственного значения, отличающегося от остальных больше чем на gap, когда остальные близки (same). */
    static int outlierOf(double[] v, double gap, double same) {
        if (v == null || v.length < 3) return -1;
        for (int i = 0; i < v.length; i++) {
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (int j = 0; j < v.length; j++) {
                if (j == i) continue;
                lo = Math.min(lo, v[j]);
                hi = Math.max(hi, v[j]);
            }
            if (hi - lo > same) continue;
            double mid = (hi + lo) / 2.0;
            if (Math.abs(v[i] - mid) > gap) return i;
        }
        return -1;
    }

    static boolean nameHit(String cls, String idName) {
        String a = cls == null ? "" : cls.toLowerCase(Locale.ROOT);
        String b = idName == null ? "" : idName.toLowerCase(Locale.ROOT);
        return a.contains("tab") || a.contains("bottom") || a.contains("nav") || b.contains("tab")
                || b.contains("bottom") || b.contains("nav");
    }

    // ------------------------------------------------------------ обход видов

    private static final class Scan {
        final int[] at = new int[2];
        final int[] origin = new int[2];
        int decorW;
        int decorH;
        float dp;
        int nodes;
        Found best;
        int bestArea = Integer.MAX_VALUE;
        int considered;
    }

    /** Ищет панель в окне; null — не нашлось. */
    static Found find(View decor) {
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) return null;
        Scan s = new Scan();
        decor.getLocationOnScreen(s.origin);
        s.decorW = decor.getWidth();
        s.decorH = decor.getHeight();
        s.dp = decor.getResources().getDisplayMetrics().density;
        walk(decor, s, 0);
        return s.best;
    }

    /** Сколько рядов-кандидатов проверено при последнем поиске (для строки состояния). */
    static volatile int lastConsidered;
    static volatile int lastNodes;

    private static void walk(View v, Scan s, int depth) {
        if (v == null || depth > MAX_DEPTH || s.nodes++ > NODE_BUDGET) return;
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int w = g.getWidth();
            int h = g.getHeight();
            if (w >= s.decorW * 0.80f && h >= 36f * s.dp && h <= 140f * s.dp && g.getChildCount() >= 4) {
                consider(g, s);
            }
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), s, depth + 1);
        }
        lastNodes = s.nodes;
        lastConsidered = s.considered;
    }

    private static void consider(ViewGroup g, Scan s) {
        g.getLocationOnScreen(s.at);
        int bottom = s.at[1] - s.origin[1] + g.getHeight();
        if (bottom < s.decorH * 0.80f) return;
        s.considered++;
        ArrayList<View> tabs = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() <= 0 || c.getHeight() <= 0) continue;
            // тонкие разделители и подложки во всю ширину не вкладки
            if (c.getWidth() > s.decorW * 0.34f || c.getHeight() < g.getHeight() * 0.4f) continue;
            tabs.add(c);
        }
        int n = tabs.size();
        if (n < 4 || n > 5) return;
        tabs.sort(new java.util.Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                return Integer.compare(a.getLeft(), b.getLeft());
            }
        });
        int[] w = new int[n];
        int[] cx = new int[n];
        boolean[] lab = new boolean[n];
        int labelled = 0;
        for (int i = 0; i < n; i++) {
            View t = tabs.get(i);
            w[i] = t.getWidth();
            cx[i] = t.getLeft() + t.getWidth() / 2;
            lab[i] = hasLabel(t, 0);
            if (lab[i]) labelled++;
        }
        String idName = null;
        int id = g.getId();
        if (id != View.NO_ID && (id & 0xFF000000) == 0x7F000000) {
            try {
                idName = g.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
                // у номера нет имени
            }
        }
        boolean nameHit = nameHit(g.getClass().getName(), idName);
        int sc = score(g.getWidth(), g.getHeight(), bottom, s.decorW, s.decorH, w, cx, labelled, nameHit, s.dp);
        if (sc <= 0) return;
        int area = g.getWidth() * g.getHeight();
        if (s.best == null || sc > s.best.score || (sc == s.best.score && area < s.bestArea)) {
            Found f = new Found();
            f.bar = g;
            f.tabs.addAll(tabs);
            f.create = createIndex(lab);
            f.score = sc;
            f.how = g.getClass().getSimpleName() + " " + n + " вкладок, подписей " + labelled
                    + (idName != null ? ", id " + idName : "");
            s.best = f;
            s.bestArea = area;
        }
    }

    /** Есть ли внутри вида короткая видимая подпись. */
    static boolean hasLabel(View v, int depth) {
        if (v == null || depth > 4 || v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return t != null && t.length() > 0 && t.length() <= 16;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasLabel(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ признаки выбранной вкладки

    /** Состояние «выбрана» у вида или у кого-то из потомков (глубина до 3). */
    static boolean anySelected(View v, int depth) {
        if (v == null) return false;
        if (v.isSelected()) return true;
        if (depth >= 3 || !(v instanceof ViewGroup)) return false;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            if (anySelected(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    /** Цвет первой подписи внутри вида или 0. */
    static int labelColor(View v, int depth) {
        if (v == null || depth > 4 || v.getVisibility() != View.VISIBLE) return 0;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence s = t.getText();
            if (s != null && s.length() > 0 && s.length() <= 16) return t.getCurrentTextColor() | 0xFF000000;
            return 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                int c = labelColor(g.getChildAt(i), depth + 1);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    /** Прозрачность первого значка внутри вида (с учётом родителей до вкладки) или 1. */
    static float iconAlpha(View v, int depth) {
        if (v == null || depth > 4 || v.getVisibility() != View.VISIBLE) return 1f;
        if (v instanceof ImageView) return v.getAlpha();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ImageView) return c.getAlpha() * v.getAlpha();
            }
        }
        return v.getAlpha();
    }
}
