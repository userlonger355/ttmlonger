package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Невидимка»: не показывать другим, что вы печатаете, и не сообщать серверу, что вы в сети. Просмотры
 * профиля отдельным запросом не уходят, поэтому спрятать их модом нельзя: экран говорит об этом прямо.
 */
final class InvisibleScreen extends Screen {
    private TextView status;

    private InvisibleScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new InvisibleScreen(act).open("Невидимка",
                "Что TikTok сообщает другим про вас. Хуки ставятся при запуске, поэтому после обновления мода "
                        + "TikTok надо запустить заново; сами переключатели действуют сразу.");
    }

    static String menuSummary() {
        return Invisible.enabledCount() + " из 2";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Чаты"));
        body.addView(toggleRow(Icons.EYE_OFF, "Не показывать «печатает…»",
                "Собеседник не увидит, что вы набираете сообщение. Мод убирает тело запроса «печатает», и сервер "
                        + "получает пустой запрос",
                Prefs.hideTyping, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideTyping(on);
                        refresh();
                    }
                }));
        body.addView(toggleRow(Icons.EYE_OFF, "Не сообщать, что я в сети",
                "Мод пропускает отчёт TikTok об активности, и «в сети» у друзей может перестать обновляться. "
                        + "Сервер видит и само подключение к сообщениям: его мод не трогает, иначе сообщения "
                        + "перестали бы приходить",
                Prefs.hideOnline, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideOnline(on);
                        refresh();
                    }
                }));

        body.addView(ui.header("Состояние"));
        status = ui.text("", 14f, Ui.INK, false);
        status.setLineSpacing(0f, 1.15f);
        status.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(status, ui.matchWrap());
        refresh();
        body.addView(ui.row(Icons.COPY, "Скопировать сведения", "Для разработчика: что мод нашёл и сколько раз сработал "
                + "(имён и сообщений там нет)", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }));

        body.addView(ui.header("Просмотры профиля"));
        body.addView(ui.note("Спрятать их модом нельзя: в коде TikTok нет отдельного отчёта о просмотре профиля, "
                + "сервер узнаёт о нём из самого запроса страницы. Это настройка самого TikTok («Просмотры профиля»: "
                + "по описанию TikTok, если выключить её, вас не покажут в чужих списках гостей, а свой список "
                + "вы тоже не увидите)."));
    }

    private void refresh() {
        if (status != null) status.setText(Invisible.status());
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", "TTMLonger " + Targets.VERSION + " · Android "
                    + android.os.Build.VERSION.SDK_INT + "\n" + Invisible.details()));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
