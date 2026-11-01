package dev.ttmlonger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реклама в ленте. TikTok получает ленту целой страницей (FeedItemList), и каждый пост в ней сам
 * сообщает, реклама ли он: у модели поста есть isAd() и поле _isAd. Поэтому рекламу убираем там, где
 * страницу читают, а не прячем на каждом экране, который мог бы её нарисовать.
 *
 * Что делает мод:
 * - после FeedItemList.getItems() отдаёт тот же список без рекламы и (если включено) без прямых
 *   эфиров, а очищенный список записывает обратно в поле items, чтобы код, читающий поле напрямую,
 *   тоже его получил;
 * - перед остальными простыми методами страницы (getRequestId и т. п.) делает то же самое: так
 *   список очищается, даже если приложение сначала читает другие свойства страницы.
 *
 * Отвечать на isAd() «нет» нельзя: пост остался бы в ленте, просто без пометки о рекламе.
 * Всё, что мод не понял, пропускается нетронутым: лента без рекламы — предпочтение, а лента,
 * которая не грузится, — сломанное приложение. Любой сбой тихий.
 */
final class AdHooks {
    static final String ITEM_LIST = "com.ss.android.ugc.aweme.feed.model.FeedItemList";

    /** Тип поста «прямой эфир» в ленте (awemeType). */
    static final int LIVE_TYPE = 101;

    static final int KEEP = 0;
    static final int AD = 1;
    static final int ROOM = 2;

    /** Сколько простых методов страницы получают проверку «на входе». */
    private static final int MAX_EXTRA = 24;

    private static final StringBuilder STATUS = new StringBuilder();
    private static volatile Class<?> listClass;
    private static volatile Field itemsField;
    private static volatile boolean itemsLooked;
    private static int hookedItems;
    private static int hookedExtra;

    // счётчики для экрана и сведений
    private static volatile int lists;
    private static volatile int posts;
    private static volatile int droppedAds;
    private static volatile int droppedRooms;
    private static volatile int writeBackOk;
    private static volatile int writeBackFail;
    private static volatile int errors;
    private static volatile String lastError = "";
    private static volatile long lastDropAt;

    /** Последний обработанный список и его результат: повторные getItems() не разбирают его заново. */
    private static WeakReference<Object> cacheIn = new WeakReference<>(null);
    private static WeakReference<Object> cacheOut = new WeakReference<>(null);
    private static int cacheInSize = -1;
    private static int cacheOutSize = -1;
    private static int cacheFlags = -1;

    /** Как устроены элементы ленты каждого класса: методы находятся один раз. */
    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();

    private AdHooks() {
    }

    // ------------------------------------------------------------ установка

    static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(ITEM_LIST, cl);
        if (c == null) {
            note("FeedItemList не найден");
            return;
        }
        listClass = c;
        hookedItems = hookNamed(c, "getItems", ITEMS);
        note("getItems " + (hookedItems > 0 ? "✓" : "✗"));

        int extra = 0;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (extra >= MAX_EXTRA) break;
                if (!simpleGetter(m)) continue;
                try {
                    XposedBridge.hookMethod(m, ENTRY);
                    extra++;
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        hookedExtra = extra;
        note("методов «на входе» " + extra);
        Field f = itemsField(c);
        note("поле items " + (f != null ? "✓" : "✗"));
    }

    /** Подходит ли метод как «вход» страницы: открытый, без аргументов, не статический, не getItems, что-то возвращает. */
    static boolean simpleGetter(Method m) {
        int mod = m.getModifiers();
        if (Modifier.isStatic(mod) || Modifier.isAbstract(mod) || m.isSynthetic() || m.isBridge()) return false;
        if (m.getParameterTypes().length != 0 || m.getReturnType() == void.class) return false;
        String n = m.getName();
        if (n.equals("getItems") || n.equals("hashCode") || n.equals("toString") || n.equals("clone")) return false;
        return n.startsWith("get") || n.startsWith("is") || n.startsWith("has");
    }

