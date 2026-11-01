package dev.ttmlonger;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * «Выбор стикера» для огоньков: избранные стикеры TikTok и те, что мод видел в чатах, со значками.
 * Нажатие на стикер выбирает его. Избранные мод просит у самого TikTok; если объект не получен,
 * достаточно открыть в TikTok панель стикеров → избранное и нажать «Обновить».
 */
final class StickerPickScreen extends Screen {
    private static final int SHOW = 60;

    private final Runnable onChosen;
    private TextView status;
    private LinearLayout list;

    private StickerPickScreen(Activity act, Runnable onChosen) {
        super(act);
        this.onChosen = onChosen;
    }

    static void show(Activity act, Runnable onChosen) {
        new StickerPickScreen(act, onChosen).open("Выбор стикера",
                "Нажмите на стикер, который мод будет отправлять в чаты с огоньком");
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.menuRow(Icons.STICKER, "Выбрать отправкой", "Нажмите, затем отправьте любой стикер в чате",
                null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StreakSticker.arm();
                        Toasts.show(act, "Отправьте любой стикер в чате: мод его запомнит");
                        dialog.dismiss();
                        if (onChosen != null) onChosen.run();
                    }
                }));
        body.addView(ui.menuRow(Icons.REFRESH, "Обновить избранные", "Попросить TikTok загрузить избранные стикеры",
                null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        load();
                    }
                }));

        status = ui.header("");
        body.addView(status);

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, ui.matchWrap());

        fill();
        if (Prefs.streakAuto) {
            StreakHooks.prepareSender();
            load();
        }
    }

    private void load() {
        status.setText("Загружаю…");
        StickerLibrary.refresh(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) fill();
            }
        });
    }

    private void fill() {
        list.removeAllViews();
        List<StickerLibrary.Entry> all = StickerLibrary.list();
        status.setText(all.isEmpty() ? "Стикеры" : "Стикеры · " + all.size());
        String why = StickerLibrary.state();
        if (all.isEmpty()) {
            list.addView(ui.note(!why.isEmpty() ? why
                    : "Пока пусто. Откройте в TikTok панель стикеров → избранное или отправьте стикер в чате, "
                    + "затем нажмите «Обновить избранные»."));
            return;
        }
        if (!why.isEmpty() && StickerLibrary.favourites() == 0) list.addView(ui.note(why));
        int n = 0;
        for (StickerLibrary.Entry e : all) {
            if (n++ >= SHOW) break;
            list.addView(row(e));
        }
        if (all.size() > SHOW) {
            list.addView(ui.note("Показано " + SHOW + " из " + all.size()));
        }
    }

    private LinearLayout row(final StickerLibrary.Entry e) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        card.setClickable(true);
        card.setBackground(ui.ripple());

        final ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(ui.round(Ui.FIELD, 10));
        iv.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60));
        lp.setMarginEnd(ui.dp(14));
        card.addView(iv, lp);
        if (e.thumb != null) {
            iv.setImageBitmap(e.thumb);
        } else {
            StickerLibrary.thumb(e, new Runnable() {
                @Override
                public void run() {
                    if (e.thumb != null) iv.setImageBitmap(e.thumb);
                }
            });
        }

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        String name = e.title == null || e.title.isEmpty() ? "Стикер " + shortId(e.id) : e.title;
        col.addView(ui.medium(name, 16f, Ui.INK));
        TextView sub = ui.text(e.fav ? "Избранный" : "Из чата", 13.5f, Ui.MUTED, false);
        sub.setPadding(0, ui.dp(2), 0, 0);
        col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (e.id.equals(StreakSticker.chosenId())) {
            card.addView(ui.icon(Icons.CHECK, TtStyle.primary(), 24));
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StreakSticker.choose(e.item, e.fav ? "из избранных" : "из списка");
                Toasts.show(act, "Стикер выбран");
                dialog.dismiss();
                if (onChosen != null) onChosen.run();
            }
        });
        card.setLayoutParams(ui.matchWrap());
        return card;
    }

    private static String shortId(String id) {
        return id.length() > 10 ? id.substring(id.length() - 10) : id;
    }
}
