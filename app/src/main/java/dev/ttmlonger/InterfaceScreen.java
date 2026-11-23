package dev.ttmlonger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;

final class InterfaceScreen extends Screen {
    InterfaceScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new InterfaceScreen(act).open("Интерфейс и конфиденциальность", null);
    }

    @Override void build(LinearLayout body) {
        body.addView(ui.header("Конфиденциальность"));
        option(body, "clean", "Очищать ссылки TikTok", "Убирать метки отслеживания при копировании и системной отправке");
        option(body, "browser", "Внешние ссылки в системном браузере", "При нажатии на ссылку внутри WebView TikTok");
        option(body, "reader", "Ограниченный встроенный браузер", "Открывать внешние страницы без JavaScript и доступа к файлам. Системный браузер имеет приоритет");
        body.addView(ui.menuRow(Icons.GLOBE, "Открывать ссылки TikTok в приложении", "Настройки ссылок Android", null,
                v -> settings(true)));
        body.addView(ui.menuRow(Icons.EYE_OFF, "Контакты и геопозиция", "Изменить разрешения TikTok в Android", null,
                v -> settings(false)));
        body.addView(ui.header("Навигация"));
        body.addView(ui.menuRow(Icons.SLIDERS, "Порядок верхних вкладок", "Рекомендации остаются на месте", null,
                v -> TabOrder.editor(act, true)));
        body.addView(ui.menuRow(Icons.SLIDERS, "Порядок нижних вкладок", "Главная остаётся на месте", null,
                v -> TabOrder.editor(act, false)));
        option(body, "labels", "Скрывать названия вкладок", "Оставить значки и счётчик входящих");
        body.addView(ui.header("Скрыть кнопки в ленте"));
        for (FeedButtons.Button button : FeedButtons.Button.values()) option(body, button.key(), button.label, null);
        body.addView(ui.header("Жесты на видео"));
        FeedGestures.settings(this, body);
        body.addView(ui.header("Подтверждение действий"));
        option(body, "like", "Двойное нажатие для лайка", null);
        option(body, "follow", "Двойное нажатие для подписки", null);
        option(body, "repost", "Двойное нажатие для репоста", null);
        option(body, "share", "Двойное нажатие для «Поделиться»", null);
        body.addView(ui.note("Повторите нажатие на ту же кнопку в течение двух секунд."));
        body.addView(ui.header("Профиль"));
        option(body, "profile_copy", "Копировать данные профиля", "Удерживайте @имя в шапке профиля");
        option(body, "profile_counts", "Точные счётчики профиля", "Полные числа под именем пользователя");
        option(body, "profile_date", "Дата создания аккаунта", "Приблизительная дата из ID аккаунта");
        option(body, "profile_follow", "Статус подписки", "Показывать, когда владелец профиля подписан на вас");
        body.addView(ui.header("Публикация"));
        option(body, "details", "Сведения по нажатию на флаг", "Нажмите на строку сведений, чтобы скопировать её");
    }

    private void option(LinearLayout body, String key, String title, String note) {
        body.addView(toggleRow(-1, title, note, InterfaceHooks.on(key), on -> {
            InterfaceHooks.set(key, on);
            if (key.startsWith("hide_")) FeedButtons.refresh();
            if (key.equals("labels")) InterfaceHooks.labels(act);
        }));
    }

    private void settings(boolean links) {
        Uri pkg = Uri.fromParts("package", act.getPackageName(), null);
        if (links && Build.VERSION.SDK_INT >= 31) {
            try {
                act.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg));
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
        } catch (ActivityNotFoundException ignored) {
            Toasts.show(act, "Откройте настройки Android → Приложения → TikTok");
        }
    }
}
