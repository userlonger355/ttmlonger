package dev.ttmlonger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Мост к огонькам TikTok. Имя интерфейса службы огоньков настоящее, а имена методов и класса,
 * который его реализует, обфусцированы и меняются от версии к версии. Поэтому:
 *
 *  - реализацию находит DexScan (класс, у которого в списке интерфейсов служба огоньков);
 *  - хуки на её методы ничего не меняют, а только запоминают сам объект службы и идентификаторы
 *    чатов, про которые TikTok спрашивает (так мод узнаёт, какие чаты проверять);
 *  - метод «прочитать огонёк» — тот, что принимает (String, boolean) и возвращает StreakData;
 *  - метод «статус огонька» — тот, что принимает StreakData и возвращает enum с константами
 *    GREY и ACTIVE;
 *  - отправка: у службы стикеров личных сообщений берётся единственный метод нужной формы
 *    (контекст, источник, стикер, идентификатор чата, флаг), источник — константа
 *    AUTO_CONSECUTIVE_SA_STICKERS, которой TikTok сам помечает такие отправки.
 *
 * Всё это проверено только по чужому описанию и не на вашей версии TikTok, поэтому каждый шаг
 * пишет в отчёт, что нашлось и чего нет.
 */
final class StreakHooks {
    private static volatile ClassLoader loader;
    private static volatile Class<?> iface;
    private static volatile Class<?> dataClass;
    private static volatile Object service;
    private static final Set<String> hooked = new HashSet<>();
    private static final Set<String> implSeen = new HashSet<>();
    private static final List<String> constClasses = new ArrayList<>();
    private static volatile int hookCount;
    private static final String SERVICE_NAME = "IStreakService";
    private static volatile String status = "ещё не искали";

    private static volatile Method readM;
    private static volatile Method statusM;
    private static volatile boolean statusSearched;
    private static volatile Field streakField;
    private static volatile boolean streakFieldSearched;
    private static volatile String readNote = "-";
    private static volatile String statusNote = "-";
    private static volatile int reads;
    private static volatile int readFails;

    // Активный поиск: служба, номер аккаунта и друзья добываются сами, не дожидаясь TikTok.
    private static volatile DexScan.Result scan;
    private static volatile boolean serviceActive;
    private static volatile String acquireNote = "ещё не искали";
    private static volatile String uidNote = "ещё не искали";
    private static volatile String friendsNote = "ещё не искали";
    private static volatile String initNote = "не вызывали";
    private static volatile String ownUid = "";
    private static volatile List<String> friendUids = new ArrayList<>();
    private static volatile Object friendsHolder;
    private static volatile Method friendsM;
    private static volatile long friendsAt;
    private static volatile long lastDiscover;
    private static volatile boolean inited;
    private static volatile int derived;
    private static final List<String> friendClasses = new ArrayList<>();

    private static volatile Object message;
    private static volatile Sender sender;
    private static volatile boolean senderHooked;
    private static volatile String senderNote = "ещё не искали";

    private StreakHooks() {
    }

    static ClassLoader loader() {
        return loader;
    }

