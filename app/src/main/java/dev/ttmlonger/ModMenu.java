package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Главная страница «Настройки мода». */
final class ModMenu extends Screen {
    private LinearLayout regionRow;

    private ModMenu(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ModMenu(act).open("Настройки мода", null);
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Основное"));
        regionRow = ui.menuRow(Icons.GLOBE, "Регион", null, regionSummary(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RegionSheet.show(act, new Runnable() {
                    @Override
                    public void run() {
                        TextView val = Ui.valueOf(regionRow);
                        if (val != null) val.setText(regionSummary());
                    }
                });
            }
        });
        body.addView(regionRow);

        body.addView(ui.menuRow(Icons.PALETTE, "Внешний вид", "Акцент, Amoled-тема, шрифт, значок приложения", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AppearanceScreen.show(act);
                    }
                }));

        body.addView(ui.header("Лента"));
        body.addView(ui.menuRow(Icons.CLOCK, "Время и регион видео",
                "Точное время публикации, флаг и код страны над именем автора", VideoInfoScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        VideoInfoScreen.show(act);
                    }
                }));

        body.addView(ui.header("Сохранение"));
        body.addView(ui.menuRow(Icons.DOWNLOAD, "Скачивание", "Без водяного знака, качество, обход запрета", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DownloadScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.VIDEO, "Видео и истории", "Сохранить то, что показывал TikTok", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        VideoScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.STICKER, "Стикеры", "Сохранить стикеры с экрана", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StickerScreen.show(act);
                    }
                }));

        body.addView(ui.header("Личные сообщения"));
        body.addView(ui.menuRow(Icons.FLAME, "Огонёк", "Само продлевает серию дней в чате", StreakScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StreakScreen.show(act);
                    }
                }));

        body.addView(ui.header("Функции TikTok"));
        body.addView(toggleRow(Icons.IMAGE, "Баннер профиля",
                "Установка баннера в «Изменить профиль». Нужен перезапуск TikTok",
                FlagStore.allOn(Targets.BANNER_FLAGS), new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        FlagStore.setAll(Targets.BANNER_FLAGS, on);
                        Restart.offer(act);
                    }
                }));

        body.addView(toggleRow(Icons.MIC, "Голосовые комментарии",
                "Запись голосового комментария. Нужен перезапуск TikTok",
                FlagStore.allOn(Targets.VOICE_COMMENT_FLAGS), new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        FlagStore.setAll(Targets.VOICE_COMMENT_FLAGS, on);
                        Restart.offer(act);
                    }
                }));

        body.addView(toggleRow(Icons.FLAG, "Набор флагов мода",
                "Голосовые и пузырь чата в личных, без рекламы, длинные видео и другое (" + RefSet.size()
                        + " шт.). Ваши ручные подмены главнее. Нужен перезапуск TikTok",
                Prefs.refSet, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setRefSet(on);
                        Restart.offer(act);
                    }
                }));

        body.addView(ui.header("Для опытных"));
        body.addView(ui.menuRow(Icons.SLIDERS, "Настройки разработчика TikTok",
                "Внутренние флаги (A/B) для тестирования функций", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FlagsScreen.show(act);
                    }
                }));

        TextView ver = ui.text("TTMLonger " + Targets.VERSION, 13, Ui.MUTED, false);
        ver.setGravity(android.view.Gravity.CENTER);
        ver.setPadding(0, ui.dp(28), 0, ui.dp(8));
        body.addView(ver, ui.matchWrap());
    }

    private static String regionSummary() {
        String name = Regions.nameOf(Prefs.region);
        return name == null ? "Не меняется" : name;
    }
}
