package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.ArrayList;
import java.util.List;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Главная страница «Настройки мода». */
final class ModMenu extends Screen {
    private LinearLayout regionRow;
    private LinearLayout tabs;
    private LinearLayout shortcuts;
    private EditText search;
    private TextView empty;
    private TextView streakValue;
    private LinearLayout streakMenuRow;
    private HorizontalScrollView tabScroll;
    private final List<LinearLayout> sections = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private int selected;

    private ModMenu(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ModMenu(act).open("Настройки мода", null);
    }

    @Override
    void build(LinearLayout page) {
        shortcuts = new LinearLayout(act);
        shortcuts.setBaselineAligned(false);
        shortcuts.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(6));
        page.addView(shortcuts, ui.matchWrap());
        streakValue = tile(Icons.FLAME, "Огоньки", StreakScreen.menuSummary(), true, () -> StreakScreen.show(act));
        tile(Icons.SLIDERS, "Интерфейс", "Лента и жесты", false, () -> InterfaceScreen.show(act));

        search = new EditText(act);
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
        tabScroll = new HorizontalScrollView(act);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(act);
        tabs.setPadding(ui.dp(14), ui.dp(12), ui.dp(8), ui.dp(6));
        tabScroll.addView(tabs);
        page.addView(tabScroll, ui.matchWrap());
        if (CrashGuard.tripped) {
            page.addView(ui.menuRow(Icons.FLAG, "Включена защита от сбоев",
                    "После нескольких неудачных запусков функции приостановлены. Нажмите для восстановления",
                    null, v -> recovery()));
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
        streakMenuRow = ui.menuRow(Icons.FLAME, "Огоньки", "Автопродление серии в чатах", StreakScreen.menuSummary(),
                v -> StreakScreen.show(act));
        body.addView(streakMenuRow);
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

        body.addView(toggleRow(Icons.FLAG, "Дополнительные возможности",
                "Дополнительные функции TikTok. Доступность зависит от аккаунта. Нужен перезапуск TikTok",
                Prefs.refSet, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setRefSet(on);
                        Restart.offer(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.SLIDERS, "Настройки разработчика TikTok",
                "Флаги A/B · расширенные настройки", null, v -> FlagsScreen.show(act)));

        TextView ver = ui.text("TTMLonger " + Targets.VERSION, 13, Ui.MUTED, false);
        ver.setGravity(android.view.Gravity.CENTER);
        ver.setPadding(0, ui.dp(28), 0, ui.dp(8));
        page.addView(ver, ui.matchWrap());
        empty = ui.note("По этому запросу настроек нет");
        page.addView(empty);
        empty.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
        });
        page.getViewTreeObserver().addOnWindowFocusChangeListener(focus -> {
            if (!focus) return;
            streakValue.setText(StreakScreen.menuSummary());
            Ui.valueOf(streakMenuRow).setText(StreakScreen.menuSummary());
        });
        filter();
    }

    private void recovery() {
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(act, Ui.dialogTheme())
                .setTitle("Восстановить функции?")
                .setMessage("Защита приостановила функции после нескольких коротких запусков. Если сбои повторятся, защита сработает снова.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Восстановить", (dlg, which) -> {
                    CrashGuard.reenable();
                    Restart.offer(act);
                }).create();
        Screen.markOwn(d);
        d.show();
        Ui.style(d);
    }

    private TextView tile(int icon, String title, String value, boolean warm, Runnable open) {
        int from = warm ? (Ui.DARK ? 0xFF542D32 : 0xFFFFDBCB) : (Ui.DARK ? 0xFF273F53 : 0xFFD5EEFA);
        int to = warm ? (Ui.DARK ? 0xFF302029 : 0xFFFFF0DD) : (Ui.DARK ? 0xFF222A40 : 0xFFEAECFF);
        int ink = Ui.DARK ? 0xFFFDF4EF : 0xFF252432;
        LinearLayout tile = new LinearLayout(act);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(ui.dp(16), ui.dp(15), ui.dp(12), ui.dp(16));
        tile.setBackground(ui.wash(from, to));
        tile.setClipToOutline(true);
        tile.setForeground(ui.ripple());
        tile.setFocusable(true);
        tile.setOnClickListener(v -> open.run());
        int tint = warm ? (Ui.DARK ? 0xFFFFB584 : 0xFF964123) : (Ui.DARK ? 0xFFA7DCFF : 0xFF285378);
        tile.addView(ui.icon(icon, tint, 27));
        TextView heading = ui.text(title, 19, ink, true);
        heading.setPadding(0, ui.dp(12), 0, ui.dp(2));
        tile.addView(heading);
        TextView detail = ui.text(value, 13, tint, false);
        tile.addView(detail);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (!warm) lp.setMarginStart(ui.dp(8));
        shortcuts.addView(tile, lp);
        return detail;
    }

    private LinearLayout section(LinearLayout page, String title) {
        int index = sections.size();
        TextView chip = ui.medium(title, 14, Ui.INK);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        chip.setMinimumHeight(ui.dp(44));
        chip.setFocusable(true);
        chip.setOnClickListener(v -> {
            selected = index;
            filter();
            tabScroll.smoothScrollTo(chip.getLeft() - ui.dp(14), 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(ui.dp(6));
        tabs.addView(chip, lp);
        labels.add(chip);
        LinearLayout card = ui.panel(page);
        sections.add(card);
        return card;
    }

    private void filter() {
        String q = VideoScreen.normalize(search.getText().toString());
        boolean browsing = q.isEmpty();
        shortcuts.setVisibility(browsing ? View.VISIBLE : View.GONE);
        tabScroll.setVisibility(browsing ? View.VISIBLE : View.GONE);
        boolean any = false;
        for (int i = 0; i < sections.size(); i++) {
            LinearLayout card = sections.get(i);
            TextView chip = labels.get(i);
            boolean current = i == selected;
            chip.setSelected(current);
            chip.setBackground(ui.round(current ? Ui.INK : Ui.FIELD, 22));
            chip.setTextColor(current ? Ui.BG : Ui.INK);
            boolean matchGroup = VideoScreen.normalize(chip.getText().toString()).contains(q);
            boolean visible = false;
            for (int j = 0; j < card.getChildCount(); j++) {
                View row = card.getChildAt(j);
                TextView title = Ui.titleOf(row);
                TextView sub = Ui.subtitleOf(row);
                String words = (title == null ? "" : title.getText()) + " " + (sub == null ? "" : sub.getText());
                boolean match = browsing ? current : matchGroup || VideoScreen.normalize(words).contains(q);
                row.setVisibility(match ? View.VISIBLE : View.GONE);
                visible |= match;
            }
            card.setVisibility(visible ? View.VISIBLE : View.GONE);
            any |= visible;
        }
        empty.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    private static String regionSummary() {
        String name = Regions.nameOf(Prefs.region);
        return name == null ? "Не меняется" : name;
    }
}
