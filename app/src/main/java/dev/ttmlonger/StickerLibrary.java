package dev.ttmlonger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Стикеры, из которых можно выбрать стикер для огоньков: избранные стикеры TikTok и те, что мод
 * видел (отправленные и нажатые в чатах). Сами модели стикеров настоящие, их выдаёт TikTok, поэтому
 * отправить можно любую.
 *
 * Избранные лежат в репозитории TikTok: у него методы getFavouriteStickersFlow (поток состояния,
 * в нём список) и refreshFavouriteStickers (загрузить). Имена этих методов не обфусцированы, а класс
 * находит DexScan по имени метода. Экземпляр репозитория берётся двумя путями: хук на эти методы
 * запоминает объект, когда TikTok сам их вызывает (достаточно открыть панель стикеров → избранное),
 * либо ищется статический доступ к синглтону в самом классе.
 */
final class StickerLibrary {
    static final class Entry {
        final String id;
        final Object item;
        final String title;
        final String url;
        volatile boolean fav;
        volatile Bitmap thumb;
        volatile boolean loading;
        volatile boolean failed;

        Entry(String id, Object item, String title, String url, boolean fav) {
            this.id = id;
            this.item = item;
            this.title = title;
            this.url = url;
            this.fav = fav;
        }
    }

    private static final int MAX = 200;
    private static final int THUMB_PX = 160;
    private static final String FLOW = "getFavouriteStickersFlow";
    private static final String REFRESH = "refreshFavouriteStickers";

