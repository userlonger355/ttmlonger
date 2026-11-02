package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * «Возможности»: функции, которые TikTok уже содержит, но не включил вам (голосовые в личных,
 * длинные репосты, хештеги без лимита, «Прочитать все», 9 фото в комментарии, телесуфлёр). У каждой
 * свой выключатель. Флаги читаются при запуске приложения, поэтому после смены нужен перезапуск TikTok.
 */
final class UnlocksScreen extends Screen {
    private UnlocksScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new UnlocksScreen(act).open("Возможности",
                "Функции, которые уже есть в TikTok, но выключены для вашего аккаунта. Мод включает их, "
                        + "когда приложение спрашивает об этом у себя.");
    }

    static String menuSummary() {
        return Unlocks.onCount() + " из " + (Unlocks.ALL.length + 1);
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("В ленте"));
        body.addView(toggleRow(Icons.CLOCK, "Дата публикации", "Время публикации на видео в ленте",
                Prefs.vtTime, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setVtTime(on);
                        VideoInfo.kick();
                    }
                }));

        body.addView(ui.header("Чаты"));
        body.addView(groupRow("voice_dm"));
        body.addView(groupRow("read_all"));

        body.addView(ui.header("Публикации и комментарии"));
        body.addView(groupRow("long_repost"));
        body.addView(groupRow("hashtags"));
        body.addView(groupRow("photos9"));

        body.addView(ui.header("Камера"));
        body.addView(groupRow("teleprompter"));

        body.addView(ui.note("Перезапустите TikTok после смены: приложение читает эти настройки при запуске. "
                + "Если функции нет у вас на экране, значит её ещё нет в этой версии TikTok или её прячет "
                + "сервер: мод может включить только то, что есть в самом приложении. Свои подмены на экране "
                + "флагов главнее этих выключателей."));
        body.addView(ui.row(Icons.REFRESH, "Перезапустить TikTok", "Приложение закроется и откроется заново", null,
                false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Restart.offer(act);
                    }
                }));
    }

    private LinearLayout groupRow(final String id) {
        final Unlocks.Group g = Unlocks.byId(id);
        return toggleRow(g.icon, g.title, g.note, Unlocks.on(id), new ModSwitch.Listener() {
            @Override
            public void onChanged(boolean on) {
                Prefs.setUnlock(id, on);
            }
        });
    }
}
