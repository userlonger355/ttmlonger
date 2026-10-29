package dev.ttmlonger;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Оформление кнопок скачивания «как в TikTok»: полностью скруглённая «пилюля», жирный текст,
 * значок «скачать» слева (рисуется кодом, см. {@link Glyph}). Главная кнопка красная (цвет
 * акцента TikTok, если вы его меняли), второстепенная и плавающая тёмно-серые, как «Поделиться»
 * в листе стикера. При нажатии кнопка чуть светлеет, как у TikTok.
 */
final class TtStyle {
    /** Красный TikTok. */
    static final int RED = 0xFFFE2C55;
    /** Тёмно-серая вторичная кнопка тёмной темы. */
    static final int GRAY = 0xFF2F2F2F;
    /** Плавающая подсказка поверх видео: тот же серый, чуть прозрачный. */
    static final int FLOAT = 0xF22B2B2B;

    private TtStyle() {
    }

    /** Цвет главной кнопки: выбранный акцент (им же красится TikTok) или красный TikTok. */
    static int primary() {
        return Prefs.accent != 0 ? Prefs.accent : RED;
    }

    /** Цвет чуть светлее исходного: вид кнопки под пальцем. */
    static int lighten(int color) {
        int a = color >>> 24;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += (255 - r) * 18 / 100;
        g += (255 - g) * 18 / 100;
        b += (255 - b) * 18 / 100;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Чёрный или белый текст в зависимости от фона кнопки. */
    static int inkOn(int fill) {
        return Theme.onColor(fill);
    }

    /**
     * Кнопка-«пилюля» со значком «скачать».
     *
     * @param heightDp высота (у TikTok 36-48dp)
     * @param textSp   размер текста
     * @param icon     рисовать ли значок слева от текста
     */
    static TextView pill(Context ctx, CharSequence text, int fill, int ink, float heightDp, float textSp,
            boolean icon) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(ink);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setHeight(Math.round(heightDp * d));
        int side = Math.round((icon ? 14 : 18) * d);
        t.setPadding(side, 0, Math.round(18 * d), 0);
        if (icon) {
            Drawable g = Glyph.download(ink);
            int s = Math.round(Math.min(heightDp * 0.5f, 22f) * d);
            g.setBounds(0, 0, s, s);
            t.setCompoundDrawablesRelative(g, null, null, null);
            t.setCompoundDrawablePadding(Math.round(6 * d));
        }
        t.setBackground(ground(fill, d));
        t.setClickable(true);
        return t;
    }

    /** Главная кнопка: красная, белый текст. */
    static TextView primary(Context ctx, CharSequence text, float heightDp, float textSp) {
        int fill = primary();
        return pill(ctx, text, fill, inkOn(fill), heightDp, textSp, true);
    }

    /** Плавающая подсказка поверх видео и листов. */
    static TextView floating(Context ctx, CharSequence text) {
        return pill(ctx, text, FLOAT, Color.WHITE, 40f, 14.5f, true);
    }

    /** Фон: полная «пилюля»; под пальцем светлее. */
    static Drawable ground(int fill, float density) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(lighten(fill), density));
        s.addState(new int[]{}, shape(fill, density));
        return s;
    }

    private static GradientDrawable shape(int color, float density) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(1000f * density);
        return g;
    }

    /** Круглая серая подложка (если у листа нет своей кнопки, у которой её можно взять). */
    static Drawable roundGround(float density) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(GRAY);
        return g;
    }
}
