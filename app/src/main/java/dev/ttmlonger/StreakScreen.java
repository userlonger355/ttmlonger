package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StreakScreen extends Screen {
    private final StreakSchedule schedule;
    private TextView summary;
    private TextView caption;
    private TextView result;
    private LinearLayout timeRow;
    private LinearLayout stickerRow;
    private LinearLayout peopleRow;
    private LinearLayout forgetRow;
    private LinearLayout checkRow;
    private boolean checking;
    private ModSwitch autoSwitch;

    private StreakScreen(Activity act) {
        super(act);
        schedule = new StreakSchedule(act);
    }

    static void show(Activity act) {
        StreakHooks.openTools();
        new StreakScreen(act).open("Огоньки", null);
    }

    static String menuSummary() {
        return Prefs.streakAuto ? "Включено" : "Выключено";
    }

    @Override
    void build(LinearLayout body) {
        LinearLayout hero = ui.panel(body);
        hero.setBackground(ui.wash(Ui.DARK ? 0xFF542D32 : 0xFFFFDBCB, Ui.DARK ? 0xFF302029 : 0xFFFFF0DD));
        hero.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(20));
        int ink = Ui.DARK ? 0xFFFFEADB : 0xFF4C251E;
        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ui.icon(Icons.FLAME, Ui.DARK ? 0xFFFFB584 : 0xFF9F4227, 30));
        TextView label = ui.medium("Автопродление", 16, ink);
        label.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        autoSwitch = new ModSwitch(act, Prefs.streakAuto);
        autoSwitch.setContentDescription("Автопродление огоньков");
        autoSwitch.setListener(on -> {
            Prefs.setStreakAuto(on);
            StreakRunner.async(() -> {
                StreakRunner.reschedule();
                if (on) StreakRunner.request(0);
            });
            refresh();
        });
        top.addView(autoSwitch);
        top.setOnClickListener(v -> autoSwitch.toggle());
        hero.addView(top);
        summary = ui.text("", 26, ink, true);
        summary.setPadding(0, ui.dp(20), 0, ui.dp(4));
        hero.addView(summary);
        caption = ui.text("", 14, ink, false);
        hero.addView(caption);

        body.addView(ui.header("Правила продления"));
        LinearLayout rules = ui.panel(body);
        timeRow = ui.menuRow(Icons.CLOCK, "Когда отправлять", "", null, v -> pickTime());
        rules.addView(timeRow);
        rules.addView(ui.divider());
        stickerRow = ui.menuRow(Icons.STICKER, "Стикер", "", null, v -> StickerPickScreen.show(act, this::refresh));
        rules.addView(stickerRow);
        forgetRow = ui.row(Icons.CLOSE, "Снять выбор стикера", null, null, false, v -> {
            StreakSticker.forget();
            refresh();
        });
        rules.addView(forgetRow);
        rules.addView(ui.divider());
        peopleRow = ui.menuRow(Icons.FLAME, "Кому продлевать", "", null, v -> StreakPeopleScreen.show(act, this::refresh));
        rules.addView(peopleRow);

        body.addView(ui.header("Действия с чатами"));
        LinearLayout actions = ui.panel(body);
        checkRow = ui.row(Icons.REFRESH, "Проверить огоньки", "Посмотреть, кому пора отправить стикер", null, false, v -> check());
        actions.addView(checkRow);
        actions.addView(ui.divider());
        actions.addView(ui.menuRow(Icons.SEND, "Отправить сейчас", "Проверить получателей и подтвердить", null, v -> sendAll()));
        result = ui.note("");
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(result);
        refresh();
        body.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
            if (focused) refresh();
        });
    }

    private void refresh() {
        boolean chosen = !StreakSticker.chosenId().isEmpty() || StreakSticker.has();
        int minute = schedule.minute(StreakHooks.liveOwner());
        summary.setText(!Prefs.streakAuto ? "Выключено" : !chosen ? "Выберите стикер"
                : minute >= 0 ? "Ежедневно в " + StreakLogic.formatMinute(minute) : "Когда огонёк сереет");
        caption.setText(!Prefs.streakAuto ? "Можно проверить чаты или отправить стикер вручную."
                : !chosen ? "Откройте избранные или выберите стикер отправкой в чате."
                : minute >= 0 ? "Действующим сериям с третьего дня, по времени телефона."
                : "Стикер уйдёт, когда серия с третьего дня ждёт продления.");
        Ui.subtitleOf(timeRow).setText(minute >= 0 ? "Каждый день в " + StreakLogic.formatMinute(minute) : "Когда огонёк станет серым");
        Ui.subtitleOf(stickerRow).setText(StreakSticker.summary());
        Ui.subtitleOf(peopleRow).setText(Prefs.streakOnlyPicked
                ? "Только отмеченные · " + StreakStore.pickedCount() : "Все чаты с действующей серией");
        forgetRow.setVisibility(chosen || StreakSticker.armed() ? View.VISIBLE : View.GONE);
    }

    private void pickTime() {
        String owner = StreakHooks.liveOwner();
        if (owner == null) {
            Toasts.show(act, "Откройте входящие TikTok, затем вернитесь к расписанию");
            return;
        }
        int minute = schedule.minute(owner);
        int initial = minute >= 0 ? minute : Prefs.streakMinute;
        TimePickerDialog picker = new TimePickerDialog(act, Ui.dialogTheme(),
                (v, hour, min) -> setTime(owner, hour * 60 + min), initial / 60, initial % 60, true);
        picker.setTitle("Время отправки");
        picker.setButton(DialogInterface.BUTTON_NEUTRAL, "Без расписания", (dialog, which) -> setTime(owner, -1));
        markOwn(picker);
        picker.show();
        Ui.style(picker);
    }

    private void setTime(String owner, int minute) {
        if (!owner.equals(StreakHooks.liveOwner())) {
            Toasts.show(act, "Аккаунт изменился. Откройте расписание заново.");
            return;
        }
        schedule.setMinute(owner, minute);
        if (minute >= 0 && !autoSwitch.isOn()) autoSwitch.toggle();
        StreakRunner.async(() -> {
            StreakRunner.reschedule();
            StreakRunner.request(0);
        });
        refresh();
    }

    private void check() {
        if (checking) return;
        checking = true;
        checkRow.setEnabled(false);
        result.setText("Проверяю текущий статус чатов…");
        StreakRunner.check(() -> {
            checking = false;
            if (!dialog.isShowing()) return;
            checkRow.setEnabled(true);
            result.setText(StreakRunner.checkSummary());
            refresh();
        });
    }

    private void sendAll() {
        if (checking || StreakRunner.busy()) return;
        checking = true;
        result.setText("Проверяю получателей…");
        StreakRunner.plan(plan -> {
            checking = false;
            if (!dialog.isShowing()) return;
            if (plan.problem != null) {
                result.setText(plan.problem);
                return;
            }
            result.setText("Действующих серий для отправки: " + plan.targets.size());
            String message = "Получателей: " + plan.targets.size() + ".\nСтикер: " + StreakSticker.summary()
                    + (plan.capped ? "\nЗа один раз доступно до " + StreakRunner.ALL_MAX + " чатов." : "");
            AlertDialog confirm = new AlertDialog.Builder(act, Ui.dialogTheme()).setTitle("Отправить стикер сейчас?")
                    .setMessage(message).setNegativeButton("Отмена", null)
                    .setPositiveButton("Отправить", (d, which) -> {
                        result.setText("Передаю стикер в TikTok…");
                        StreakRunner.sendAll(plan, status -> {
                            if (dialog.isShowing()) result.setText(status);
                        });
                    }).create();
            markOwn(confirm);
            confirm.show();
            Ui.style(confirm);
        });
    }
}
