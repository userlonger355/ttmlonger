package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URI;
import java.util.List;

/**
 * «Стикеры»: отдельный экран сохранения. Показывает картинки, которые сейчас на экране TikTok
 * (в переписке, в комментариях, на листе стикера), с кнопкой «Сохранить» у каждой. Работает без
 * хуков на нажатия: нужен только обход view.
 */
final class StickerScreen extends Screen {
    private StickerScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new StickerScreen(act).open("Стикеры",
                "Картинки на экране TikTok, которые можно сохранить. Откройте переписку или "
                        + "комментарии со стикерами и вызовите меню двумя пальцами");
    }

    @Override
    void build(LinearLayout body) {
        final Context app = act.getApplicationContext() != null ? act.getApplicationContext() : act;

        final String tapped = StickerSaver.freshUrl();
        if (tapped != null) {
            body.addView(row(null, "Последний нажатый стикер", "ссылка: " + host(tapped),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            StickerSaver.saveAsync(app, tapped, null);
                        }
                    }));
        }

        StickerScan.Result r;
        try {
            r = StickerScan.scan(act);
        } catch (Throwable t) {
            FlagStore.log(t);
            TextView err = ui.text("Не удалось просмотреть экран: " + t, 14, Ui.RED, false);
            err.setPadding(ui.dp(27), ui.dp(12), ui.dp(27), 0);
            body.addView(err, ui.matchWrap());
            return;
        }
        Diag.note("экран «Стикеры»: " + r.summary());

        int n = 0;
        for (final StickerScan.Cand c : r.items) {
            n++;
            String what = c.url != null ? "ссылка: " + host(c.url) : "ссылки нет, сохранится снимок";
            body.addView(row(c.thumb, "Картинка " + n + " · " + c.w + "×" + c.h, what,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            StickerSaver.saveAsync(app, c.url, c.shot);
                        }
                    }));
        }

        if (r.items.isEmpty() && tapped == null) {
            TextView none = ui.text("На экране под меню нет подходящих картинок. Закройте меню, откройте "
                    + "переписку или комментарии со стикерами, вызовите меню двумя пальцами и зайдите сюда "
                    + "снова.", 16, Ui.MUTED, false);
            none.setPadding(ui.dp(27), ui.dp(16), ui.dp(27), 0);
            body.addView(none, ui.matchWrap());
        }

        TextView foot = ui.text("Просмотрено: " + r.summary()
                + "\n\nСохраняйте для себя. Чужие стикеры не публикуйте без согласия автора.",
                12, Ui.MUTED, false);
        foot.setPadding(ui.dp(27), ui.dp(20), ui.dp(27), 0);
        body.addView(foot, ui.matchWrap());
    }

    private LinearLayout row(android.graphics.Bitmap thumb, String title, String sub, View.OnClickListener save) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        card.setBackground(ui.round(Ui.CARD, 24));

        if (thumb != null) {
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(thumb);
            iv.setBackground(ui.round(0xFF262626, 12));
            iv.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(80), ui.dp(80));
            lp.setMarginEnd(ui.dp(14));
            card.addView(iv, lp);
        }

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.text(title, 17, Ui.WHITE, true));
        TextView s = ui.text(sub, 13, Ui.MUTED, true);
        s.setPadding(0, ui.dp(4), ui.dp(8), 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = TtStyle.primary(act, "Сохранить", 36f, 14f);
        btn.setOnClickListener(save);
        card.addView(btn);

        card.setLayoutParams(sidePadded());
        return card;
    }

    private static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "?" : h;
        } catch (Throwable t) {
            return "?";
        }
    }
}
