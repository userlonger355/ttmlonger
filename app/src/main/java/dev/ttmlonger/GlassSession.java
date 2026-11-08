package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Стеклянная панель поверх одной настоящей панели вкладок (TikTok или предпросмотр в настройках).
 *
 * <p>Что делается с панелью: её фон заменяется обёрткой, которая рисует прежний фон и поверх него
 * полупрозрачную капсулу (под значками), а над значками добавляется слой-линза в оверлей панели. Линза рисуется
 * шейдером из index.html по снимку значков и подписей (снимок обновляется по необходимости). Сами вкладки и их
 * нажатия не трогаются: мод только смотрит на касания и подъезжает линзой к выбранной вкладке.
 * Всё возвращается как было при {@link #detach()}.
 */
final class GlassSession implements Choreographer.FrameCallback {
    private static final long POLL_MS = 500L;
    private static final long BURST_MS = 900L;
    /** Как часто обновляется снимок значков, пока панель рисует шейдер. */
    private static final long CAP_MS = 240L;

    final ViewGroup bar;
    final ArrayList<View> tabs;
    final int create;
    final boolean preview;
    final GlassPhysics ph = new GlassPhysics();
    private final float dm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final int[] eligible;

    // геометрия в координатах панели
    private boolean measured;
    private int capL, capT, capR, capB, pad;
    private double[] centers = new double[0];
    private double tabW;
    private boolean dark = true;
    /** Цвет панели под капсулой (RGB): цвет её фона или чёрный/белый по теме. */
    private int strip = 0x000000;
    /** Цвет панели прочитан с настоящего фона (иначе сжимать панель нельзя: нечем закрыть прежние значки). */
    private boolean stripKnown;
    // сжатие панели: где значки по вертикали, где начинаются подписи, высота значка, ширина «+»
    private double yc;
    private double lt;
    private double iconH;
    private double plusHalf;
    /** Прямоугольник кнопки «+» в снимке значков (left, top, right, bottom) и светлое ли у родной кнопки тело. */
    private final double[] plusBox = new double[4];
    private boolean plusBoxOk;
    private boolean plusLight = true;
    private long lastPlusScan;
    /** Холст не аппаратный: шейдер на нём не рисует, значит, капсулу целиком шейдеру отдавать нельзя. */
    private boolean swCanvas;

    // снимок значков
    private Bitmap tex;
    private boolean capDirty = true;
    private long lastCap;
    private long burstUntil;

    private final Background bg = new Background();
    private final Lens lens = new Lens();
    private boolean attached;
    private boolean framePosted;
    private long lastNanos;
    private boolean track;
    /** Вкладка (номер в tabs), на которую лёг палец. */
    private int downTab = -1;
    /** Идёт наше собственное нажатие на вкладку: оно не должно попадать в onTouch. */
    private boolean synthetic;
    /** Устройство настоящего касания: синтетическое нажатие подписывается им же, как у пальца. */
    private int lastDevice;
    /** Что вышло с переходом на вкладку при протягивании пальца (для сведений). */
    private String dragNote = "";
    /** Палец лёг вне панели: ждём, поведёт ли он по вертикали (прокрутка ленты). */
    private boolean sTrack;
    private boolean scrolling;
    private float sx0;
    private float sy0;
    /** В снимке значков есть содержимое: только тогда шейдер перерисовывает панель сам. */
    private boolean texOk;
    private int lastSelected = -1;
    private int seenRev = -1;
    private int bgFailures;

    private AgslLens agsl;
    private String shaderNote = "";
    /** Снимок полосы ленты над панелью для стеклянного фона (Android 8+); null, если недоступен. */
    private BackdropCopy bcopy;
    private Window window;
    private String bkNote = "";
    private boolean bodyWas;
    private int bkMisses;
    private final int[] loc = new int[2];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripPaint = new Paint();
    /** Сплошные фоны обёрток панели, которые мы сняли (и вернём), и прежняя прозрачность вкладок на время сжатия. */
    private final ArrayList<View> clearedViews = new ArrayList<>();
    private final ArrayList<Drawable> clearedBgs = new ArrayList<>();
    private float[] tabAlpha;
    private boolean tabsHidden;
    private final Runnable expandRun = new Runnable() {
        @Override
        public void run() {
            if (attached) setCompact(false);
        }
    };

    GlassSession(ViewGroup bar, ArrayList<View> tabs, int create, boolean preview) {
        this.bar = bar;
        this.tabs = tabs;
        this.create = create;
        this.preview = preview;
        this.dm = bar.getResources().getDisplayMetrics().density;
        ArrayList<Integer> e = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) if (i != create) e.add(i);
        eligible = new int[e.size()];
        for (int i = 0; i < eligible.length; i++) eligible[i] = e.get(i);
    }

    // ------------------------------------------------------------ подключение

    void attach() {
        if (attached) return;
        attached = true;
        initShader();
        initBackdrop();
        Drawable cur = bar.getBackground();
        bg.orig = cur;
        measure();
        bar.setBackground(bg);
        // View.setBackground снимает обратный вызов у прежнего фона, поэтому свой ставим уже после
        if (cur != null) cur.setCallback(bg);
        applyFill();
        bar.getOverlay().add(lens);
        bar.addOnLayoutChangeListener(layout);
        ph.reset(Math.max(0, eligible.length - 1), Math.max(0, posOf(readSelected())));
        lastSelected = readSelected();
        seenRev = GlassCfg.rev;
        capDirty = true;
        burstUntil = SystemClock.uptimeMillis() + BURST_MS;
        h.postDelayed(poll, POLL_MS);
        h.postDelayed(bkRun, 200L);
        kick();
    }

    void detach() {
        if (!attached) return;
        attached = false;
        h.removeCallbacks(poll);
        h.removeCallbacks(bkRun);
        if (bcopy != null) {
            bcopy.release();
            bcopy = null;
        }
        h.removeCallbacks(expandRun);
        try {
            Choreographer.getInstance().removeFrameCallback(this);
        } catch (Throwable ignored) {
            // нет Choreographer — нечего снимать
        }
        framePosted = false;
        bar.removeOnLayoutChangeListener(layout);
        try {
            bar.getOverlay().remove(lens);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        if (bar.getBackground() == bg) bar.setBackground(bg.orig);
        if (bg.orig != null && bg.orig.getCallback() == bg) bg.orig.setCallback(null);
        restoreFill();
        hideTabs(false);
        if (agsl != null) agsl.release();
        if (tex != null) {
            tex.recycle();
            tex = null;
        }
    }

    boolean isAttached() {
        return attached;
    }

    private void initShader() {
        agsl = null;
        if (Build.VERSION.SDK_INT < 33) {
            shaderNote = "шейдер недоступен (нужен Android 13), рисуется упрощённая линза";
            return;
        }
        try {
            agsl = new AgslLens();
            shaderNote = "шейдер AGSL работает";
        } catch (Throwable t) {
            agsl = null;
            String m = String.valueOf(t.getMessage());
            int nl = m.indexOf('\n');
            if (nl > 0) m = m.substring(0, nl);
            shaderNote = "шейдер не скомпилировался (" + t.getClass().getSimpleName() + ": "
                    + (m.length() > 120 ? m.substring(0, 120) : m) + "), рисуется упрощённая линза";
            FlagStore.log(t);
        }
    }

    /** Окно, с которого снимается фон: у TikTok окно Activity, у предпросмотра в диалоге — окно диалога. */
    void setWindow(Window w) {
        window = w;
    }

    private Window windowOf() {
        if (window != null) return window;
        Context c = bar.getContext();
        for (int i = 0; i < 8 && c instanceof ContextWrapper; i++) {
            if (c instanceof Activity) return ((Activity) c).getWindow();
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private void initBackdrop() {
        bcopy = null;
        if (Build.VERSION.SDK_INT < 26) {
            bkNote = "фон из ленты недоступен (нужен Android 8)";
            return;
        }
        Window w = windowOf();
        if (w == null) {
            bkNote = "фон из ленты недоступен (не нашлось окно панели)";
            return;
        }
        bcopy = new BackdropCopy(w, h);
        bkNote = "";
    }

    /**
     * Раз в четверть секунды обновляет снимок значков (капсулу и значки рисует шейдер, родные значки закрыты) и
     * снимает полосу ленты над панелью: часто, пока картинка меняется, и редко, пока стоит.
     */
    private final Runnable bkRun = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            long next = 600L;
            try {
                boolean live = measured && bar.isShown() && shaderOnly();
                if (live) {
                    next = CAP_MS;
                    if (SystemClock.uptimeMillis() - lastCap > CAP_MS) {
                        capDirty = true;
                        kick();
                    }
                }
                BackdropCopy bc = bcopy;
                if (bc != null && GlassCfg.bk && GlassCfg.glass() && !CrashGuard.tripped && measured && useAgsl()
                        && bar.isShown() && !bc.busy()) {
                    next = Math.min(next, bc.lastChanged() || ph.active() ? 120L : 360L);
                    bar.getLocationInWindow(loc);
                    View dv = windowDecor();
                    int[] pl = dv == null ? null : GlassBackdrop.plan(loc[0], loc[1], capL, capT, capR, capB, pad,
                            dv.getWidth(), dv.getHeight(), GlassBackdrop.sigmaPx(GlassCfg.bl, dm));
                    if (pl == null) {
                        bkMisses++;
                        bkNote = dv == null ? "у окна нет корневого вида" : "полоса над панелью не помещается в окно";
                    } else {
                        bkNote = "";
                        bc.request(pl, GlassBackdrop.sigmaPx(GlassCfg.bl, dm), onBackdrop);
                    }
                } else if (bc != null && bc.busy()) {
                    next = Math.min(next, 60L);
                }
            } catch (Throwable t) {
                FlagStore.log(t);
                next = 1500L;
            } finally {
                if (attached) h.postDelayed(this, next);
            }
        }
    };

    private final Runnable onBackdrop = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            boolean on = bodyOn();
            if (on != bodyWas) {
                bodyWas = on;
                bg.invalidateSelf();
            }
            lens.invalidateSelf();
        }
    };

    private View windowDecor() {
        Window w = windowOf();
        return w == null ? null : w.peekDecorView();
    }

    /** Рисуется ли стеклянный фон: включён, шейдер работает и снимок ленты уже есть. */
    private boolean bodyOn() {
        BackdropCopy bc = bcopy;
        return GlassCfg.bk && GlassCfg.glass() && useAgsl() && bc != null && bc.ready() && !CrashGuard.tripped;
    }

    /**
     * Капсулу целиком рисует шейдер: стекло включено, шейдер работает, снимок значков есть, холст аппаратный. Тогда
     * Background ничего поверх прежнего фона не рисует, а шейдер закрывает капсулу непрозрачным телом.
     */
    private boolean shaderOnly() {
        return GlassCfg.glass() && useAgsl() && !swCanvas && !CrashGuard.tripped;
    }

    String shaderNote() {
        return shaderNote;
    }

    /** Одна строка для экрана настроек: работает ли фон из ленты и что говорит снимок. */
    String bkStatus() {
        if (!GlassCfg.bk) return "фон из ленты выключен: капсула сплошная";
        String note = bkNote.length() > 0 ? ", " + bkNote : "";
        if (!shaderOnly()) return "фон из ленты: шейдер не рисует (" + (agsl == null ? "нет шейдера" : !texOk
                ? "нет снимка значков" : swCanvas ? "холст не аппаратный" : "выключено") + ")";
        if (bcopy == null) return "фон из ленты: " + (bkNote.length() > 0 ? bkNote : "снимок недоступен");
        return "фон из ленты: " + (bodyOn() ? "работает" : "ждёт снимка") + " (" + bcopy.status() + note + ")";
    }

    boolean shaderOn() {
        return agsl != null;
    }

    /** Рисует ли шейдер: он включён, и в снимке значков что-то есть (иначе нечего искажать). */
    private boolean useAgsl() {
        return agsl != null && texOk;
    }

    // ------------------------------------------------------------ геометрия

    private final View.OnLayoutChangeListener layout = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (r - l != or - ol || b - t != ob - ot) {
                measured = false;
            }
            capDirty = true;
            kick();
        }
    };

    private void measure() {
        int w = bar.getWidth();
        int hgt = bar.getHeight();
        if (w <= 0 || hgt <= 0 || eligible.length == 0) {
            measured = false;
            return;
        }
        int[] mm = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int e : eligible) bounds(tabs.get(e), 0, 0, mm, 0);
        int top = mm[0] == Integer.MAX_VALUE ? 0 : mm[0];
        int bottom = mm[1] == Integer.MIN_VALUE ? 0 : mm[1];
        int[] c = GlassGeo.capsule(w, hgt, top, bottom, dm);
        capL = c[0];
        capT = c[1];
        capR = c[2];
        capB = c[3];
        pad = c[4];
        centers = new double[eligible.length];
        double minW = Double.MAX_VALUE;
        for (int i = 0; i < eligible.length; i++) {
            View t = tabs.get(eligible[i]);
            centers[i] = (t.getLeft() + t.getRight()) / 2.0 - capL;
            minW = Math.min(minW, t.getWidth());
        }
        tabW = minW == Double.MAX_VALUE ? 0 : Math.min(minW, 96.0 * dm);
        scanIcons();
        dark = readDark();
        measured = true;
        lens.setBounds(capL, capT - pad, capR, capB + pad);
    }

    /**
     * Где в снимке значки и где подписи (для сжатия панели): центр значков по вертикали, высота значка, линия между
     * значками и подписями. Круглая метка с числом (99+) выше подписей, поэтому берётся самый нижний текст.
     */
    private void scanIcons() {
        int[] ib = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int e : eligible) icons(tabs.get(e), 0, 0, ib, 0);
        int oy = capT - pad;
        int texH = capB - capT + 2 * pad;
        if (ib[0] == Integer.MAX_VALUE || ib[1] == Integer.MIN_VALUE) {
            iconH = 24.0 * dm;
            yc = texH / 2.0;
            lt = texH * 4.0;
            return;
        }
        iconH = Math.max(8.0 * dm, ib[1] - ib[0]);
        yc = (ib[0] + ib[1]) / 2.0 - oy;
        boolean label = ib[2] != Integer.MIN_VALUE && ib[2] >= ib[0] + (ib[1] - ib[0]) / 2;
        lt = label ? (ib[1] + ib[2]) / 2.0 - oy : texH * 4.0;
    }

    private static void icons(View v, int ox, int oy, int[] ib, int depth) {
        if (v == null || depth > 6 || v.getVisibility() != View.VISIBLE) return;
        int left = ox + v.getLeft();
        int top = oy + v.getTop();
        if (v instanceof ImageView && ((ImageView) v).getDrawable() != null) {
            ib[0] = Math.min(ib[0], top);
            ib[1] = Math.max(ib[1], top + v.getHeight());
            return;
        }
        if (v instanceof TextView && ((TextView) v).getText() != null && ((TextView) v).getText().length() > 0) {
            ib[2] = Math.max(ib[2], top);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) icons(g.getChildAt(i), left, top, ib, depth + 1);
        }
    }

    /** Верх и низ значков и подписей вкладки в координатах панели. */
    private static void bounds(View v, int ox, int oy, int[] mm, int depth) {
        if (v == null || depth > 6 || v.getVisibility() != View.VISIBLE) return;
        int left = ox + v.getLeft();
        int top = oy + v.getTop();
        boolean leaf = (v instanceof TextView && ((TextView) v).getText() != null
                && ((TextView) v).getText().length() > 0)
                || (v instanceof ImageView && ((ImageView) v).getDrawable() != null);
        if (leaf) {
            mm[0] = Math.min(mm[0], top);
            mm[1] = Math.max(mm[1], top + v.getHeight());
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) bounds(g.getChildAt(i), left, top, mm, depth + 1);
        }
    }

    /** Тёмная ли подложка: по цвету прежнего фона панели или её родителей, иначе по теме мода. */
    private boolean readDark() {
        Drawable o = bg.orig;
        // свой фон не сплошной цвет (градиент, слои): точный цвет под значками неизвестен
        boolean ownUnknown = o != null && !(o instanceof ColorDrawable);
        for (int i = 0; i < 6; i++) {
            if (o instanceof ColorDrawable) {
                int c = ((ColorDrawable) o).getColor();
                if (Color.alpha(c) > 40) {
                    stripKnown = Color.alpha(c) > 200 && !ownUnknown;
                    strip = c & 0xFFFFFF;
                    return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 128;
                }
            }
            View v = i == 0 ? bar : parentAt(i);
            if (v == null) break;
            o = bgOf(v);
        }
        strip = Ui.DARK ? 0x000000 : 0xFFFFFF;
        stripKnown = false;
        return Ui.DARK;
    }

    /** Убирается ли сплошная заливка панели (и её обёртки): включено в настройках и работает стекло. */
    private boolean tpOn() {
        return GlassCfg.tp && (GlassCfg.glass() || preview) && !CrashGuard.tripped;
    }

    /** Фон вида с учётом снятых нами: прежний цвет нужен, чтобы знать, тёмная ли панель. */
    private Drawable bgOf(View v) {
        int i = clearedViews.indexOf(v);
        return i >= 0 ? clearedBgs.get(i) : v.getBackground();
    }

    /**
     * Снимает сплошной фон с обёрток панели (родители не выше двух высот панели); фон самой панели не рисуется в
     * {@link Background}. Корень окна и большие контейнеры не трогаются. В предпросмотре родителей не касаемся.
     */
    private void applyFill() {
        if (!tpOn()) {
            restoreFill();
            hideTabs(false);
            return;
        }
        if (preview) return;
        View v = bar;
        for (int i = 0; i < 6 && v != null; i++) {
            Object par = v.getParent();
            if (!(par instanceof View)) break;
            if (v != bar && clearedViews.indexOf(v) < 0 && GlassGeo.isWrapper(v.getHeight(), v.getWidth(), bar.getHeight(),
                    bar.getWidth())) {
                Drawable d = v.getBackground();
                if (d instanceof ColorDrawable && Color.alpha(((ColorDrawable) d).getColor()) > 0) {
                    clearedViews.add(v);
                    clearedBgs.add(d);
                    v.setBackground(null);
                }
            }
            v = (View) par;
        }
    }

    private void restoreFill() {
        for (int i = 0; i < clearedViews.size(); i++) {
            View v = clearedViews.get(i);
            if (v.getBackground() == null) v.setBackground(clearedBgs.get(i));
        }
        clearedViews.clear();
        clearedBgs.clear();
    }

    /** Прячет родные вкладки прозрачностью (касания проходят), пока панель сжата: значки рисует шейдер. */
    private void hideTabs(boolean hide) {
        if (hide == tabsHidden) return;
        if (hide) {
            tabAlpha = new float[tabs.size()];
            for (int i = 0; i < tabAlpha.length; i++) {
                tabAlpha[i] = tabs.get(i).getAlpha();
                tabs.get(i).setAlpha(0f);
            }
            tabsHidden = true;
        } else {
            if (tabAlpha != null) {
                for (int i = 0; i < tabAlpha.length && i < tabs.size(); i++) tabs.get(i).setAlpha(tabAlpha[i]);
            }
            tabAlpha = null;
            tabsHidden = false;
        }
    }

    private View parentAt(int up) {
        View v = bar;
        for (int i = 0; i < up; i++) {
            Object p = v.getParent();
            if (!(p instanceof View)) return null;
            v = (View) p;
        }
        return v;
    }

    // ------------------------------------------------------------ выбранная вкладка

    private int readSelected() {
        int n = tabs.size();
        boolean[] sel = new boolean[n];
        int[] col = new int[n];
        float[] al = new float[n];
        for (int i = 0; i < n; i++) {
            View t = tabs.get(i);
            sel[i] = BarFinder.anySelected(t, 0);
            col[i] = BarFinder.labelColor(t, 0);
            al[i] = BarFinder.iconAlpha(t, 0);
        }
        return BarFinder.pickSelected(eligible, sel, col, al);
    }

    private int posOf(int tabIndex) {
        for (int i = 0; i < eligible.length; i++) if (eligible[i] == tabIndex) return i;
        return -1;
    }

    /** Номер выбранной вкладки среди tabs или -1 (для строки состояния). */
    int selectedTab() {
        return readSelected();
    }

    private void syncSelection() {
        int s = readSelected();
        if (s < 0) return;
        if (s != lastSelected) {
            lastSelected = s;
            capDirty = true;
            burstUntil = SystemClock.uptimeMillis() + BURST_MS;
        }
        int p = posOf(s);
        if (p >= 0 && !ph.drag && Math.round(ph.tgt) != p) {
            ph.tgt = p;
            burstUntil = SystemClock.uptimeMillis() + BURST_MS;
            kick();
        }
    }

    // ------------------------------------------------------------ опрос и кадры

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            try {
                if (CrashGuard.tripped || !GlassCfg.glass() && !preview) return;
                if (bar.getBackground() != bg) {
                    // TikTok поставил свой фон заново: берём его за основу и накрываем ещё раз
                    if (bgFailures++ < 20) {
                        Drawable now = bar.getBackground();
                        bg.orig = now;
                        bar.setBackground(bg);
                        if (now != null) now.setCallback(bg);
                    }
                }
                if (!measured) measure();
                else {
                    boolean old = dark;
                    dark = readDark();
                    if (old != dark) capDirty = true;
                }
                syncSelection();
                if (!GlassCfg.cp && ph.cmT != 0) {
                    ph.cmT = 0;
                    kick();
                }
                applyFill();
                if (seenRev != GlassCfg.rev) {
                    seenRev = GlassCfg.rev;
                    capDirty = true;
                    bg.invalidateSelf();
                    lens.invalidateSelf();
                }
                if (SystemClock.uptimeMillis() - lastCap > 800L && bar.isShown()) capDirty = true;
                if (capDirty || ph.active()) kick();
            } catch (Throwable t) {
                FlagStore.log(t);
            } finally {
                if (attached) h.postDelayed(this, POLL_MS);
            }
        }
    };

    void refresh() {
        capDirty = true;
        seenRev = GlassCfg.rev;
        if (!measured) measure();
        bg.invalidateSelf();
        lens.invalidateSelf();
        kick();
    }

    private void kick() {
        if (!attached || framePosted) return;
        framePosted = true;
        try {
            Choreographer.getInstance().postFrameCallback(this);
        } catch (Throwable t) {
            framePosted = false;
            FlagStore.log(t);
        }
    }

    @Override
    public void doFrame(long nanos) {
        framePosted = false;
        if (!attached) return;
        try {
            double dt = lastNanos == 0 ? 0.016 : (nanos - lastNanos) / 1e9;
            lastNanos = nanos;
            ph.step(dt);
            long now = SystemClock.uptimeMillis();
            if (!measured) measure();
            if (measured && (capDirty || (now < burstUntil && now - lastCap > 110L))) capture(now);
            hideTabs(tpOn() && useAgsl() && (ph.cmp > 0.002 || ph.cmT > 0.5));
            lens.invalidateSelf();
            if (ph.active() || now < burstUntil || (capDirty && measured)) {
                framePosted = true;
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                lastNanos = 0;
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    // ------------------------------------------------------------ снимок значков

    private void capture(long now) {
        capDirty = false;
        lastCap = now;
        int w = capR - capL;
        int hh = capB - capT + 2 * pad;
        if (w <= 0 || hh <= 0 || w > 4096 || hh > 1024) return;
        if (tex == null || tex.getWidth() != w || tex.getHeight() != hh) {
            if (tex != null) tex.recycle();
            tex = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            if (agsl != null) agsl.release();
        } else {
            tex.eraseColor(Color.TRANSPARENT);
        }
        Canvas c = new Canvas(tex);
        int ox = capL;
        int oy = capT - pad;
        for (View t : tabs) {
            if (t.getVisibility() != View.VISIBLE || t.getWidth() <= 0) continue;
            int save = c.save();
            c.translate(t.getLeft() + t.getTranslationX() - ox, t.getTop() + t.getTranslationY() - oy);
            c.clipRect(0, 0, t.getWidth(), t.getHeight());
            try {
                t.draw(c);
            } catch (Throwable th) {
                FlagStore.log(th);
            }
            c.restoreToCount(save);
        }
        texOk = hasContent(tex);
        plusHalf = texOk && GlassCfg.cp ? scanPlus() : 0;
        if (texOk && GlassCfg.plus != 0) scanPlusBox(now);
    }

    /**
     * Где в снимке кнопка «+» (прямоугольник её вкладки) и светлое ли у неё тело: по непрозрачным и не цветным точкам
     * (цветные — боковые полоски TikTok), которых больше светлых или тёмных. Тело больше знака, поэтому оно перевешивает.
     */
    private void scanPlusBox(long now) {
        if (create < 0 || create >= tabs.size() || tex == null) {
            plusBoxOk = false;
            return;
        }
        View t = tabs.get(create);
        int w = tex.getWidth();
        int hh = tex.getHeight();
        int oy = capT - pad;
        double x0 = Math.max(0, t.getLeft() + t.getTranslationX() - capL);
        double x1 = Math.min(w, t.getRight() + t.getTranslationX() - capL);
        double y0 = Math.max(0, t.getTop() + t.getTranslationY() - oy);
        double y1 = Math.min(hh, t.getBottom() + t.getTranslationY() - oy);
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            plusBoxOk = false;
            return;
        }
        plusBox[0] = x0;
        plusBox[1] = y0;
        plusBox[2] = x1;
        plusBox[3] = y1;
        plusBoxOk = true;
        if (now - lastPlusScan < 1000L && lastPlusScan != 0) return;
        lastPlusScan = now;
        int bw = (int) (x1 - x0);
        int bh = (int) (y1 - y0);
        int[] px = new int[bw * bh];
        tex.getPixels(px, 0, bw, (int) x0, (int) y0, bw, bh);
        int[] lh = GlassGeo.plusBodyVotes(px, bw, bh);
        if (lh[0] + lh[1] >= 8) plusLight = lh[0] >= lh[1];
    }

    /**
     * Половина ширины кнопки «+» в снимке (с запасом): эта зона при сжатии панели не уменьшается. Ищется по снимку:
     * от середины кнопки в обе стороны, пока не кончатся непрозрачные точки. 0 — «+» нет или она не по центру.
     */
    private double scanPlus() {
        if (create < 0 || create >= tabs.size() || tex == null) return 0;
        View t = tabs.get(create);
        double pc = (t.getLeft() + t.getRight()) / 2.0 - capL;
        double cx = (capR - capL) / 2.0;
        if (Math.abs(pc - cx) > Math.max(8.0 * dm, tabW * 0.3)) return 0;
        int w = tex.getWidth();
        int hh = tex.getHeight();
        int ci = (int) Math.round(pc);
        int best = 0;
        for (int dy = -8; dy <= 8; dy += 4) {
            int y = (int) Math.round(yc) + dy;
            if (y < 0 || y >= hh || ci < 0 || ci >= w) continue;
            int gap = 0;
            for (int x = ci; x < w && gap < 3; x++) {
                if ((tex.getPixel(x, y) >>> 24) > 24) {
                    best = Math.max(best, x - ci);
                    gap = 0;
                } else {
                    gap++;
                }
            }
            gap = 0;
            for (int x = ci; x >= 0 && gap < 3; x--) {
                if ((tex.getPixel(x, y) >>> 24) > 24) {
                    best = Math.max(best, ci - x);
                    gap = 0;
                } else {
                    gap++;
                }
            }
        }
        if (best < 4.0 * dm) best = (int) Math.round(tabW * 0.3);
        return Math.min(best + 4.0 * dm, tabW);
    }

    /** Есть ли в снимке заметное число непрозрачных точек (проверка по сетке, дёшево). */
    private static boolean hasContent(Bitmap b) {
        int hit = 0;
        int w = b.getWidth();
        int hh = b.getHeight();
        for (int y = 2; y < hh; y += 6) {
            for (int x = 2; x < w; x += 6) {
                if ((b.getPixel(x, y) >>> 24) > 40 && ++hit >= 12) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ касания

    /** Касание окна (для TikTok вызывается из хука Activity.dispatchTouchEvent). Только смотрит, ничего не гасит. */
    void onTouch(MotionEvent ev) {
        if (!attached || !measured || ev == null || synthetic) return;
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            lastDevice = ev.getDeviceId();
            track = false;
            downTab = -1;
            sTrack = false;
            scrolling = false;
            if (!bar.isShown()) return;
            int[] loc = new int[2];
            bar.getLocationOnScreen(loc);
            float x = ev.getRawX() - loc[0];
            float y = ev.getRawY() - loc[1];
            if (x < capL || x > capR || y < capT - 6 * dm || y > capB + 6 * dm) {
                // палец лёг вне панели: если он поедет по вертикали, это прокрутка ленты, и панель сожмётся
                if (GlassCfg.cp) {
                    sTrack = true;
                    sx0 = ev.getRawX();
                    sy0 = ev.getRawY();
                }
                return;
            }
            setCompact(false);
            int t = tabAt(x);
            if (t < 0 || t == create) return;
            track = true;
            downTab = t;
            ph.down(GlassGeo.xToPos(centers, x - capL));
            capDirty = true;
            burstUntil = SystemClock.uptimeMillis() + BURST_MS;
            kick();
        } else if (sTrack) {
            if (a == MotionEvent.ACTION_MOVE) {
                if (!scrolling) {
                    float dy = Math.abs(ev.getRawY() - sy0);
                    float dx = Math.abs(ev.getRawX() - sx0);
                    if (dy > 12 * dm && dy > 1.4f * dx) {
                        scrolling = true;
                        setCompact(true);
                    }
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                sTrack = false;
                if (scrolling) {
                    scrolling = false;
                    // лента докручивается ещё немного, потом панель разворачивается
                    h.removeCallbacks(expandRun);
                    h.postDelayed(expandRun, 550L);
                }
            }
        } else if (!track) {
            return;
        } else if (a == MotionEvent.ACTION_MOVE) {
            int[] loc = new int[2];
            bar.getLocationOnScreen(loc);
            ph.move(GlassGeo.xToPos(centers, ev.getRawX() - loc[0] - capL));
            kick();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            track = false;
            int[] loc = new int[2];
            bar.getLocationOnScreen(loc);
            long delay = 450L;
            if (a == MotionEvent.ACTION_UP) {
                float y = ev.getRawY() - loc[1];
                int n = ph.up(GlassGeo.xToPos(centers, ev.getRawX() - loc[0] - capL));
                // отпустили над другой вкладкой: сам TikTok нажатия не увидит (палец лёг на одну, поднят над другой),
                // поэтому нажимаем на вкладку под линзой сами; если палец увели далеко от панели, это отмена
                boolean near = y >= capT - 48 * dm && y <= capB + 48 * dm;
                if (near && n >= 0 && n < eligible.length) {
                    int target = eligible[n];
                    if (target != downTab) {
                        chooseLater(target);
                        delay = 1900L;
                    }
                }
            } else {
                ph.up(ph.tgt);
            }
            burstUntil = SystemClock.uptimeMillis() + BURST_MS;
            capDirty = true;
            kick();
            // если вкладка так и не переключилась, линза вернётся на выбранную
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (attached) syncBack();
                }
            }, delay);
        }
    }

    /**
     * Через мгновение переводит TikTok на вкладку под линзой, если он сам её не выбрал. Задержка нужна, чтобы
     * настоящее касание успело дойти до TikTok: если он выбрал вкладку сам, второе нажатие не делается (по нажатию на
     * «Главную» при выбранной «Главной» TikTok обновляет ленту). Порядок: нажатие пальцем в центр вкладки с настоящими
     * экранными координатами, проверка через полсекунды, и только если вкладка не выбралась (или TikTok вообще не
     * принял касание), обычный клик по вкладке: сама вкладка, её дочерние виды, потом родители внутри панели.
     */
    private void chooseLater(final int target) {
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!attached || target < 0 || target >= tabs.size()) return;
                if (readSelected() == target) {
                    dragNote = "TikTok сам выбрал вкладку " + (target + 1);
                    return;
                }
                final boolean taken = tapTab(target);
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!attached) return;
                        int sel = readSelected();
                        if (sel == target) {
                            dragNote = "нажатие на вкладку " + (target + 1) + " сработало";
                            return;
                        }
                        if (sel < 0 && taken) {
                            dragNote = "нажатие на вкладку " + (target + 1) + " принято, выбранную прочитать нельзя";
                            return;
                        }
                        boolean ok = clickDeep(tabs.get(target), bar, 0);
                        dragNote = "нажатие " + (taken ? "принято, но вкладка не выбралась" : "не принято")
                                + ", обычный клик по вкладке " + (target + 1) + ": " + (ok ? "выполнен" : "нет обработчика");
                        kick();
                    }
                }, 520L);
            }
        }, 140L);
    }

    /** Клик по виду: сам вид, потом дочерние и (на верхнем уровне) родители до панели. true, если обработчик сработал. */
    private static boolean clickDeep(View v, View stop, int depth) {
        if (v == null || depth > 4) return false;
        if (v.hasOnClickListeners() && v.performClick()) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (clickDeep(g.getChildAt(i), stop, depth + 1)) return true;
            }
        }
        if (depth == 0) {
            for (android.view.ViewParent p = v.getParent(); p instanceof View && p != stop; p = p.getParent()) {
                View pv = (View) p;
                if (pv.hasOnClickListeners() && pv.performClick()) return true;
            }
        }
        return false;
    }

    private MotionEvent touch(long down, long when, int action, float rawX, float rawY, int ox, int oy) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = rawX;
        pc[0].y = rawY;
        pc[0].pressure = 1f;
        pc[0].size = 1f;
        MotionEvent e = MotionEvent.obtain(down, when, action, 1, pp, pc, 0, 0, 1f, 1f, lastDevice, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        // как у настоящего касания: экранные координаты в getRawX/getRawY, а локальные уже в системе панели
        e.offsetLocation(-ox, -oy);
        return e;
    }

    /**
     * Нажатие пальцем в центр вкладки: те же события, что у настоящего касания (палец, сенсорный экран, экранные
     * координаты), поэтому работает и с обработчиками на панели, и с вкладками, которые сверяют getRawX. DOWN уходит
     * сразу, UP через 60 мс. Возвращает, принял ли TikTok нажатие.
     */
    private boolean tapTab(int index) {
        final View t = tabs.get(index);
        if (t.getWidth() <= 0 || bar.getWidth() <= 0) return false;
        int[] bl = new int[2];
        int[] tl = new int[2];
        bar.getLocationOnScreen(bl);
        t.getLocationOnScreen(tl);
        final float sx = tl[0] + t.getWidth() / 2f;
        final float sy = tl[1] + t.getHeight() / 2f;
        final int ox = bl[0];
        final int oy = bl[1];
        final long t0 = SystemClock.uptimeMillis();
        boolean taken = false;
        MotionEvent down = touch(t0, t0, MotionEvent.ACTION_DOWN, sx, sy, ox, oy);
        synthetic = true;
        try {
            taken = bar.dispatchTouchEvent(down);
        } catch (Throwable th) {
            FlagStore.log(th);
        } finally {
            synthetic = false;
            down.recycle();
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                MotionEvent up = touch(t0, now, MotionEvent.ACTION_UP, sx, sy, ox, oy);
                synthetic = true;
                try {
                    bar.dispatchTouchEvent(up);
                } catch (Throwable th) {
                    FlagStore.log(th);
                } finally {
                    synthetic = false;
                    up.recycle();
                }
            }
        }, 60L);
        return taken;
    }

    private void syncBack() {
        int s = readSelected();
        int p = posOf(s);
        if (p >= 0 && !ph.drag && Math.round(ph.tgt) != p) {
            ph.tgt = p;
            kick();
        }
    }

    /** Номер вкладки (среди tabs) под x в координатах панели или -1. */
    private int tabAt(float x) {
        for (int i = 0; i < tabs.size(); i++) {
            View t = tabs.get(i);
            if (x >= t.getLeft() && x <= t.getRight()) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------ сжатие

    /** Можно ли сжимать панель: включено в настройках, шейдер работает и цвет панели под значками известен. */
    private boolean compactOk() {
        return GlassCfg.cp && GlassCfg.glass() && measured && useAgsl() && (stripKnown || tpOn()) && !CrashGuard.tripped;
    }

    /** Сжимает панель до значков (on) или разворачивает обратно; сжатая панель сама развернётся через 4 с. */
    private void setCompact(boolean on) {
        h.removeCallbacks(expandRun);
        if (on) {
            if (!compactOk()) return;
            ph.cmT = 1;
            h.postDelayed(expandRun, 4000L);
        } else {
            ph.cmT = 0;
        }
        burstUntil = SystemClock.uptimeMillis() + BURST_MS;
        kick();
    }

    /** Для предпросмотра в настройках: показывает сжатие и разворачивает через секунду с небольшим. */
    void demoCompact() {
        if (!attached) return;
        capDirty = true;
        setCompact(true);
        h.removeCallbacks(expandRun);
        h.postDelayed(expandRun, 1300L);
    }

    boolean compacted() {
        return ph.cmT > 0.5;
    }

    // ------------------------------------------------------------ рисунки

    private GlassGeo.Frame frame() {
        GlassGeo.Frame f = GlassGeo.frame(ph, centers, tabW, capR - capL, capB - capT, pad, dm, dark);
        int a = GlassGeo.fillAlpha(GlassCfg.tr, GlassCfg.br);
        GlassGeo.backdrop(f, strip, dark ? a : a / 2, dark);
        BackdropCopy bc = bcopy;
        if (bodyOn() && bc != null && bc.bitmap() != null) {
            GlassGeo.glassBody(f, true, bc.bitmap().getWidth(), bc.bitmap().getHeight(), dark, dark ? a : a / 2);
        }
        f.op = shaderOnly() ? 1 : 0;
        if (f.op > 0.5 && plusBoxOk && GlassCfg.plus != 0) {
            GlassGeo.plusColors(f, GlassCfg.plus, GlassCfg.plH, GlassCfg.plS, GlassCfg.plV, plusLight);
            f.pz0 = plusBox[0];
            f.pz1 = plusBox[1];
            f.pz2 = plusBox[2];
            f.pz3 = plusBox[3];
        }
        double cx = (capR - capL) / 2.0;
        double lastOff = 0;
        for (double c : centers) lastOff = Math.max(lastOff, Math.abs(c - cx));
        GlassGeo.compact(f, compactOk() ? ph.cmp : 0.0, cx, plusHalf, yc, lt, lastOff, iconH, dm);
        return f;
    }

    /** Фон панели: прежний фон и поверх него капсула. */
    private final class Background extends Drawable implements Drawable.Callback {
        Drawable orig;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        @Override
        public void draw(Canvas c) {
            try {
                Drawable o = orig;
                if (o != null && !tpOn()) {
                    o.setBounds(getBounds());
                    o.draw(c);
                }
                if (!measured) return;
                // капсулу целиком рисует шейдер над значками (непрозрачную: ни лента, ни родные значки не просвечивают),
                // здесь остаётся только прежний фон панели
                if (shaderOnly()) return;
                int a = GlassGeo.fillAlpha(GlassCfg.tr, GlassCfg.br);
                if (!dark) a = a / 2;
                fill.setStyle(Paint.Style.FILL);
                // пока шейдера нет, без чёрной заливки капсула всё равно сплошная, иначе под ней просвечивает лента
                fill.setColor(tpOn() ? 0xFF000000 | GlassGeo.opaqueFill(strip, a, dark)
                        : (a << 24) | (dark ? 0xFFFFFF : 0x000000));
                float r = (capB - capT) / 2f;
                rect.set(capL, capT, capR, capB);
                c.drawRoundRect(rect, r, r, fill);
                if (!useAgsl()) {
                    pill(c);
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(Math.max(1f, dm * 0.6f));
                    ring.setColor(dark ? 0x40FFFFFF : 0x22000000);
                    rect.inset(ring.getStrokeWidth() / 2f, ring.getStrokeWidth() / 2f);
                    c.drawRoundRect(rect, r, r, ring);
                }
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }

        /** Без шейдера «таблетка» выбранной вкладки рисуется здесь, под значками (слой-линза лежит над ними). */
        private void pill(Canvas c) {
            GlassGeo.Frame f = frame();
            if (f.k <= 0.01) return;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(((int) Math.round(255 * f.k * (0.10 + 0.12 * f.d)) << 24) | (f.d > 0.5 ? 0xFFFFFF : 0x000000));
            float cy = (capT + capB) / 2f;
            rect.set((float) (capL + f.px - f.bw), (float) (cy - f.bh), (float) (capL + f.px + f.bw),
                    (float) (cy + f.bh));
            c.drawRoundRect(rect, (float) f.bh, (float) f.bh, fill);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            if (orig != null) orig.setBounds(b);
        }

        @Override
        public boolean isStateful() {
            return orig != null && orig.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] s) {
            return orig != null && orig.setState(s);
        }

        @Override
        public boolean getPadding(Rect out) {
            return orig != null && orig.getPadding(out);
        }

        @Override
        public int getIntrinsicWidth() {
            return orig != null ? orig.getIntrinsicWidth() : -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return orig != null ? orig.getIntrinsicHeight() : -1;
        }

        @Override
        public void setAlpha(int alpha) {
            if (orig != null) orig.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            if (orig != null) orig.setColorFilter(cf);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    }

    /** Слой над значками: «таблетка» выбранной вкладки и линза. */
    private final class Lens extends Drawable {
        private final RectF r = new RectF();

        @Override
        public void draw(Canvas c) {
            if (!measured || !attached) return;
            int w = capR - capL;
            int hh = capB - capT + 2 * pad;
            if (getBounds().width() != w || getBounds().height() != hh) {
                setBounds(capL, capT - pad, capR, capB + pad);
            }
            Rect b = getBounds();
            int save = c.save();
            try {
                c.translate(b.left, b.top);
                GlassGeo.Frame f = frame();
                swCanvas = !c.isHardwareAccelerated();
                if (useAgsl()) {
                    if (!swCanvas) {
                        if (f.ca > 0.001 && !tpOn()) {
                            // при сжатии прежние значки TikTok закрываются цветом панели, а панель рисует шейдер целиком
                            // (без чёрной заливки значки прячутся прозрачностью вкладок, см. hideTabs)
                            stripPaint.setColor(0xFF000000 | strip);
                            c.drawRect(0f, 0f, (float) f.rw, (float) f.rh, stripPaint);
                        }
                        agsl.draw(c, tex, f.bk > 0.5 && bcopy != null ? bcopy.bitmap() : null, f);
                    }
                } else {
                    plain(c, f);
                }
            } catch (Throwable t) {
                FlagStore.log(t);
                agsl = null;
                shaderNote = "шейдер отключён после сбоя: " + t.getClass().getSimpleName();
            } finally {
                c.restoreToCount(save);
            }
        }

        /** Запасной рисунок без шейдера: полупрозрачная линза при касании («таблетка» лежит в фоне). */
        private void plain(Canvas c, GlassGeo.Frame f) {
            double vis = 1 - f.z;
            if (vis > 0.01) {
                r.set((float) (f.lx - f.lw), (float) (f.ly - f.lh), (float) (f.lx + f.lw), (float) (f.ly + f.lh));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(((int) Math.round(255 * 0.14 * vis) << 24) | 0xFFFFFF);
                c.drawRoundRect(r, (float) f.lh, (float) f.lh, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dm * 0.8f));
                paint.setColor(((int) Math.round(255 * 0.45 * vis) << 24) | 0xFFFFFF);
                c.drawRoundRect(r, (float) f.lh, (float) f.lh, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------ сведения

    String details() {
        StringBuilder sb = new StringBuilder();
        sb.append("панель: ").append(bar.getClass().getName()).append(", вкладок ").append(tabs.size());
        sb.append(", кнопка «+»: ").append(create < 0 ? "нет" : String.valueOf(create + 1));
        sb.append(", выбрана: ").append(lastSelected < 0 ? "не определена" : String.valueOf(lastSelected + 1));
        sb.append(", ").append(dark ? "тёмная" : "светлая").append(" подложка");
        sb.append("\n").append(shaderNote);
        sb.append("\nсжатие панели: ").append(!GlassCfg.cp ? "выключено" : compactOk() ? "работает"
                : "недоступно (" + (!useAgsl() ? "нет шейдера или снимка значков" : !stripKnown
                ? "цвет фона панели неизвестен" : "панель не измерена") + ")");
        sb.append(", «+» ").append(Math.round(plusHalf * 2)).append(" px, значки ").append(Math.round(iconH))
                .append(" px");
        sb.append("\nкапсула: ").append(shaderOnly() ? "рисует шейдер, непрозрачная" : "рисуется без шейдера ("
                + (agsl == null ? "шейдера нет" : !texOk ? "нет снимка значков" : swCanvas ? "холст не аппаратный"
                : "стекло выключено") + ")");
        sb.append("\nкнопка «+»: ").append(GlassCfg.plus < GlassCfg.PLUS_NAMES.length ? GlassCfg.PLUS_NAMES[GlassCfg.plus]
                : "?");
        if (GlassCfg.plus != 0) {
            sb.append(plusBoxOk ? String.format(", область %d×%d px, тело родной кнопки %s",
                    Math.round(plusBox[2] - plusBox[0]), Math.round(plusBox[3] - plusBox[1]),
                    plusLight ? "светлое" : "тёмное") : ", область не найдена");
        }
        sb.append("\n").append(bkStatus());
        sb.append(", размытие ").append(GlassCfg.bl).append(" dp");
        if (bkMisses > 0) sb.append(", полоса не помещалась раз: ").append(bkMisses);
        sb.append("\nзаливка панели: ").append(tpOn() ? "убрана (у видов-обёрток: " + clearedViews.size() + ")" : "оставлена");
        sb.append(", цепочка фонов:");
        View cv = bar;
        for (int i = 0; i < 6 && cv != null; i++) {
            Drawable d = bgOf(cv);
            String name = d == null ? "нет" : d instanceof ColorDrawable
                    ? String.format("#%08X", ((ColorDrawable) d).getColor()) : d.getClass().getSimpleName();
            sb.append("\n  ").append(cv.getClass().getSimpleName()).append(' ').append(cv.getWidth()).append('×')
                    .append(cv.getHeight()).append(": ").append(name).append(clearedViews.contains(cv) ? " (снят)" : "");
            Object par = cv.getParent();
            cv = par instanceof View ? (View) par : null;
        }
        if (dragNote.length() > 0) sb.append("\nпереход пальцем на другую вкладку: ").append(dragNote);
        return sb.toString();
    }
}
