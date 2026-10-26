package dev.ttmlonger;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Блок «Мод → Настройки мода» для страницы настроек TikTok. Цвета берутся с самой страницы
 * (см. {@link Palette}): так блок одинаково выглядит в светлой и тёмной теме.
 */
final class ModBlock {
    private ModBlock() {
    }

    /** Цвета блока, подобранные под фон страницы. */
    static final class Palette {
        final int bg;
        final int card;
        final int ink;
        final int muted;
        final boolean light;

        private Palette(int bg, int card, int ink, int muted, boolean light) {
            this.bg = bg;
            this.card = card;
            this.ink = ink;
            this.muted = muted;
            this.light = light;
        }

        /** Палитра под заданный фон страницы (непрозрачный цвет 0xFFRRGGBB). */
        static Palette of(int pageBg) {
            int bg = 0xFF000000 | (pageBg & 0xFFFFFF);
            boolean light = luminance(bg) >= 0.5f;
            int card;
            if (light) {
                // На почти белой странице карточка чуть темнее, на сероватой — белая.
                card = luminance(bg) > 0.97f ? 0xFFF4F4F4 : 0xFFFFFFFF;
            } else {
                card = mix(bg, 0xFFFFFFFF, 0.09f);
            }
            return new Palette(bg, card, light ? 0xFF000000 : 0xFFFFFFFF,
                    light ? 0xFF6B6B6B : 0xFF8A8A8A, light);
        }

        boolean sameAs(Palette o) {
            return o != null && o.bg == bg;
        }
    }

    /** Яркость цвета от 0 (чёрный) до 1 (белый). */
    static float luminance(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    /** Смесь двух цветов: t=0 даёт a, t=1 даёт b. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * Самый частый цвет среди пикселей: фон страницы занимает больше места, чем карточки и
     * текст, поэтому полоска у левого края даёт именно его.
     *
     * @return цвет или 0, если пикселей нет
     */
    static int dominant(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        Map<Integer, Integer> count = new HashMap<>();
        int best = 0;
        int bestN = 0;
        for (int p : pixels) {
            int c = p & 0xFFFFFF;
            Integer n = count.get(c);
            int v = n == null ? 1 : n + 1;
            count.put(c, v);
            if (v > bestN) {
                bestN = v;
                best = c;
            }
        }
        return 0xFF000000 | best;
    }

    /** Блок целиком: подпись «Мод» и карточка «Настройки мода». Отступ под строку состояния ставит хозяин. */
    static LinearLayout build(final Activity act, Palette p) {
        Ui ui = new Ui(act);

        LinearLayout col = new LinearLayout(act);
        col.setTag(Diag.OWN_TAG);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(p.bg);
        col.setClickable(true); // не пропускать касания к содержимому под блоком
        col.setPadding(0, 0, 0, ui.dp(6));

        TextView label = ui.text("Мод", 15, p.muted, true);
        label.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8));
        col.addView(label);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(20), ui.dp(16), ui.dp(16), ui.dp(16));
        row.setBackground(ui.round(p.card, 20));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModMenu.show(act);
            }
        });

        TextView gear = ui.text("⚙", 24, p.muted, false);
        gear.setPadding(0, 0, ui.dp(16), 0);
        row.addView(gear);
        TextView name = ui.text("Настройки мода", 19, p.ink, true);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ui.text("›", 28, p.muted, false));

        LinearLayout.LayoutParams lp = ui.matchWrap();
        lp.setMargins(ui.dp(7), 0, ui.dp(7), 0);
        col.addView(row, lp);
        return col;
    }

    /**
     * Прячет содержимое блока, оставляя отступ под строку состояния (страница ниже сама его не
     * делает), или возвращает его.
     */
    static void fold(LinearLayout block, boolean folded, int statusInset, int bottomPad) {
        for (int i = 0; i < block.getChildCount(); i++) {
            block.getChildAt(i).setVisibility(folded ? View.GONE : View.VISIBLE);
        }
        block.setPadding(0, statusInset, 0, folded ? 0 : bottomPad);
    }
}
