package dev.ttmlonger;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Общие мелочи оформления: во view TikTok ресурсов мода нет, всё рисуется кодом. */
final class Ui {
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF141414;
    static final int FIELD = 0xFF2B2B2B;
    static final int WHITE = 0xFFFFFFFF;
    static final int MUTED = 0xFF8A8A8A;
    static final int RED = 0xFFFF2D55;

    final Context ctx;
    private final float density;

    Ui(Context ctx) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

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

    /**
     * Карточка-пункт меню: заголовок, подзаголовок, необязательное описание и шеврон справа.
     * Возвращает контейнер; заголовок подзаголовка можно менять через {@link #subtitleOf}.
     */
    LinearLayout menuRow(String title, String subtitle, String description, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(22), dp(18), dp(18), dp(18));
        card.setBackground(round(CARD, 24));
        card.setClickable(true);
        card.setOnClickListener(click);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 21, WHITE, true));
        if (subtitle != null) {
            TextView sub = text(subtitle, 16, MUTED, true);
            sub.setTag("sub");
            sub.setPadding(0, dp(4), 0, 0);
            col.addView(sub);
        }
        if (description != null) {
            TextView d = text(description, 16, MUTED, true);
            d.setPadding(0, dp(10), 0, 0);
            col.addView(d);
        }
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = text("›", 30, MUTED, false);
        chevron.setPadding(dp(12), 0, dp(4), 0);
        card.addView(chevron);

        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(dp(7), dp(6), dp(7), dp(6));
        card.setLayoutParams(lp);
        return card;
    }

    /** Подзаголовок, созданный в {@link #menuRow}. */
    static TextView subtitleOf(View row) {
        return (TextView) row.findViewWithTag("sub");
    }
}
