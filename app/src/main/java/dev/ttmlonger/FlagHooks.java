package dev.ttmlonger;

import android.os.Handler;
import android.os.Looper;

import dalvik.system.DexFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Находит геттеры флагов в менеджере A/B-настроек TikTok и вешает на них хуки. */
final class FlagHooks {
    private static final Set<String> hooked = ConcurrentHashMap.newKeySet();
    private static final Set<String> hookedClasses = new TreeSet<>();
    private static volatile int count;
    private static boolean retryScheduled;

    private FlagHooks() {
    }

    static int count() {
        return count;
    }

    /** Строка для экрана флагов: сколько методов подцеплено, или почему ничего. */
    static String status() {
        if (count > 0) {
            synchronized (hookedClasses) {
                return "Хуки: " + count + " " + plural(count) + " · " + join(hookedClasses);
            }
        }
        return "Хуки не установлены: не найден менеджер A/B-настроек (искали "
                + join(Targets.CLASSES) + " и классы с префиксом " + join(Targets.SCAN_PREFIXES) + ")";
    }

    static synchronized void install(ClassLoader cl) {
        if (cl == null) return;

        for (String name : Targets.CLASSES) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c != null) hookClass(c);
        }

        if (count == 0) {
            for (String name : scan(cl)) {
                Class<?> c = XposedHelpers.findClassIfExists(name, cl);
                if (c != null && countMatches(c) >= Targets.SCAN_MIN_GETTERS) hookClass(c);
            }
        }
        FlagStore.log(status());
    }

    /** Классы могут появиться позже (динамическая загрузка) — пробуем ещё несколько раз. */
    static synchronized void retryLater(final ClassLoader cl) {
        if (retryScheduled) return;
        retryScheduled = true;
        final long[] delays = {3_000L, 10_000L, 30_000L};
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            private int i = 0;

            @Override
            public void run() {
                try {
                    install(cl);
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
                if (count == 0 && ++i < delays.length) h.postDelayed(this, delays[i]);
            }
        }, delays[0]);
    }

    // ---------------------------------------------------------------- внутреннее

    private static int countMatches(Class<?> c) {
        int n = 0;
        for (Method m : methodsOf(c)) {
            if (Targets.match(m) != null) n++;
        }
        return n;
    }

    private static void hookClass(Class<?> c) {
        int added = 0;
        for (Method m : methodsOf(c)) {
            int[] sig = Targets.match(m);
            if (sig == null) continue;
            if (!hooked.add(m.toString())) continue;
            try {
                XposedBridge.hookMethod(m, new GetterHook(sig[0], sig[1]));
                added++;
            } catch (Throwable t) {
                hooked.remove(m.toString());
                FlagStore.log(t);
            }
        }
        if (added > 0) {
            count += added;
            synchronized (hookedClasses) {
                hookedClasses.add(c.getName());
            }
        }
    }

    private static List<Method> methodsOf(Class<?> c) {
        List<Method> out = new ArrayList<>();
        try {
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (m.isSynthetic() || m.isBridge() || Modifier.isAbstract(mod) || Modifier.isNative(mod)) {
                    continue;
                }
                out.add(m);
            }
        } catch (Throwable t) {
            // Класс ссылается на то, чего нет в загрузчике — пропускаем.
        }
        return out;
    }

    /** Имена классов из всех dex-файлов загрузчика, начинающиеся с нужных префиксов. */
    private static List<String> scan(ClassLoader cl) {
        List<String> out = new ArrayList<>();
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object el : elements) {
                Object df = XposedHelpers.getObjectField(el, "dexFile");
                if (!(df instanceof DexFile)) continue;
                Enumeration<String> names = ((DexFile) df).entries();
                while (names.hasMoreElements()) {
                    String n = names.nextElement();
                    for (String p : Targets.SCAN_PREFIXES) {
                        if (n.startsWith(p)) {
                            out.add(n);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return out;
    }

    private static String plural(int n) {
        int m100 = n % 100;
        int m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return "методов";
        if (m10 == 1) return "метод";
        if (m10 >= 2 && m10 <= 4) return "метода";
        return "методов";
    }

    private static String join(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.substring(s.lastIndexOf('.') + 1));
        }
        return sb.toString();
    }

    private static String join(String[] items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return join(l);
    }

    /** Записывает чтение флага и подменяет результат, если есть переопределение. */
    private static final class GetterHook extends XC_MethodHook {
        private final int keyIndex;
        private final int type;

        GetterHook(int keyIndex, int type) {
            this.keyIndex = keyIndex;
            this.type = type;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object k = param.args[keyIndex];
                if (!(k instanceof String)) return;
                String key = (String) k;

                if (!param.hasThrowable()) FlagStore.onRead(key, type, param.getResult());

                FlagStore.Patch o = FlagStore.getOverride(key);
                if (o == null) return;
                Object v = FlagStore.coerce(o.raw, type);
                if (v != null) param.setResult(v);
            } catch (Throwable t) {
                // Ошибка в модуле не должна ронять TikTok.
            }
        }
    }
}
