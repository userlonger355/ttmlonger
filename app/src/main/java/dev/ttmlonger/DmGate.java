package dev.ttmlonger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Модель настроек личных сообщений, которую TikTok получает с сервера: в ней есть поля
 * {@code voice_message_entrance_status} (показывать ли кнопку записи голосового) и
 * {@code voice_message_display_status} (показывать ли голосовые, а не заглушку). Для аккаунта с регионом
 * Россия сервер, судя по заглушке «не поддерживается в вашем регионе», присылает «выключено».
 *
 * <p>Класс модели обфусцирован, но в его {@code toString()} лежат строки с настоящими именами полей:
 * по ним класс находится при разборе dex. Имена полей в классе тоже обфусцированы, поэтому соответствие
 * «имя в toString → поле» узнаётся опытом: поле временно получает приметное число, и смотрим, у какого
 * имени оно появилось в {@code toString()}. Потом поля голосовых (а при совпадении по имени — обоев и пузыря)
 * со значением 0 или «нет значения» получают 1.
 *
 * <p>Это попытка, а не проверенное решение: что именно означают значения, мод не знает; он записывает,
 * какие значения пришли с сервера, чтобы это можно было уточнить. Значения вне 0 и «нет» не трогаются.
 */
final class DmGate {
    /** Строки из toString модели: по ним класс находится в dex. */
    static final String[] MARKERS = {", voice_message_entrance_status=", ", voice_message_display_status="};

    private static final int MAX_CLASSES = 3;
    private static final int SENTINEL = 7_100_001;
    private static final Pattern NAME = Pattern.compile("(?:[{(]|, )([A-Za-z][A-Za-z0-9_]*)=");
    private static final String[] FEATURE = {"voice_message", "wallpaper", "bubble", "chat_customize"};
    private static final String[] SWITCH = {"entrance", "display", "enable", "status", "support", "allow", "show",
            "avail"};
    /** Имена, значения которых безопасно показывать в отчёте (настройки, а не идентификаторы). */
    private static final String[] SHOWABLE = {"status", "enable", "switch", "mode", "support", "allow", "show",
            "display", "entrance", "region", "limit", "avail"};

    private static final ConcurrentHashMap<Class<?>, Model> MODELS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();
    private static volatile String note = "не ставился";
    private static volatile int classes;

    private DmGate() {
    }

    /** Что известно про один класс модели. */
    static final class Model {
        final Class<?> cls;
        /** Имя в toString → поле; пусто, пока опыт не сделан. */
        final Map<String, Field> fields = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();
        final Map<String, String> first = new LinkedHashMap<>();
        final Map<String, String> forced = new LinkedHashMap<>();
        boolean built;
        String problem = "";
        int objects;

        Model(Class<?> cls) {
            this.cls = cls;
        }
    }

    // ------------------------------------------------------------ чистая логика

    /** Имена полей из текста вида «Имя{a=1, b=2}» или «Имя(a=1, b=2)» или «, a=1, b=2». */
    static List<String> parseNames(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = NAME.matcher(text);
        while (m.find()) {
            String n = m.group(1);
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    /** Имя, у которого в тексте стоит заданное значение: «…, name=значение, …». */
    static String nameBefore(String text, String value) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?:[{(]|, )([A-Za-z][A-Za-z0-9_]*)=" + Pattern.quote(value)
                + "(?![0-9])").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Нужно ли включать поле с таким именем: функция личных сообщений и слово про доступность. */
    static boolean wanted(String name) {
        if (name == null) return false;
        String l = name.toLowerCase(Locale.ROOT);
        boolean feature = false;
        for (String f : FEATURE) {
            if (l.contains(f)) {
                feature = true;
                break;
            }
        }
        if (!feature) return false;
        for (String s : SWITCH) {
            if (l.contains(s)) return true;
        }
        return false;
    }

    static boolean showable(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        for (String s : SHOWABLE) {
            if (l.contains(s)) return true;
        }
        return false;
    }

    private static boolean numeric(Class<?> t) {
        return t == int.class || t == Integer.class || t == long.class || t == Long.class;
    }

    private static boolean isZero(Object v) {
        if (v == null) return true;
        if (v instanceof Integer) return ((Integer) v) == 0;
        if (v instanceof Long) return ((Long) v) == 0L;
        return false;
    }

    private static void setNumber(Field f, Object obj, long v) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) f.setInt(obj, (int) v);
        else if (t == long.class) f.setLong(obj, v);
        else if (t == Integer.class) f.set(obj, Integer.valueOf((int) v));
        else f.set(obj, Long.valueOf(v));
    }

    private static String shown(Object v) {
        return v == null ? "нет" : String.valueOf(v);
    }

