package dev.ttmlonger;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Оформление мода «как в TikTok»: плоские строки со значками, подписи разделов, переключатели и
 * шапка по центру. Во view TikTok ресурсов мода нет, поэтому всё рисуется кодом. Цвета берутся из
 * палитры страницы настроек TikTok (см. {@link ModBlock.Palette}), поэтому экраны мода совпадают с
 * ней и в светлой, и в тёмной теме.
 */
final class Ui {
    /** Метка на view мода: в дереве экрана они пропускаются и не принимаются за чужие. */
    static final String OWN_TAG = "ttmlonger";

    /** Красный TikTok. */
    static final int RED = 0xFFFE2C55;

    // Текущая палитра. Меняется только из use()/ensure(), поэтому обычные поля.
    static int BG = 0xFF000000;
    static int CARD = 0xFF171717;
    static int FIELD = 0xFF1F1F1F;
    static int INK = 0xFFFFFFFF;
    static int MUTED = 0xFF8A8A8A;
    static int DIVIDER = 0xFF1A1A1A;
    static boolean DARK = true;
    private static boolean set;

    final Context ctx;
    private final float density;

    Ui(Context ctx) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
        ensure(ctx);
    }

    // ---------------------------------------------------------------- палитра

    /** Принять палитру страницы TikTok (из блока «Мод» или по снимку экрана). */
    static synchronized void use(ModBlock.Palette p) {
        apply(p);
        if (Prefs.pageBg != p.bg) Prefs.setPageBg(p.bg);
    }

    private static void apply(ModBlock.Palette p) {
        BG = p.bg;
        CARD = p.card;
        FIELD = p.field;
        INK = p.ink;
        MUTED = p.muted;
        DIVIDER = p.divider;
        DARK = !p.light;
        set = true;
    }

    /** Если палитру ещё не задавали: взять сохранённую с прошлого раза или тему системы. */
    static synchronized void ensure(Context ctx) {
        if (set) return;
        int bg = Prefs.pageBg;
        if (bg == 0) {
            boolean night = (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            bg = night ? 0xFF000000 : 0xFFFFFFFF;
        }
        apply(ModBlock.Palette.of(bg));
    }

    static int alpha(int color, int a) {
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** Тема системных диалогов под цвет страницы. */
    static int dialogTheme() {
        return DARK ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    static int pageTheme() {
        return DARK ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar;
    }

    /** Подгоняет системный диалог под вид TikTok: скруглённая подложка, кнопки цвета акцента. Вызывать после show(). */
    static void style(AlertDialog d) {
        try {
            Ui ui = new Ui(d.getContext());
            Window w = d.getWindow();
            if (w != null) w.setBackgroundDrawable(ui.round(DARK ? CARD : BG, 20));
            int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
            for (int id : ids) {
                Button b = d.getButton(id);
                if (b == null) continue;
                b.setAllCaps(false);
                b.setTextColor(id == AlertDialog.BUTTON_POSITIVE ? TtStyle.primary() : INK);
            }
        } catch (Throwable ignored) {
            // оформление не обязательно
        }
    }

    // ---------------------------------------------------------------- мелочи

    int dp(float v) {
        return Math.round(v * density);
    }

    TextView text(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Средний вес шрифта, как у заголовков строк в TikTok. */
    TextView medium(CharSequence s, float sp, int color) {
        TextView t = text(s, sp, color, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Скругление только сверху и/или снизу. */
    GradientDrawable corners(int color, float topDp, float bottomDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float t = dp(topDp);
        float b = dp(bottomDp);
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return g;
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    View space(int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    /** Тонкая линия-разделитель. */
    View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(DIVIDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
    }

    /** Подсветка при нажатии: волна цвета текста поверх строки. */
    Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(alpha(INK, 0x1F)), null, new ColorDrawable(Color.WHITE));
    }

    ImageView icon(int id, int color, float sizeDp) {
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(Icons.of(id, color));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    // ---------------------------------------------------------------- строки

    /** Подпись раздела («Основное», «Сохранение»): мелкий серый текст над группой строк. */
    TextView header(String title) {
        TextView t = medium(title, 13.5f, MUTED);
        t.setPadding(dp(16), dp(24), dp(16), dp(6));
        t.setLayoutParams(matchWrap());
        return t;
    }

    /** Пояснение под строками. */
    TextView note(CharSequence s) {
        TextView t = text(s, 13, MUTED, false);
        t.setLineSpacing(0f, 1.1f);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setLayoutParams(matchWrap());
        return t;
    }

    /**
     * Строка списка: значок слева (icon &lt; 0 — без значка), название, пояснение под ним, значение
     * справа и шеврон. Название и пояснение можно менять через {@link #subtitleOf}.
     */
    LinearLayout row(int icon, CharSequence title, CharSequence sub, CharSequence value, boolean chevron,
            View.OnClickListener click) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(16), dp(12), dp(16), dp(12));

        if (icon >= 0) {
            ImageView iv = icon(icon, INK, 24);
            iv.setTag(Integer.valueOf(icon));
            ((LinearLayout.LayoutParams) iv.getLayoutParams()).setMarginEnd(dp(16));
            r.addView(iv);
        }

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tt = medium(title, 16f, INK);
        tt.setTag("title");
        col.addView(tt);
        if (sub != null) {
            TextView s = text(sub, 13.5f, MUTED, false);
            s.setTag("sub");
            s.setLineSpacing(0f, 1.08f);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            TextView v = text(value, 15f, MUTED, false);
            v.setTag("value");
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setMaxWidth(dp(160));
            v.setPadding(dp(12), 0, 0, 0);
            r.addView(v);
        }
        if (chevron) {
            ImageView c = icon(Icons.CHEVRON_RIGHT, MUTED, 20);
            ((LinearLayout.LayoutParams) c.getLayoutParams()).setMarginStart(dp(4));
            r.addView(c);
        }
        if (click != null) {
            r.setClickable(true);
            r.setBackground(ripple());
            r.setOnClickListener(click);
        }
        r.setLayoutParams(matchWrap());
        return r;
    }

    /** Строка-пункт меню со значком и шевроном. */
    LinearLayout menuRow(int icon, String title, String sub, String value, View.OnClickListener click) {
        return row(icon, title, sub, value, true, click);
    }

    /** Строка с переключателем справа; нажатие в любом месте строки переключает. */
    LinearLayout toggleRow(int icon, String title, String desc, boolean initial, ModSwitch.Listener l) {
        final ModSwitch sw = new ModSwitch(ctx, initial);
        sw.setListener(l);
        LinearLayout r = row(icon, title, desc, null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(16));
        r.addView(sw, lp);
        return r;
    }

    /** Подзаголовок, созданный в {@link #row}. */
    static TextView subtitleOf(View row) {
        return (TextView) row.findViewWithTag("sub");
    }

    /** Название строки, созданное в {@link #row}. */
    static TextView titleOf(View row) {
        return (TextView) row.findViewWithTag("title");
    }

    /** Перекрашивает название и значок строки (например, в красный для опасного действия). */
    static void tint(View row, int color) {
        TextView t = titleOf(row);
        if (t != null) t.setTextColor(color);
        if (row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0) {
            View first = ((ViewGroup) row).getChildAt(0);
            if (first instanceof ImageView && first.getTag() instanceof Integer) {
                ((ImageView) first).setImageDrawable(Icons.of((Integer) first.getTag(), color));
            }
        }
    }

    /** Значение справа, созданное в {@link #row}. */
    static TextView valueOf(View row) {
        return (TextView) row.findViewWithTag("value");
    }
}
