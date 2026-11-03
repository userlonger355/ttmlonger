package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
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
    private LinearLayout peopleRow;
    private LinearLayout sendRow;
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

        body.addView(ui.header("Люди"));
        peopleRow = ui.menuRow(Icons.FLAME, "Кому продлевать",
                "Список людей с серией дней и галочки: можно только выбранным", StreakPeopleScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StreakPeopleScreen.show(act, new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                });
        body.addView(peopleRow);

        body.addView(ui.header("Сейчас"));
        sendRow = ui.row(Icons.SEND, sendTitle(), sendNote(), null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAll();
            }
        });
        body.addView(sendRow);

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

        body.addView(ui.row(Icons.COPY, "Скопировать сведения", "Что нашёл мод: если огонёк не работает, "
                + "пришлите это разработчику", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDetails();
            }
        }));
        refresh();
    }

    // ---------------------------------------------------------------- отправка всем

    private void sendAll() {
        if (StreakRunner.busy()) {
            Toasts.show(act, "Отправка уже идёт");
            return;
        }
        Toasts.show(act, "Смотрю, где есть огоньки…");
        StreakRunner.plan(new StreakRunner.Callback<StreakRunner.Plan>() {
            @Override
            public void on(StreakRunner.Plan p) {
                if (p.problem != null) {
                    message("Отправить всем", p.problem);
                } else {
                    confirm(p);
                }
            }
        });
    }

    private void message(String title, String text) {
        AlertDialog d = new AlertDialog.Builder(act, Ui.dialogTheme()).setTitle(title).setMessage(text)
                .setPositiveButton("Понятно", null).create();
        markOwn(d);
        d.show();
        Ui.style(d);
    }

    private void confirm(final StreakRunner.Plan p) {
        int n = p.targets.size();
        StringBuilder sb = new StringBuilder();
        if (p.guess) {
            sb.append("Не удалось определить, где есть огонёк. Стикер уйдёт во все известные чаты: ").append(n)
                    .append(".");
        } else {
            sb.append("Чатов с огоньком: ").append(n).append(".");
        }
        sb.append("\nСтикер: ").append(stickerSummary().toLowerCase(java.util.Locale.ROOT)).append(".");
        sb.append("\nОтправка идёт по одному чату раз в полторы секунды, около ")
                .append(Math.max(1, Math.round(n * 1.5f))).append(" с.");
        if (p.capped) sb.append("\nЗа раз отправляется не больше ").append(StreakRunner.ALL_MAX).append(" чатов.");
        AlertDialog d = new AlertDialog.Builder(act, Ui.dialogTheme()).setTitle("Отправить стикер всем?")
                .setMessage(sb.toString())
                .setPositiveButton("Отправить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        Toasts.show(act, "Отправляю…");
                        StreakRunner.sendAll(p, new StreakRunner.Callback<String>() {
                            @Override
                            public void on(String result) {
                                Toasts.show(act, result);
                                refresh();
                            }
                        });
                        refresh();
                    }
                }).setNegativeButton("Отмена", null).create();
        markOwn(d);
        d.show();
        Ui.style(d);
    }

    private void copyDetails() {
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = StreakRunner.details();
                } catch (Throwable t) {
                    text = "сведения не собрались: " + t;
                }
                final String copy = text;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", copy));
                            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
                        } catch (Throwable t) {
                            Toasts.show(act, "Не удалось скопировать");
                        }
                    }
                });
            }
        });
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

    private static String sendTitle() {
        return Prefs.streakOnlyPicked ? "Отправить выбранным сейчас" : "Отправить всем сейчас";
    }

    private static String sendNote() {
        return Prefs.streakOnlyPicked ? "Стикер уйдёт людям с галочкой, не дожидаясь времени"
                : "Стикер уйдёт во все чаты с огоньком, не дожидаясь времени";
    }

    private void refresh() {
        TextView sub = Ui.subtitleOf(stickerRow);
        if (sub != null) sub.setText(stickerSummary());
        TextView pv = Ui.valueOf(peopleRow);
        if (pv != null) pv.setText(StreakPeopleScreen.menuSummary());
        TextView st = Ui.titleOf(sendRow);
        if (st != null) st.setText(sendTitle());
        TextView sn = Ui.subtitleOf(sendRow);
        if (sn != null) sn.setText(sendNote());
        report.setText(StreakRunner.status());
    }
}
