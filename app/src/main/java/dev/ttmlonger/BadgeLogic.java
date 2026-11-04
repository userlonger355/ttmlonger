package dev.ttmlonger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Метка бейджика внутри текста имени. Бейджик рисуется картинкой, а имя приходит в приложение строкой,
 * поэтому за именем идёт невидимая метка из трёх знаков нулевой ширины: она говорит, какой бейджик
 * нарисовать. Знаков шесть, число из трёх знаков по основанию 6 (0..215) и есть номер бейджика.
 *
 * Какой знак что значит, мод выбирает случайно при каждом запуске. Поэтому метку нельзя подделать
 * чужим именем или комментарием: чужой знак с экрана другого телефона или другого запуска даст другой
 * номер. Из имён все такие знаки перед разметкой убираются.
 *
 * Класс без Android: проверяется на обычной JVM.
 */
final class BadgeLogic {
    /** Невидимые знаки нулевой ширины: пробел нулевой ширины и «невидимые операторы». Набор постоянный. */
    static final char[] SET = {'​', '⁠', '⁡', '⁢', '⁣', '⁤'};
    static final int BASE = 6;
    static final int WIDTH = 3;
    static final int MAX = BASE * BASE * BASE;

    private static volatile char[] digits = shuffled(new Random());

    private BadgeLogic() {
    }

    /** Те же знаки в случайном порядке. */
    static char[] shuffled(Random r) {
        char[] a = SET.clone();
        for (int i = a.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            char t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }

    /** Только для проверок: задать порядок знаков. */
    static void useDigits(char[] d) {
        digits = d.clone();
    }

    static boolean isSymbol(char c) {
        return c == '​' || (c >= '⁠' && c <= '⁤');
    }

    static boolean hasSymbol(CharSequence s) {
        if (s == null) return false;
        for (int i = 0, n = s.length(); i < n; i++) {
            if (isSymbol(s.charAt(i))) return true;
        }
        return false;
    }

    /** Метка бейджика с номером index; пустая строка, если номер вне 0..215. */
    static String mark(int index) {
        if (index < 0 || index >= MAX) return "";
        char[] d = digits;
        char[] out = new char[WIDTH];
        int n = index;
        for (int k = WIDTH - 1; k >= 0; k--) {
            out[k] = d[n % BASE];
            n /= BASE;
        }
        return new String(out);
    }

    private static int digit(char c) {
        char[] d = digits;
        for (int i = 0; i < d.length; i++) {
            if (d[i] == c) return i;
        }
        return -1;
    }

    /** Строка без знаков метки; та же строка, если их нет. */
    static String strip(String s) {
        if (s == null || !hasSymbol(s)) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isSymbol(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** Одна метка в тексте: где стоит (с, по) и номер бейджика. */
    static final class Mark {
        final int start;
        final int end;
        final int index;

        Mark(int start, int end, int index) {
            this.start = start;
            this.end = end;
            this.index = index;
        }
    }

    /**
     * Метки в тексте. Берутся серии знаков, длина которых делится на три; серия другой длины остаётся
     * как есть (это не наша метка).
     */
    static List<Mark> marks(CharSequence s) {
        List<Mark> out = new ArrayList<>();
        if (s == null) return out;
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (!isSymbol(s.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && isSymbol(s.charAt(j))) j++;
            int len = j - i;
            if (len % WIDTH == 0) {
                for (int p = i; p < j; p += WIDTH) {
                    int v = 0;
                    boolean ok = true;
                    for (int k = 0; k < WIDTH; k++) {
                        int dv = digit(s.charAt(p + k));
                        if (dv < 0) {
                            ok = false;
                            break;
                        }
                        v = v * BASE + dv;
                    }
                    if (ok) out.add(new Mark(p, p + WIDTH, v));
                }
            }
            i = j;
        }
        return out;
    }
}
