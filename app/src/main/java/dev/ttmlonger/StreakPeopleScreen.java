package dev.ttmlonger;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * «Люди»: кому продлевать огонёк. Список людей, с которыми есть серия (с третьего дня), с числом дней,
 * статусом огонька и галочкой. Режим «Только выбранным» ограничивает автопродление и кнопку отправки
 * отмеченными людьми; выключенный режим продлевает всем, как раньше.
 */
final class StreakPeopleScreen extends Screen {
    private final Runnable onChanged;
    private TextView summary;
    private TextView status;
    private LinearLayout list;
    private final List<StreakRunner.Person> shown = new ArrayList<>();
    private boolean loading;

    private StreakPeopleScreen(Activity act, Runnable onChanged) {
        super(act);
        this.onChanged = onChanged;
    }

    static void show(Activity act, Runnable onChanged) {
        new StreakPeopleScreen(act, onChanged).open("Люди",
                "Отметьте тех, кому продлевать огонёк. Имена берутся из списка друзей TikTok.");
    }

    static String menuSummary() {
        return Prefs.streakOnlyPicked ? "Выбрано " + StreakStore.pickedCount() : "Все";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(toggleRow(Icons.FLAME, "Только выбранным",
                "Выключено: стикер идёт всем, у кого есть огонёк. Включено: только людям с галочкой",
                Prefs.streakOnlyPicked, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setStreakOnlyPicked(on);
                        refreshSummary();
                        changed();
                    }
                }));

        body.addView(ui.row(Icons.CHECK, "Отметить всех", "Всех, кто сейчас в списке", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        List<String> all = new ArrayList<>();
                        for (StreakRunner.Person p : shown) all.add(p.conv);
                        StreakStore.pickMany(all);
                        saveAndRedraw();
                    }
                }));
        body.addView(ui.row(Icons.CLOSE, "Снять все галочки", null, null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StreakStore.clearPicked();
                saveAndRedraw();
            }
        }));

        status = ui.header("Люди");
        body.addView(status);
        summary = ui.text("", 14f, Ui.MUTED, false);
        summary.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8));
        body.addView(summary, ui.matchWrap());

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, ui.matchWrap());

        body.addView(ui.row(Icons.REFRESH, "Обновить список", "Заново прочитать огоньки у TikTok", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        load();
                    }
                }));
        refreshSummary();
        load();
    }

    private void changed() {
        if (onChanged != null) onChanged.run();
    }

    private void load() {
        if (loading) return;
        loading = true;
        status.setText("Люди · загружаю…");
        StreakRunner.people(new StreakRunner.Callback<StreakRunner.People>() {
            @Override
            public void on(StreakRunner.People p) {
                loading = false;
                if (dialog == null || !dialog.isShowing()) return;
                shown.clear();
                shown.addAll(p.list);
                fill(p);
            }
        });
    }

    private void fill(StreakRunner.People p) {
        list.removeAllViews();
        status.setText(p.list.isEmpty() ? "Люди" : "Люди · " + p.list.size());
        if (p.problem != null) {
            list.addView(ui.note(p.problem));
            refreshSummary();
            return;
        }
        if (p.list.isEmpty()) {
            list.addView(ui.note("Людей с огоньком не видно (проверено чатов: " + p.known
                    + (p.unreadable > 0 ? ", не прочитано: " + p.unreadable : "") + "). Огонёк появляется со "
                    + "третьего дня подряд."));
            refreshSummary();
            return;
        }
        for (StreakRunner.Person person : p.list) list.addView(row(person));
        if (p.unreadable > 0) {
            list.addView(ui.note("Не удалось прочитать огонёк у чатов: " + p.unreadable));
        }
        refreshSummary();
    }

    private LinearLayout row(final StreakRunner.Person person) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        card.setClickable(true);
        card.setBackground(ui.ripple());

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.medium(person.name, 16f, Ui.INK));
        TextView sub = ui.text(subtitle(person), 13.5f, Ui.MUTED, false);
        sub.setPadding(0, ui.dp(2), 0, 0);
        col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final FrameLayout box = new FrameLayout(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28));
        lp.setMarginStart(ui.dp(12));
        card.addView(box, lp);
        draw(box, StreakStore.isPicked(person.conv));

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !StreakStore.isPicked(person.conv);
                StreakStore.setPicked(person.conv, now);
                draw(box, now);
                saveAsync();
                refreshSummary();
                changed();
            }
        });
        card.setLayoutParams(ui.matchWrap());
        return card;
    }

    /** Галочка: заполненный круг цвета акцента с белой галочкой или пустой круг. */
    private void draw(FrameLayout box, boolean checked) {
        box.removeAllViews();
        box.setBackground(ui.round(checked ? TtStyle.primary() : Ui.FIELD, 14));
        if (checked) {
            View tick = ui.icon(Icons.CHECK, Color.WHITE, 18);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER);
            box.addView(tick, p);
        }
    }

    private static String subtitle(StreakRunner.Person p) {
        StringBuilder sb = new StringBuilder(StreakLogic.days(p.streak));
        String s = StreakLogic.statusLabel(p.status);
        if (!s.isEmpty()) sb.append(" · ").append(s);
        if (p.sentToday) sb.append(" · стикер уже отправлен");
        return sb.toString();
    }

    private void saveAsync() {
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                StreakStore.flush();
            }
        });
    }

    private void saveAndRedraw() {
        saveAsync();
        list.removeAllViews();
        StreakRunner.People again = new StreakRunner.People(new ArrayList<>(shown), shown.size(), 0, null);
        fill(again);
        changed();
    }

    private void refreshSummary() {
        int picked = StreakStore.pickedCount();
        String mode = Prefs.streakOnlyPicked ? "Продлеваю только отмеченным." : "Сейчас продлеваю всем: режим «Только выбранным» выключен.";
        String warn = Prefs.streakOnlyPicked && picked == 0 ? " Никто не отмечен, поэтому ничего не отправится." : "";
        summary.setText("Отмечено: " + picked + ". " + mode + warn);
    }
}
