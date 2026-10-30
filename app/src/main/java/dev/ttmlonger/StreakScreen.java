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
                    }
                }));

        timeRow = ui.menuRow("Время ежедневной отправки", StreakLogic.formatMinute(Prefs.streakMinute),
                "Нажмите, чтобы сменить: 12:00, 18:00, 20:00, 22:00. Действует только в режиме «каждый день»",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setStreakMinute(StreakLogic.nextChoice(Prefs.streakMinute));
                        TextView sub = Ui.subtitleOf(timeRow);
                        if (sub != null) sub.setText(StreakLogic.formatMinute(Prefs.streakMinute));
                    }
                });
        body.addView(timeRow);

        stickerRow = ui.menuRow("Стикер для огоньков", stickerSummary(),
                "Мод берёт настоящий стикер, когда вы отправляете его в чате. Нажмите здесь и отправьте "
                        + "любой стикер в течение 5 минут, чтобы выбрать другой",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!Prefs.streakAuto) {
                            Toasts.show(act, "Сначала включите автопродление и перезапустите TikTok");
                            return;
                        }
                        StreakSticker.arm();
                        Toasts.show(act, "Отправьте любой стикер в чате: мод его запомнит");
                        refresh();
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
        return StreakSticker.status();
    }

    private void refresh() {
        TextView sub = Ui.subtitleOf(stickerRow);
        if (sub != null) sub.setText(stickerSummary());
        report.setText(StreakRunner.report()
                + "\nСкопируйте этот отчёт из «Диагностика» → «Скопировать кратко», если что-то не работает.");
    }
}
