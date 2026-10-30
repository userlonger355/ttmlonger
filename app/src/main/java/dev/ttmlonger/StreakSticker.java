package dev.ttmlonger;

import org.json.JSONObject;

import java.io.File;

/**
 * Стикер для огоньков. Мод не знает, какой стикер вы хотите, и сам собрать модель стикера не может,
 * поэтому берёт настоящую: ту, которую TikTok передаёт при отправке стикера в чат или при нажатии
 * на стикер. Первый пойманный запоминается; чтобы выбрать другой, на экране «Огонёк» нажимается
 * «Выбрать другой стикер» и в течение нескольких минут отправляется любой стикер.
 *
 * Между запусками TikTok модель сохраняется через Gson самого приложения (если он на месте).
 */
final class StreakSticker {
    private static final String FILE = "streak_sticker.json";
    private static final long ARM_MS = 5L * 60L * 1000L;

    private static volatile Object item;
    private static volatile Class<?> itemClass;
    private static volatile String from = "нет";
    private static volatile String saved = "ещё не сохраняли";
    private static volatile long armedUntil;
    private static volatile boolean restored;
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

    /** Следующий пойманный стикер заменит запомненный. */
    static void arm() {
        armedUntil = android.os.SystemClock.uptimeMillis() + ARM_MS;
    }

    static boolean has() {
        return get() != null;
    }

    static String status() {
        return (item != null ? "запомнен (" + from + ")" : "не выбран")
                + (armed() ? ", ждём новый стикер" : "") + "; хранение: " + saved;
    }

    /** Для хуков нажатий: ищет среди аргументов модель стикера. */
    static void offerAny(Object[] args, String how) {
        Class<?> c = itemClass;
        if (c == null || args == null) return;
        if (item != null && !armed()) return;
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
        if (item != null && !armed()) return;
        item = o;
        armedUntil = 0;
        from = how;
        Diag.note("огонёк: стикер запомнен (" + how + ")");
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                persist(o);
            }
        });
    }

    /** Запомненный стикер или null; при первом обращении пробует достать сохранённый. */
    static Object get() {
        Object o = item;
        if (o != null) return o;
        if (!restored) {
            restored = true;
            try {
                restore();
            } catch (Throwable t) {
                saved = "не прочитан: " + t.getClass().getSimpleName();
                FlagStore.log(t);
            }
        }
        return item;
    }

    static void forget() {
        item = null;
        from = "нет";
        restored = true;
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
        try {
            Object g = gson();
            String json = (String) g.getClass().getMethod("toJson", Object.class).invoke(g, o);
            if (json == null || json.length() < 2) {
                saved = "Gson вернул пустое";
                return;
            }
            JSONObject f = new JSONObject();
            f.put("key", appKey);
            f.put("json", json);
            FlagStore.writeAtomic(new File(d, FILE), f.toString());
            saved = "сохранён (" + json.length() + " знаков)";
        } catch (ClassNotFoundException e) {
            saved = "Gson не найден: стикер помнится до перезапуска TikTok";
        } catch (Throwable t) {
            saved = "не сохранён: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
    }

    private static void restore() throws Exception {
        File d = FlagStore.dir();
        Class<?> c = itemClass;
        if (d == null || c == null) {
            restored = false; // ещё рано, попробуем позже
            return;
        }
        File f = new File(d, FILE);
        if (!f.isFile()) {
            saved = "сохранённого нет";
            return;
        }
        JSONObject o = new JSONObject(FlagStore.readFile(f));
        if (!appKey.equals(o.optString("key"))) {
            saved = "сохранён для другой сборки TikTok, нужен новый";
            return;
        }
        Object g = gson();
        Object model = g.getClass().getMethod("fromJson", String.class, Class.class)
                .invoke(g, o.getString("json"), c);
        if (model != null && c.isInstance(model)) {
            item = model;
            from = "с прошлого запуска";
            saved = "восстановлен";
        } else {
            saved = "восстановить не удалось";
        }
    }
}
