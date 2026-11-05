package dev.ttmlonger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * «Перехват запросов»: запись настоящих вызовов TikTok к его серверу по теме лайков, избранного и репостов.
 *
 * <p>Запросы TikTok описаны интерфейсами (Retrofit): у метода в аннотации лежит адрес, а в аннотациях
 * параметров их имена. Сами объекты-реализации создаются через {@link Proxy#newProxyInstance}, а вызов
 * любого метода проходит через один {@link InvocationHandler}. Мод вешается на создание прокси, находит
 * обработчик и записывает вызовы тех методов, в чьих аннотациях есть слова favorite, collect, digg, repost
 * или upvote: имя интерфейса и метода, адрес, имена и значения параметров, кто вызвал и какого типа ответ.
 *
 * <p>Это нужно, чтобы массовое удаление своих лайков, избранного и репостов делалось по настоящим вызовам,
 * а не наугад: человек открывает свои вкладки и убирает по одному элементу, мод это записывает.
 *
 * <p>Что не записывается: куки, токены, заголовки и подписи (до них мод не добирается), содержимое ответов.
 * Длинные строки (например, идентификатор профиля) обрезаются до начала и длины.
 */
final class ApiTap {
    /** Слова, по которым вызов считается подходящим (ищутся в адресах и именах из аннотаций, без учёта регистра). */
    static final String[] WORDS = {"favorite", "collect", "digg", "repost", "upvote"};

    private static final int MAX_LINES = 60;
    private static final int MAX_REPORT = 17_500;
    private static final String[] SKIP_FRAMES = {"$Proxy", "java.lang.reflect.", "de.robv.android.xposed.",
            "org.lsposed.", "LSPHooker", "dev.ttmlonger.", "android.os.", "libcore.", "dalvik.system."};
    private static final String[] SKIP_IFACE = {"java.", "javax.", "android.", "androidx.", "libcore.", "kotlin.",
            "dalvik.", "com.android.", "sun."};

    /** Что известно про метод интерфейса: адреса и имена параметров из аннотаций. */
    static final class Route {
        final List<String> values = new ArrayList<>();
        final String[] names;
        final boolean hit;

        Route(List<String> values, String[] names) {
            this.values.addAll(values);
            this.names = names;
            boolean h = false;
            for (String v : values) {
                String low = v.toLowerCase(java.util.Locale.ROOT);
                for (String w : WORDS) {
                    if (low.contains(w)) {
                        h = true;
                        break;
                    }
                }
            }
            this.hit = h;
        }
    }

    private static final Route NONE = new Route(new ArrayList<String>(), new String[0]);
    private static final Map<Method, Route> ROUTES = new ConcurrentHashMap<>();
    private static final Set<Class<?>> HOOKED = ConcurrentHashMap.newKeySet();
    private static final List<String> LINES = new ArrayList<>();
    private static final Set<String> INTERFACES = new LinkedHashSet<>();
    private static final Set<String> HANDLERS = new LinkedHashSet<>();
    private static final Set<String> CALL_TYPES = new LinkedHashSet<>();
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    private static volatile boolean installed;
    private static volatile String note = "ещё не ставился";
    private static volatile int proxies;
    private static volatile int calls;
    private static volatile int hits;

    private ApiTap() {
    }

    // ------------------------------------------------------------ установка

    /** Вешается на создание прокси; только если перехват включён (по умолчанию включён). */
    static void install() {
        if (!Prefs.apiTapOn || installed) return;
        installed = true;
        try {
            XposedBridge.hookAllMethods(Proxy.class, "newProxyInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length >= 3) {
                            onProxy((Class<?>[]) param.args[1], param.args[2]);
                        }
                    } catch (Throwable t) {
                        FlagStore.log(t);
                    }
                }
            });
            note = "ждёт создания прокси";
        } catch (Throwable t) {
            note = "не получилось: " + t;
            FlagStore.log(t);
        }
    }

    /** Подходит ли набор интерфейсов: не аннотации и не системные классы. */
    static boolean watch(Class<?>[] ifaces) {
        if (ifaces == null || ifaces.length == 0) return false;
        for (Class<?> c : ifaces) {
            if (c == null || c.isAnnotation()) return false;
            String n = c.getName();
            for (String p : SKIP_IFACE) {
                if (n.startsWith(p)) return false;
            }
        }
        return true;
    }

    /** Создан прокси: запоминаем обработчик и вешаемся на его invoke. Возвращает true, если обработчик новый. */
    static boolean onProxy(Class<?>[] ifaces, Object handler) {
        if (BUSY.get() != null || CrashGuard.tripped) return false;
        BUSY.set(Boolean.TRUE);
        try {
            if (handler == null || !watch(ifaces)) return false;
            proxies++;
            Class<?> hc = handler.getClass();
            if (!HOOKED.add(hc)) return false;
            synchronized (HANDLERS) {
                if (HANDLERS.size() < 12) HANDLERS.add(hc.getName());
            }
            Method invoke = invokeOf(hc);
            if (invoke == null) {
                note = "у обработчика " + hc.getName() + " нет invoke";
                return true;
            }
            XposedBridge.hookMethod(invoke, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3 || !(param.args[1] instanceof Method)) return;
                        onCall((Method) param.args[1], (Object[]) param.args[2], param.getResult(),
                                param.getThrowable(), null);
                    } catch (Throwable t) {
                        FlagStore.log(t);
                    }
                }
            });
            note = "работает";
            return true;
        } finally {
            BUSY.remove();
        }
    }

    /** Метод invoke(Object, Method, Object[]) у класса обработчика или его родителей. */
    static Method invokeOf(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod("invoke", Object.class, Method.class, Object[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // выше по иерархии
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ запись вызова

    /** Адреса и имена параметров метода интерфейса из аннотаций (кэшируется). */
    static Route routeOf(Method m) {
        Route r = ROUTES.get(m);
        if (r != null) return r;
        r = readRoute(m);
        ROUTES.put(m, r);
        return r;
    }

    private static Route readRoute(Method m) {
        List<String> values = new ArrayList<>();
        String[] names;
        try {
            for (Annotation a : m.getAnnotations()) collect(a, values);
            Annotation[][] pa = m.getParameterAnnotations();
            names = new String[pa.length];
            for (int i = 0; i < pa.length; i++) {
                List<String> v = new ArrayList<>();
                for (Annotation a : pa[i]) collect(a, v);
                names[i] = v.isEmpty() ? null : v.get(0);
            }
        } catch (Throwable t) {
            return NONE;
        }
        if (values.isEmpty()) return new Route(values, names);
        return new Route(values, names);
    }

    private static void collect(Annotation a, List<String> out) {
        try {
            for (Method am : a.annotationType().getDeclaredMethods()) {
                if (am.getParameterTypes().length != 0) continue;
                Class<?> rt = am.getReturnType();
                if (rt != String.class && rt != String[].class) continue;
                am.setAccessible(true);
                Object v = am.invoke(a);
                if (v instanceof String) add(out, (String) v);
                else if (v instanceof String[]) for (String s : (String[]) v) add(out, s);
            }
        } catch (Throwable ignored) {
            // аннотация без читаемых значений
        }
    }

    private static void add(List<String> out, String s) {
        if (s != null && !s.isEmpty() && s.length() <= 160 && out.size() < 12) out.add(s);
    }

    /** Вызов метода интерфейса прошёл через обработчик. */
    static void onCall(Method m, Object[] args, Object result, Throwable error, StackTraceElement[] stack) {
        if (BUSY.get() != null || CrashGuard.tripped) return;
        BUSY.set(Boolean.TRUE);
        try {
            calls++;
            Route r = routeOf(m);
            if (!r.hit) return;
            hits++;
            if (stack == null) stack = new Throwable().getStackTrace(); // только у подходящих: на остальных дорого
            String iface = m.getDeclaringClass().getName();
            synchronized (INTERFACES) {
                if (INTERFACES.size() < 40) INTERFACES.add(iface);
            }
            StringBuilder sb = new StringBuilder();
            sb.append('#').append(hits).append(' ').append(shortName(iface)).append('.').append(m.getName()).append('(');
            Class<?>[] pt = m.getParameterTypes();
            for (int i = 0; i < pt.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(shortName(pt[i].getName()));
            }
            sb.append("): ").append(shortName(m.getReturnType().getName()));
            sb.append("\n    адрес: ");
            for (int i = 0; i < r.values.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(r.values.get(i));
            }
            sb.append("\n    значения: ");
            if (args == null || args.length == 0) sb.append("нет");
            else {
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(", ");
                    String name = i < r.names.length && r.names[i] != null ? r.names[i] : "a" + i;
                    sb.append(name).append('=').append(formatArg(args[i]));
                }
            }
            sb.append("\n    ответ: ").append(result == null ? "null" : shortName(result.getClass().getName()));
            if (error != null) sb.append("; ошибка ").append(error.getClass().getSimpleName());
            sb.append("\n    вызвали: ").append(callers(stack, 5));
            if (result != null) {
                String type = describeType(result.getClass());
                if (type != null) sb.append("\n    тип ответа: ").append(type);
            }
            synchronized (LINES) {
                LINES.add(sb.toString());
                while (LINES.size() > MAX_LINES) LINES.remove(0);
            }
        } finally {
            BUSY.remove();
        }
    }

    /** Один раз на тип ответа: какие методы он умеет (имена и типы, чтобы знать, как получить результат). */
    static String describeType(Class<?> c) {
        synchronized (CALL_TYPES) {
            if (!CALL_TYPES.add(c.getName())) return null;
        }
        StringBuilder sb = new StringBuilder(shortName(c.getName())).append(": ");
        int n = 0;
        try {
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class || m.getParameterTypes().length > 1) continue;
                if (n++ >= 14) {
                    sb.append("…");
                    break;
                }
                if (n > 1) sb.append("; ");
                sb.append(m.getName()).append('(');
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1) sb.append(shortName(p[0].getName()));
                sb.append("):").append(shortName(m.getReturnType().getName()));
            }
        } catch (Throwable ignored) {
            sb.append("не прочитать");
        }
        return sb.toString();
    }

    /** Первые кадры стека вызова без служебных (прокси, рефлексия, хуки, сам мод). */
    static String callers(StackTraceElement[] st, int limit) {
        if (st == null) return "?";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            boolean skip = false;
            for (String p : SKIP_FRAMES) {
                if (c.contains(p) || c.startsWith(p)) {
                    skip = true;
                    break;
                }
            }
            if (skip || isHandlerClass(c)) continue;
            if (n > 0) sb.append(" ← ");
            sb.append(shortName(c)).append('.').append(e.getMethodName());
            if (++n >= limit) break;
        }
        return n == 0 ? "?" : sb.toString();
    }

    private static boolean isHandlerClass(String className) {
        synchronized (HANDLERS) {
            return HANDLERS.contains(className);
        }
    }

    /** Значение параметра в коротком виде: числа и флаги как есть, длинные строки обрезаны, остальное по имени типа. */
    static String formatArg(Object v) {
        return formatArg(v, 0);
    }

    private static String formatArg(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) return String.valueOf(v);
        if (v instanceof CharSequence) {
            String s = v.toString();
            if (s.length() > 30 && s.indexOf(' ') < 0) return s.substring(0, 8) + "…(" + s.length() + ")";
            return s.length() > 60 ? "\"" + s.substring(0, 60) + "…\"" : "\"" + s + "\"";
        }
        if (v instanceof Map && depth < 1) {
            StringBuilder sb = new StringBuilder("{");
            int n = 0;
            for (Object o : ((Map<?, ?>) v).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                if (n++ >= 8) {
                    sb.append(", …");
                    break;
                }
                if (n > 1) sb.append(", ");
                sb.append(e.getKey()).append('=').append(formatArg(e.getValue(), depth + 1));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Collection && depth < 1) {
            StringBuilder sb = new StringBuilder("[");
            int n = 0;
            for (Object o : (Collection<?>) v) {
                if (n++ >= 5) {
                    sb.append(", …");
                    break;
                }
                if (n > 1) sb.append(", ");
                sb.append(formatArg(o, depth + 1));
            }
            return sb.append(']').toString();
        }
        return shortName(v.getClass().getName());
    }

    static String shortName(String name) {
        if (name == null) return "?";
        if (name.startsWith("com.ss.android.ugc.aweme.")) return "~" + name.substring("com.ss.android.ugc.aweme.".length());
        if (name.startsWith("java.lang.")) return name.substring("java.lang.".length());
        if (name.startsWith("java.util.")) return name.substring("java.util.".length());
        return name;
    }

    // ------------------------------------------------------------ для экрана

    static String menuSummary() {
        if (!Prefs.apiTapOn) return "выкл";
        synchronized (LINES) {
            return LINES.isEmpty() ? "ждёт" : LINES.size() + " записей";
        }
    }

    static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Перехват: ").append(Prefs.apiTapOn ? "включён" : "выключен").append(" (").append(note).append(")\n");
        sb.append("Прокси видел: ").append(proxies).append(", обработчиков: ").append(HANDLERS.size())
                .append(", вызовов: ").append(calls).append(", подходящих: ").append(hits);
        synchronized (LINES) {
            sb.append(", записано: ").append(LINES.size());
        }
        return sb.toString();
    }

    static String details() {
        StringBuilder sb = new StringBuilder();
        sb.append(status()).append('\n');
        synchronized (HANDLERS) {
            sb.append("Обработчики: ").append(HANDLERS.isEmpty() ? "нет" : HANDLERS.toString()).append('\n');
        }
        synchronized (INTERFACES) {
            sb.append("Интерфейсы с подходящими вызовами: ").append(INTERFACES.isEmpty() ? "нет" : INTERFACES.toString())
                    .append('\n');
        }
        List<String> copy;
        synchronized (LINES) {
            copy = new ArrayList<>(LINES);
        }
        sb.append('\n');
        for (String l : copy) sb.append(l).append("\n\n");
        return Recon.cap(sb.toString(), MAX_REPORT);
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        synchronized (CALL_TYPES) {
            CALL_TYPES.clear();
        }
        hits = 0;
    }

    /** Только для проверок: сбрасывает всё накопленное. */
    static void resetForTest() {
        clear();
        ROUTES.clear();
        HOOKED.clear();
        synchronized (HANDLERS) {
            HANDLERS.clear();
        }
        synchronized (INTERFACES) {
            INTERFACES.clear();
        }
        proxies = 0;
        calls = 0;
        hits = 0;
        installed = false;
        note = "ещё не ставился";
    }
}
