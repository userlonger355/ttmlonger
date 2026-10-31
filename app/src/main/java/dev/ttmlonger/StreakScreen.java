package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Огонёк»: автопродление серии дней в личных сообщениях. Мод сам отправляет стикер в чат, где
 * огонёк гаснет. Включение требует перезапуска TikTok: хуки на службу огоньков ставятся при запуске.
 */
final class StreakScreen extends Screen {
    private TextView report;
    private LinearLayout stickerRow;
    private LinearLayout timeRow;

    private StreakScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new StreakScreen(act).open("Огонёк",
                "Мод сам отправляет стикер в чат, где серия дней вот-вот оборвётся: не больше одного стикера "
                        + "в сутки на чат. Работает, пока TikTok запущен, в том числе в фоне. "
                        + "Функция экспериментальная.");
    }

    static String menuSummary() {
        return Prefs.streakAuto ? "Вкл" : "Выкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Автопродление"));
        body.addView(toggleRow(Icons.FLAME, "Продлевать огонёк",
                "Нужен перезапуск TikTok. Пока не выбран стикер, ничего не отправляется",
                Prefs.streakAuto, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setStreakAuto(on);
                        Restart.offer(act);
                        refresh();
                    }
                }));

        body.addView(toggleRow(Icons.CLOCK, "Каждый день",
                "Раз в сутки отправлять стикер во все чаты с огоньком в выбранное время. "
                        + "Выключено: только когда огонёк уже серый",
                Prefs.streakDaily, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setStreakDaily(on);
                        StreakRunner.reschedule();
                    }
                }));

        timeRow = ui.menuRow(-1, "Время отправки", "Действует в режиме «Каждый день»",
                StreakLogic.formatMinute(Prefs.streakMinute), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickTime();
                    }
                });
        body.addView(timeRow);

        body.addView(ui.header("Стикер"));
        stickerRow = ui.menuRow(Icons.STICKER, "Стикер для огоньков", stickerSummary(), null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StickerPickScreen.show(act, new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                });
        body.addView(stickerRow);

        body.addView(ui.row(Icons.TRASH, "Забыть стикер", null, null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StreakSticker.forget();
                Toasts.show(act, "Стикер забыт");
                refresh();
            }
        }));

        body.addView(ui.header("Состояние"));
        report = ui.text("", 14f, Ui.INK, false);
        report.setLineSpacing(0f, 1.15f);
        report.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(report, ui.matchWrap());

        body.addView(ui.row(Icons.REFRESH, "Проверить огоньки", "Только прочитать, ничего не отправляется", null,
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!Prefs.streakAuto) {
                            Toasts.show(act, "Сначала включите автопродление и перезапустите TikTok");
                            return;
                        }
                        Toasts.show(act, "Проверяю…");
                        StreakRunner.check(new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                }));
        refresh();
    }

    private static String stickerSummary() {
        String s = StreakSticker.summary();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Редактор времени: часы и минуты, 24 часа. */
    private void pickTime() {
        int m = Prefs.streakMinute;
        android.app.TimePickerDialog d = new android.app.TimePickerDialog(act,
                Ui.dialogTheme(), new android.app.TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(android.widget.TimePicker view, int hour, int minute) {
                Prefs.setStreakMinute(hour * 60 + minute);
                StreakRunner.reschedule();
                TextView val = Ui.valueOf(timeRow);
                if (val != null) val.setText(StreakLogic.formatMinute(Prefs.streakMinute));
                if (!Prefs.streakDaily) {
                    Toasts.show(act, "Время сохранено. Оно действует в режиме «Каждый день»");
                }
            }
        }, m / 60, m % 60, true);
        markOwn(d);
        d.show();
        Ui.style(d);
    }

    private void refresh() {
        TextView sub = Ui.subtitleOf(stickerRow);
        if (sub != null) sub.setText(stickerSummary());
        report.setText(StreakRunner.status());
    }
}
