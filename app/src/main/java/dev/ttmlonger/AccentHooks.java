package dev.ttmlonger;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.SweepGradient;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Глубокая подмена акцента: то, что {@link ColorHooks} не ловит, потому что цвет не проходит через
 * Resources.getColor. Ставится только если выбран акцент и включён переключатель «Менять цвет и в
 * рисунках» (Prefs.accentDeep), иначе накладных расходов нет.
 *
 * Что перехватывается:
 *   значения ресурсов и атрибутов темы (ResourcesImpl.getValue, Theme.resolveAttribute,
 *   TypedArray.getValue/peekValue): TUX и AppCompat читают цвета темы именно так;
 *   цвета при отрисовке (Paint.setColor, Canvas.drawColor): текст, фигуры, Compose, Lottie,
 *   цвета, зашитые в код;
 *   цветовые фильтры и тонировка (PorterDuffColorFilter, ColorStateList.valueOf), градиенты
 *   (LinearGradient, RadialGradient, SweepGradient), Color.parseColor;
 *   растровые иконки из ресурсов (PNG/WebP, до 512 пикселей): перекрашиваются один раз при загрузке.
 *
 * Не затронуты: картинки из сети (аватарки, обложки), видео, анимации-картинки (GIF/APNG).
 * Каждый хук ставится отдельно, сбой одного не мешает остальным.
 */
final class AccentHooks {
    private static final int MAX_SIDE = 512;
    private static final Object NO_CHANGE = new Object();

    private static boolean installed;
    private static int hooks;
    private static int failed;
    private static Method mConf;
    private static final WeakHashMap<Bitmap, Object> RECOLORED = new WeakHashMap<>();

    private static final AtomicInteger mappedDraw = new AtomicInteger();
    private static final AtomicInteger mappedRes = new AtomicInteger();
    private static final AtomicInteger mappedBitmaps = new AtomicInteger();

    private AccentHooks() {
    }

