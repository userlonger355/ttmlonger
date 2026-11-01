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
                + "отправка: " + senderNote;
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
        try {
            hookService(r, cl);
        } catch (Throwable t) {
            status = "служба огоньков: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
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
