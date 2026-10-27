package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Главная страница «Настройки мода». */
final class ModMenu extends Screen {
    private LinearLayout regionRow;

    private ModMenu(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ModMenu(act).open("Настройки мода", "TTMLonger " + Targets.VERSION);
    }

    @Override
    void build(LinearLayout body) {
        regionRow = ui.menuRow("Регион", regionSummary(), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RegionSheet.show(act, new Runnable() {
                    @Override
                    public void run() {
                        TextView sub = Ui.subtitleOf(regionRow);
                        if (sub != null) sub.setText(regionSummary());
                    }
                });
            }
        });
        body.addView(regionRow);

        body.addView(ui.menuRow("Внешний вид", "Цветовой акцент, Amoled-тема, иконка приложения", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AppearanceScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow("Скачивание", "Видео без водяного знака, любое качество, стикеры", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DownloadScreen.show(act);
                    }
                }));

        body.addView(toggleRow("Баннер профиля",
                "Включает в TikTok установку баннера в профиле («Изменить профиль»). "
                        + "Нужен перезапуск TikTok",
                FlagStore.allOn(Targets.BANNER_FLAGS), new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        FlagStore.setAll(Targets.BANNER_FLAGS, on);
                        Restart.offer(act);
                    }
                }));

        body.addView(toggleRow("Голосовые комментарии",
                "Включает в TikTok запись голосового комментария. Нужен перезапуск TikTok",
                FlagStore.allOn(Targets.VOICE_COMMENT_FLAGS), new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        FlagStore.setAll(Targets.VOICE_COMMENT_FLAGS, on);
                        Restart.offer(act);
                    }
                }));

        body.addView(ui.menuRow("Настройки разработчика TikTok (A/B)", "Просмотр и настройка флагов",
                "Внутренние флаги функций TikTok для тестирования. "
                        + "Позволяет вручную переключать экспериментальные функции",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FlagsScreen.show(act);
                    }
                }));

        body.addView(toggleRow("Подсказка при запуске",
                "Короткое уведомление при открытии TikTok: видно, что мод загрузился",
                Prefs.startupToast, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setStartupToast(on);
                    }
                }));

        body.addView(ui.menuRow("Диагностика", "Отчёт о том, что мод видит на экране", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DiagScreen.show(act);
                    }
                }));

        TextView foot = ui.text(FlagHooks.status(), 13, Ui.MUTED, false);
        foot.setPadding(ui.dp(27), ui.dp(24), ui.dp(27), 0);
        body.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static String regionSummary() {
        String name = Regions.nameOf(Prefs.region);
        return name == null ? "Не подменяется" : name;
    }
}
