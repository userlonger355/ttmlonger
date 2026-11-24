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

    private InvisibleScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new InvisibleScreen(act).open("Невидимка",
                "Настройки видимости вашей активности. Действуют сразу.");
    }

    static String menuSummary() {
        return Invisible.enabledCount() + " из 2";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Чаты"));
        body.addView(toggleRow(Icons.EYE_OFF, "Не показывать «печатает…»",
                "Мод пытается скрыть индикатор набора сообщения.",
                Prefs.hideTyping, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideTyping(on);
                    }
                }));
        body.addView(toggleRow(Icons.EYE_OFF, "Не сообщать, что я в сети",
                "Мод пытается скрыть статус активности. TikTok может определять его на сервере.",
                Prefs.hideOnline, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideOnline(on);
                    }
                }));

        body.addView(ui.header("Просмотры профиля"));
        body.addView(ui.note("Спрятать их модом нельзя: в коде TikTok нет отдельного отчёта о просмотре профиля, "
                + "сервер узнаёт о нём из самого запроса страницы. Это настройка самого TikTok («Просмотры профиля»: "
                + "по описанию TikTok, если выключить её, вас не покажут в чужих списках гостей, а свой список "
                + "вы тоже не увидите)."));
    }

}
