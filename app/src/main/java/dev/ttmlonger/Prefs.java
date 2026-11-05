package dev.ttmlonger;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Настройки мода (файл prefs.json рядом с остальными данными).
 * Поля читаются из хуков на каждый вызов, поэтому они статические и volatile.
 */
final class Prefs {
    /** Регион по умолчанию: подмена на Беларусь, пока человек сам не выбрал другое (или «Не менять»). */
    static final String DEFAULT_REGION = "BY";
    /** ISO-код региона SIM в верхнем регистре; пустая строка — не подменять. */
    static volatile String region = DEFAULT_REGION;
    /** true — язык устройства не трогаем, подменяются только SIM и сеть. */
    static volatile boolean keepLanguage = true;
    /** 0 — стандартный цвет TikTok; иначе 0xFFRRGGBB. */
    static volatile int accent = 0;
    /** Заменять тёмно-серый фон на чёрный в тёмной теме. */
    static volatile boolean amoled = false;
    /** Выбранная иконка ярлыка: пустая строка — стандартная иконка TikTok, иначе имя файла. */
    static volatile String iconId = "";
    /** Название ярлыка; пустая строка — название приложения. */
    static volatile String iconLabel = "";
    /** Название выбранного шрифта; пустая строка — шрифт приложения. */
    static volatile String fontName = "";
    /** Скачивать видео без водяного знака TikTok. */
    static volatile boolean dlNoWatermark = true;
    /** Кнопка «Сохранить» остаётся, даже если автор запретил скачивание. */
    static volatile boolean dlAlways = true;
    /** Подсказка «Сохранить через мод», когда сохраняется видео с запретом скачивания. */
    static volatile boolean dlPill = true;
    /** Качество скачивания: 0 как у TikTok, -1 лучшее, -2 наименьшее, иначе потолок по высоте (1080, 720...). */
    static volatile int dlQuality = -1;
    /** Кнопка «Сохранить стикер» на листе стикера. */
    static volatile boolean saveStickers = true;
    /** Набор флагов мода (RefSet): подставляется, пока флаг не переопределён вручную. */
    static volatile boolean refSet = true;
    /** Менять акцент и в рисунках, анимациях и цветах из кода (AccentHooks); нужен перезапуск. */
    static volatile boolean accentDeep = true;

    /** Автопродление огонька: само отправляет стикер в чат, где огонёк гаснет. Выключено, пока не включат. */
    static volatile boolean streakAuto = false;
    /** true — раз в день отправлять во все огоньки (после времени streakMinute), а не только в гаснущие. */
    static volatile boolean streakDaily = false;
    /** true — продлевать огонёк только людям, отмеченным галочкой на экране «Люди». */
    static volatile boolean streakOnlyPicked = false;
    /** Время суток для ежедневной отправки, минут от полуночи. */
    static volatile int streakMinute = 20 * 60;

    /** Плашка с точным временем публикации над именем автора в ленте. */
    static volatile boolean vtTime = true;
    /** Показывать в плашке секунды (только для точного времени). */
    static volatile boolean vtSeconds = true;
    /** Вид времени в плашке: 0 точное («26.09 02:32:00»), 1 давность, а для старых видео дата («3 дн. назад», «26.09.2026»). */
    static volatile int vtFormat = 1;
    /** Плашка с регионом видео: флаг и код страны. */
    static volatile boolean vtRegion = true;
    /** Вид плашки региона: 0 флаг и код, 1 только флаг, 2 только код. */
    static volatile int vtRegionMode = 0;

    /** Вид плашек: 0 по теме приложения, 1 тёмные, 2 светлые. */
    static volatile int vtStyle = 0;
    /** Где плашки: 0 авто (там, где свободно), 1 справа от имени, 2 над именем, 3 под именем. */
    static volatile int vtPos = 0;
    /** Непрозрачность плашек в процентах, 20..100. */
    static volatile int vtOpacity = 90;

    /** Просмотр заблокированного профиля: прятать от экрана профиля, что аккаунт заблокирован. */
    static volatile boolean viewBlocked = false;
    /** true — прятать блокировку на любом экране, а не только на экранах профиля. */
    static volatile boolean viewBlockedEverywhere = false;

    /** Бейджики после имён аккаунтов (нужен перезапуск TikTok). */
    static volatile boolean badgesOn = false;
    /** Читать общий список бейджиков (badges.json на GitHub). */
    static volatile boolean badgesRemote = true;

    /** «Невидимка»: не отправлять статус «печатает…». */
    static volatile boolean hideTyping = false;
    /** «Невидимка»: не отправлять отчёт об активности («в сети»). */
    static volatile boolean hideOnline = false;

