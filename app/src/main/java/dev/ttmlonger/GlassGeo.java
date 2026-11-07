package dev.ttmlonger;

/**
 * Геометрия стеклянной панели без Android: где капсула, как положение линзы переводится в пиксели и какие
 * значения получает шейдер. Формулы взяты из drawGL() в index.html; всё в пикселях холста, размеры в dp
 * умножаются на плотность экрана dm.
 *
 * <p>Холст — это область наложения над панелью: капсула по ширине, по высоте с запасом pad сверху и снизу
 * (так же устроена панель в index.html: PAD = 8).
 */
final class GlassGeo {
    private GlassGeo() {
    }

    /** Значения для шейдера одного кадра. */
    static final class Frame {
        /** Холст. */
        double rw, rh;
        /** Капсула. */
        double nw, nh;
        /** Полуразмеры «таблетки» выбранной вкладки и её центр по x. */
        double bw, bh, px;
        /** Линза: центр и полуразмеры. */
        double lx, ly, lw, lh;
        /** Преломление, надувание, блик, аберрация, прозрачность линзы (Z = 1 − видимость), таблетка, тема, режим. */
        double s, p, q, y, z, k, d, i;
        /** Насыщенность и яркость тела линзы. */
        double sat, brt;
        /** Цвет подложки капсулы (прежний фон панели с заливкой капсулы): им закрывается всё, что лежит под линзой. */
        double bgR, bgG, bgB;
    }

    /**
     * Цвет подложки капсулы: заливка (белая на тёмной панели, чёрная на светлой) с прозрачностью alpha (0…255)
     * поверх цвета панели strip (0xRRGGBB). Под линзой должны исчезнуть родные значки TikTok, иначе они были бы
     * видны сквозь преломлённые, поэтому шейдер закрывает линзу этим цветом.
     */
    static void backdrop(Frame f, int strip, int alpha, boolean dark) {
        double a = alpha / 255.0;
        double fill = dark ? 1.0 : 0.0;
        f.bgR = ((strip >> 16) & 255) / 255.0 * (1 - a) + fill * a;
        f.bgG = ((strip >> 8) & 255) / 255.0 * (1 - a) + fill * a;
        f.bgB = (strip & 255) / 255.0 * (1 - a) + fill * a;
    }

    /** Капсула внутри панели: {left, top, right, bottom, pad}; pad — запас холста сверху и снизу. */
    static int[] capsule(int barW, int barH, int contentTop, int contentBottom, float dm) {
        int mx = Math.round(12f * dm);
        if (mx * 2 >= barW) mx = Math.max(0, barW / 8);
        boolean hasContent = contentBottom > contentTop;
        int ch = hasContent ? contentBottom - contentTop : barH;
        double cy = hasContent ? (contentTop + contentBottom) / 2.0 : barH / 2.0;
        int h = (int) Math.round(Math.max(46f * dm, Math.min(66f * dm, ch + 14f * dm)));
        h = Math.min(h, barH);
        int top = (int) Math.round(cy - h / 2.0);
        top = Math.max(0, Math.min(barH - h, top));
        int bottom = top + h;
        int pad = Math.max(0, Math.min(Math.round(8f * dm), Math.min(top, barH - bottom)));
        return new int[]{mx, top, barW - mx, bottom, pad};
    }

    /** Положение pos (в вкладках) → x по центрам вкладок; за краями продолжает последний шаг. */
    static double posToX(double[] c, double pos) {
        if (c == null || c.length == 0) return 0;
        if (c.length == 1) return c[0];
        int i = (int) Math.floor(pos);
        i = Math.max(0, Math.min(c.length - 2, i));
        double f = pos - i;
        return c[i] + (c[i + 1] - c[i]) * f;
    }

    /** x → положение в вкладках (0 … c.length − 1). */
    static double xToPos(double[] c, double x) {
        if (c == null || c.length < 2) return 0;
        if (x <= c[0]) return 0;
        if (x >= c[c.length - 1]) return c.length - 1;
        for (int i = 0; i < c.length - 1; i++) {
            if (x <= c[i + 1]) {
                double w = c[i + 1] - c[i];
                return w <= 0 ? i : i + (x - c[i]) / w;
            }
        }
        return c.length - 1;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Кадр: ph — пружина, c — центры вкладок в координатах холста, tabW — ширина вкладки, cap — размеры капсулы
     * (ширина, высота), pad — запас сверху и снизу, dm — плотность, dark — тёмная ли подложка.
     */
    static Frame frame(GlassPhysics ph, double[] c, double tabW, double capW, double capH, double pad, float dm,
                       boolean dark) {
        boolean io = GlassCfg.ios;
        Frame f = new Frame();
        f.rw = capW;
        f.rh = capH + 2 * pad;
        f.nw = capW;
        f.nh = capH;
        double press = ph.press;
        double sv = clamp(ph.sx, -0.3, 0.3);
        double pz = GlassCfg.pz / 10.0;
        double halfTab = Math.max(2.0, tabW / 2.0 - 2.0 * dm);
        double halfH = Math.max(2.0, capH / 2.0 - 5.0 * dm);
        double hw = Math.max(1.0, halfTab * (1 + press * (io ? 0.46 : 0.2) * pz) * (1 + sv));
        double hh = Math.max(1.0, Math.min(capH / 2.0 + 3.0 * dm, halfH * (1 + press * 0.2 * pz) * (1 - sv * 0.5)));
        double vis = io ? clamp((press - 0.02) * 2.4, 0, 1) : 1;
        double pillA = io ? 1 - clamp(press * 1.7, 0, 1) : 0;
        double px0 = posToX(c, ph.pos);
        // «таблетка» крайних вкладок не выходит за капсулу: подрезается с краёв
        double pl = Math.max(px0 - halfTab, 6.0 * dm);
        double pr = Math.min(px0 + halfTab, capW - 6.0 * dm);
        if (pr - pl < 4.0) {
            pl = px0 - halfTab;
            pr = px0 + halfTab;
        }
        double lcx = io ? clamp(px0, hw + 1, Math.max(hw + 1, capW - hw - 1)) : px0;
        f.px = (pl + pr) / 2.0;
        f.lx = px0 + (lcx - px0) * vis;
        f.ly = f.rh / 2.0;
        f.lw = hw;
        f.lh = hh;
        f.bw = (pr - pl) / 2.0;
        f.bh = halfH;
        f.s = GlassCfg.gs / 100.0 * clamp(press, 0, 1.25);
        f.p = clamp(press, 0, 1);
        f.q = GlassCfg.sr / 100.0;
        f.y = GlassCfg.tn / 100.0;
        f.z = 1 - vis;
        f.k = pillA;
        f.d = dark ? 1 : 0;
        f.i = io ? 1 : 0;
        f.sat = Math.max(0.5, 1.22 * GlassCfg.sa / 180.0);
        f.brt = GlassCfg.br / 106.0;
        return f;
    }

    /** Прозрачность заливки капсулы (0…255) по настройке «Непрозрачность» и яркости. */
    static int fillAlpha(int tr, int br) {
        // на чёрной подложке TikTok при 55 % получается серая капсула около #2D2D2D, как в видео-образце
        double a = (0.06 + (tr - GlassCfg.TR_MIN) / (double) (GlassCfg.TR_MAX - GlassCfg.TR_MIN) * 0.18)
                * (br / 100.0);
        return (int) Math.round(clamp(a, 0.0, 0.5) * 255.0);
    }
}
