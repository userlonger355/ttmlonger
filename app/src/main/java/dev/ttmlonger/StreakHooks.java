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

    private static volatile Object message;
    private static volatile Method senderM;
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

    static String status() {
        return status;
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
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

        Class<?> ic = DexHooks.load(cl, Targets.STREAK_SERVICE);
        if (ic == null) {
            status = "в этой версии TikTok нет службы огоньков (IStreakService)";
            return;
        }
        iface = ic;
        dataClass = cls(Targets.STREAK_DATA);
        for (DexScan.Ref ref : r.refs) {
            if (ref.label.equals("const:" + Targets.STREAK_SOURCE) && !constClasses.contains(ref.cls)) {
                constClasses.add(ref.cls);
            }
        }

        Set<String> sigs = new HashSet<>();
        for (Method m : ic.getMethods()) sigs.add(m.getName() + DexHooks.protoOf(m));

        int found = 0;
        for (DexScan.Ref ref : r.refs("impl:streak")) {
            Class<?> c = DexHooks.load(cl, ref.cls);
            if (c == null || !ic.isAssignableFrom(c) || c.isInterface()) {
                DexHooks.note("огонёк: класс " + ref.cls + " не подошёл");
                continue;
            }
            found++;
            implSeen.add(c.getName());
            Method[] ms;
            try {
                ms = c.getDeclaredMethods();
            } catch (Throwable t) {
                DexHooks.note("огонёк: методы " + ref.cls + " недоступны: " + t.getClass().getSimpleName());
                continue;
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
        status = "реализаций службы " + found + ", хуков " + hookCount
                + (dataClass == null ? "; класса StreakData нет" : "")
                + (constClasses.isEmpty() ? "; константы источника в dex нет" : "");
        DexHooks.note("огонёк: " + status);
        StreakRunner.init(ctx);
        // Файлы с известными чатами читаем заранее и в фоне: хук вызывается в потоках TikTok.
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                StreakStore.count();
            }
        });
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

    /** Метод отправки: (Context, ?, ?, источник, стикер, ?, ?, ?, String чат, ..., boolean). */
    private static Method pickSender(Object ms, boolean note) {
        Class<?> item = StreakSticker.itemClass();
        if (item == null) return null;
        Method pick = null;
        int n = 0;
        for (Method m : ms.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 9 || m.getReturnType() != void.class) continue;
            if (!Context.class.isAssignableFrom(p[0])) continue;
            if (!p[4].isAssignableFrom(item)) continue;
            if (p[8] != String.class || p[p.length - 1] != boolean.class) continue;
            if (sourceFor(p[3]) == null) continue;
            n++;
            if (pick == null || p.length == 13) pick = m;
        }
        if (note && n != 1 && pick != null) senderNote = "подходящих методов отправки " + n + ", взят " + pick.getName();
        return pick;
    }

    /** Находит метод отправки и вешает наблюдатель за настоящими отправками стикеров. Главный поток. */
    static boolean ensureSender() {
        if (senderM != null) return true;
        if (StreakSticker.itemClass() == null) {
            senderNote = "класса StickerItem нет в этой версии";
            return false;
        }
        Object ms = messageService();
        if (ms == null) return false;
        Method m = pickSender(ms, true);
        if (m == null) {
            senderNote = "метод отправки не найден на " + ms.getClass().getName();
            return false;
        }
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
            // публичный
        }
        senderM = m;
        if (!senderNote.startsWith("подходящих")) senderNote = "найден " + m.getName() + " (" + m.getParameterTypes().length + " арг.)";
        if (!senderHooked) {
            senderHooked = true;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object[] a = param.args;
                            if (a != null && a.length > 4) StreakSticker.offer(a[4], "отправка стикера в чат");
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
        if (senderM != null) return;
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
            Method sender = senderM;
            Object ms = message;
            Class<?>[] types = sender.getParameterTypes();
            Object source = sourceFor(types[3]);
            if (source == null) {
                senderNote = "источник " + Targets.STREAK_SOURCE + " не найден";
                return false;
            }
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i].isPrimitive()) args[i] = blank(types[i]);
                else if (i == 0) args[i] = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                else if (i == 3) args[i] = source;
                else if (i == 4) args[i] = sticker;
                else if (i == 5) args[i] = options(types[5]);
                else if (i == 8) args[i] = conv;
                else args[i] = null;
            }
            sender.invoke(ms, args);
            senderNote = "последняя отправка: вызов прошёл (" + sender.getName() + "), доставка не подтверждена";
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

    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("  хуки: ").append(status).append('\n');
        sb.append("  служба получена: ").append(service != null ? service.getClass().getName() : "нет").append('\n');
        sb.append("  реализации: ").append(implSeen).append('\n');
        Class<?> dc = dataClass;
        sb.append("  StreakData: ").append(dc == null ? "нет" : fieldsOf(dc)).append('\n');
        sb.append("  чтение: ").append(readM != null ? readM.getName() : "не найдено").append(", запросов ").append(reads)
                .append(", ошибок ").append(readFails).append(", заметка: ").append(readNote).append('\n');
        sb.append("  статус: ").append(statusNote).append('\n');
        sb.append("  отправка: ").append(senderNote).append('\n');
        sb.append("  константа источника в dex: ").append(constClasses).append('\n');
        return sb.toString();
    }
}
