package dev.ttmlonger;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Запуск поиска в dex (DexScan) на телефоне и подключение того, что нашлось.
 *
 * TikTok читает флаги не только через менеджер A/B-настроек, но и через отдельный обфусцированный
 * класс-читатель, имя которого меняется в каждой версии. Без хуков на него большинство флагов
 * (в том числе баннер профиля и голосовые комментарии) мод не видит и подменить не может. Здесь
 * класс находится по устройству, а не по имени. Результат поиска кэшируется по APK и версии мода,
 * так что дорогой разбор нужен один раз.
 */
final class DexHooks {
    private static final String CACHE_FILE = "dexscan.txt";
    private static final long SCAN_LIMIT_MS = 180_000L;
    /** Хуки на методы, найденные по ключу флага; больше — подозрительно, берём самые используемые. */
    private static final int MAX_KEYED = 30;
    private static final int MAX_READERS = 3;

    private static volatile String status = "ещё не запускался";
    private static volatile DexScan.Result last;
    private static volatile String how = "-";
    private static final List<String> log = Collections.synchronizedList(new ArrayList<String>());
    private static boolean started;

    private DexHooks() {
    }

    static String status() {
        return status;
    }

    /** Подробный отчёт для диагностики. */
    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Поиск в dex: ").append(status).append('\n');
        sb.append("  источник: ").append(how).append('\n');
        DexScan.Result r = last;
        if (r != null) {
            sb.append("  ").append(r.summary()).append(", ключевых строк в коде ").append(r.anchorStrings).append('\n');
            for (DexScan.ReaderClass c : r.readers) {
                sb.append("  читатель по форме: ").append(c.cls).append(" (методов ").append(c.methods)
                        .append(", типов ").append(c.kinds).append(")\n");
            }
            int shown = 0;
            for (DexScan.Anchored a : r.anchored) {
                if (shown++ >= 12) {
                    sb.append("  ... ещё методов с ключами: ").append(r.anchored.size() - 12).append('\n');
                    break;
                }
                sb.append("  метод с ключом: ").append(a.cls).append('.').append(a.name).append(a.proto)
                        .append(a.isStatic ? " static" : "").append(" ключ=параметр ").append(a.keyParam)
                        .append(" × ").append(a.uses).append(' ').append(a.keys).append('\n');
            }
            for (DexScan.Ref ref : r.refs) {
                sb.append("  по форме [").append(ref.label).append("]: ").append(ref.cls).append('.')
                        .append(ref.name).append(ref.proto).append('\n');
            }
            for (String n : r.notes) sb.append("  заметка: ").append(n).append('\n');
        }
        synchronized (log) {
            for (String l : log) sb.append("  ").append(l).append('\n');
        }
        sb.append("Второй читатель: ").append(FlagHooks.readerStatus()).append('\n');
        return sb.toString();
    }

    static void note(String s) {
        if (log.size() < 80) log.add(s);
        FlagStore.log(s);
    }

    /** Запускает всё в фоне: разбор может занять несколько секунд, и ждать его запуск TikTok не должен. */
    static synchronized void start(final Context ctx, final ClassLoader cl) {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work(ctx, cl);
                } catch (Throwable e) {
                    status = "сбой: " + e;
                    FlagStore.log(e);
                }
            }
        }, "ttmlonger-dexscan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void work(Context ctx, ClassLoader cl) throws Exception {
        ApplicationInfo ai = ctx.getApplicationInfo();
        List<String> apks = new ArrayList<>();
        apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) apks.add(s);
        }
        String key = cacheKey(apks);
        File dir = FlagStore.dir();
        File cache = dir == null ? null : new File(dir, CACHE_FILE);

        DexScan.Result r = null;
        if (cache != null && cache.isFile()) {
            try {
                r = DexScan.Result.parse(FlagStore.readFile(cache), key);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        if (r != null) {
            how = "кэш";
        } else {
            status = "идёт разбор dex…";
            DexScan.Options o = new DexScan.Options();
            o.anchors = Targets.SCAN_ANCHORS;
            o.clickInterface = Targets.STICKER_CLICK_IFACE;
            o.stickerTemplate = Targets.STICKER_TEMPLATE;
            o.shapes = Targets.STICKER_SHAPES;
            o.interfaces = new String[][]{{"streak", Targets.STREAK_SERVICE}};
            o.consts = new String[]{Targets.STREAK_SOURCE};
            o.deadline = System.currentTimeMillis() + SCAN_LIMIT_MS;
            ApkSource src = new ApkSource(apks);
            try {
                r = DexScan.run(src, o);
            } finally {
                src.close();
            }
            how = "разбор " + r.ms + " мс";
            if (cache != null && r.dexes > 0) {
                try {
                    FlagStore.writeAtomic(cache, r.serialize(key));
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
        }
        last = r;
        apply(r, cl, ctx);
    }

    private static String cacheKey(List<String> apks) {
        StringBuilder sb = new StringBuilder();
        sb.append(Targets.VERSION).append('|').append(DexScan.VERSION);
        for (String p : apks) {
            File f = new File(p);
            sb.append('|').append(p).append(':').append(f.length()).append(':').append(f.lastModified());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- подключение найденного

    private static void apply(DexScan.Result r, ClassLoader cl, Context ctx) {
        int readers = 0;
        int keyed = 0;

        int taken = 0;
        for (DexScan.ReaderClass rc : r.readers) {
            if (taken++ >= MAX_READERS) break;
            Class<?> c = load(cl, rc.cls);
            if (c == null) {
                note("читатель " + rc.cls + ": класс не загрузился");
                continue;
            }
            int n = FlagHooks.hookReaderClass(c);
            readers += n;
            note("читатель " + rc.cls + ": подключено методов " + n);
        }

        int tried = 0;
        for (DexScan.Anchored a : r.anchored) {
            if (tried++ >= MAX_KEYED) break;
            if (foreign(a.cls)) continue;
            Method m = findMethod(cl, a.cls, a.name, a.proto);
            if (m == null) {
                note("метод с ключом " + a.cls + "." + a.name + ": не найден в загруженном классе");
                continue;
            }
            if (FlagHooks.hookKeyed(m, a.keyParam)) {
                keyed++;
                note("метод с ключом " + a.cls + "." + a.name + a.proto + ": подключён");
            }
        }

        StickerHooks.apply(r, cl);
        try {
            StreakHooks.apply(r, cl, ctx);
        } catch (Throwable t) {
            FlagStore.log(t);
        }

        status = "читателей по форме " + r.readers.size() + ", подключено методов " + readers
                + "; методов по ключам " + r.anchored.size() + ", подключено " + keyed;
        FlagStore.log("dexscan: " + status);
    }

    private static boolean foreign(String desc) {
        return desc.startsWith("Landroid/") || desc.startsWith("Ljava/") || desc.startsWith("Ljavax/")
                || desc.startsWith("Lkotlin/") || desc.startsWith("Landroidx/") || desc.startsWith("Ldalvik/");
    }

    // ---------------------------------------------------------------- классы и методы по дескрипторам

    static String javaName(String desc) {
        if (desc.length() > 2 && desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';') {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }

    static Class<?> load(ClassLoader cl, String desc) {
        try {
            return Class.forName(javaName(desc), false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static String descriptor(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == short.class) return "S";
        if (c == char.class) return "C";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return "[" + descriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    static String protoOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(descriptor(p));
        return sb.append(')').append(descriptor(m.getReturnType())).toString();
    }

    static Method findMethod(ClassLoader cl, String clsDesc, String name, String proto) {
        Class<?> c = load(cl, clsDesc);
        if (c == null) return null;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && protoOf(m).equals(proto)) return m;
            }
        } catch (Throwable t) {
            // класс ссылается на то, чего нет в загрузчике
        }
        return null;
    }

    // ---------------------------------------------------------------- dex из APK

    /** Все classes*.dex из основного APK и его частей. */
    private static final class ApkSource implements DexScan.Source {
        private final List<String[]> items = new ArrayList<>();
        private ZipFile open;
        private String openPath;

        ApkSource(List<String> apks) {
            for (String apk : apks) {
                ZipFile z = null;
                try {
                    z = new ZipFile(apk);
                    List<String> names = new ArrayList<>();
                    java.util.Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        if (n.matches("classes\\d*\\.dex")) names.add(n);
                    }
                    Collections.sort(names, new Comparator<String>() {
                        @Override
                        public int compare(String a, String b) {
                            return Integer.compare(num(a), num(b));
                        }
                    });
                    for (String n : names) items.add(new String[]{apk, n});
                } catch (IOException e) {
                    FlagStore.log(e);
                } finally {
                    if (z != null) {
                        try {
                            z.close();
                        } catch (IOException ignored) {
                            // не страшно
                        }
                    }
                }
            }
        }

        private static int num(String n) {
            String d = n.replace("classes", "").replace(".dex", "");
            return d.isEmpty() ? 1 : Integer.parseInt(d);
        }

        void close() {
            if (open != null) {
                try {
                    open.close();
                } catch (IOException ignored) {
                    // не страшно
                }
                open = null;
            }
        }

        @Override
        public int count() {
            return items.size();
        }

        @Override
        public String name(int i) {
            return new File(items.get(i)[0]).getName() + "!" + items.get(i)[1];
        }

        @Override
        public byte[] read(int i) throws IOException {
            String apk = items.get(i)[0];
            if (open == null || !apk.equals(openPath)) {
                if (open != null) open.close();
                open = new ZipFile(apk);
                openPath = apk;
            }
            ZipEntry e = open.getEntry(items.get(i)[1]);
            if (e == null) throw new IOException("нет записи");
            InputStream in = open.getInputStream(e);
            try {
                long size = e.getSize();
                if (size > 0 && size < Integer.MAX_VALUE) {
                    byte[] buf = new byte[(int) size];
                    int off = 0;
                    while (off < buf.length) {
                        int n = in.read(buf, off, buf.length - off);
                        if (n < 0) throw new IOException("обрыв чтения");
                        off += n;
                    }
                    return buf;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] tmp = new byte[65536];
                int n;
                while ((n = in.read(tmp)) > 0) out.write(tmp, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        }
    }
}