    static Object service() {
        return service;
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- состояние

    /** Последнее замечание об отправке: для сообщений об ошибке. */
    static String senderNote() {
        return senderNote;
    }

    /** Сведения для разработчика: что нашлось и чего нет. Идентификаторов чатов здесь нет. */
    static String details() {
        Class<?> ic = iface;
        Class<?> dc = dataClass;
        return "служба: " + status + "\n"
                + "интерфейс " + (ic == null ? "нет" : ic.getName()) + ", данные " + (dc == null ? "нет" : dc.getName())
                + ", объект " + (service == null ? "не получен" : service.getClass().getName()) + "\n"
                + "чтение: " + readNote + "; чтений " + reads + ", сбоев " + readFails + "\n"
                + "статус: " + statusNote + "\n"
                + "отправка: " + senderNote + "\n"
                + "поиск службы: " + acquireNote + (serviceActive ? " (сам)" : "") + "\n"
                + "номер аккаунта: " + uidNote + "\n"
                + "друзья: " + friendsNote + "; чатов по друзьям добавлено всего " + derived + "\n"
                + "инициализация: " + initNote;
    }

    // ---------------------------------------------------------------- подключение

    static void apply(DexScan.Result r, ClassLoader cl, Context ctx) {
        loader = cl;
        if (!Prefs.streakAuto) {
            status = "выключено в настройках (включите и перезапустите TikTok)";
            return;
        }
        if (CrashGuard.tripped) {
            status = "отключено защитой от сбоев";
            return;
        }
        String key = "";
        try {
            File apk = new File(ctx.getApplicationInfo().sourceDir);
            key = apk.length() + ":" + apk.lastModified();
        } catch (Throwable ignored) {
            // без ключа сохранённый стикер просто не восстановится
        }
        StreakSticker.setup(cls(Targets.STICKER_ITEM_CLASS), key);

        // Стикеры и проходы нужны независимо от того, нашлась ли служба огоньков: без них не выбрать стикер.
        try {
            StickerLibrary.apply(r, cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        StreakRunner.init(ctx);
        // Файлы с известными чатами читаем заранее и в фоне: хук вызывается в потоках TikTok.
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                StreakStore.count();
            }
        });

        for (DexScan.Ref ref : r.refs) {
            if (ref.label.equals("const:" + Targets.STREAK_SOURCE) && !constClasses.contains(ref.cls)) {
                constClasses.add(ref.cls);
            }
        }
        scan = r;
        try {
            hookService(r, cl);
        } catch (Throwable t) {
            status = "служба огоньков: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
        try {
            hookFriends(r, cl);
        } catch (Throwable t) {
            friendsNote = "хуки друзей: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
        // Через двадцать секунд после запуска вход в аккаунт, скорее всего, уже выполнен: ищем службу сами.
        StreakRunner.later(new Runnable() {
            @Override
            public void run() {
                discover(true);
            }
        }, 20_000L);
    }

    private static void hookService(DexScan.Result r, ClassLoader cl) {
        Class<?> ic = DexHooks.load(cl, Targets.STREAK_SERVICE);
        List<Class<?>> impls = new ArrayList<>();
        for (DexScan.Ref ref : r.refs("impl:streak")) {
            Class<?> c = DexHooks.load(cl, ref.cls);
            if (c == null || c.isInterface()) continue;
            if (ic == null) ic = interfaceNamed(c, SERVICE_NAME);
            impls.add(c);
        }
        if (ic == null) {
            status = "службы огоньков в этой версии TikTok не нашли";
            return;
        }
        iface = ic;
        Class<?> dc = cls(Targets.STREAK_DATA);
        dataClass = dc != null ? dc : inferData(ic);

        Set<String> sigs = new HashSet<>();
        for (Method m : ic.getMethods()) sigs.add(m.getName() + DexHooks.protoOf(m));

        int found = 0;
        for (Class<?> impl : impls) {
            if (!ic.isAssignableFrom(impl)) continue;
            found++;
            implSeen.add(impl.getName());
            // Метод службы может быть объявлен в родительском классе: идём вверх до платформенных классов.
            for (Class<?> k = impl; k != null && k != Object.class; k = k.getSuperclass()) {
                String kn = k.getName();
                if (kn.startsWith("java.") || kn.startsWith("android.") || kn.startsWith("kotlin.")) break;
                hookDeclared(k, sigs);
            }
        }
        status = "реализаций службы " + found + ", хуков " + hookCount
                + (dataClass == null ? "; класса StreakData нет" : "")
                + (constClasses.isEmpty() ? "; константы источника в dex нет" : "");
    }

    private static void hookDeclared(Class<?> c, Set<String> sigs) {
        Method[] ms;
        try {
            ms = c.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method m : ms) {
            try {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isAbstract(mod) || m.isBridge()) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || p[0] != String.class) continue;
                if (!sigs.contains(m.getName() + DexHooks.protoOf(m))) continue;
                synchronized (hooked) {
                    if (!hooked.add(m.toString())) continue;
                }
                XposedBridge.hookMethod(m, new Seen());
                hookCount++;
            } catch (Throwable t) {
                // один метод не подошёл (например, нет его класса): остальные подключаем
                FlagStore.log(t);
            }
        }
    }