    /**
     * Опыт с приметными числами: каждому числовому полю по очереди ставится своё число, и по toString видно, как
     * называется поле в протоколе. Значения возвращаются на место. Вызывать на свежем объекте.
     */
    static void build(Model m, Object sample) {
        if (m.built) return;
        m.built = true;
        try {
            String base = String.valueOf(sample);
            m.names.addAll(parseNames(base));
            int k = 0;
            for (Class<?> c = m.cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !numeric(f.getType())) continue;
                    f.setAccessible(true);
                    Object before = f.get(sample);
                    long mark = SENTINEL + k++;
                    String name = null;
                    try {
                        setNumber(f, sample, mark);
                        name = nameBefore(String.valueOf(sample), Long.toString(mark));
                    } finally {
                        if (before == null) f.set(sample, null);
                        else setNumber(f, sample, ((Number) before).longValue());
                    }
                    if (name != null && !m.fields.containsKey(name)) m.fields.put(name, f);
                }
            }
            for (String n : parseNames(String.valueOf(sample))) {
                if (!m.names.contains(n)) m.names.add(n);
            }
            for (String n : m.fields.keySet()) {
                if (!m.names.contains(n)) m.names.add(n);
            }
            if (m.fields.isEmpty()) m.problem = "числовых полей по toString не определено";
        } catch (Throwable t) {
            m.problem = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        }
    }

    /** Включает нужные поля (0 или «нет» → 1), запоминает, что было. */
    static void force(Model m, Object obj) {
        m.objects++;
        for (Map.Entry<String, Field> e : m.fields.entrySet()) {
            String name = e.getKey();
            Field f = e.getValue();
            try {
                Object v = f.get(obj);
                if (!m.first.containsKey(name) && (wanted(name) || showable(name))) m.first.put(name, shown(v));
                if (!wanted(name) || !isZero(v)) continue;
                setNumber(f, obj, 1L);
                m.forced.put(name, shown(v) + " → 1");
            } catch (Throwable t) {
                m.problem = t.getClass().getSimpleName();
            }
        }
    }

    // ------------------------------------------------------------ установка

    /** Находит классы модели по строкам из их toString и ставит хуки. */
    static void apply(DexScan.Result r, ClassLoader cl) {
        if (!Prefs.dmGate) {
            note = "выключено";
            return;
        }
        List<Class<?>> found = new ArrayList<>();
        for (String marker : MARKERS) {
            for (DexScan.Ref ref : r.refs("const:" + marker)) {
                Class<?> c = DexHooks.load(cl, ref.cls);
                if (c != null && !found.contains(c) && found.size() < MAX_CLASSES) found.add(c);
            }
        }
        if (found.isEmpty()) {
            note = "класс модели не найден";
            return;
        }
        int hooks = 0;
        for (Class<?> c : found) hooks += hookClass(c);
        classes = found.size();
        note = "классов " + found.size() + ", хуков " + hooks;
    }

    static int hookClass(Class<?> c) {
        int n = 0;
        try {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(k, AFTER);
                    n++;
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0 || m.isSynthetic()) continue;
                Class<?> rt = m.getReturnType();
                boolean simple = rt == int.class || rt == Integer.class || rt == long.class || rt == Long.class
                        || rt == boolean.class || rt == Boolean.class;
                if (!simple || m.getName().equals("hashCode")) continue;
                try {
                    XposedBridge.hookMethod(m, BEFORE);
                    n++;
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return n;
    }

    private static final XC_MethodHook AFTER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            touch(param.thisObject);
        }
    };

    private static final XC_MethodHook BEFORE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            touch(param.thisObject);
        }
    };

    static void touch(Object obj) {
        if (obj == null || BUSY.get() != null || CrashGuard.tripped || !Prefs.dmGate) return;
        BUSY.set(Boolean.TRUE);
        try {
            Class<?> c = obj.getClass();
            Model m = MODELS.get(c);
            if (m == null) {
                m = new Model(c);
                Model had = MODELS.putIfAbsent(c, m);
                if (had != null) m = had;
            }
            synchronized (m) {
                build(m, obj);
                force(m, obj);
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        } finally {
            BUSY.remove();
        }
    }

    // ------------------------------------------------------------ отчёт

    static String status() {
        if (!Prefs.dmGate) return "выключено";
        int objects = 0;
        int forced = 0;
        for (Model m : MODELS.values()) {
            objects += m.objects;
            forced += m.forced.size();
        }
        if (objects == 0) return note + "; объектов ещё не было";
        return note + "; объектов " + objects + ", включено полей " + forced;
    }

    static String report() {
        StringBuilder sb = new StringBuilder("[модель настроек голосовых] ").append(note).append('\n');
        if (MODELS.isEmpty()) {
            sb.append("  объектов модели ещё не создавалось: откройте чат и вернитесь в меню мода\n");
            return sb.toString();
        }
        for (Model m : MODELS.values()) {
            synchronized (m) {
                sb.append("  ").append(m.cls.getName()).append(": объектов ").append(m.objects)
                        .append(", полей-чисел определено ").append(m.fields.size());
                if (!m.problem.isEmpty()) sb.append(", сбой: ").append(m.problem);
                sb.append('\n');
                for (Map.Entry<String, String> e : m.first.entrySet()) {
                    sb.append("    ").append(e.getKey()).append(" пришло: ").append(e.getValue());
                    String f = m.forced.get(e.getKey());
                    if (f != null) sb.append(" → включено (").append(f).append(')');
                    sb.append('\n');
                }
                sb.append("    все имена полей: ");
                for (int i = 0; i < m.names.size() && i < 40; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(m.names.get(i));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static void resetForTest() {
        MODELS.clear();
        note = "не ставился";
        classes = 0;
    }

    static int classesForTest() {
        return classes;
    }

    static Model modelForTest(Class<?> c) {
        Model m = new Model(c);
        MODELS.put(c, m);
        return m;
    }
}
