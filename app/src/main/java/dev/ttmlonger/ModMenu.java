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
    void build(LinearLayout page) {
        android.widget.EditText search = new android.widget.EditText(act);
        search.setSingleLine(true);
        search.setHint("Поиск по настройкам мода");
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(ui.round(Ui.FIELD, 16));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        LinearLayout.LayoutParams inset = ui.matchWrap();
        inset.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        page.addView(search, inset);
        if (CrashGuard.tripped) {
            page.addView(ui.menuRow(Icons.FLAG, "Включена защита от сбоев",
                    "После нескольких коротких запусков часть функций приостановлена. Открыть настройки защиты",
                    null, v -> FlagsScreen.show(act)));
        }
        LinearLayout body = section(page, "Основное");
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
        body.addView(ui.menuRow(Icons.SLIDERS, "Интерфейс и конфиденциальность",
                "Ссылки, вкладки и подтверждение действий", null, v -> InterfaceScreen.show(act)));

        body.addView(ui.menuRow(Icons.PALETTE, "Внешний вид", "Акцент, Amoled-тема, шрифт, значок приложения", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AppearanceScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.SLIDERS, "Нижняя панель", "Оригинал или Liquid Glass: стеклянная капсула и линза",
                GlassBarScreen.menuSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        GlassBarScreen.show(act);
                    }
                }));

        body = section(page, "Лента");
        body.addView(ui.menuRow(Icons.CLOCK, "Время и регион видео",
                "Точное время публикации, флаг и код страны над именем автора", VideoInfoScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        VideoInfoScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.CHECK, "Возможности",
                "Голосовые в ЛС, длинные репосты, хештеги без лимита, 9 фото, телесуфлёр",
                UnlocksScreen.menuSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UnlocksScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.FLAG, "Бейджики",
                "Картинка после имени аккаунта: в профиле, комментариях, ленте", BadgesScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        BadgesScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.EYE_OFF, "Невидимка",
                "Не показывать «печатает…» и «в сети»", InvisibleScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        InvisibleScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.SEARCH, "Перехват запросов",
                "Для удаления лайков, избранного и репостов: запись настоящих вызовов TikTok", ApiTap.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ApiTapScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.SEARCH, "Разведка кода",
                "Для «Невидимки», заблокированного профиля, удаления и личных сообщений", ReconScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ReconScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.EYE_OFF, "Реклама и LIVE",
                "Автоскрытие рекламы в ленте, скрытие кнопки LIVE", AdsScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AdsScreen.show(act);
                    }
                }));

        body = section(page, "Сохранение");
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

        body = section(page, "Личные сообщения");
        body.addView(ui.menuRow(Icons.FLAME, "Огонёк", "Само продлевает серию дней в чате", StreakScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StreakScreen.show(act);
                    }
                }));
        body.addView(toggleRow(Icons.MIC, "Голосовые и обои без региона",
                "Пробует включить функции чата, которые сервер скрывает по региону аккаунта. Экспериментально. "
                        + "Нужен перезапуск TikTok",
                Prefs.dmGate, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setDmGate(on);
                        Restart.offer(act);
                    }
                }));

        body = section(page, "Функции TikTok");
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

        body = section(page, "Для опытных");
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
        page.addView(ver, ui.matchWrap());
        TextView empty = ui.note("По этому запросу настроек нет");
        page.addView(empty);
        empty.setVisibility(View.GONE);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(android.text.Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = VideoScreen.normalize(s.toString());
                boolean any = false;
                for (int i = 1; i < page.getChildCount(); i++) {
                    View item = page.getChildAt(i);
                    if (!(item instanceof LinearLayout) || !(item.getTag() instanceof TextView)) continue;
                    LinearLayout card = (LinearLayout) item;
                    TextView header = (TextView) card.getTag();
                    boolean group = VideoScreen.normalize(header.getText().toString()).contains(q);
                    boolean visible = false;
                    for (int j = 0; j < card.getChildCount(); j++) {
                        View row = card.getChildAt(j);
                        TextView title = Ui.titleOf(row);
                        TextView sub = Ui.subtitleOf(row);
                        String words = (title == null ? "" : title.getText()) + " " + (sub == null ? "" : sub.getText());
                        boolean match = group || VideoScreen.normalize(words).contains(q);
                        row.setVisibility(match ? View.VISIBLE : View.GONE);
                        visible |= match;
                    }
                    header.setVisibility(visible ? View.VISIBLE : View.GONE);
                    card.setVisibility(header.getVisibility());
                    any |= visible;
                }
                empty.setVisibility(any ? View.GONE : View.VISIBLE);
            }
        });
    }

    private LinearLayout section(LinearLayout page, String title) {
        TextView heading = ui.header(title);
        page.addView(heading);
        LinearLayout card = new LinearLayout(act);
        card.setTag(heading);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(Ui.DARK ? Ui.CARD : Ui.FIELD, 18));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams lp = ui.matchWrap();
        lp.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(2));
        page.addView(card, lp);
        return card;
    }

    private static String regionSummary() {
        String name = Regions.nameOf(Prefs.region);
        return name == null ? "Не меняется" : name;
    }
}