    private static final LinkedHashMap<String, Entry> items = new LinkedHashMap<>();
    private static final Set<String> hooked = new HashSet<>();
    private static final List<String> repoClasses = new ArrayList<>();
    private static volatile Object repo;
    private static volatile Method flowM;
    private static volatile Method refreshM;
    private static volatile String favNote = "ещё не искали";
    private static volatile int favCount;
    private static volatile int hooks;
    private static volatile long lastRefresh;
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ttmlonger-thumb");
            t.setDaemon(true);
            return t;
        }
    });

    private StickerLibrary() {
    }

    // ---------------------------------------------------------------- модель стикера

    private static Object field(Object o, String name) {
        if (o == null) return null;
        try {
            Field f;
            try {
                f = o.getClass().getField(name);
            } catch (NoSuchFieldException e) {
                f = o.getClass().getDeclaredField(name);
                f.setAccessible(true);
            }
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Идентификатор стикера: id из stickerBase, иначе адрес картинки, иначе хэш адреса. */
    static String idOf(Object item) {
        Object base = field(item, "stickerBase");
        if (base != null) {
            Object id = field(base, "id");
            if (id != null) return String.valueOf(id);
            for (String n : new String[]{"image", "thumbnail"}) {
                Object uri = field(field(base, n), "uri");
                if (uri instanceof String && !((String) uri).isEmpty()) return (String) uri;
            }
        }
        String url = StickerSaver.modelUrl(item);
        return url == null ? null : "u" + Integer.toHexString(url.hashCode());
    }

    static String titleOf(Object item) {
        Object t = field(field(item, "stickerBase"), "title");
        return t instanceof String ? (String) t : "";
    }

    /** Адрес картинки для значка: сначала уменьшенная, потом обычная, потом любая из модели. */
    static String thumbUrlOf(Object item) {
        Object base = field(item, "stickerBase");
        for (String n : new String[]{"thumbnail", "image"}) {
            Object part = field(base, n);
            if (part == null) continue;
            String u = StickerSaver.modelUrl(part);
            if (u != null) return u;
        }
        return StickerSaver.modelUrl(item);
    }

    // ---------------------------------------------------------------- список

    /** Добавляет стикер; true, если такого ещё не было. fav — из избранных. */
    static boolean add(Object item, boolean fav) {
        try {
            Class<?> c = StreakSticker.itemClass();
            if (item == null || c == null || !c.isInstance(item)) return false;
            String id = idOf(item);
            if (id == null) return false;
            synchronized (items) {
                Entry have = items.get(id);
                if (have != null) {
                    if (fav) have.fav = true;
                    return false;
                }
                if (items.size() >= MAX) {
                    // выбрасываем самый старый из не избранных
                    String drop = null;
                    for (Map.Entry<String, Entry> e : items.entrySet()) {
                        if (!e.getValue().fav) {
                            drop = e.getKey();
                            break;
                        }
                    }
                    if (drop == null) drop = items.keySet().iterator().next();
                    items.remove(drop);
                }
                items.put(id, new Entry(id, item, titleOf(item), thumbUrlOf(item), fav));
            }
            return true;
        } catch (Throwable t) {
            FlagStore.log(t);
            return false;
        }
    }

    static Entry byId(String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (items) {
            return items.get(id);
        }
    }

    /** Избранные первыми (в порядке TikTok), потом остальные, новые сверху. */
    static List<Entry> list() {
        List<Entry> favs = new ArrayList<>();
        List<Entry> seen = new ArrayList<>();
        synchronized (items) {
            for (Entry e : items.values()) (e.fav ? favs : seen).add(e);
        }
        java.util.Collections.reverse(seen);
        favs.addAll(seen);
        return favs;
    }

    static int count() {
        synchronized (items) {
            return items.size();
        }
    }

    // ---------------------------------------------------------------- значки

    /** Загружает значок в фоне; done вызывается в главном потоке, когда он готов. */
    static void thumb(final Entry e, final Runnable done) {
        if (e.thumb != null || e.failed || e.url == null) return;
        synchronized (e) {
            if (e.loading) return;
            e.loading = true;
        }
        THUMBS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String secure = AvatarHooks.secure(e.url);
                    if (secure == null) throw new IllegalStateException("нет https-адреса");
                    byte[] data = AvatarSaver.download(secure);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > THUMB_PX * 2) sample *= 2;
                    BitmapFactory.Options d = new BitmapFactory.Options();
                    d.inSampleSize = sample;
                    Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
                    if (b == null) {
                        e.failed = true;
                    } else {
                        e.thumb = b;
                    }
                } catch (Throwable t) {
                    e.failed = true;
                } finally {
                    e.loading = false;
                }
                if (done != null && e.thumb != null) new Handler(Looper.getMainLooper()).post(done);
            }
        });
    }

    // ---------------------------------------------------------------- избранные стикеры TikTok

    /** Подключает хуки на методы репозитория избранных; вызывается, когда включено автопродление. */
    static void apply(DexScan.Result r, ClassLoader cl) {
        for (DexScan.Ref ref : r.refs) {
            if (!ref.label.startsWith("method:")) continue;
            Class<?> c = DexHooks.load(cl, ref.cls);
            if (c == null) continue;
            if (!repoClasses.contains(c.getName())) repoClasses.add(c.getName());
            Method m = DexHooks.findMethod(cl, ref.cls, ref.name, ref.proto);
            if (m == null) continue;
            synchronized (hooked) {
                if (!hooked.add(m.toString())) continue;
            }
            try {
                XposedBridge.hookMethod(m, new Capture());
                hooks++;
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        favNote = repoClasses.isEmpty() ? "класса с getFavouriteStickersFlow в dex нет"
                : "классов " + repoClasses.size() + ", хуков " + hooks + "; объект ещё не получен";
        DexHooks.note("избранные стикеры: " + favNote);
    }

    /** Хук-наблюдатель: запоминает репозиторий, когда TikTok сам обращается к избранным. */
    private static final class Capture extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object self = param.thisObject;
                if (self != null && repo == null) {
                    repo = self;
                    favNote = "репозиторий получен из вызова TikTok";
                    StreakRunner.async(new Runnable() {
                        @Override
                        public void run() {
                            readFavourites();
                        }
                    });
                }
            } catch (Throwable ignored) {
                // не мешаем TikTok
            }
        }
    }

    /** Ищет экземпляр репозитория без вызова TikTok: статическое поле или метод-синглтон в самом классе. */
    private static Object findRepo(ClassLoader cl) {
        for (String name : repoClasses) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || !c.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && c.isInstance(v)) return v;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != c) continue;
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v != null && c.isInstance(v)) return v;
                }
            } catch (Throwable ignored) {
                // не получилось: ждём хука
            }
        }
        return null;
    }

    private static Method methodNamed(Object o, String name, int paramsAtLeast) {
        try {
            for (Method m : o.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length >= paramsAtLeast) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length >= paramsAtLeast) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        } catch (Throwable ignored) {
            // нет метода
        }
        return null;
    }

    /** Список из состояния потока: сам список или первый метод/поле, отдающее список стикеров. */
    private static List<?> listOf(Object state) {
        if (state == null) return null;
        if (state instanceof List) return (List<?>) state;
        Class<?> item = StreakSticker.itemClass();
        List<?> any = null;
        try {
            for (Method m : state.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0 || !List.class.isAssignableFrom(m.getReturnType())) continue;
                m.setAccessible(true);
                Object v = m.invoke(state);
                if (!(v instanceof List)) continue;
                List<?> l = (List<?>) v;
                if (!l.isEmpty() && item != null && item.isInstance(l.get(0))) return l;
                if (any == null) any = l;
            }
            for (Class<?> k = state.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(state);
                    if (!(v instanceof List)) continue;
                    List<?> l = (List<?>) v;
                    if (!l.isEmpty() && item != null && item.isInstance(l.get(0))) return l;
                    if (any == null) any = l;
                }
            }
        } catch (Throwable ignored) {
            // отдаём что нашли
        }
        return any;
    }

    /** Читает избранные из репозитория; возвращает число найденных или -1, если репозитория нет. */
    static int readFavourites() {
        try {
            Object r = repo;
            if (r == null) {
                ClassLoader cl = StreakHooks.loader();
                if (cl != null) r = findRepo(cl);
                if (r != null) repo = r;
            }
            if (r == null) {
                favNote = "репозиторий не получен: откройте в TikTok панель стикеров → избранное";
                return -1;
            }
            Method fm = flowM;
            if (fm == null) {
                fm = methodNamed(r, FLOW, 0);
                flowM = fm;
            }
            if (fm == null) {
                favNote = "метод " + FLOW + " не найден на " + r.getClass().getName();
                return -1;
            }
            Object flow = fm.invoke(r);
            if (flow == null) {
                favNote = "поток избранных пуст";
                return 0;
            }
            Method gv = methodNamed(flow, "getValue", 0);
            if (gv == null) {
                favNote = "у потока нет getValue (" + flow.getClass().getName() + ")";
                return -1;
            }
            List<?> list = listOf(gv.invoke(flow));
            if (list == null) {
                favNote = "в состоянии потока нет списка стикеров";
                return 0;
            }
            int n = 0;
            for (Object o : new ArrayList<Object>(list)) {
                add(o, true);
                n++;
            }
            favCount = n;
            favNote = "избранных в TikTok: " + n;
            return n;
        } catch (Throwable t) {
            Throwable why = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            favNote = "чтение избранных: " + why.getClass().getSimpleName() + " " + why.getMessage();
            return -1;
        }
    }

    /**
     * Просит TikTok загрузить избранные, ждёт до 8 секунд и вызывает done в главном потоке.
     * Вызывается из любого потока.
     */
    static void refresh(final Runnable done) {
        lastRefresh = System.currentTimeMillis();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object r = repo;
                    if (r == null) {
                        ClassLoader cl = StreakHooks.loader();
                        if (cl != null) {
                            r = findRepo(cl);
                            if (r != null) repo = r;
                        }
                    }
                    if (r != null) {
                        Method rm = refreshM;
                        if (rm == null) {
                            rm = methodNamed(r, REFRESH, 0);
                            refreshM = rm;
                        }
                        if (rm != null) {
                            Class<?>[] p = rm.getParameterTypes();
                            Object[] args = new Object[p.length];
                            for (int i = 0; i < p.length; i++) {
                                if (p[i] == boolean.class) args[i] = Boolean.FALSE;
                                else if (p[i] == String.class) args[i] = "ttmlonger";
                                else if (p[i] == int.class) args[i] = 0;
                                else if (p[i] == long.class) args[i] = 0L;
                            }
                            rm.invoke(r, args);
                        }
                    }
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
                StreakRunner.async(new Poll(done, main));
            }
        });
    }

    /** Опрашивает репозиторий раз в 0,6 с (не больше 14 раз), пока избранные не подгрузятся. */
    private static final class Poll implements Runnable {
        private final Runnable done;
        private final Handler main;
        private int tries;

        Poll(Runnable done, Handler main) {
            this.done = done;
            this.main = main;
        }

        @Override
        public void run() {
            int n = readFavourites();
            tries++;
            if ((n > 0 && tries >= 2) || tries >= 14) {
                if (done != null) main.post(done);
                return;
            }
            StreakRunner.later(this, 600L);
        }
    }

    static long lastRefresh() {
        return lastRefresh;
    }

    static String report() {
        return "  избранные стикеры: " + favNote + "; в списке " + count() + " (избранных " + favCount + ")\n";
    }
}