    private static int hookNamed(Class<?> c, String name, XC_MethodHook hook) {
        try {
            int n = XposedBridge.hookAllMethods(c, name, hook).size();
            if (n > 0) return n;
            // метод мог быть объявлен выше по иерархии
            Method m = DownloadHooks.findMethodNamed(c, name);
            if (m != null) {
                XposedBridge.hookMethod(m, hook);
                return 1;
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return 0;
    }

    private static void note(String s) {
        STATUS.append(s).append("; ");
    }

    // ------------------------------------------------------------ хуки

    private static final XC_MethodHook ITEMS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (CrashGuard.tripped || param.hasThrowable()) return;
                boolean ads = Prefs.hideAds;
                boolean rooms = Prefs.hideLiveRooms;
                if (!ads && !rooms) return;
                Class<?> lc = listClass;
                if (lc != null && !lc.isInstance(param.thisObject)) return;
                Object r = param.getResult();
                if (!(r instanceof List)) return;
                List out = process((List) r, ads, rooms);
                if (out != r) {
                    param.setResult(out);
                    writeBack(param.thisObject, r, out);
                }
            } catch (Throwable t) {
                error(t);
            }
        }
    };

    private static final XC_MethodHook ENTRY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (CrashGuard.tripped) return;
                boolean ads = Prefs.hideAds;
                boolean rooms = Prefs.hideLiveRooms;
                if (!ads && !rooms) return;
                Object page = param.thisObject;
                Class<?> lc = listClass;
                if (page == null || (lc != null && !lc.isInstance(page))) return;
                Field f = itemsField(page.getClass());
                if (f == null) return;
                Object cur = f.get(page);
                if (!(cur instanceof List)) return;
                List out = process((List) cur, ads, rooms);
                if (out != cur) writeBack(page, cur, out);
            } catch (Throwable t) {
                error(t);
            }
        }
    };

    /** Список без нежелательного; если убирать нечего, возвращается тот же объект. Повторы берутся из памяти. */
    static List process(List in, boolean ads, boolean rooms) {
        int flags = (ads ? 1 : 0) | (rooms ? 2 : 0);
        int size = in.size();
        synchronized (AdHooks.class) {
            if (cacheFlags == flags) {
                // тот же исходный список: отдаём прежний результат
                if (cacheInSize == size && cacheIn.get() == in) {
                    Object o = cacheOut.get();
                    if (o instanceof List) return (List) o;
                }
                // это уже очищенный нами список
                if (cacheOutSize == size && cacheOut.get() == in) return in;
            }
        }
        List out = filter(in, ads, rooms);
        synchronized (AdHooks.class) {
            cacheIn = new WeakReference<Object>(in);
            cacheOut = new WeakReference<Object>(out);
            cacheInSize = size;
            cacheOutSize = out.size();
            cacheFlags = flags;
        }
        lists++;
        return out;
    }

    /** Записывает очищенный список в поле items, если там всё ещё исходный. */
    private static void writeBack(Object page, Object was, Object now) {
        try {
            Field f = itemsField(page.getClass());
            if (f == null) return;
            if (f.get(page) != was) return;
            f.set(page, now);
            writeBackOk++;
        } catch (Throwable t) {
            writeBackFail++;
        }
    }

    private static Field itemsField(Class<?> c) {
        if (itemsLooked && itemsField != null && itemsField.getDeclaringClass().isAssignableFrom(c)) return itemsField;
        Field f = DownloadHooks.findField(c, "items");
        if (f != null && !List.class.isAssignableFrom(f.getType())) f = null;
        if (f != null && Modifier.isStatic(f.getModifiers())) f = null;
        if (f != null) itemsField = f;
        itemsLooked = true;
        return f;
    }

    private static void error(Throwable t) {
        errors++;
        lastError = t.getClass().getSimpleName();
    }

    // ------------------------------------------------------------ разбор элементов

    /** Методы и поля одного класса элемента ленты. */
    private static final class Shape {
        final String name;
        final Method isAd;
        final Field adFlag;
        final Method getAweme;
        final Method preciseAd;
        final Method aiAdCard;
        final Method awemeType;
        final Method liveId;
        final Method room;
        final Method author;
        final boolean post;
        volatile int seen;
        volatile int ads;
        volatile int rooms;

        Shape(Class<?> c) {
            name = c.getName();
            Method m = DownloadHooks.findMethodNamed(c, "isAd");
            if (m != null && m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) m = null;
            isAd = m;
            adFlag = DownloadHooks.findField(c, "_isAd");
            post = isAd != null || adFlag != null;
            getAweme = post ? null : DownloadHooks.findMethodNamed(c, "getAweme");
            preciseAd = post ? null : DownloadHooks.findMethodNamed(c, "getPreciseAd");
            aiAdCard = post ? null : DownloadHooks.findMethodNamed(c, "getAiAdCard");
            awemeType = DownloadHooks.findMethodNamed(c, "getAwemeType");
            liveId = DownloadHooks.findMethodNamed(c, "getLiveId");
            room = DownloadHooks.findMethodNamed(c, "getRoomFeedCellStruct");
            author = DownloadHooks.findMethodNamed(c, "getAuthorUid");
        }
    }

    private static Shape shapeOf(Class<?> c) {
        Shape s = SHAPES.get(c);
        if (s == null) {
            s = new Shape(c);
            Shape was = SHAPES.put(c, s);
            if (was != null) s = was;
        }
        return s;
    }

    /** Реклама ли этот пост: isAd() или явный флаг _isAd, который сервер ставит раньше, чем придёт сама реклама. */
    private static boolean advert(Shape s, Object post) throws Exception {
        if (s.isAd != null && Boolean.TRUE.equals(s.isAd.invoke(post))) return true;
        if (s.adFlag != null && Boolean.TRUE.equals(s.adFlag.get(post))) return true;
        return false;
    }

    /** Прямой эфир ли этот пост: тип 101, номер эфира или структура комнаты. */
    private static boolean room(Shape s, Object post) throws Exception {
        if (s.awemeType != null && DownloadHooks.number(s.awemeType.invoke(post)) == LIVE_TYPE) return true;
        if (s.liveId != null && DownloadHooks.number(s.liveId.invoke(post)) != 0) return true;
        return s.room != null && s.room.invoke(post) != null;
    }

    /** KEEP, AD или ROOM для одного элемента ленты. Ничего не понял — KEEP. */
    static int judge(Object item, boolean ads, boolean rooms) {
        if (item == null) return KEEP;
        Shape s = shapeOf(item.getClass());
        s.seen++;
        try {
            Object post = item;
            if (!s.post) {
                // обёртка вокруг поста: рекламная карточка бывает и без самого поста
                if (ads) {
                    if ((s.preciseAd != null && s.preciseAd.invoke(item) != null)
                            || (s.aiAdCard != null && s.aiAdCard.invoke(item) != null)) {
                        s.ads++;
                        return AD;
                    }
                }
                post = s.getAweme == null ? null : s.getAweme.invoke(item);
                if (post == null) return KEEP;
            }
            Shape ps = post == item ? s : shapeOf(post.getClass());
            if (ads && advert(ps, post)) {
                s.ads++;
                return AD;
            }
            if (rooms && room(ps, post) && !mine(ps, post)) {
                s.rooms++;
                return ROOM;
            }
        } catch (Throwable t) {
            error(t);
        }
        return KEEP;
    }

    /** Свой пост не прячется никогда: эти же списки показывают и ваш профиль. */
    private static boolean mine(Shape s, Object post) {
        try {
            String me = StreakHooks.knownOwnUid();
            if (me == null || me.isEmpty() || s.author == null) return false;
            return me.equals(String.valueOf(s.author.invoke(post)));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Тот же список без рекламы (и без эфиров, если rooms). Если убирать нечего или что-то пошло не
     * так, возвращается исходный объект.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static List filter(List items, boolean ads, boolean rooms) {
        if (items == null || (!ads && !rooms)) return items;
        try {
            int n = items.size();
            posts += n;
            ArrayList kept = null;
            int a = 0;
            int r = 0;
            for (int i = 0; i < n; i++) {
                Object item = items.get(i);
                int v = judge(item, ads, rooms);
                if (v != KEEP) {
                    if (kept == null) {
                        kept = new ArrayList(n);
                        kept.addAll(items.subList(0, i));
                    }
                    if (v == AD) a++;
                    else r++;
                } else if (kept != null) {
                    kept.add(item);
                }
            }
            if (kept == null) return items;
            droppedAds += a;
            droppedRooms += r;
            lastDropAt = System.currentTimeMillis();
            return kept;
        } catch (Throwable t) {
            error(t);
            return items;
        }
    }

    // ------------------------------------------------------------ для экрана настроек

    static boolean enabled() {
        return (Prefs.hideAds || Prefs.hideLiveRooms) && !CrashGuard.tripped;
    }

    static boolean installed() {
        return hookedItems > 0 || hookedExtra > 0;
    }

    static String status() {
        if (!Prefs.hideAds && !Prefs.hideLiveRooms) return "Реклама: выключено";
        if (CrashGuard.tripped) return "Реклама: отключена защитой от вылетов";
        if (!installed()) return "Реклама: не установилось (" + STATUS + ")";
        StringBuilder sb = new StringBuilder("Реклама: работает");
        sb.append(" · убрано рекламы ").append(droppedAds);
        if (Prefs.hideLiveRooms) sb.append(", эфиров ").append(droppedRooms);
        if (lists == 0) {
            sb.append("\nСтраницы ленты пока не приходили: пролистайте ленту «Для вас» и обновите её.");
        } else {
            sb.append("\nПроверено страниц ленты: ").append(lists).append(", постов: ").append(posts);
            if (lastDropAt > 0) {
                sb.append("\nПоследний раз убирал ").append(VideoInfo.ageText(
                        Math.max(0, (System.currentTimeMillis() - lastDropAt) / 1000L)));
            }
        }
        return sb.toString();
    }

    /** Сведения для разработчика: в них только названия классов TikTok и числа. */
    static String details() {
        StringBuilder sb = new StringBuilder();
        sb.append("Реклама: ").append(Prefs.hideAds ? "вкл" : "выкл").append(", эфиры в ленте: ")
                .append(Prefs.hideLiveRooms ? "вкл" : "выкл").append('\n');
        sb.append("хуки: ").append(STATUS.length() == 0 ? "-" : STATUS.toString()).append('\n');
        sb.append("страниц ").append(lists).append(", постов ").append(posts).append(", убрано рекламы ")
                .append(droppedAds).append(", эфиров ").append(droppedRooms).append('\n');
        sb.append("запись очищенного списка в поле: ").append(writeBackOk).append(" удачно, ").append(writeBackFail)
                .append(" нет; сбоев ").append(errors).append(errors > 0 ? " (последний " + lastError + ")" : "")
                .append('\n');
        int shown = 0;
        for (Shape s : SHAPES.values()) {
            if (shown++ >= 8) break;
            sb.append("элемент ").append(s.name).append(": ").append(s.seen).append(" шт., реклама ").append(s.ads)
                    .append(", эфиры ").append(s.rooms).append(" [");
            sb.append(s.post ? "пост" : "обёртка");
            if (s.isAd != null) sb.append(" isAd");
            if (s.adFlag != null) sb.append(" _isAd");
            if (s.getAweme != null) sb.append(" getAweme");
            if (s.preciseAd != null) sb.append(" getPreciseAd");
            if (s.aiAdCard != null) sb.append(" getAiAdCard");
            sb.append("]\n");
        }
        if (SHAPES.isEmpty()) sb.append("элементов ленты пока не было\n");
        return sb.toString().trim();
    }
}
