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
        body.addView(toggleRow(Icons.EYE_OFF, "Пропускать рекламу",
                "Фильтрует рекламные ролики и рекламные карточки поиска до показа. Действует на страницы, которые придут после "
                        + "включения: обновите ленту, чтобы увидеть результат",
                Prefs.hideAds, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideAds(on);
                    }
                }));

        if(SponsorSegments.available()){
        body.addView(ui.header("Авторская реклама в видео"));
        body.addView(toggleRow(Icons.VIDEO,"Пропускать размеченные отрезки",
                "Внешний сервис margy.narez.xyz получает только ID текущего видео. Не отправляет учётные данные; звук не скачивается. Выключено по умолчанию.",
                SponsorSegments.enabled(act),on->SponsorSegments.set(act,on)));
        body.addView(ui.note("Если сервис знает рекламный отрезок, мод перематывает его и предлагает вернуть пропущенное. Без разметки воспроизведение не меняется."));
        }
        body.addView(ui.header("Фильтры рекламы"));
        body.addView(toggleRow(-1, "Рекламные видео", "Посты с явной отметкой рекламы",
                Prefs.hideAdVideos, on -> { Prefs.setAdVideos(on); }));
        body.addView(toggleRow(-1, "Рекламные карточки", "Карточки PreciseAd, в том числе без видео",
                Prefs.hideAdCards, on -> { Prefs.setAdCards(on); }));
        body.addView(toggleRow(-1, "AI-реклама", "Карточки AiAdCard, если они присутствуют в ленте",
                Prefs.hideAdAiCards, on -> { Prefs.setAdAiCards(on); }));
        body.addView(ui.note("Фильтры работают при включённом общем переключателе рекламы. Обновите ленту после изменения."));

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
