package dev.ttmlonger;

/**
 * Фон-стекло без Android: какую полосу окна снимать и как размывать. Всё считается в пикселях.
 *
 * <p>Под панелью TikTok ничего нет (она непрозрачная), поэтому «то, что лежит за капсулой», берётся из ленты над
 * панелью: снимается полоса окна прямо над панелью, и под капсулу она ложится зеркально (как отражение от верхнего
 * края панели). Строка снимка = высота холста − y холста, поэтому шейдер читает снимок снизу вверх.
 *
 * <p>Размытие делается здесь, на процессоре, по маленькому снимку: чем сильнее размытие, тем мельче снимок
 * (меньше точек, быстрее и мягче). Это гауссово размытие тремя проходами ящичного фильтра по строкам и столбцам;
 * сигма — значение ползунка (как blur() в index.html, где радиус в CSS-пикселях, то есть в dp). Шейдеру остаётся
 * прочитать одну точку с билинейной фильтрацией.
 */
final class GlassBackdrop {
    private GlassBackdrop() {
    }

    /** Снимок не меньше этого размера, иначе размывать нечего. */
    static final int MIN_W = 4, MIN_H = 2;

    /** Во сколько раз уменьшать снимок при сигме sigmaPx (px холста): сильное размытие терпит мелкий снимок. */
    static int scaleFor(double sigmaPx) {
        if (sigmaPx <= 3.0) return 1;
        if (sigmaPx <= 8.0) return 2;
        if (sigmaPx <= 20.0) return 4;
        return 8;
    }

    /**
     * Что снимать. barX, barY — положение панели в окне; capL…capB — капсула внутри панели, pad — запас холста
     * сверху и снизу (холст: [capL, capT − pad] … [capR, capB + pad]); winW, winH — размер окна.
     *
     * @return {слева, сверху, справа, снизу (в окне), ширина снимка, высота снимка, уменьшение} или null, если
     * полоса над панелью не помещается в окно
     */
    static int[] plan(int barX, int barY, int capL, int capT, int capR, int capB, int pad, int winW, int winH,
                      double sigmaPx) {
        int w = capR - capL;
        int h = capB - capT + 2 * pad;
        if (w <= 0 || h <= 0) return null;
        int l = barX + capL;
        int r = barX + capR;
        // холст y (сверху вниз от верха холста) лежит на d = capT − pad + y ниже верха панели; зеркало берёт d выше
        int b = barY - (capT - pad);
        int t = barY - (capB + pad);
        if (l < 0 || t < 0 || r > winW || b > winH || b - t != h) return null;
        int ds = scaleFor(sigmaPx);
        int dw = Math.max(MIN_W, (w + ds - 1) / ds);
        int dh = Math.max(MIN_H, (h + ds - 1) / ds);
        return new int[]{l, t, r, b, dw, dh, ds};
    }

    /** Строка снимка (сверху вниз, в пикселях снимка), которую читает холст на высоте y (в пикселях холста). */
    static double mirrorRow(double y, double canvasH, double rows) {
        return (canvasH - y) * rows / canvasH;
    }

    /** Радиусы трёх ящичных фильтров, дающих гауссиану с сигмой sigma (в точках). */
    static int[] boxRadii(double sigma) {
        double ideal = Math.sqrt(12.0 * sigma * sigma / 3.0 + 1.0);
        int wl = (int) Math.floor(ideal);
        if (wl % 2 == 0) wl--;
        if (wl < 1) wl = 1;
        int wu = wl + 2;
        double mIdeal = (12.0 * sigma * sigma - 3.0 * wl * wl - 12.0 * wl - 9.0) / (-4.0 * wl - 4.0);
        int m = (int) Math.round(mIdeal);
        int[] r = new int[3];
        for (int i = 0; i < 3; i++) r[i] = ((i < m ? wl : wu) - 1) / 2;
        return r;
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int o = y * w;
            long sum = (long) (r + 1) * src[o];
            for (int i = 1; i <= r; i++) sum += src[o + Math.min(i, w - 1)];
            for (int x = 0; x < w; x++) {
                dst[o + x] = (int) ((sum + win / 2) / win);
                sum += src[o + Math.min(x + r + 1, w - 1)] - src[o + Math.max(x - r, 0)];
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            long sum = (long) (r + 1) * src[x];
            for (int i = 1; i <= r; i++) sum += src[Math.min(i, h - 1) * w + x];
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (int) ((sum + win / 2) / win);
                sum += src[Math.min(y + r + 1, h - 1) * w + x] - src[Math.max(y - r, 0) * w + x];
            }
        }
    }

    /**
     * Размывает снимок px (ARGB, w × h) по Гауссу с сигмой sigma точек; края растягиваются. Прозрачность не
     * трогается (снимок окна непрозрачный).
     */
    static void blur(int[] px, int w, int h, double sigma) {
        if (px == null || w < 1 || h < 1 || px.length < w * h || sigma < 0.4) return;
        int[] box = boxRadii(sigma);
        int[] c = new int[w * h];
        int[] t = new int[w * h];
        int n = w * h;
        for (int ch = 0; ch < 3; ch++) {
            int sh = 16 - 8 * ch;
            for (int i = 0; i < n; i++) c[i] = (px[i] >>> sh) & 255;
            for (int k = 0; k < 3; k++) {
                boxH(c, t, w, h, box[k]);
                boxV(t, c, w, h, box[k]);
            }
            for (int i = 0; i < n; i++) {
                int v = Math.max(0, Math.min(255, c[i]));
                px[i] = (px[i] & ~(255 << sh)) | (v << sh);
            }
        }
    }

    /** Несколько десятков точек снимка: по ним видно, изменилась ли картинка. */
    static int[] sample(int[] px, int w, int h) {
        int[] s = new int[40];
        if (px == null || w < 1 || h < 1 || px.length < w * h) return s;
        int n = 0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 10; i++) {
                s[n++] = px[Math.min(h - 1, (j * h + h / 2) / 4) * w + Math.min(w - 1, (i * w + w / 2) / 10)];
            }
        }
        return s;
    }

    /** Отличаются ли два набора проб: если нет, снимок не менялся и перерисовывать нечего. */
    static boolean changed(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return true;
        return false;
    }

    /** Имя кода результата PixelCopy для сведений. */
    static String resultName(int code) {
        switch (code) {
            case 0:
                return "снято";
            case 1:
                return "неизвестная ошибка";
            case 2:
                return "не дождались кадра";
            case 3:
                return "в окне ещё нет кадра";
            case 4:
                return "окно нельзя снимать (защищено или не показано)";
            case 5:
                return "снимок не подошёл по размеру";
            default:
                return "код " + code;
        }
    }
}
