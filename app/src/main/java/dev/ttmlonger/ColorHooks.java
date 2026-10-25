package dev.ttmlonger;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Field;

/**
 * Цветовой акцент и Amoled-тема: цвета подменяются в тот момент, когда приложение читает их
 * из ресурсов (Resources.getColor, TypedArray.getColor и т. п.).
 *
 * Что это не затрагивает: цвета, зашитые прямо в код (Color.parseColor, 0xFFFE2C55),
 * и картинки (PNG, Lottie, векторы с готовым цветом) — они остаются как есть.
 */
final class ColorHooks {
    /** Розовый акцент TikTok (RGB без альфы). */
    private static final int[] PINKS = {0xFE2C55, 0xFF2D55};
    /** Тёмно-серые фоны тёмной темы. */
    private static final int[] DARK_BG = {0x121212, 0x161616, 0x161823};

    private static Field fColors;
    private static Field fDefault;

    private ColorHooks() {
    }

    static void install() {
        try {
            fColors = ColorStateList.class.getDeclaredField("mColors");
            fColors.setAccessible(true);
            fDefault = ColorStateList.class.getDeclaredField("mDefaultColor");
            fDefault.setAccessible(true);
        } catch (Throwable t) {
            fColors = null;
            fDefault = null;
            FlagStore.log("ColorStateList: поля недоступны, подменяется только основной цвет селекторов");
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!active()) return;
                    Object r = param.getResult();
                    if (r instanceof Integer) {
                        int c = (Integer) r;
                        int m = map(c, night(param.thisObject));
                        if (m != c) param.setResult(m);
                    } else if (r instanceof ColorStateList) {
                        ColorStateList repl = mapList((ColorStateList) r, night(param.thisObject));
                        if (repl != r) param.setResult(repl);
                    }
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        };

        hookAll(Resources.class, "getColor", hook);
        hookAll(Resources.class, "getColorStateList", hook);
        hookAll(TypedArray.class, "getColor", hook);
        hookAll(TypedArray.class, "getColorStateList", hook);
        hookAll(TypedArray.class, "getComplexColor", hook);
    }

    private static void hookAll(Class<?> c, String method, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(c, method, hook);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static boolean active() {
        return (Prefs.accent != 0 || Prefs.amoled) && !CrashGuard.tripped;
    }

    private static boolean night(Object owner) {
        if (!Prefs.amoled) return false;
        try {
            Resources res = null;
            if (owner instanceof Resources) res = (Resources) owner;
            else if (owner instanceof TypedArray) res = ((TypedArray) owner).getResources();
            if (res == null) return false;
            return (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Возвращает тот же цвет, если подменять нечего. Прозрачность сохраняется. */
    static int map(int argb, boolean night) {
        int rgb = argb & 0xFFFFFF;
        int accent = Prefs.accent;
        if (accent != 0) {
            for (int p : PINKS) {
                if (rgb == p) return (argb & 0xFF000000) | (accent & 0xFFFFFF);
            }
        }
        if (night && Prefs.amoled) {
            for (int g : DARK_BG) {
                if (rgb == g) return argb & 0xFF000000;
            }
        }
        return argb;
    }

    /** Меняет цвета внутри списка на месте (результат кэшируется системой) и возвращает его же. */
    private static ColorStateList mapList(ColorStateList csl, boolean night) throws Exception {
        if (fColors != null && fDefault != null) {
            int[] colors = (int[]) fColors.get(csl);
            if (colors != null) {
                for (int i = 0; i < colors.length; i++) colors[i] = map(colors[i], night);
            }
            int def = fDefault.getInt(csl);
            int mapped = map(def, night);
            if (mapped != def) fDefault.setInt(csl, mapped);
            return csl;
        }
        if (!csl.isStateful()) {
            int def = csl.getDefaultColor();
            int mapped = map(def, night);
            if (mapped != def) return ColorStateList.valueOf(mapped);
        }
        return csl;
    }
}