    /** Убирать рекламные посты из ленты ещё до показа. */
    static volatile boolean hideAds = true;
    /** Прятать кнопку LIVE в левом верхнем углу главной. */
    static volatile boolean hideLiveButton = true;
    /** Убирать из ленты прямые эфиры, которые TikTok подмешивает среди видео. */
    static volatile boolean hideLiveRooms = false;

    /** Выключенные «Возможности» (идентификаторы через запятую); пусто — все включены. */
    static volatile String unlockOff = "";

    /** Цвет фона страницы настроек TikTok, снятый при последнем показе блока «Мод» (0 — не снимали). */
    static volatile int pageBg = 0;

    private static File file;

    private Prefs() {
    }

    static synchronized void init(File dir) {
        file = new File(dir, "prefs.json");
        try {
            if (!file.exists()) {
                region = DEFAULT_REGION;
                return;
            }
            JSONObject o = new JSONObject(FlagStore.readFile(file));
            region = o.optString("region", "").trim().toUpperCase(Locale.ROOT);
            // Один раз для настроек, сохранённых до появления региона по умолчанию (нет метки regionInit):
            // пустой регион означал «никто не выбирал», поэтому ставится Беларусь. Дальше выбор человека
            // (в том числе «Не менять») сохраняется как есть.
            if (!o.has("regionInit") && region.isEmpty()) region = DEFAULT_REGION;
            keepLanguage = o.optBoolean("keepLanguage", true);
            int a = o.optInt("accent", 0);
            accent = a == 0 ? 0 : (0xFF000000 | a);
            amoled = o.optBoolean("amoled", false);
            iconId = o.optString("iconId", "");
            iconLabel = o.optString("iconLabel", "");
            fontName = o.optString("fontName", "");
            dlNoWatermark = o.optBoolean("dlNoWatermark", true);
            dlAlways = o.optBoolean("dlAlways", true);
            dlQuality = o.optInt("dlQuality", -1);
            dlPill = o.optBoolean("dlPill", true);
            saveStickers = o.optBoolean("saveStickers", true);
            refSet = o.optBoolean("refSet", true);
            accentDeep = o.optBoolean("accentDeep", true);
            streakAuto = o.optBoolean("streakAuto", false);
            streakDaily = o.optBoolean("streakDaily", false);
            streakMinute = Math.max(0, Math.min(1439, o.optInt("streakMinute", 20 * 60)));
            streakOnlyPicked = o.optBoolean("streakOnlyPicked", false);
            vtTime = o.optBoolean("vtTime", true);
            vtSeconds = o.optBoolean("vtSeconds", true);
            vtFormat = Math.max(0, Math.min(1, o.optInt("vtFormat", 1)));
            vtRegion = o.optBoolean("vtRegion", true);
            vtRegionMode = Math.max(0, Math.min(2, o.optInt("vtRegionMode", 0)));
            vtStyle = Math.max(0, Math.min(2, o.optInt("vtStyle", 0)));
            vtPos = Math.max(0, Math.min(3, o.optInt("vtPos", 0)));
            vtOpacity = Math.max(20, Math.min(100, o.optInt("vtOpacity", 90)));
            viewBlocked = o.optBoolean("viewBlocked", false);
            viewBlockedEverywhere = o.optBoolean("viewBlockedEverywhere", false);
            badgesOn = o.optBoolean("badgesOn", false);
            badgesRemote = o.optBoolean("badgesRemote", true);
            hideTyping = o.optBoolean("hideTyping", false);
            hideOnline = o.optBoolean("hideOnline", false);
            hideAds = o.optBoolean("hideAds", true);
            hideLiveButton = o.optBoolean("hideLiveButton", true);
            hideLiveRooms = o.optBoolean("hideLiveRooms", false);
            unlockOff = o.optString("unlockOff", "");
            int pb = o.optInt("pageBg", 0);
            pageBg = pb == 0 ? 0 : (0xFF000000 | pb);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    static void setRegion(String iso) {
        region = iso == null ? "" : iso.trim().toUpperCase(Locale.ROOT);
        save();
    }

    static void setKeepLanguage(boolean v) {
        keepLanguage = v;
        save();
    }

    /** @param rgb цвет 0xRRGGBB или 0 для стандартного цвета TikTok */
    static void setAccent(int rgb) {
        accent = rgb == 0 ? 0 : (0xFF000000 | (rgb & 0xFFFFFF));
        save();
    }

    static void setAmoled(boolean v) {
        amoled = v;
        save();
    }

    static void setIconId(String id) {
        iconId = id == null ? "" : id;
        save();
    }

    static void setIconLabel(String label) {
        iconLabel = label == null ? "" : label.trim();
        save();
    }

    static void setFontName(String name) {
        fontName = name == null ? "" : name.trim();
        save();
    }

    static void setDlNoWatermark(boolean v) {
        dlNoWatermark = v;
        save();
    }

    static void setDlAlways(boolean v) {
        dlAlways = v;
        save();
    }

    static void setDlPill(boolean v) {
        dlPill = v;
        save();
    }

    static void setDlQuality(int v) {
        dlQuality = v;
        save();
    }

    static void setSaveStickers(boolean v) {
        saveStickers = v;
        save();
    }

    static void setAccentDeep(boolean v) {
        accentDeep = v;
        save();
    }

    static void setStreakAuto(boolean v) {
        streakAuto = v;
        save();
    }

    static void setStreakDaily(boolean v) {
        streakDaily = v;
        save();
    }

    static void setStreakOnlyPicked(boolean v) {
        streakOnlyPicked = v;
        save();
    }

    static void setStreakMinute(int v) {
        streakMinute = Math.max(0, Math.min(1439, v));
        save();
    }

    static void setVtTime(boolean v) {
        vtTime = v;
        save();
    }

    static void setVtSeconds(boolean v) {
        vtSeconds = v;
        save();
    }

    static void setVtFormat(int v) {
        vtFormat = Math.max(0, Math.min(1, v));
        save();
    }

    static void setVtRegion(boolean v) {
        vtRegion = v;
        save();
    }

    static void setVtRegionMode(int v) {
        vtRegionMode = Math.max(0, Math.min(2, v));
        save();
    }

    /** Нужны ли вообще плашки времени и региона. */
    static boolean vtMaster() {
        return vtTime || vtRegion;
    }

    static void setVtStyle(int v) {
        vtStyle = Math.max(0, Math.min(2, v));
        save();
    }

    static void setVtPos(int v) {
        vtPos = Math.max(0, Math.min(3, v));
        save();
    }

    static void setVtOpacity(int v) {
        vtOpacity = Math.max(20, Math.min(100, v));
        save();
    }

    static void setViewBlocked(boolean v) {
        viewBlocked = v;
        save();
    }

    static void setViewBlockedEverywhere(boolean v) {
        viewBlockedEverywhere = v;
        save();
    }

    static void setBadgesOn(boolean v) {
        badgesOn = v;
        save();
    }

    static void setBadgesRemote(boolean v) {
        badgesRemote = v;
        save();
    }

    static void setHideTyping(boolean v) {
        hideTyping = v;
        save();
    }

    static void setHideOnline(boolean v) {
        hideOnline = v;
        save();
    }

    static void setHideAds(boolean v) {
        hideAds = v;
        save();
    }

    static void setHideLiveButton(boolean v) {
        hideLiveButton = v;
        save();
    }

    static void setHideLiveRooms(boolean v) {
        hideLiveRooms = v;
        save();
    }

    static void setUnlock(String id, boolean on) {
        unlockOff = Unlocks.with(unlockOff, id, !on);
        save();
    }

    static void setPageBg(int argb) {
        pageBg = argb == 0 ? 0 : (0xFF000000 | (argb & 0xFFFFFF));
        save();
    }

    static void setRefSet(boolean v) {
        refSet = v;
        save();
    }

    private static synchronized void save() {
        if (file == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("region", region);
            o.put("regionInit", true);
            o.put("keepLanguage", keepLanguage);
            o.put("accent", accent);
            o.put("amoled", amoled);
            o.put("iconId", iconId);
            o.put("iconLabel", iconLabel);
            o.put("fontName", fontName);
            o.put("dlNoWatermark", dlNoWatermark);
            o.put("dlAlways", dlAlways);
            o.put("dlQuality", dlQuality);
            o.put("dlPill", dlPill);
            o.put("saveStickers", saveStickers);
            o.put("refSet", refSet);
            o.put("accentDeep", accentDeep);
            o.put("streakAuto", streakAuto);
            o.put("streakDaily", streakDaily);
            o.put("streakMinute", streakMinute);
            o.put("streakOnlyPicked", streakOnlyPicked);
            o.put("vtTime", vtTime);
            o.put("vtSeconds", vtSeconds);
            o.put("vtFormat", vtFormat);
            o.put("vtRegion", vtRegion);
            o.put("vtRegionMode", vtRegionMode);
            o.put("vtStyle", vtStyle);
            o.put("vtPos", vtPos);
            o.put("vtOpacity", vtOpacity);
            o.put("viewBlocked", viewBlocked);
            o.put("viewBlockedEverywhere", viewBlockedEverywhere);
            o.put("badgesOn", badgesOn);
            o.put("badgesRemote", badgesRemote);
            o.put("hideTyping", hideTyping);
            o.put("hideOnline", hideOnline);
            o.put("hideAds", hideAds);
            o.put("hideLiveButton", hideLiveButton);
            o.put("hideLiveRooms", hideLiveRooms);
            o.put("unlockOff", unlockOff);
            o.put("pageBg", pageBg);
            FlagStore.writeAtomic(file, o.toString());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
