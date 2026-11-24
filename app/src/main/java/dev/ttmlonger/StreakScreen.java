package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StreakScreen extends Screen {
    private final StreakSchedule schedule;
    private TextView summary;
    private TextView caption;
    private TextView result,overviewTime;
    private final TextView[] metrics=new TextView[4];
    private LinearLayout timeRow;
    private LinearLayout stickerRow;
    private LinearLayout peopleRow;
    private LinearLayout forgetRow;
    private LinearLayout checkRow;
    private boolean checking;
    private ModSwitch autoSwitch;
    private StreakChatPreview chatPreview;

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
        timeRow = ui.row(Icons.CLOCK, "Время отправки", "", null, true, v -> pickTime());
        rules.addView(timeRow);
        rules.addView(ui.divider());
        stickerRow = ui.row(Icons.STICKER, "Стикер для отправки", "", null, true, v -> StickerPickScreen.show(act, this::refresh));
        rules.addView(stickerRow);
        forgetRow = ui.row(Icons.CLOSE, "Снять выбор стикера", "Вернуть выбор по умолчанию", null, false, v -> {
            StreakSticker.forget();
            refresh();
        });
        rules.addView(forgetRow);
        rules.addView(ui.divider());
        peopleRow = ui.row(Icons.FLAME, "Кому продлевать", "", null, true, v -> StreakPeopleScreen.show(act, this::refresh));
        rules.addView(peopleRow);

        body.addView(ui.header("Состояние серий"));
        LinearLayout dashboard=ui.panel(body);dashboard.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(10));
        String[] labels={"Активные","Серые","Отправлено сегодня","К отправке"};
        for(int r=0;r<2;r++){LinearLayout line=new LinearLayout(act);for(int c=0;c<2;c++){int i=r*2+c;LinearLayout cell=new LinearLayout(act);cell.setOrientation(1);cell.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));cell.setBackground(ui.round(Ui.FIELD,15));metrics[i]=ui.text("—",24,i==1?Ui.MUTED:Ui.INK,true);cell.addView(metrics[i]);cell.addView(ui.text(labels[i],12,Ui.MUTED,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));line.addView(cell,lp);}dashboard.addView(line,ui.matchWrap());}
        overviewTime=ui.note("Нажмите «Проверить огоньки» для получения данных.");dashboard.addView(overviewTime);

        body.addView(ui.header("Предпросмотр отправки"));
        chatPreview=new StreakChatPreview(act);
        LinearLayout.LayoutParams previewParams=ui.matchWrap();previewParams.setMargins(ui.dp(14),ui.dp(4),ui.dp(14),ui.dp(6));body.addView(chatPreview,previewParams);
        body.addView(ui.header("Действия с чатами"));
        LinearLayout actions = ui.panel(body);
        checkRow = ui.row(Icons.REFRESH, "Проверить огоньки", "Посмотреть, кому пора отправить стикер", null, false, v -> check());
        actions.addView(checkRow);
        actions.addView(ui.divider());
        actions.addView(ui.row(Icons.SEND, "Отправить сейчас", "Проверить получателей и подтвердить", null, true, v -> sendAll()));
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
                : !chosen ? "Откройте обычные или избранные стикеры в чате."
                : minute >= 0 ? "Действующим сериям с третьего дня, по времени телефона."
                : "Стикер уйдёт, когда серия с третьего дня ждёт продления.");
        int custom=schedule.count(StreakHooks.liveOwner());
        Ui.subtitleOf(timeRow).setText((minute >= 0 ? "Каждый день в " + StreakLogic.formatMinute(minute) : "Когда огонёк станет серым")+(custom>0?" · личных: "+custom:""));
        Ui.subtitleOf(stickerRow).setText(chosen?"Стикер выбран":StreakSticker.armed()?"Ожидание стикера из чата":"Не выбран");
        Ui.subtitleOf(peopleRow).setText(Prefs.streakOnlyPicked
                ? "Только отмеченные · " + StreakStore.pickedCount() : "Все чаты с действующей серией");
        forgetRow.setVisibility(chosen || StreakSticker.armed() ? View.VISIBLE : View.GONE);
        if(chatPreview!=null)chatPreview.update(Prefs.streakAuto,minute);
        StreakRunner.Overview o=StreakRunner.overview(StreakHooks.liveOwner());if(overviewTime!=null){int[] nums=o==null?null:new int[]{o.active,o.grey,o.sent,o.due};for(int i=0;i<metrics.length;i++)if(metrics[i]!=null)metrics[i].setText(nums==null?"—":String.valueOf(nums[i]));String status=o==null?"Нажмите «Проверить огоньки»":("Последняя проверка: "+new java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(o.at))+(o.unknown>0?" · не прочитано: "+o.unknown:""));if(StreakRunner.holdRemaining()>0)status+=" · пауза после ошибок";overviewTime.setText(status);}
    }

    private void pickTime() {
        String owner = StreakHooks.liveOwner();
        if (owner == null) {
            Toasts.show(act, "Откройте входящие TikTok, затем вернитесь к расписанию");
            return;
        }
        int minute = schedule.minute(owner);
        int initial = minute >= 0 ? minute : Prefs.streakMinute;
        StreakTimeSheet.show(act, initial, value -> setTime(owner,value));
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
            result.setText("Серий к отправке: " + plan.targets.size());
            String message = "Получателей: " + plan.targets.size() + ".\nСтикер: " + StreakSticker.summary()
                    + "\nПовторные отправки в этот день пропускаются."
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
