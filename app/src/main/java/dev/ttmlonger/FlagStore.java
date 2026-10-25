package dev.ttmlonger;

import android.content.Context;

import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Каталог известных флагов и пользовательские переопределения.
 *
 * Файлы лежат в files/ttmlonger внутри данных TikTok:
 *   overrides.json — переопределения (пишется сразу при изменении),
 *   catalog.json   — все когда-либо прочитанные флаги (сохраняется раз в 20 секунд),
 *   guard.json     — состояние защиты от крашей.
 */
final class FlagStore {
    static final int BOOL = 0;
    static final int INT = 1;
    static final int LONG = 2;
    static final int FLOAT = 3;
    static final int DOUBLE = 4;
    static final int STRING = 5;

    private static final String[] TYPE_NAMES = {"bool", "int", "long", "float", "double", "string"};
    private static final int MAX_VALUE_LEN = 4096;

    static final class Flag {
        final String key;
        final String lower;
        volatile int type;
        /** Реальное значение, которое вернуло приложение (до подмены). */
        volatile String value;
        volatile boolean readNow;

        Flag(String key, int type, String value) {
            this.key = key;
            this.lower = key.toLowerCase(Locale.ROOT);
            this.type = type;
            this.value = value;
        }
    }

    static final class Patch {
        final int type;
        final String raw;

        Patch(int type, String raw) {
            this.type = type;
            this.raw = raw;
        }
    }

    private static final ConcurrentHashMap<String, Flag> flags = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Patch> overrides = new ConcurrentHashMap<>();
    private static final AtomicInteger readCount = new AtomicInteger();
    private static final ExecutorService io = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ttmlonger-io");
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile boolean ready;
    private static volatile boolean catalogLoaded;
    private static volatile boolean catalogDirty;
    private static File dir;

    private FlagStore() {
    }

    // ---------------------------------------------------------------- жизненный цикл

    static synchronized void init(Context ctx) {
        if (ready) return;
        dir = new File(ctx.getFilesDir(), "ttmlonger");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Prefs.init(dir);
        loadOverrides();
        CrashGuard.start(dir);
        ready = true;
        // Каталог может весить мегабайты — читаем не на главном потоке.
        io.execute(new Runnable() {
            @Override
            public void run() {
                loadCatalog();
            }
        });
        startCatalogSaver();
    }

    /** Папка данных мода (files/ttmlonger); до init() — null. */
    static File dir() {
        return dir;
    }

    // ---------------------------------------------------------------- горячий путь (хуки)

    /** Вызывается из хука при каждом чтении флага. Должен быть дешёвым. */
    static void onRead(String key, int type, Object value) {
        if (!ready) return;
        Flag f = flags.get(key);
        if (f == null) {
            f = new Flag(key, type, null);
            Flag prev = flags.putIfAbsent(key, f);
            if (prev != null) f = prev;
        }
        if (!f.readNow) {
            synchronized (f) {
                if (!f.readNow) {
                    f.type = type;
                    f.value = str(value);
                    f.readNow = true;
                    readCount.incrementAndGet();
                    catalogDirty = true;
                }
            }
        }
    }

    /** Переопределение, которое надо применить прямо сейчас (null, если защита сработала). */
    static Patch getOverride(String key) {
        if (overrides.isEmpty() || CrashGuard.tripped) return null;
        return overrides.get(key);
    }

    // ---------------------------------------------------------------- для интерфейса

    static Patch rawOverride(String key) {
        return overrides.get(key);
    }

    static void setOverride(String key, int type, String raw) {
        overrides.put(key, new Patch(type, raw));
        if (!flags.containsKey(key)) flags.putIfAbsent(key, new Flag(key, type, null));
        catalogDirty = true;
        saveOverridesAsync();
    }

    static void removeOverride(String key) {
        overrides.remove(key);
        saveOverridesAsync();
    }

    static void clearOverrides() {
        overrides.clear();
        saveOverridesAsync();
    }

    static int total() {
        return flags.size();
    }

    static int readThisSession() {
        return readCount.get();
    }

    static int overrideCount() {
        return overrides.size();
    }

    static List<Flag> snapshot() {
        return new ArrayList<>(flags.values());
    }

    static String exportJson(String pkg) throws Exception {
        List<Flag> all = snapshot();
        Collections.sort(all, new Comparator<Flag>() {
            @Override
            public int compare(Flag a, Flag b) {
                return a.key.compareTo(b.key);
            }
        });
        JSONObject root = new JSONObject();
        root.put("package", pkg);
        root.put("exported_at", System.currentTimeMillis());
        root.put("total", all.size());
        root.put("read_this_session", readCount.get());
        root.put("overrides", overrides.size());
        JSONObject out = new JSONObject();
        for (Flag f : all) {
            JSONObject e = new JSONObject();
            e.put("type", typeName(f.type));
            e.put("value", f.value == null ? JSONObject.NULL : f.value);
            e.put("read_this_session", f.readNow);
            Patch o = overrides.get(f.key);
            if (o != null) e.put("override", o.raw);
            out.put(f.key, e);
        }
        root.put("flags", out);
        return root.toString(2);
    }