    /** Ставит хуки, если нужно (акцент выбран и глубокая подмена включена). Повторный вызов ничего не делает. */
    static synchronized void installIfNeeded() {
        if (installed || Prefs.accent == 0 || !Prefs.accentDeep) return;
        installed = true;
        try {
            install();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    static String status() {
        if (!installed) {
            return Prefs.accent == 0 ? "не нужна (акцент стандартный)"
                    : !Prefs.accentDeep ? "выключена" : "не установлена";
        }
        return "хуков " + hooks + (failed > 0 ? ", не встали " + failed : "")
                + "; подменено при отрисовке " + mappedDraw.get() + ", в ресурсах " + mappedRes.get()
                + ", иконок " + mappedBitmaps.get();
    }

    private static void install() {
        // --- цвет при отрисовке и в фильтрах: подменяется первый аргумент-число
        XC_MethodHook firstArg = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object[] a = param.args;
                    if (a == null || a.length == 0 || !(a[0] instanceof Integer)) return;
                    int c = (Integer) a[0];
                    if (!AccentMap.isPinkish(c)) return;
                    int m = accentOnly(c);
                    if (m != c) {
                        a[0] = m;
                        mappedDraw.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        };
        method(Paint.class, "setColor", firstArg);
        method(Canvas.class, "drawColor", firstArg);
        method(ColorStateList.class, "valueOf", firstArg);
        constructors(PorterDuffColorFilter.class, firstArg);

        // --- градиенты: все числа и массивы чисел в конструкторе — цвета
        XC_MethodHook allColors = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object[] a = param.args;
                    if (a == null) return;
                    for (int i = 0; i < a.length; i++) {
                        if (a[i] instanceof Integer) {
                            int c = (Integer) a[i];
                            int m = accentOnly(c);
                            if (m != c) {
                                a[i] = m;
                                mappedDraw.incrementAndGet();
                            }
                        } else if (a[i] instanceof int[]) {
                            int[] src = (int[]) a[i];
                            int[] copy = null;
                            for (int k = 0; k < src.length; k++) {
                                int m = accentOnly(src[k]);
                                if (m != src[k]) {
                                    if (copy == null) copy = src.clone();
                                    copy[k] = m;
                                }
                            }
                            if (copy != null) {
                                a[i] = copy;
                                mappedDraw.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        };
        constructors(LinearGradient.class, allColors);
        constructors(RadialGradient.class, allColors);
        constructors(SweepGradient.class, allColors);

        // --- Color.parseColor("#FE2C55")
        method(Color.class, "parseColor", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object r = param.getResult();
                    if (r instanceof Integer) {
                        int c = (Integer) r;
                        int m = accentOnly(c);
                        if (m != c) {
                            param.setResult(m);
                            mappedDraw.incrementAndGet();
                        }
                    }
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        });

        // --- значения ресурсов и тем: цвет лежит в TypedValue.data
        method(forName("android.content.res.ResourcesImpl"), "getValue", valueAt(1, true));
        method(Resources.Theme.class, "resolveAttribute", valueAt(1, false));
        method(TypedArray.class, "getValue", valueAt(1, false));
        method(TypedArray.class, "peekValue", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object r = param.getResult();
                    if (r instanceof TypedValue) mapValue((TypedValue) r, param.thisObject);
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        });

        // --- растровые иконки из ресурсов
        method(forName("android.content.res.ResourcesImpl"), "loadDrawable", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object r = param.getResult();
                    if (!(r instanceof BitmapDrawable) || Prefs.accent == 0 || CrashGuard.tripped) return;
                    BitmapDrawable old = (BitmapDrawable) r;
                    Bitmap src = old.getBitmap();
                    if (src == null || src.isRecycled()) return;
                    Bitmap out = recolored(src);
                    if (out == null) return;
                    Object res = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    BitmapDrawable nd = res instanceof Resources
                            ? new BitmapDrawable((Resources) res, out) : new BitmapDrawable(null, out);
                    nd.setAutoMirrored(old.isAutoMirrored());
                    param.setResult(nd);
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        });
        FlagStore.log("акцент: глубокая подмена, хуков " + hooks + ", не встали " + failed);
    }

    // ------------------------------------------------------------ подмена

    /** Подмена только акцента (без Amoled) с учётом защиты от крашей. */
    private static int accentOnly(int argb) {
        int accent = Prefs.accent;
        if (accent == 0 || CrashGuard.tripped) return argb;
        return ColorHooks.map(argb, false);
    }

    private static XC_MethodHook valueAt(final int index, final boolean owner) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object[] a = param.args;
                    if (a == null || a.length <= index || !(a[index] instanceof TypedValue)) return;
                    mapValue((TypedValue) a[index], owner ? param.thisObject : null);
                } catch (Throwable ignored) {
                    // Ошибка в подмене цвета не должна ронять TikTok.
                }
            }
        };
    }

    private static void mapValue(TypedValue v, Object owner) {
        if (v.type < TypedValue.TYPE_FIRST_COLOR_INT || v.type > TypedValue.TYPE_LAST_COLOR_INT) return;
        if (CrashGuard.tripped || (Prefs.accent == 0 && !Prefs.amoled)) return;
        int c = v.data;
        int m = ColorHooks.map(c, Prefs.amoled && nightOf(owner));
        if (m != c) {
            v.data = m;
            mappedRes.incrementAndGet();
        }
    }

    /** Тёмная ли тема у владельца ресурсов (ResourcesImpl); нужна только для Amoled. */
    private static boolean nightOf(Object owner) {
        if (owner == null) return false;
        try {
            if (mConf == null) mConf = owner.getClass().getMethod("getConfiguration");
            Object conf = mConf.invoke(owner);
            if (conf instanceof android.content.res.Configuration) {
                int mode = ((android.content.res.Configuration) conf).uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignored) {
            // темнота неизвестна — считаем светлой
        }
        return false;
    }

    /** Перекрашенная копия иконки или null, если менять нечего. Результат запоминается. */
    private static Bitmap recolored(Bitmap src) {
        synchronized (RECOLORED) {
            Object known = RECOLORED.get(src);
            if (known != null) return known == NO_CHANGE ? null : (Bitmap) known;
        }
        Object result = NO_CHANGE;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w > 0 && h > 0 && w <= MAX_SIDE && h <= MAX_SIDE) {
                Bitmap work = src.copy(Bitmap.Config.ARGB_8888, true);
                if (work != null) {
                    int[] px = new int[w * h];
                    work.getPixels(px, 0, w, 0, 0, w, h);
                    if (AccentMap.recolor(px, Prefs.accent) > 0) {
                        work.setPixels(px, 0, w, 0, 0, w, h);
                        result = work;
                        mappedBitmaps.incrementAndGet();
                    } else {
                        work.recycle();
                    }
                }
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        synchronized (RECOLORED) {
            RECOLORED.put(src, result);
        }
        return result == NO_CHANGE ? null : (Bitmap) result;
    }

    // ------------------------------------------------------------ установка

    private static Class<?> forName(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void method(Class<?> c, String name, XC_MethodHook hook) {
        if (c == null) {
            failed++;
            return;
        }
        try {
            java.util.Set<?> set = XposedBridge.hookAllMethods(c, name, hook);
            if (set != null && !set.isEmpty()) hooks++;
            else failed++;
        } catch (Throwable t) {
            failed++;
            FlagStore.log(t);
        }
    }

    private static void constructors(Class<?> c, XC_MethodHook hook) {
        try {
            java.util.Set<?> set = XposedBridge.hookAllConstructors(c, hook);
            if (set != null && !set.isEmpty()) hooks++;
            else failed++;
        } catch (Throwable t) {
            failed++;
            FlagStore.log(t);
        }
    }
}
