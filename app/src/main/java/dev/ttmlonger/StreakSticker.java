package dev.ttmlonger;

import org.json.JSONObject;

import java.io.File;

/**
 * Стикер для огоньков. Мод не умеет собрать модель стикера сам, поэтому берёт настоящую, которую
 * выдаёт TikTok: из избранных стикеров (экран «Выбор стикера»), из отправки стикера в чат или из
 * нажатия на стикер. Выбранный стикер запоминается по идентификатору (его же находим в избранных
 * после перезапуска) и, если Gson самого TikTok на месте, целиком в файле.
 *
 * Правила автоматического выбора: пока ничего не выбрано, берётся первый пойманный стикер; когда
 * выбор есть, его меняет только явный выбор на экране или режим «выбрать отправкой» (arm).
 */
final class StreakSticker {
    private static final String FILE = "streak_sticker.json";
    private static final long ARM_MS = 5L * 60L * 1000L;
    private static final long RESOLVE_EVERY_MS = 5L * 60L * 1000L;

    private static volatile Object item;
    private static volatile Class<?> itemClass;
    private static volatile String chosenId = "";
    private static volatile String chosenTitle = "";
    private static volatile String from = "нет";
    private static volatile String saved = "ещё не сохраняли";
    private static volatile long armedUntil;
    private static volatile boolean loaded;
    private static volatile long lastResolve;
    private static volatile String appKey = "";

    private StreakSticker() {
    }

    static void setup(Class<?> cls, String key) {
        itemClass = cls;
        appKey = key == null ? "" : key;
    }

    static Class<?> itemClass() {
        return itemClass;
    }

    static boolean armed() {
        return android.os.SystemClock.uptimeMillis() < armedUntil;
    }

    /** Следующий пойманный стикер заменит выбранный. */
    static void arm() {
        armedUntil = android.os.SystemClock.uptimeMillis() + ARM_MS;
    }

    static boolean has() {
        return get() != null;
    }

    static String chosenId() {
        ensureLoaded();
        return chosenId;
    }

    /** Короткое описание выбора для экрана. */
    static String summary() {
        ensureLoaded();
        if (item == null && chosenId.isEmpty()) return armed() ? "ждём стикер из чата" : "не выбран";
        String name = chosenTitle.isEmpty() ? "№ " + chosenId : chosenTitle;
        return (item != null ? "выбран: " : "выбран, ещё не загружен: ") + name;
    }

    static String status() {
        ensureLoaded();
        return (item != null ? "в памяти (" + from + ")" : chosenId.isEmpty() ? "не выбран" : "выбран по номеру, объект не загружен")
                + (armed() ? ", ждём новый стикер" : "") + "; хранение: " + saved;
    }

    // ---------------------------------------------------------------- пойманные стикеры

    /** Для хуков нажатий: ищет среди аргументов модель стикера. */
    static void offerAny(Object[] args, String how) {
        Class<?> c = itemClass;
        if (c == null || args == null) return;
        for (Object a : args) {
            if (a != null && c.isInstance(a)) {
                offer(a, how);
                return;
            }
        }
    }

    static void offer(final Object o, String how) {
        Class<?> c = itemClass;
        if (o == null || c == null || !c.isInstance(o)) return;
        StickerLibrary.add(o, false);
        ensureLoaded();
        String id = StickerLibrary.idOf(o);
        if (item == null && id != null && id.equals(chosenId)) {
            // тот самый стикер, который выбран по номеру: теперь есть объект
            item = o;
            from = how;
            return;
        }
        boolean take = armed() || (item == null && chosenId.isEmpty());
        if (!take) return;
        choose(o, how);
    }

    /** Явный выбор стикера. */
    static void choose(final Object o, String how) {
        Class<?> c = itemClass;
        if (o == null || c == null || !c.isInstance(o)) return;
        String id = StickerLibrary.idOf(o);
        loaded = true;
        item = o;
        chosenId = id == null ? "" : id;
        chosenTitle = StickerLibrary.titleOf(o);
        from = how;
        armedUntil = 0;
        Diag.note("огонёк: стикер выбран (" + how + ", " + (chosenId.isEmpty() ? "без номера" : chosenId) + ")");
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                persist(o);
            }
        });
    }

    /** Выбранный стикер или null; если он выбран только по номеру, пробует найти его в списке. */
    static Object get() {
        ensureLoaded();
        Object o = item;
        if (o != null) return o;
        if (chosenId.isEmpty()) return null;
        StickerLibrary.Entry e = StickerLibrary.byId(chosenId);
        if (e != null) {
            item = e.item;
            from = e.fav ? "из избранных" : "из списка";
            return item;
        }
        long now = System.currentTimeMillis();
        if (now - lastResolve > RESOLVE_EVERY_MS) {
            lastResolve = now;
            StickerLibrary.refresh(null);
        }
        return null;
    }

    static void forget() {
        item = null;
        chosenId = "";
        chosenTitle = "";
        from = "нет";
        loaded = true;
        File d = FlagStore.dir();
        if (d != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(d, FILE).delete();
        }
        saved = "удалён";
    }

    // ---------------------------------------------------------------- хранение

    private static Object gson() throws Exception {
        Class<?> g = Class.forName("com.google.gson.Gson", true, StreakHooks.loader());
        return g.getConstructor().newInstance();
    }

    private static void persist(Object o) {
        File d = FlagStore.dir();
        if (d == null) return;
        JSONObject f = new JSONObject();
        try {
            f.put("key", appKey);
            f.put("id", chosenId);
            f.put("title", chosenTitle);
            String note;
            try {
                Object g = gson();
                String json = (String) g.getClass().getMethod("toJson", Object.class).invoke(g, o);
                if (json != null && json.length() > 2) {
                    f.put("json", json);
                    note = "сохранён (" + json.length() + " знаков)";
                } else {
                    note = "сохранён только номер (Gson вернул пустое)";
                }
            } catch (ClassNotFoundException e) {
                note = "сохранён только номер (Gson не найден)";
            } catch (Throwable t) {
                note = "сохранён только номер (" + t.getClass().getSimpleName() + ")";
            }
            FlagStore.writeAtomic(new File(d, FILE), f.toString());
            saved = note;
        } catch (Throwable t) {
            saved = "не сохранён: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
    }

    /** Один раз читает сохранённое: номер и название, а если можно, то и сам стикер. */
    private static void ensureLoaded() {
        if (loaded) return;
        File d = FlagStore.dir();
        if (d == null) return;
        loaded = true;
        try {
            File f = new File(d, FILE);
            if (!f.isFile()) {
                saved = "сохранённого нет";
                return;
            }
            JSONObject o = new JSONObject(FlagStore.readFile(f));
            chosenId = o.optString("id", "");
            chosenTitle = o.optString("title", "");
            Class<?> c = itemClass;
            String json = o.optString("json", "");
            if (c != null && !json.isEmpty() && appKey.equals(o.optString("key"))) {
                try {
                    Object g = gson();
                    Object model = g.getClass().getMethod("fromJson", String.class, Class.class).invoke(g, json, c);
                    if (model != null && c.isInstance(model)) {
                        item = model;
                        from = "с прошлого запуска";
                        saved = "восстановлен";
                        return;
                    }
                } catch (Throwable t) {
                    saved = "целиком не восстановлен (" + t.getClass().getSimpleName() + "), ищем по номеру";
                    return;
                }
            }
            saved = chosenId.isEmpty() ? "сохранённого нет" : "восстановлен только номер, ищем в списке";
        } catch (Throwable t) {
            saved = "не прочитан: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
    }
}
