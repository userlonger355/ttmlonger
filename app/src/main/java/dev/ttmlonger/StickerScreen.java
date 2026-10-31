package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


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
            body.addView(row(null, "Последний нажатый стикер", "Тот, на который вы нажимали в чате",
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
            body.addView(ui.note("Не удалось просмотреть экран. Закройте меню и попробуйте ещё раз."));
            return;
        }

        int n = 0;
        for (final StickerScan.Cand c : r.items) {
            n++;
            String what = c.w + "×" + c.h + (c.url != null ? "" : " · сохранится снимок экрана");
            body.addView(row(c.thumb, "Картинка " + n, what,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            StickerSaver.saveAsync(app, c.url, c.shot);
                        }
                    }));
        }

        if (r.items.isEmpty() && tapped == null) {
            body.addView(ui.note("На экране под меню нет подходящих картинок. Закройте меню, откройте "
                    + "переписку или комментарии со стикерами, вызовите меню двумя пальцами и зайдите сюда "
                    + "снова."));
        }

        body.addView(ui.note("Сохраняйте для себя. Чужие стикеры не публикуйте без согласия автора."));
    }

    private LinearLayout row(android.graphics.Bitmap thumb, String title, String sub, View.OnClickListener save) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));

        if (thumb != null) {
            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(thumb);
            iv.setBackground(ui.round(Ui.FIELD, 10));
            iv.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72));
            lp.setMarginEnd(ui.dp(14));
            card.addView(iv, lp);
        }

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.medium(title, 15.5f, Ui.INK));
        TextView s = ui.text(sub, 13, Ui.MUTED, false);
        s.setPadding(0, ui.dp(3), ui.dp(8), 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btn = TtStyle.primary(act, "Сохранить", 36f, 14f);
        btn.setOnClickListener(save);
        card.addView(btn);

        card.setLayoutParams(ui.matchWrap());
        return card;
    }
}
