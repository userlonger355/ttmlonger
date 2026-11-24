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
 * «Реклама и LIVE»: автоскрытие рекламы в ленте и скрытие кнопки LIVE. Работает сразу, перезапуск
 * TikTok не нужен: реклама убирается из тех страниц ленты, что приходят после включения.
 */
final class AdsScreen extends Screen {

    private AdsScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new AdsScreen(act).open("Реклама и LIVE",
                "Мод убирает рекламные ролики из ленты ещё до показа и прячет кнопку LIVE в левом верхнем "
                        + "углу главной.");
    }

    static String menuSummary() {
        return Prefs.hideAds || Prefs.hideLiveButton || Prefs.hideLiveRooms ? "Вкл" : "Выкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Реклама"));
        body.addView(toggleRow(Icons.EYE_OFF, "Скрывать рекламу в ленте",
                "Рекламные ролики не попадают в ленту. Действует на страницы ленты, которые придут после "
                        + "включения: обновите ленту, чтобы увидеть результат",
                Prefs.hideAds, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideAds(on);
                    }
                }));

        body.addView(ui.header("LIVE"));
        body.addView(toggleRow(Icons.EYE_OFF, "Скрыть кнопку LIVE",
                "Кнопка в левом верхнем углу главной прячется, остальная шапка не сдвигается",
                Prefs.hideLiveButton, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideLiveButton(on);
                        LiveButton.kick();
                    }
                }));
        body.addView(toggleRow(-1, "Убирать эфиры из ленты",
                "Прямые эфиры, которые TikTok подмешивает среди видео. Ваши собственные посты не трогаются",
                Prefs.hideLiveRooms, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideLiveRooms(on);
                    }
                }));

        body.addView(ui.note("Изменения действуют на новые страницы ленты. Обновите ленту."));
    }
}