    /** Интерфейс с заданным коротким именем среди интерфейсов класса и его предков. */
    private static Class<?> interfaceNamed(Class<?> c, String simple) {
        try {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Class<?> i : k.getInterfaces()) {
                    String n = i.getName();
                    if (n.equals(simple) || n.endsWith("." + simple)) return i;
                    Class<?> up = interfaceNamed(i, simple);
                    if (up != null) return up;
                }
            }
        } catch (Throwable ignored) {
            // класс ссылается на то, чего нет в загрузчике
        }
        return null;
    }

    /** Класс данных огонька, если его имя другое: тип, который метод службы (String, boolean) возвращает и у которого есть поле streak. */
    private static Class<?> inferData(Class<?> ic) {
        Class<?> only = null;
        int n = 0;
        for (Method m : ic.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            Class<?> rt = m.getReturnType();
            if (p.length != 2 || p[0] != String.class || p[1] != boolean.class) continue;
            if (rt.isPrimitive() || rt == String.class || rt == Object.class || rt.isInterface()) continue;
            n++;
            only = rt;
            try {
                rt.getDeclaredField("streak");
                return rt;
            } catch (Throwable ignored) {
                // не тот
            }
        }
        return n == 1 ? only : null;
    }

    /** Хук-наблюдатель: ничего не меняет, запоминает службу и идентификатор чата. */
    private static final class Seen extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object self = param.thisObject;
                if (self == null) return;
                Object cur = service;
                if (cur == null) {
                    service = self;
                    prepareSender();
                    StreakRunner.request(3000L);
                } else if (cur != self && cur.getClass() == self.getClass()) {
                    service = self;
                }
                Object[] a = param.args;
                if (a != null && a.length > 0 && a[0] instanceof String && StreakStore.note((String) a[0])) {
                    StreakRunner.request(8000L);
                }
            } catch (Throwable ignored) {
                // наблюдатель не должен мешать TikTok
            }
        }
    }

    // ---------------------------------------------------------------- активный поиск

    /** Как называется служба в этой версии: интерфейс, который нашли при разборе dex, или известное имя. */
    private static Class<?> serviceInterface() {
        Class<?> ic = iface;
        if (ic == null) ic = DexHooks.load(loader, Targets.STREAK_SERVICE);
        return ic;
    }

    /**
     * Достаёт службу огоньков сам: по найденному в dex статическому методу без параметров, который
     * её отдаёт, по синглтону в классах-реализациях и через ServiceManager. Работает в фоновом потоке.
     */
    private static Object acquire(Class<?> ic) {
        StringBuilder why = new StringBuilder();
        DexScan.Result r = scan;
        if (r != null) {
            List<DexScan.Ref> refs = r.refs("act:streak-get");
            if (refs.isEmpty()) why.append("метода-доступа в dex нет; ");
            for (DexScan.Ref ref : refs) {
                try {
                    Method m = DexHooks.findMethod(loader, ref.cls, ref.name, ref.proto);
                    if (m == null || !Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v != null && ic.isInstance(v)) {
                        acquireNote = "получена статическим методом " + ref.name + " в " + ref.cls;
                        return v;
                    }
                    why.append(ref.name).append(" вернул ").append(v == null ? "null" : v.getClass().getSimpleName()).append("; ");
                } catch (Throwable t) {
                    why.append("метод ").append(ref.name).append(": ").append(causeOf(t)).append("; ");
                }
            }
        }
        for (String name : new ArrayList<>(implSeen)) {
            Object v = Instances.find(cls(name));
            if (v != null && ic.isInstance(v)) {
                acquireNote = "получена как синглтон " + name;
                return v;
            }
        }
        for (String holder : new String[]{"com.ss.android.ugc.aweme.framework.services.ServiceManager"}) {
            try {
                Class<?> sm = Class.forName(holder, false, loader);
                Object inst = sm.getMethod("get").invoke(null);
                Method gs = null;
                for (Method m : sm.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("getService") && p.length == 1 && p[0] == Class.class) gs = m;
                }
                if (inst == null || gs == null) {
                    why.append("ServiceManager без getService; ");
                    continue;
                }
                Object v = gs.invoke(inst, ic);
                if (v != null && ic.isInstance(v)) {
                    acquireNote = "получена через ServiceManager";
                    return v;
                }
                why.append("ServiceManager вернул ").append(v == null ? "null" : v.getClass().getSimpleName()).append("; ");
            } catch (ClassNotFoundException e) {
                why.append("ServiceManager в этой версии нет; ");
            } catch (Throwable t) {
                why.append("ServiceManager: ").append(causeOf(t)).append("; ");
            }
        }
        acquireNote = "не получена: " + why;
        return null;
    }

    private static String causeOf(Throwable t) {
        Throwable why = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        return why.getClass().getSimpleName() + (why.getMessage() == null ? "" : " " + why.getMessage());
    }

    /**
     * Единственный публичный метод службы без параметров и без результата, которого нет в интерфейсе
     * (инициализация после входа в аккаунт): вызывается один раз, и только если служба получена самим
     * модом. Если таких методов несколько, ничего не вызывается.
     */
    private static void initService(final Object svc, Class<?> ic) {
        if (inited) return;
        inited = true;
        Method only = null;
        int n = 0;
        StringBuilder names = new StringBuilder();
        try {
            for (Method m : svc.getClass().getMethods()) {
                Class<?> owner = m.getDeclaringClass();
                if (owner == Object.class || owner.isInterface() || Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 0 || m.getReturnType() != void.class) continue;
                String on = owner.getName();
                if (on.startsWith("java.") || on.startsWith("android.") || on.startsWith("kotlin.")) continue;
                boolean inIface = false;
                for (Method im : ic.getMethods()) {
                    if (im.getName().equals(m.getName()) && im.getParameterTypes().length == 0) inIface = true;
                }
                if (inIface) continue;
                n++;
                only = m;
                if (names.length() < 80) names.append(m.getName()).append(' ');
            }
        } catch (Throwable t) {
            initNote = "поиск инициализации: " + causeOf(t);
            return;
        }
        if (n != 1) {
            initNote = n == 0 ? "метода инициализации нет" : "кандидатов " + n + " (" + names.toString().trim() + "), не вызываю";
            return;
        }
        final Method m = only;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    m.setAccessible(true);
                    m.invoke(svc);
                    initNote = "вызван " + m.getName();
                } catch (Throwable t) {
                    initNote = "вызов " + m.getName() + ": " + causeOf(t);
                }
            }
        });
    }

    /** Номер аккаунта владельца, если он уже известен (ничего не ищет и не вызывает). */
    static String knownOwnUid() {
        String have = ownUid;
        return have.isEmpty() ? null : have;
    }

    /** Номер аккаунта владельца: из службы аккаунта TikTok, а если не вышло, по общему номеру в известных чатах. */
    private static String ownUid() {
        String have = ownUid;
        if (!have.isEmpty()) return have;
        StringBuilder why = new StringBuilder();
        DexScan.Result r = scan;
        Class<?> ac = null;
        try {
            ac = Class.forName("com.ss.android.ugc.aweme.IAccountUserService", false, loader);
        } catch (Throwable ignored) {
            why.append("IAccountUserService нет; ");
        }
        if (r != null && ac != null) {
            for (DexScan.Ref ref : r.refs("act:account-user")) {
                try {
                    Method m = DexHooks.findMethod(loader, ref.cls, ref.name, ref.proto);
                    if (m == null || !Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                    m.setAccessible(true);
                    Object svc = m.invoke(null);
                    if (svc == null || !ac.isInstance(svc)) continue;
                    Object v = DownloadHooks.call(svc, "getCurUserId");
                    String uid = v instanceof String ? (String) v : v instanceof Number ? String.valueOf(((Number) v).longValue()) : null;
                    if (StreakLogic.isUid(uid)) {
                        ownUid = uid;
                        uidNote = "из службы аккаунта (" + uid.length() + " цифр)";
                        return uid;
                    }
                    why.append("getCurUserId пусто; ");
                } catch (Throwable t) {
                    why.append("служба аккаунта: ").append(causeOf(t)).append("; ");
                }
            }
        }
        String common = StreakLogic.commonUid(StreakStore.conversations());
        if (common != null) {
            ownUid = common;
            uidNote = "по общему номеру в известных чатах (" + common.length() + " цифр)";
            return common;
        }
        uidNote = "не получен: " + why + "общего номера в чатах пока нет";
        return null;
    }

    /** Номер аккаунта у объекта пользователя из списка друзей. */
    static String uidOf(Object user) {
        if (user == null) return null;
        for (String name : new String[]{"getUid", "getUserId", "getUidStr", "getId"}) {
            Object v = DownloadHooks.call(user, name);
            String s = v instanceof String ? (String) v : v instanceof Number ? String.valueOf(((Number) v).longValue()) : null;
            if (StreakLogic.isUid(s)) return s;
        }
        for (String name : new String[]{"uid", "userId", "id"}) {
            Field f = DownloadHooks.findField(user.getClass(), name);
            if (f == null) continue;
            try {
                Object v = f.get(user);
                String s = v instanceof String ? (String) v : v instanceof Number ? String.valueOf(((Number) v).longValue()) : null;
                if (StreakLogic.isUid(s)) return s;
            } catch (Throwable ignored) {
                // другое поле
            }
        }
        return null;
    }

    /** Ставит хуки на getAllFriends: когда TikTok сам запрашивает друзей, мод берёт их список. */
    private static void hookFriends(DexScan.Result r, ClassLoader cl) {
        int hooks = 0;
        for (DexScan.Ref ref : r.refs) {
            if (!ref.label.equals("method:" + Targets.FRIEND_METHODS[0])) continue;
            Class<?> c = DexHooks.load(cl, ref.cls);
            if (c == null) continue;
            Method m = DexHooks.findMethod(cl, ref.cls, ref.name, ref.proto);
            if (m == null || Modifier.isAbstract(m.getModifiers()) || Modifier.isStatic(m.getModifiers())
                    || m.getParameterTypes().length != 0 || !List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            synchronized (hooked) {
                if (!hooked.add(m.toString())) continue;
            }
            if (!friendClasses.contains(c.getName())) friendClasses.add(c.getName());
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object self = param.thisObject;
                            if (self != null && friendsHolder == null) {
                                friendsHolder = self;
                                friendsM = (Method) param.method;
                            }
                            Object res = param.getResult();
                            long now = android.os.SystemClock.uptimeMillis();
                            if (res instanceof List && now - friendsAt > 60_000L) {
                                friendsAt = now;
                                final List<Object> copy = new ArrayList<Object>((List<?>) res);
                                StreakRunner.async(new Runnable() {
                                    @Override
                                    public void run() {
                                        onFriends(copy);
                                    }
                                });
                            }
                        } catch (Throwable ignored) {
                            // не мешаем TikTok
                        }
                    }
                });
                hooks++;
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        friendsNote = friendClasses.isEmpty() ? "класса со списком друзей в dex нет"
                : "хуков " + hooks + ", списка друзей ещё не получали";
    }

    /** Список друзей получен: запоминаем номера и превращаем их в идентификаторы чатов. */
    static void onFriends(List<?> users) {
        List<String> uids = new ArrayList<>();
        int bad = 0;
        for (Object u : users) {
            if (uids.size() >= 600) break;
            String uid = uidOf(u);
            if (uid == null) bad++;
            else uids.add(uid);
        }
        friendUids = uids;
        friendsNote = "друзей в списке " + users.size() + ", с номером " + uids.size() + (bad > 0 ? ", без номера " + bad : "");
        applyFriends();
    }

    private static void applyFriends() {
        List<String> uids = friendUids;
        if (uids.isEmpty()) return;
        String own = ownUid();
        if (own == null) {
            friendsNote += "; свой номер аккаунта неизвестен, чаты не построены";
            return;
        }
        int added = 0;
        for (String f : uids) {
            String conv = StreakLogic.convId(own, f);
            if (conv != null && StreakStore.noteDerived(conv)) added++;
        }
        derived += added;
        friendsNote += "; чатов добавлено " + added;
        if (added > 0) {
            StreakStore.flush();
            StreakRunner.request(3000L);
        }
    }

    /**
     * Активные шаги: достать службу, узнать свой номер аккаунта, взять список друзей. Вызывается из
     * фонового потока перед каждым проходом (не чаще раза в 30 секунд, если не force).
     */
    static synchronized void discover(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastDiscover < 30_000L) return;
        lastDiscover = now;
        if (!Prefs.streakAuto || CrashGuard.tripped || loader == null) return;
        try {
            if (service == null) {
                Class<?> ic = serviceInterface();
                if (ic == null) {
                    acquireNote = "интерфейса IStreakService нет в этой версии";
                } else {
                    Object s = acquire(ic);
                    if (s != null) {
                        if (iface == null) iface = ic;
                        if (dataClass == null) {
                            Class<?> dc = cls(Targets.STREAK_DATA);
                            dataClass = dc != null ? dc : inferData(ic);
                        }
                        service = s;
                        serviceActive = true;
                        initService(s, ic);
                        StreakRunner.request(2000L);
                    }
                }
            }
        } catch (Throwable t) {
            acquireNote = "поиск службы: " + causeOf(t);
            FlagStore.log(t);
        }
        try {
            // Свой номер мог появиться позже (вошли в аккаунт): строим чаты по уже известному списку друзей.
            if (!friendUids.isEmpty()) applyFriends();
            Object holder = friendsHolder;
            if (holder == null) {
                for (String name : new ArrayList<>(friendClasses)) {
                    holder = Instances.find(cls(name));
                    if (holder != null) {
                        friendsHolder = holder;
                        break;
                    }
                }
            }
            Method fm = friendsM;
            if (holder != null && friendUids.isEmpty() && now - friendsAt > 60_000L) {
                if (fm == null) fm = DownloadHooks.findMethodNamed(holder.getClass(), Targets.FRIEND_METHODS[0]);
                if (fm != null) {
                    friendsM = fm;
                    friendsAt = now;
                    Object res = fm.invoke(holder);
                    if (res instanceof List) onFriends(new ArrayList<Object>((List<?>) res));
                }
            }
        } catch (Throwable t) {
            friendsNote = "список друзей: " + causeOf(t);
        }
    }

    // ---------------------------------------------------------------- чтение огонька

    static final class Info {
        /** Дней подряд; -1 — не удалось прочитать. */
        final int streak;
        /** Имя статуса из enum TikTok (GREY, ACTIVE...) или null. */
        final String status;

        Info(int streak, String status) {
            this.streak = streak;
            this.status = status;
        }
    }

    /** @return null, если прочитать нельзя (служба ещё не получена или нет метода) */
    static Info read(String conv, boolean fresh) {
        Object svc = service;
        Class<?> dc = dataClass;
        Class<?> ic = iface;
        if (svc == null || dc == null || ic == null) return null;
        try {
            Method m = readM;
            if (m == null) {
                m = findReader(ic, dc);
                if (m == null) {
                    readNote = "метод чтения огонька (String, boolean → StreakData) не найден";
                    return null;
                }
                readM = m;
            }
            Object d = m.invoke(svc, conv, fresh);
            reads++;
            if (d == null) return new Info(0, null);
            return new Info(streakOf(dc, d), statusOf(svc, d));
        } catch (Throwable t) {
            readFails++;
            Throwable why = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            readNote = "чтение: " + why.getClass().getSimpleName() + " " + why.getMessage();
            return null;
        }
    }

    private static Method findReader(Class<?> ic, Class<?> dc) {
        Method best = null;
        int n = 0;
        for (Method m : ic.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == String.class && p[1] == boolean.class && m.getReturnType() == dc) {
                n++;
                if (best == null) best = m;
            }
        }
        if (n > 1) readNote = "подходящих методов чтения " + n + ", взят " + best.getName();
        return best;
    }

    private static int streakOf(Class<?> dc, Object d) {
        Field f = streakField;
        if (f == null && !streakFieldSearched) {
            streakFieldSearched = true;
            try {
                f = dc.getField("streak");
            } catch (Throwable e1) {
                try {
                    f = dc.getDeclaredField("streak");
                    f.setAccessible(true);
                } catch (Throwable e2) {
                    f = null;
                }
            }
            if (f != null && f.getType() != int.class && f.getType() != long.class && f.getType() != Integer.class) {
                f = null;
            }
            if (f == null) f = guessStreakField(dc);
            streakField = f;
            if (f == null) readNote = "в StreakData нет числового поля streak; поля: " + fieldsOf(dc);
        }
        if (f == null) return -1;
        try {
            Object v = f.get(d);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Поле называется иначе: берём числовое поле со словом streak в имени, а если оно единственное числовое, то его. */
    private static Field guessStreakField(Class<?> dc) {
        Field named = null;
        Field only = null;
        int n = 0;
        try {
            for (Field f : dc.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t != int.class && t != long.class) continue;
                n++;
                only = f;
                if (named == null && f.getName().toLowerCase(java.util.Locale.ROOT).contains("streak")) named = f;
            }
        } catch (Throwable ignored) {
            return null;
        }
        Field pick = named != null ? named : (n == 1 ? only : null);
        if (pick != null) {
            try {
                pick.setAccessible(true);
            } catch (Throwable ignored) {
                return null;
            }
            readNote = "поле дней найдено по догадке: " + pick.getName();
        }
        return pick;
    }

    private static String fieldsOf(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : c.getDeclaredFields()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName());
            }
        } catch (Throwable t) {
            sb.append("?");
        }
        return sb.toString();
    }

    private static String statusOf(Object svc, Object data) {
        Method m = statusM;
        if (m == null && !statusSearched) {
            statusSearched = true;
            m = findStatus(svc, data);
            statusM = m;
        }
        if (m == null) return null;
        try {
            Object v = m.invoke(svc, data);
            if (v == null) return null;
            return v instanceof Enum ? ((Enum<?>) v).name() : String.valueOf(v);
        } catch (Throwable t) {
            Throwable why = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            statusNote = "статус: " + why.getClass().getSimpleName() + " " + why.getMessage();
            return null;
        }
    }

    /** Метод службы «StreakData → enum»; из нескольких берётся тот, где есть GREY и ACTIVE. */
    private static Method findStatus(Object svc, Object data) {
        List<Method> all = new ArrayList<>();
        for (Method m : svc.getClass().getMethods()) all.add(m);
        for (Class<?> c = svc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) all.add(m);
            } catch (Throwable ignored) {
                // класс ссылается на то, чего нет
            }
        }
        Method fallback = null;
        int cands = 0;
        for (Method m : all) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !p[0].isInstance(data) || !m.getReturnType().isEnum()) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            cands++;
            if (fallback == null) fallback = m;
            boolean grey = false;
            boolean active = false;
            Object[] cs = m.getReturnType().getEnumConstants();
            if (cs != null) {
                for (Object c : cs) {
                    String n = ((Enum<?>) c).name();
                    if (StreakLogic.GREY.equals(n)) grey = true;
                    if (StreakLogic.ACTIVE.equals(n)) active = true;
                }
            }
            if (grey && active) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // публичный метод и так вызовется
                }
                statusNote = "метод " + m.getName() + " (кандидатов " + cands + "+)";
                return m;
            }
        }
        if (fallback != null) {
            try {
                fallback.setAccessible(true);
            } catch (Throwable ignored) {
                // см. выше
            }
            statusNote = "метод " + fallback.getName() + " без GREY/ACTIVE в enum (кандидатов " + cands + ")";
            return fallback;
        }
        statusNote = "метод статуса (StreakData → enum) не найден";
        return null;
    }

    // ---------------------------------------------------------------- отправка стикера

    /** Служба стикеров личных сообщений: у IMStickerApi берётся экземпляр и его getImStickerMessageService. */
    private static Object messageService() {
        Object have = message;
        if (have != null) return have;
        try {
            Class<?> api = cls(Targets.STICKER_API);
            if (api == null) {
                senderNote = "IMStickerApi нет в этой версии";
                return null;
            }
            Object instance = null;
            for (Field field : api.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object holder = field.get(null);
                if (holder == null) continue;
                if (api.isInstance(holder)) {
                    instance = holder;
                    break;
                }
                for (Method m : holder.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 0 && api.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        instance = m.invoke(holder);
                        break;
                    }
                }
                if (instance != null) break;
            }
            if (instance == null) {
                senderNote = "экземпляр IMStickerApi не получен";
                return null;
            }
            Object ms = null;
            try {
                Method m = instance.getClass().getMethod("getImStickerMessageService");
                m.setAccessible(true);
                ms = m.invoke(instance);
            } catch (NoSuchMethodException e) {
                // другая версия: ищем метод без аргументов, чей результат умеет отправлять стикер
                for (Method m : instance.getClass().getMethods()) {
                    if (m.getParameterTypes().length != 0 || m.getReturnType().isPrimitive()
                            || m.getDeclaringClass() == Object.class) continue;
                    try {
                        m.setAccessible(true);
                        Object cand = m.invoke(instance);
                        if (cand != null && pickSender(cand, false) != null) {
                            ms = cand;
                            break;
                        }
                    } catch (Throwable ignored) {
                        // не тот метод
                    }
                }
            }
            if (ms == null) senderNote = "служба стикеров сообщений не получена";
            else message = ms;
            return ms;
        } catch (Throwable t) {
            senderNote = "служба стикеров: " + t.getClass().getSimpleName() + " " + t.getMessage();
            return null;
        }
    }

    /** Источник отправки: константа AUTO_CONSECUTIVE_SA_STICKERS в enum, который принимает метод. */
    private static Object sourceFor(Class<?> contract) {
        if (contract == null || contract.isPrimitive()) return null;
        Object v = constant(contract, Targets.STREAK_SOURCE);
        if (v != null) return v;
        for (String desc : constClasses) {
            Class<?> c = DexHooks.load(loader, desc);
            if (c == null || !c.isEnum() || !contract.isAssignableFrom(c)) continue;
            v = constant(c, Targets.STREAK_SOURCE);
            if (v != null) return v;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> type, String name) {
        if (!type.isEnum()) return null;
        try {
            return Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Найденный метод отправки и номера его важных аргументов. */
    private static final class Sender {
        final Method m;
        final int ctx;
        final int source;
        final int sticker;
        final int conv;

        Sender(Method m, int ctx, int source, int sticker, int conv) {
            this.m = m;
            this.ctx = ctx;
            this.source = source;
            this.sticker = sticker;
            this.conv = conv;
        }
    }

    private static boolean platform(Class<?> c) {
        String n = c.getName();
        return c.isPrimitive() || n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.");
    }

    /**
     * Метод отправки. Ждём форму (Context, ?, ?, источник, стикер, ?, ?, ?, String чат, ..., boolean),
     * но если у этой версии TikTok число аргументов другое, берём любой void-метод, в котором есть
     * стикер, источник-enum с константой AUTO_CONSECUTIVE_SA_STICKERS и строка после стикера (чат).
     */
    private static Sender pickSender(Object ms, boolean note) {
        Class<?> item = StreakSticker.itemClass();
        Sender best = null;
        int bestScore = -1;
        int n = 0;
        for (Method m : ms.getClass().getMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 5 || p.length > 20) continue;
            int st = -1;
            int so = -1;
            for (int i = 0; i < p.length; i++) {
                if (st < 0 && !platform(p[i])
                        && ((item != null && p[i].isAssignableFrom(item)) || StreakSticker.isStickerClass(p[i]))) {
                    st = i;
                }
                if (so < 0 && p[i].isEnum() && sourceFor(p[i]) != null) so = i;
            }
            if (st < 0 || so < 0) continue;
            int cv = -1;
            if (p.length > 8 && p[8] == String.class && st == 4 && so == 3) {
                cv = 8;
            } else {
                for (int i = st + 1; i < p.length && cv < 0; i++) {
                    if (p[i] == String.class) cv = i;
                }
            }
            if (cv < 0) continue;
            int ctx = Context.class.isAssignableFrom(p[0]) ? 0 : -1;
            boolean exact = p.length >= 9 && ctx == 0 && st == 4 && so == 3 && cv == 8
                    && p[p.length - 1] == boolean.class;
            int score = (exact ? 100 : 50) + (p.length == 13 ? 1 : 0);
            n++;
            if (score > bestScore) {
                bestScore = score;
                best = new Sender(m, ctx, so, st, cv);
            }
        }
        if (note && n > 1 && best != null) {
            senderNote = "подходящих методов отправки " + n + ", взят " + best.m.getName();
        }
        return best;
    }

    /** Находит метод отправки и вешает наблюдатель за настоящими отправками стикеров. Главный поток. */
    static boolean ensureSender() {
        if (sender != null) return true;
        Object ms = messageService();
        if (ms == null) return false;
        final Sender sp = pickSender(ms, true);
        if (sp == null) {
            senderNote = "метод отправки не найден на " + ms.getClass().getName();
            return false;
        }
        try {
            sp.m.setAccessible(true);
        } catch (Throwable ignored) {
            // публичный
        }
        sender = sp;
        StreakSticker.adoptClass(sp.m.getParameterTypes()[sp.sticker]);
        if (!senderNote.startsWith("подходящих")) {
            senderNote = "найден " + sp.m.getName() + " (" + sp.m.getParameterTypes().length + " арг.)";
        }
        if (!senderHooked) {
            senderHooked = true;
            try {
                XposedBridge.hookMethod(sp.m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object[] a = param.args;
                            if (a != null && a.length > sp.sticker) StreakSticker.offer(a[sp.sticker], "отправка стикера в чат");
                        } catch (Throwable ignored) {
                            // не мешаем отправке
                        }
                    }
                });
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        return true;
    }

    /** Подготовка в фоне: найти отправку на главном потоке, чтобы поймать первый настоящий стикер. */
    static void prepareSender() {
        if (sender != null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureSender();
                } catch (Throwable t) {
                    senderNote = "поиск отправки: " + t.getClass().getSimpleName();
                    FlagStore.log(t);
                }
            }
        });
    }

    /** Отправляет стикер в чат; true — вызов прошёл без ошибки (доставку TikTok не подтверждает). */
    static boolean send(final Context ctx, final Object sticker, final String conv) {
        if (Looper.myLooper() == Looper.getMainLooper()) return sendOnMain(ctx, sticker, conv);
        FutureTask<Boolean> task = new FutureTask<>(new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return sendOnMain(ctx, sticker, conv);
            }
        });
        if (!new Handler(Looper.getMainLooper()).post(task)) return false;
        try {
            return task.get(8, TimeUnit.SECONDS);
        } catch (Throwable e) {
            task.cancel(false);
            senderNote = "отправка не уложилась в 8 с: " + e.getClass().getSimpleName();
            return false;
        }
    }

    private static boolean sendOnMain(Context ctx, Object sticker, String conv) {
        try {
            if (!ensureSender()) return false;
            Sender sp = sender;
            Object ms = message;
            Class<?>[] types = sp.m.getParameterTypes();
            Object source = sourceFor(types[sp.source]);
            if (source == null) {
                senderNote = "источник " + Targets.STREAK_SOURCE + " не найден";
                return false;
            }
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i].isPrimitive()) args[i] = blank(types[i]);
                else if (i == sp.ctx) args[i] = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                else if (i == sp.source) args[i] = source;
                else if (i == sp.sticker) args[i] = sticker;
                else if (i == sp.sticker + 1) args[i] = options(types[i]);
                else if (i == sp.conv) args[i] = conv;
                else args[i] = null;
            }
            sp.m.invoke(ms, args);
            senderNote = "последняя отправка: вызов прошёл (" + sp.m.getName() + "), доставка не подтверждена";
            return true;
        } catch (Throwable t) {
            Throwable why = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            StackTraceElement[] st = why.getStackTrace();
            senderNote = "отправка не удалась: " + why.getClass().getSimpleName() + " " + why.getMessage()
                    + (st != null && st.length > 0 ? " at " + st[0] : "");
            return false;
        }
    }

    private static Object blank(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    /** Объект настроек отправки: конструктор с одним непримитивным аргументом, которому даём null. */
    private static Object options(Class<?> type) {
        try {
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != 1 || p[0].isPrimitive()) continue;
                c.setAccessible(true);
                return c.newInstance(new Object[]{null});
            }
        } catch (Throwable ignored) {
            // отправка пойдёт с null
        }
        return null;
    }

    // ---------------------------------------------------------------- отчёт

}
