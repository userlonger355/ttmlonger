package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** «Диагностика»: отчёт о том, что мод видит на экране TikTok, с кнопками «Скопировать» и «Отправить». */
final class DiagScreen extends Screen {
    private String report;
    private String shortReport;
    private TextView out;

    private DiagScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new DiagScreen(act).open("Диагностика",
                "Откройте нужную страницу TikTok, нажмите два пальца на 1,2 секунды, «Диагностика»: "
                        + "отчёт покажет, что мод видит на этой странице");
    }

    @Override
    void build(LinearLayout body) {
        report = Diag.report(act);
        shortReport = Diag.shortReport(act);

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12));
        buttons.addView(button("Скопировать", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }), weight());
        buttons.addView(button("Отправить", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        }), weight());
        body.addView(buttons, ui.matchWrap());

        LinearLayout buttons2 = new LinearLayout(act);
        buttons2.setOrientation(LinearLayout.HORIZONTAL);
        buttons2.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        buttons2.addView(button("Скопировать кратко", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(shortReport, "Краткий отчёт скопирован. Вставьте его в чат");
            }
        }), weight());
        body.addView(buttons2, ui.matchWrap());

        out = ui.text(report, 11, Ui.MUTED, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(24));
        body.addView(out, ui.matchWrap());
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(ui.dp(7), 0, ui.dp(7), 0);
        return lp;
    }

    private TextView button(String label, View.OnClickListener click) {
        TextView b = ui.text(label, 17, Ui.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(ui.dp(12), ui.dp(15), ui.dp(12), ui.dp(15));
        b.setBackground(ui.round(Ui.CARD, 24));
        b.setClickable(true);
        b.setOnClickListener(click);
        return b;
    }

    private void copy() {
        copyText(report, "Отчёт скопирован. Вставьте его в чат");
    }

    private void copyText(String text, String done) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", text));
            Toast.makeText(act, done, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            FlagStore.log(t);
            Toast.makeText(act, "Не удалось скопировать", Toast.LENGTH_LONG).show();
        }
    }

    private void send() {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "TTMLonger " + Targets.VERSION + ": отчёт");
            i.putExtra(Intent.EXTRA_TEXT, report);
            act.startActivity(Intent.createChooser(i, "Отправить отчёт"));
        } catch (Throwable t) {
            FlagStore.log(t);
            Toast.makeText(act, "Не удалось открыть выбор приложения", Toast.LENGTH_LONG).show();
        }
    }
}