    // ---------------------------------------------------------------- типы и значения

    static int typeOf(Class<?> c) {
        if (c == boolean.class || c == Boolean.class) return BOOL;
        if (c == int.class || c == Integer.class) return INT;
        if (c == long.class || c == Long.class) return LONG;
        if (c == float.class || c == Float.class) return FLOAT;
        if (c == double.class || c == Double.class) return DOUBLE;
        if (c == String.class) return STRING;
        return -1;
    }

    static String typeName(int type) {
        return type >= 0 && type < TYPE_NAMES.length ? TYPE_NAMES[type] : "?";
    }

    /**
     * Превращает сохранённую строку в значение нужного типа.
     * Возвращает null, если строку нельзя разобрать: тогда подмены не будет.
     */
    static Object coerce(String raw, int type) {
        if (raw == null) return null;
        try {
            String s = raw.trim();
            switch (type) {
                case BOOL:
                    if (s.equalsIgnoreCase("true") || s.equals("1")) return Boolean.TRUE;
                    if (s.equalsIgnoreCase("false") || s.equals("0")) return Boolean.FALSE;
                    return null;
                case INT:
                    return Integer.valueOf(Math.toIntExact(parseLong(s)));
                case LONG:
                    return Long.valueOf(parseLong(s));
                case FLOAT:
                    return Float.valueOf(s);
                case DOUBLE:
                    return Double.valueOf(s);
                case STRING:
                    return raw;
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseLong(String s) {
        if (s.equalsIgnoreCase("true")) return 1L;
        if (s.equalsIgnoreCase("false")) return 0L;
        return Long.parseLong(s);
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.length() > MAX_VALUE_LEN ? s.substring(0, MAX_VALUE_LEN) + "…" : s;
    }

    // ---------------------------------------------------------------- файлы

    private static void loadOverrides() {
        try {
            File f = new File(dir, "overrides.json");
            if (!f.exists()) return;
            JSONObject o = new JSONObject(readFile(f));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = o.optJSONArray(k);
                if (a == null) continue;
                int type = a.optInt(0, STRING);
                String raw = a.optString(1, "");
                overrides.put(k, new Patch(type, raw));
                flags.putIfAbsent(k, new Flag(k, type, null));
            }
        } catch (Throwable t) {
            log(t);
        }
    }

    private static void saveOverridesAsync() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject();
                    for (Map.Entry<String, Patch> e : overrides.entrySet()) {
                        o.put(e.getKey(), new JSONArray().put(e.getValue().type).put(e.getValue().raw));
                    }
                    writeAtomic(new File(dir, "overrides.json"), o.toString());
                } catch (Throwable t) {
                    log(t);
                }
            }
        });
    }

    private static void loadCatalog() {
        try {
            File f = new File(dir, "catalog.json");
            if (!f.exists()) return;
            JSONObject o = new JSONObject(readFile(f));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = o.optJSONArray(k);
                if (a == null) continue;
                int type = a.optInt(0, STRING);
                String v = a.isNull(1) ? null : a.optString(1, null);
                Flag cur = flags.get(k);
                if (cur == null) {
                    flags.putIfAbsent(k, new Flag(k, type, v));
                } else if (!cur.readNow && cur.value == null) {
                    cur.value = v;
                }
            }
        } catch (Throwable t) {
            log(t);
        } finally {
            catalogLoaded = true;
        }
    }

    private static void startCatalogSaver() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(20_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (catalogLoaded && catalogDirty) {
                        catalogDirty = false;
                        saveCatalog();
                    }
                }
            }
        }, "ttmlonger-saver");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void saveCatalog() {
        try {
            JSONObject o = new JSONObject();
            for (Flag f : flags.values()) {
                o.put(f.key, new JSONArray().put(f.type).put(f.value == null ? JSONObject.NULL : f.value));
            }
            writeAtomic(new File(dir, "catalog.json"), o.toString());
        } catch (Throwable t) {
            log(t);
        }
    }

    static String readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024L, f.length()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    static void writeAtomic(File dst, String text) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            if (!tmp.renameTo(dst)) throw new IOException("не удалось заменить " + dst);
        }
    }

    static void log(Throwable t) {
        try {
            XposedBridge.log(t);
        } catch (Throwable ignored) {
            // Вне Xposed (например, при проверке) логировать некуда.
        }
    }

    static void log(String s) {
        try {
            XposedBridge.log("[TTMLonger] " + s);
        } catch (Throwable ignored) {
            // см. выше
        }
    }
}
