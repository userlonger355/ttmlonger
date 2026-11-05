package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Перехват запросов»: человек открывает у себя Понравившиеся, Избранное и Репосты, убирает по одному
 * элементу, а мод записывает настоящие вызовы TikTok к серверу. По записи потом делается массовое удаление.
 */
final class ApiTapScreen extends Screen {
    private TextView status;

    private ApiTapScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ApiTapScreen(act).open("Перехват запросов",
                "Чтобы удалять лайки, избранное и репосты по-настоящему, нужно знать, как TikTok делает это сам. "
                        + "Мод записывает такие вызовы: адрес, имена и значения параметров, кто вызвал. "
                        + "Куки, токены и содержимое ответов не записываются.");
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Как записать"));
        body.addView(ui.note("1. Полностью закройте TikTok и откройте заново (хук ставится при запуске).\n"
                + "2. В своём профиле откройте «Понравившиеся», пролистайте вниз и уберите один лайк (потом верните).\n"
                + "3. Откройте «Избранное»: уберите одно видео из избранного (потом верните).\n"
                + "4. Откройте «Репосты»: уберите один репост (потом верните) и пролистайте список.\n"
                + "5. Сюда: «Скопировать запись» и отправьте мне."));
        body.addView(ui.header("Запись"));
        body.addView(toggleRow(Icons.SEARCH, "Перехват включён",
                "Хук ставится при запуске TikTok, поэтому после переключения TikTok нужно запустить заново",
                Prefs.apiTapOn, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setApiTapOn(on);
                        refresh();
                    }
                }));
        status = ui.text("", 14f, Ui.INK, false);
        status.setLineSpacing(0f, 1.15f);
        status.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(status, ui.matchWrap());
        refresh();
        body.addView(ui.row(Icons.COPY, "Скопировать запись",
                "Адреса и параметры запросов TikTok. Ваших сообщений и паролей там нет", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copy();
                    }
                }));
        body.addView(ui.row(Icons.SEARCH, "Обновить", "Показать, что записано на сейчас", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        refresh();
                    }
                }));
        body.addView(ui.row(Icons.COPY, "Очистить запись", "Начать запись заново", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ApiTap.clear();
                        refresh();
                        Toasts.show(act, "Запись очищена");
                    }
                }));
    }

    private void refresh() {
        if (status != null) status.setText(ApiTap.status());
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", "TTMLonger " + Targets.VERSION + " · Android "
                    + android.os.Build.VERSION.SDK_INT + "\n" + ApiTap.details()));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
