package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * «Нижняя панель»: выбор «Оригинал / Liquid Glass», живой предпросмотр и настройки стекла из index.html.
 * Изменения действуют сразу, перезапуск TikTok не нужен.
 */
final class GlassBarScreen extends Screen {
    private LinearLayout choiceOriginal;
    private LinearLayout choiceGlass;
    private LinearLayout glassBox;
    private TextView state;
    private LinearLayout compactRow;
    private LinearLayout backRow;
    private LinearLayout fillRow;
    private LinearLayout presetRow;
    private LinearLayout physRow;
    private LinearLayout modeRow;
    private final ArrayList<Runnable> syncs = new ArrayList<>();

    private PreviewBar previewBar;
    private GlassSession previewSession;

    private GlassBarScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new GlassBarScreen(act).open("Нижняя панель",
                "Нижняя панель TikTok: как есть («Оригинал») или стеклянная капсула, по которой скользит линза "
                        + "с преломлением и бликом. Действует сразу, перезапуск не нужен.");
    }

    static String menuSummary() {
        return GlassCfg.summary();
    }

    private interface Fmt {
        String of(int v);
    }

    private interface Put {
        void set(int v);
    }

    @Override
    void build(LinearLayout body) {
        dialog.setOnDismissListener(d -> stopPreview());

        body.addView(ui.header("Вид панели"));
        choiceOriginal = choiceRow("Оригинал", "Панель TikTok без изменений", GlassCfg.ORIGINAL);
        choiceGlass = choiceRow("Liquid Glass", "Капсула из стекла и линза на выбранной вкладке", GlassCfg.GLASS);
        body.addView(choiceOriginal);
        body.addView(choiceGlass);

        body.addView(ui.header("Предпросмотр"));
        body.addView(buildPreview(), previewParams());

        glassBox = new LinearLayout(act);
        glassBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(glassBox, ui.matchWrap());
        buildGlass(glassBox);

        body.addView(ui.header("Состояние"));
        state = ui.text("", 14f, Ui.INK, false);
        state.setLineSpacing(0f, 1.15f);
        state.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(state, ui.matchWrap());
        body.addView(ui.row(Icons.REFRESH, "Обновить состояние", "Панель мод ищет раз в секунду, пока открыт TikTok",
                null, false, v -> {
                    GlassBar.refresh();
                    refreshState();
                }));
        body.addView(ui.row(Icons.COPY, "Скопировать сведения",
                "Если панель не нашлась или выглядит не так, пришлите это разработчику (текстов с экрана в сведениях нет)",
                null, false, v -> copy()));
        body.addView(ui.row(Icons.REFRESH, "Сбросить оформление", "Вернуть значения стекла и пружины по умолчанию",
                null, false, v -> {
                    GlassCfg.resetLook();
                    applyAll();
                }));
        body.addView(ui.note("Непрозрачность, насыщенность и яркость действуют на капсулу и линзу. Фон из ленты "
                + "снимается через PixelCopy: на каких-то экранах TikTok он может не сняться (тогда капсула "
                + "остаётся сплошной), причину покажет «Скопировать сведения». Шейдер работает на Android 13 и "
                + "новее; на старых версиях рисуется упрощённая линза без преломления."));

        syncChoice();
        syncAll();
        refreshState();
    }

    // ------------------------------------------------------------ выбор Оригинал / Liquid Glass

    private LinearLayout choiceRow(String title, String sub, final int value) {
        LinearLayout r = ui.row(-1, title, sub, null, false, v -> {
            GlassCfg.setStyle(value);
            GlassBar.refresh();
            syncChoice();
            refreshState();
        });
        ImageView check = ui.icon(Icons.CHECK, Theme.accent(), 22);
        check.setTag("check");
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) check.getLayoutParams();
        lp.setMarginStart(ui.dp(12));
        r.addView(check);
        r.setTag(Integer.valueOf(value));
        return r;
    }

    private void syncChoice() {
        boolean glass = GlassCfg.style == GlassCfg.GLASS;
        mark(choiceOriginal, !glass);
        mark(choiceGlass, glass);
        glassBox.setVisibility(glass ? View.VISIBLE : View.GONE);
        updatePreview();
    }

    private static void mark(LinearLayout row, boolean on) {
        View c = row.findViewWithTag("check");
        if (c != null) c.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    // ------------------------------------------------------------ настройки стекла

    private void buildGlass(LinearLayout box) {
        box.addView(ui.header("Стекло"));
        presetRow = chipRow();
        box.addView(presetRow);
        slider(box, "Непрозрачность", "Насколько заметна капсула на подложке TikTok", GlassCfg.TR_MIN,
                GlassCfg.TR_MAX, () -> GlassCfg.tr, v -> GlassCfg.setTr(v), v -> v + "%");
        slider(box, "Насыщенность", "Цвета значков внутри линзы", GlassCfg.SA_MIN, GlassCfg.SA_MAX,
                () -> GlassCfg.sa, v -> GlassCfg.setSa(v), v -> v + "%");
        slider(box, "Яркость", "Подложка капсулы и тело линзы", GlassCfg.BR_MIN, GlassCfg.BR_MAX,
                () -> GlassCfg.br, v -> GlassCfg.setBr(v), v -> v + "%");
        slider(box, "Преломление при касании", "Насколько линза искажает значки", GlassCfg.GS_MIN,
                GlassCfg.GS_MAX, () -> GlassCfg.gs, v -> GlassCfg.setGs(v), GlassBarScreen::hundredths);
        slider(box, "Блик по кромке", "Specular Rim: яркая кромка капли", GlassCfg.SR_MIN, GlassCfg.SR_MAX,
                () -> GlassCfg.sr, v -> GlassCfg.setSr(v), GlassBarScreen::hundredths);
        slider(box, "Хроматическая аберрация", "Цветная кайма у края линзы", GlassCfg.TN_MIN, GlassCfg.TN_MAX,
                () -> GlassCfg.tn, v -> GlassCfg.setTn(v), GlassBarScreen::hundredths);

        slider(box, "Увеличение под линзой", "Значки под линзой крупнее, как в видео-образце", GlassCfg.ZM_MIN,
                GlassCfg.ZM_MAX, () -> GlassCfg.zm, v -> GlassCfg.setZm(v), v -> v + "%");
        slider(box, "Смещение значков", "Линза раздвигает соседние вкладки: Главная, Друзья и остальные",
                GlassCfg.MV_MIN, GlassCfg.MV_MAX, () -> GlassCfg.mv, v -> GlassCfg.setMv(v), v -> v + "%");

        box.addView(ui.header("Фон под капсулой"));
        fillRow = chipRow();
        box.addView(fillRow);
        backRow = chipRow();
        box.addView(backRow);
        slider(box, "Размытие фона", "Как «Размытие» в index.html: 0 — слабое (минимум 2 dp), 40 — сильное (dp)", GlassCfg.BL_MIN,
                GlassCfg.BL_MAX, () -> GlassCfg.bl, v -> GlassCfg.setBl(v), v -> v + " dp");
        box.addView(ui.note("«Без чёрной заливки»: мод не рисует сплошной фон панели и снимает такой фон у её "
                + "обёртки (родителя не выше двух высот панели), капсула лежит прямо на ленте. Если снизу всё "
                + "равно чёрное, чернит что-то другое: «Скопировать сведения» покажет цепочку фонов. «Как в "
                + "TikTok» возвращает всё как было."));
        box.addView(ui.note("«Из ленты»: мод снимает полоску окна над панелью и продолжает её свет под капсулу "
                + "(под панелью TikTok ничего нет), размывает и подкрашивает, а линза преломляет его. Так получается "
                + "жидкое стекло, как в index.html. «Сплошной»: капсула просто серая, без снимка. Нужен Android 13 (шейдер) и Android 8 "
                + "(снимок окна); если снять окно нельзя, панель остаётся сплошной."));

        box.addView(ui.header("Физика линзы"));
        physRow = chipRow();
        box.addView(physRow);
        slider(box, "Жёсткость пружины", null, GlassCfg.PK_MIN, GlassCfg.PK_MAX, () -> GlassCfg.pk,
                v -> GlassCfg.setPk(v), v -> String.valueOf(v));
        slider(box, "Затухание", null, GlassCfg.PD_MIN, GlassCfg.PD_MAX, () -> GlassCfg.pd, v -> GlassCfg.setPd(v),
                v -> String.valueOf(v));
        slider(box, "Желейность", "Растяжение линзы при движении", GlassCfg.JL_MIN, GlassCfg.JL_MAX,
                () -> GlassCfg.jl, v -> GlassCfg.setJl(v), GlassBarScreen::tenths);
        slider(box, "Надувание при касании", "Насколько линза раздувается под пальцем", GlassCfg.PZ_MIN,
                GlassCfg.PZ_MAX, () -> GlassCfg.pz, v -> GlassCfg.setPz(v), GlassBarScreen::tenths);

        box.addView(ui.header("Линза"));
        modeRow = chipRow();
        box.addView(modeRow);
        box.addView(ui.note("«Как в iOS»: в покое выбранная вкладка лежит на светлой матовой «таблетке», которая "
                + "плавно переезжает на нажатую вкладку; линза появляется, если палец задержался или поехал по "
                + "панели. «Классика»: линза всегда видна."));

        box.addView(ui.header("Сжатие панели"));
        compactRow = chipRow();
        box.addView(compactRow);
        box.addView(ui.note("Как на видео-образце: пока листаете ленту, панель сжимается до значков без подписей "
                + "(«+» остаётся как есть), а когда лента остановилась, разворачивается. Нажатие на панель "
                + "разворачивает её сразу. Работает на Android 13 и новее и только если мод смог определить цвет "
                + "фона панели."));
    }

    private LinearLayout chipRow() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        return r;
    }

    private TextView chip(String label, boolean on, View.OnClickListener click) {
        TextView t = ui.text(label, 14f, on ? Theme.onColor(Theme.accent()) : Ui.INK, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        t.setBackground(ui.round(on ? Theme.accent() : Ui.alpha(Ui.INK, 0x1F), 18));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(ui.dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private interface Get {
        int value();
    }

    private void slider(LinearLayout box, String title, String sub, final int min, final int max, final Get get,
                        final Put put, final Fmt fmt) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6));
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = ui.text(title, 16f, Ui.INK, false);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = ui.text(fmt.of(get.value()), 15f, Ui.MUTED, false);
        head.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head, ui.matchWrap());
        if (sub != null) {
            TextView s = ui.text(sub, 13f, Ui.MUTED, false);
            s.setPadding(0, ui.dp(2), 0, ui.dp(2));
            col.addView(s, ui.matchWrap());
        }
        final SeekBar bar = new SeekBar(act);
        bar.setMax(max - min);
        bar.setProgress(get.value() - min);
        ColorStateList accent = ColorStateList.valueOf(Theme.accent());
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.DIVIDER));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                int v = progress + min;
                val.setText(fmt.of(v));
                if (fromUser) {
                    put.set(v);
                    live();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
                b.getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                GlassCfg.save();
                syncPresets();
            }
        });
        col.addView(bar, ui.matchWrap());
        box.addView(col, ui.matchWrap());
        syncs.add(() -> {
            bar.setProgress(get.value() - min);
            val.setText(fmt.of(get.value()));
        });
    }

    private static String hundredths(int v) {
        return String.format(Locale.US, "%.2f", v / 100.0);
    }

    private static String tenths(int v) {
        return String.format(Locale.US, "%.1f", v / 10.0);
    }

    /** Подкрутили ползунок: показать сразу и на панели TikTok, и в предпросмотре. */
    private void live() {
        GlassBar.refresh();
        if (previewSession != null) previewSession.refresh();
    }

    private void applyAll() {
        GlassBar.refresh();
        syncAll();
        refreshState();
    }

    private void syncAll() {
        for (Runnable r : syncs) r.run();
        syncPresets();
        if (previewSession != null) previewSession.refresh();
    }

    private void syncPresets() {
        presetRow.removeAllViews();
        int cur = GlassCfg.glassPreset();
        for (int i = 0; i < GlassCfg.GLASS_NAMES.length; i++) {
            final int idx = i;
            presetRow.addView(chip(GlassCfg.GLASS_NAMES[i], cur == i, v -> {
                GlassCfg.applyGlassPreset(idx);
                applyAll();
            }));
        }
        physRow.removeAllViews();
        int pc = GlassCfg.physPreset();
        LinearLayout inner = new LinearLayout(act);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < GlassCfg.PHYS_NAMES.length; i++) {
            final int idx = i;
            inner.addView(chip(GlassCfg.PHYS_NAMES[i], pc == i, v -> {
                GlassCfg.applyPhysPreset(idx);
                applyAll();
                demo();
            }));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(inner);
        physRow.addView(scroll);
        modeRow.removeAllViews();
        modeRow.addView(chip("Как в iOS", GlassCfg.ios, v -> {
            GlassCfg.setIos(true);
            applyAll();
            demo();
        }));
        modeRow.addView(chip("Классика", !GlassCfg.ios, v -> {
            GlassCfg.setIos(false);
            applyAll();
            demo();
        }));
        fillRow.removeAllViews();
        fillRow.addView(chip("Без чёрной заливки", GlassCfg.tp, v -> {
            GlassCfg.setTp(true);
            applyAll();
        }));
        fillRow.addView(chip("Как в TikTok", !GlassCfg.tp, v -> {
            GlassCfg.setTp(false);
            applyAll();
        }));
        backRow.removeAllViews();
        backRow.addView(chip("Из ленты", GlassCfg.bk, v -> {
            GlassCfg.setBk(true);
            applyAll();
        }));
        backRow.addView(chip("Сплошной", !GlassCfg.bk, v -> {
            GlassCfg.setBk(false);
            applyAll();
        }));
        compactRow.removeAllViews();
        compactRow.addView(chip("Сжимать", GlassCfg.cp, v -> {
            GlassCfg.setCp(true);
            applyAll();
        }));
        compactRow.addView(chip("Не сжимать", !GlassCfg.cp, v -> {
            GlassCfg.setCp(false);
            applyAll();
        }));
        compactRow.addView(chip("Показать сжатие", false, v -> {
            if (previewSession != null) previewSession.demoCompact();
            else Toasts.show(act, "Предпросмотр работает только при выборе «Liquid Glass»");
        }));
    }

    // ------------------------------------------------------------ предпросмотр

    private static final String[] TAB_NAMES = {"Главная", "Друзья", "", "Входящие", "Профиль"};
    private static final int[] TAB_ICONS = {Icons.VIDEO, Icons.STICKER, -1, Icons.SEND, Icons.PALETTE};

    private LinearLayout.LayoutParams previewParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(150));
        lp.setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        return lp;
    }

    /** Образец: «лента» с градиентом и настоящая панель из пяти вкладок, поверх которой работает тот же код, что и на TikTok. */
    private View buildPreview() {
        FrameLayout host = new FrameLayout(act);
        GradientDrawable feed = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF74ACDF, 0xFF2B3A67, 0xFFB23A8F});
        feed.setCornerRadius(ui.dp(16));
        host.setBackground(feed);
        host.setClipToOutline(true);

        TextView hint = ui.text("Коснитесь вкладок или проведите пальцем по панели", 13f, 0xFFFFFFFF, false);
        hint.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), 0);
        host.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        previewBar = new PreviewBar(act);
        previewBar.setOrientation(LinearLayout.HORIZONTAL);
        previewBar.setBackground(new ColorDrawable(Ui.DARK ? 0xFF000000 : 0xFFFFFFFF));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            previewBar.addView(previewTab(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    1f));
        }
        host.addView(previewBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(70),
                Gravity.BOTTOM));
        selectTab(0);

        previewBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l > 0 && previewSession == null) updatePreview();
        });
        return host;
    }

    private View previewTab(final int i) {
        LinearLayout tab = new LinearLayout(act);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        if (TAB_ICONS[i] < 0) {
            // кнопка «+» создать: у неё нет подписи
            TextView plus = ui.text("+", 24f, 0xFF000000, true);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(ui.round(0xFFFFFFFF, 10));
            tab.addView(plus, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(32)));
            return tab;
        }
        ImageView icon = new ImageView(act);
        icon.setTag("icon");
        tab.addView(icon, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        TextView label = ui.text(TAB_NAMES[i], 11f, Ui.MUTED, true);
        label.setTag("label");
        label.setPadding(0, ui.dp(3), 0, 0);
        tab.addView(label);
        tab.setClickable(true);
        tab.setOnClickListener(v -> selectTab(i));
        return tab;
    }

    private void selectTab(int index) {
        previewBar.selected = index;
        int on = Ui.DARK ? 0xFFFFFFFF : 0xFF000000;
        int off = 0xFF8A8A8A;
        for (int i = 0; i < previewBar.getChildCount(); i++) {
            View tab = previewBar.getChildAt(i);
            tab.setSelected(i == index);
            ImageView icon = tab.findViewWithTag("icon") instanceof ImageView
                    ? (ImageView) tab.findViewWithTag("icon") : null;
            TextView label = tab.findViewWithTag("label") instanceof TextView
                    ? (TextView) tab.findViewWithTag("label") : null;
            int c = i == index ? on : off;
            if (icon != null) icon.setImageDrawable(Icons.of(TAB_ICONS[i], c));
            if (label != null) label.setTextColor(c);
        }
    }

    /** Предпросмотр показывает стекло, только когда выбран «Liquid Glass». */
    private void updatePreview() {
        if (previewBar == null) return;
        boolean glass = GlassCfg.style == GlassCfg.GLASS;
        if (glass && previewSession == null && previewBar.getWidth() > 0) {
            ArrayList<View> tabs = new ArrayList<>();
            for (int i = 0; i < previewBar.getChildCount(); i++) tabs.add(previewBar.getChildAt(i));
            previewSession = new GlassSession(previewBar, tabs, 2, true);
            previewSession.setWindow(dialog.getWindow());
            previewBar.session = previewSession;
            previewSession.attach();
        } else if (!glass) {
            stopPreview();
        }
    }

    private void stopPreview() {
        if (previewSession != null) {
            previewSession.detach();
            previewSession = null;
        }
        if (previewBar != null) previewBar.session = null;
    }

    /** Показывает пружину: линза сама проезжает на другую вкладку. */
    private void demo() {
        if (previewBar == null) return;
        int next = previewBar.selected == 0 ? 4 : 0;
        selectTab(next);
    }

    /** Панель предпросмотра: отдаёт касания сессии, как это делает хук Activity.dispatchTouchEvent на TikTok. */
    private static final class PreviewBar extends LinearLayout {
        GlassSession session;
        int selected;

        PreviewBar(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            GlassSession s = session;
            if (s != null) s.onTouch(ev);
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    // ------------------------------------------------------------ состояние

    private void refreshState() {
        StringBuilder sb = new StringBuilder(GlassBar.status());
        if (previewSession != null) sb.append("\nпредпросмотр: ").append(previewSession.shaderNote());
        state.setText(sb.toString());
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            String text = "TTMLonger " + Targets.VERSION + "\n" + GlassBar.details()
                    + (previewSession != null ? "\nпредпросмотр: " + previewSession.details() : "");
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", text));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            FlagStore.log(t);
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
