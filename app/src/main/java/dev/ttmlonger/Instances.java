package dev.ttmlonger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Поиск готового объекта класса без вызова TikTok: статическое поле или метод-одиночка в самом классе. */
final class Instances {
    private Instances() {
    }

    /**
     * Ищет в классе c статическое поле или статический метод без параметров, отдающие объект самого
     * c (Kotlin-синглтон INSTANCE, getInstance и подобные).
     *
     * @return объект или null, если такого нет или он ещё не создан
     */
    static Object find(Class<?> c) {
        if (c == null) return null;
        try {
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
            // не получилось: остаётся ждать, пока TikTok сам обратится к объекту
        }
        return null;
    }
}
