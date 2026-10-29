package dev.ttmlonger;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Настройки мода (файл prefs.json рядом с остальными данными).
 * Поля читаются из хуков на каждый вызов, поэтому они статические и volatile.
 */
final class Prefs {
    /** ISO-код региона SIM в верхнем регистре; пустая строка — не подменять. */
    static volatile String region = "";
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
    /** Показывать подсказку при запуске: по ней видно, что мод загрузился. */
    static volatile boolean startupToast = true;
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

    private static File file;

    private Prefs() {
    }

    static synchronized void init(File dir) {
        file = new File(dir, "prefs.json");
        try {
            if (!file.exists()) return;
            JSONObject o = new JSONObject(FlagStore.readFile(file));
            region = o.optString("region", "").trim().toUpperCase(Locale.ROOT);
            keepLanguage = o.optBoolean("keepLanguage", true);
            int a = o.optInt("accent", 0);
            accent = a == 0 ? 0 : (0xFF000000 | a);
            amoled = o.optBoolean("amoled", false);
            iconId = o.optString("iconId", "");
            iconLabel = o.optString("iconLabel", "");
            startupToast = o.optBoolean("startupToast", true);
            fontName = o.optString("fontName", "");
            dlNoWatermark = o.optBoolean("dlNoWatermark", true);
            dlAlways = o.optBoolean("dlAlways", true);
            dlQuality = o.optInt("dlQuality", -1);
            dlPill = o.optBoolean("dlPill", true);
            saveStickers = o.optBoolean("saveStickers", true);
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

    static void setStartupToast(boolean v) {
        startupToast = v;
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

    private static synchronized void save() {
        if (file == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("region", region);
            o.put("keepLanguage", keepLanguage);
            o.put("accent", accent);
            o.put("amoled", amoled);
            o.put("iconId", iconId);
            o.put("iconLabel", iconLabel);
            o.put("startupToast", startupToast);
            o.put("fontName", fontName);
            o.put("dlNoWatermark", dlNoWatermark);
            o.put("dlAlways", dlAlways);
            o.put("dlQuality", dlQuality);
            o.put("dlPill", dlPill);
            o.put("saveStickers", saveStickers);
            FlagStore.writeAtomic(file, o.toString());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
