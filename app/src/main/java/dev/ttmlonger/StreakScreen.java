package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
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
                "Само отправляет стикер в чат, где серия дней вот-вот оборвётся. Не больше одного стикера "
                        + "в сутки на чат. Работает, пока TikTok запущен (в том числе в фоне)");
    }

    static String menuSummary() {
        return Prefs.streakAuto ? "Включено" : "Выключено";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(toggleRow("Автопродление огонька",
                "Нужен перезапуск TikTok. Пока не выберете стикер, ничего не отправляется. "
                        + "Функция экспериментальная: на вашей версии TikTok она ещё не проверялась",
                Prefs.streakAuto, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setStreakAuto(on);
                        Restart.offer(act);
                    }
                }));

        body.addView(toggleRow("Каждый день, а не только гаснущие",
                "Раз в сутки отправлять стикер во все чаты с огоньком после выбранного времени. "
                        + "Выключено: только когда огонёк уже серый",
                Prefs.streakDaily, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setStreakDaily(on);
                        StreakRunner.reschedule();
                    }
                }));

        timeRow = ui.menuRow("Время ежедневной отправки", StreakLogic.formatMinute(Prefs.streakMinute),
                "Нажмите и задайте время с точностью до минуты. Действует в режиме «каждый день»: "
                        + "мод отправит стикер именно в эту минуту, если TikTok запущен",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickTime();
                    }
                });
        body.addView(timeRow);

        stickerRow = ui.menuRow("Стикер для огоньков", stickerSummary(),
                "Выберите из избранных стикеров TikTok или из тех, что мод видел в чатах",
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

        body.addView(ui.menuRow("Забыть стикер", null, null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StreakSticker.forget();
                Toasts.show(act, "Стикер забыт");
                refresh();
            }
        }));

        body.addView(ui.menuRow("Проверить огоньки", "Только прочитать, ничего не отправляется", null,
                new View.OnClickListener() {
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

        report = ui.text("", 12, Ui.MUTED, false);
        report.setPadding(ui.dp(27), ui.dp(20), ui.dp(27), 0);
        report.setTextIsSelectable(true);
        body.addView(report, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
    }

    private static String stickerSummary() {
        return StreakSticker.summary();
    }

    /** Редактор времени: часы и минуты, 24 часа. */
    private void pickTime() {
        int m = Prefs.streakMinute;
        android.app.TimePickerDialog d = new android.app.TimePickerDialog(act,
                android.R.style.Theme_Material_Dialog_Alert, new android.app.TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(android.widget.TimePicker view, int hour, int minute) {
                Prefs.setStreakMinute(hour * 60 + minute);
                StreakRunner.reschedule();
                TextView sub = Ui.subtitleOf(timeRow);
                if (sub != null) sub.setText(StreakLogic.formatMinute(Prefs.streakMinute));
                if (!Prefs.streakDaily) {
                    Toasts.show(act, "Время сохранено. Оно действует в режиме «каждый день»");
                }
            }
        }, m / 60, m % 60, true);
        markOwn(d);
        d.show();
    }

    private void refresh() {
        TextView sub = Ui.subtitleOf(stickerRow);
        if (sub != null) sub.setText(stickerSummary());
        report.setText(StreakRunner.report()
                + "\nСкопируйте этот отчёт из «Диагностика» → «Скопировать кратко», если что-то не работает.");
    }
}
