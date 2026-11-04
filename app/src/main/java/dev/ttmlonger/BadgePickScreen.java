package dev.ttmlonger;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Какие бейджики у одного аккаунта: список с картинками и галочками, не больше трёх. */
final class BadgePickScreen extends Screen {
    private final String uid;
    private final String name;
    private final Runnable onChanged;
    private final List<ImageView> checks = new ArrayList<>();
    private TextView summary;

    private BadgePickScreen(Activity act, String uid, String name, Runnable onChanged) {
        super(act);
        this.uid = uid;
        this.name = name;
        this.onChanged = onChanged;
    }

    static void show(Activity act, String uid, String name, Runnable onChanged) {
        new BadgePickScreen(act, uid, name, onChanged).open("Бейджики аккаунта",
                name + ". Можно выбрать до " + BadgeSet.PER_ACCOUNT + ".");
    }

    @Override
    void build(LinearLayout body) {
        summary = ui.text("", 14f, Ui.MUTED, false);
        summary.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4));
        body.addView(summary, ui.matchWrap());
        for (int i = 0; i < BadgeSet.ALL.length; i++) body.addView(row(BadgeSet.ALL[i]));
        body.addView(ui.row(Icons.TRASH, "Убрать все бейджики", null, null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BadgeStore.remove(uid);
                changed();
            }
        }));
        refresh();
    }

    private LinearLayout row(final BadgeSet.Def def) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        card.setClickable(true);
        card.setBackground(ui.ripple());

        ImageView pic = new ImageView(act);
        pic.setImageDrawable(new BadgeArt(def));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32));
        pl.setMarginEnd(ui.dp(14));
        card.addView(pic, pl);

        card.addView(ui.medium(def.title, 16f, Ui.INK),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView ck = ui.icon(Icons.CHECK, TtStyle.primary(), 24);
        ck.setTag(def.id);
        checks.add(ck);
        card.addView(ck);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int r = BadgeStore.toggle(uid, name, def.id);
                if (r == 0) {
                    Toasts.show(act, "Не больше " + BadgeSet.PER_ACCOUNT + " бейджиков на аккаунт");
                    return;
                }
                changed();
            }
        });
        card.setLayoutParams(ui.matchWrap());
        return card;
    }

    private void changed() {
        BadgeStore.saveAsync();
        refresh();
        if (onChanged != null) onChanged.run();
    }

    private void refresh() {
        BadgeStore.Rule rule = BadgeStore.rule(uid);
        for (ImageView ck : checks) {
            ck.setVisibility(rule != null && rule.ids.contains((String) ck.getTag()) ? View.VISIBLE : View.INVISIBLE);
        }
        summary.setText(rule == null ? "Сейчас без бейджиков" : "Сейчас: " + BadgeSet.titles(rule.ids));
    }
}
