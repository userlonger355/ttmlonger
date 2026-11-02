package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Разведка»: сбор названий в коде TikTok для функций «Невидимка» и «Просмотр заблокированного
 * профиля». Эти функции нельзя безопасно сделать наугад, поэтому сначала снимаются сведения.
 */
final class ReconScreen extends Screen {
    private TextView state;

    private ReconScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ReconScreen(act).open("Разведка кода",
                "Для «Невидимки» и просмотра заблокированного профиля нужно знать, какие места в TikTok за это "
                        + "отвечают. Мод соберёт названия из кода приложения, вам останется скопировать отчёт.");
    }

    static String menuSummary() {
        return Recon.running() ? "идёт" : Recon.report().isEmpty() ? "не запускалась" : "готова";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Разбор"));
        body.addView(ui.row(Icons.SEARCH, "Запустить разведку",
                "Займёт до пары минут, TikTok можно не закрывать", null, false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Recon.start(act.getApplicationContext());
                        poll();
                    }
                }));
        state = ui.text("", 14f, Ui.INK, false);
        state.setLineSpacing(0f, 1.15f);
        state.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(state, ui.matchWrap());
        body.addView(ui.row(Icons.COPY, "Скопировать отчёт",
                "Названия классов и методов TikTok и строки из его кода. Ваших данных в нём нет", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copy();
                    }
                }));
        body.addView(ui.note("Скопируйте отчёт после слов «готово» и пришлите разработчику: по нему подключаются "
                + "«Скрыть мой онлайн», «Не показывать «печатает…»», «Не сообщать о просмотре профилей» и "
                + "просмотр заблокированного профиля."));
        refresh();
    }

    private void refresh() {
        state.setText("Состояние: " + Recon.state());
    }

    private void poll() {
        refresh();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dialog == null || !dialog.isShowing()) return;
                refresh();
                if (Recon.running()) poll();
            }
        }, 1500L);
    }

    private void copy() {
        if (Recon.report().isEmpty()) {
            Toasts.show(act, "Сначала запустите разведку");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            String text = "TTMLonger " + Targets.VERSION + " · Android " + android.os.Build.VERSION.SDK_INT + "\n"
                    + Recon.report();
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", text));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
