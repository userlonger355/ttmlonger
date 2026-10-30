package dev.ttmlonger;

/**
 * Подмена розово-красных цветов TikTok на выбранный акцент по оттенку, а не только по точному
 * совпадению с #FE2C55. У TikTok есть десятки вариантов этого цвета (нажатая кнопка, полупрозрачные
 * заливки, градиенты, тёмная тема), и точная подмена их не ловила.
 *
 * Цвет считается «розово-красным», если он достаточно насыщенный и яркий, а его оттенок лежит
 * между 335° и 6° (у #FE2C55 — 348°). Такой цвет получает оттенок акцента, а насыщенность и
 * яркость масштабируются так же, как у акцента относительно #FE2C55: тёмный вариант остаётся
 * тёмным, бледный бледным. Прозрачность не трогается. Слабонасыщенные цвета (телесные, серые,
 * белые) не меняются. Подмена идемпотентна: цвета могут проходить через несколько хуков (ресурс,
 * затем краска), и уже подмененный не меняется второй раз. Класс не зависит от Android.
 */
final class AccentMap {
    /** Оттенок, насыщенность и яркость #FE2C55. */
    private static final float REF_H = 348.3f;
    private static final float REF_S = 0.8268f;
    private static final float REF_V = 0.9961f;
    /** Ниже этой насыщенности цвет не трогаем, выше {@link #FULL_SAT} подменяем полностью. */
    private static final float MIN_SAT = 0.30f;
    private static final float FULL_SAT = 0.55f;
    /** Тёмнее этого (из 255) не трогаем: почти чёрное не бывает акцентом. */
    private static final int MIN_MAX = 90;
    /** Разница оттенков (градусы), при которой цвет считается уже подмененным. */
    private static final float SAME_HUE = 2.5f;

    /** Разложенный на оттенок/насыщенность/яркость акцент (неизменяемый, подменяется целиком). */
    private static final class Accent {
        final int rgb;
        final float h;
        final float s;
        final float v;

        Accent(int rgb) {
            this.rgb = rgb;
            float[] hsv = new float[3];
            toHsv((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, hsv);
            h = hsv[0];
            s = hsv[1];
            v = hsv[2];
        }
    }

    private static volatile Accent cached = new Accent(0);

    private AccentMap() {
    }

    private static Accent accent(int rgb) {
        Accent a = cached;
        if (a.rgb != rgb) {
            a = new Accent(rgb);
            cached = a;
        }
        return a;
    }

    /**
     * Похож ли цвет на розово-красный акцент TikTok (быстрая проверка на целых числах). Используется
     * там, где цвет спрашивают очень часто: большинство цветов отсеивается за несколько операций.
     */
    static boolean isPinkish(int argb) {
        int r = (argb >> 16) & 255;
        int g = (argb >> 8) & 255;
        int b = argb & 255;
        if (r < MIN_MAX || r < g || r < b) return false; // красный канал самый сильный
        int d = r - Math.min(g, b);
        if (d * 100 < 30 * r) return false;               // насыщенность ниже 0.30
        int gb = g - b;
        // Оттенок 60 * gb / d: от -0.4 * 60 = -24° (336°) до 0.1 * 60 = 6°.
        return gb * 10 >= -4 * d && gb * 10 <= d;
    }

    /**
     * @param argb   исходный цвет
     * @param accent акцент (RGB; альфа игнорируется), не 0
     * @return цвет с оттенком акцента или тот же цвет, если он не розово-красный
     *         (подмена идемпотентна: уже подмененный цвет повторно не меняется)
     */
    static int map(int argb, int accent) {
        if (accent == 0 || !isPinkish(argb)) return argb;
        int a = argb & 0xFF000000;
        int rgb = argb & 0xFFFFFF;
        if (rgb == (accent & 0xFFFFFF)) return argb;
        if (rgb == 0xFE2C55 || rgb == 0xFF2D55) return a | (accent & 0xFFFFFF);

        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        float[] hsv = new float[3];
        toHsv(r, g, b, hsv);

        Accent ac = accent(accent & 0xFFFFFF);
        // Оттенок уже как у акцента: цвет, скорее всего, уже подменён (или акцент сам розовый).
        float dh = Math.abs(hsv[0] - ac.h);
        if (dh > 180f) dh = 360f - dh;
        if (dh <= SAME_HUE) return argb;

        float weight = clamp01((hsv[1] - MIN_SAT) / (FULL_SAT - MIN_SAT));
        float ns = clamp01(ac.s * hsv[1] / REF_S);
        float nv = clamp01(ac.v * hsv[2] / REF_V);
        int mapped = fromHsv(ac.h, ns, nv);

        if (weight >= 0.999f) return a | mapped;
        return a | blend(rgb, mapped, weight);
    }

    /**
     * Перекрашивает пиксели в массиве ARGB (как из Bitmap.getPixels) на месте.
     *
     * @return сколько пикселей изменено
     */
    static int recolor(int[] px, int accent) {
        int n = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if ((p >>> 24) == 0) continue;
            int m = map(p, accent);
            if (m != p) {
                px[i] = m;
                n++;
            }
        }
        return n;
    }

    private static int blend(int from, int to, float w) {
        int r = Math.round(((from >> 16) & 255) * (1f - w) + ((to >> 16) & 255) * w);
        int g = Math.round(((from >> 8) & 255) * (1f - w) + ((to >> 8) & 255) * w);
        int b = Math.round((from & 255) * (1f - w) + (to & 255) * w);
        return (r << 16) | (g << 8) | b;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    /** Оттенок в градусах [0, 360), насыщенность и яркость в [0, 1]. */
    static void toHsv(int r, int g, int b, float[] out) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int d = max - min;
        float h;
        if (d == 0) {
            h = 0f;
        } else if (max == r) {
            h = 60f * ((g - b) / (float) d);
            if (h < 0f) h += 360f;
        } else if (max == g) {
            h = 60f * ((b - r) / (float) d + 2f);
        } else {
            h = 60f * ((r - g) / (float) d + 4f);
        }
        out[0] = h;
        out[1] = max == 0 ? 0f : d / (float) max;
        out[2] = max / 255f;
    }

    /** Обратное к {@link #toHsv}; оттенок можно передавать вне [0, 360). */
    static int fromHsv(float h, float s, float v) {
        h = h % 360f;
        if (h < 0f) h += 360f;
        float c = v * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - c;
        float r;
        float g;
        float b;
        if (h < 60f) {
            r = c;
            g = x;
            b = 0f;
        } else if (h < 120f) {
            r = x;
            g = c;
            b = 0f;
        } else if (h < 180f) {
            r = 0f;
            g = c;
            b = x;
        } else if (h < 240f) {
            r = 0f;
            g = x;
            b = c;
        } else if (h < 300f) {
            r = x;
            g = 0f;
            b = c;
        } else {
            r = c;
            g = 0f;
            b = x;
        }
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (clamp255(ri) << 16) | (clamp255(gi) << 8) | clamp255(bi);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
