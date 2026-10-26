package dev.ttmlonger;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Свой шрифт. Файл .ttf или .otf копируется в files/ttmlonger/fonts/custom.ttf, а когда
 * приложение задаёт TextView шрифт (TextView.setTypeface), подставляется выбранный: жирность
 * и курсив остаются теми, что запросило приложение.
 *
 * Не затрагивается: текст, который приложение рисует само на холсте (Paint.setTypeface, Lynx,
 * Compose), и уже созданные на экране надписи: поэтому нужен перезапуск TikTok.
 */
final class FontHooks {
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private static final Map<Integer, Typeface> cache = new ConcurrentHashMap<>();
    private static final Set<Typeface> ours = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<Typeface, Boolean>()));
    private static volatile Typeface base;
    private static volatile boolean baseFailed;

    private FontHooks() {
    }

    static void install() {
        XposedHelpers.findAndHookMethod(TextView.class, "setTypeface", Typeface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (Prefs.fontName.isEmpty()) return;
                    Typeface orig = (Typeface) param.args[0];
                    Typeface mapped = map(orig);
                    if (mapped != orig) param.args[0] = mapped;
                } catch (Throwable ignored) {
                    // Шрифт не должен ронять TikTok.
                }
            }
        });
        // Надписи без своего шрифта (обычный Roboto) не вызывают setTypeface при создании:
        // после конструктора подставляем шрифт и им.
        try {
            XposedBridge.hookAllConstructors(TextView.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (Prefs.fontName.isEmpty() || CrashGuard.tripped) return;
                        TextView tv = (TextView) param.thisObject;
                        Typeface cur = tv.getTypeface();
                        Typeface mapped = map(cur);
                        if (mapped != cur) tv.setTypeface(mapped);
                    } catch (Throwable ignored) {
                        // см. выше
                    }
                }
            });
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    // ---------------------------------------------------------------- подстановка

    static Typeface map(Typeface orig) {
        if (Prefs.fontName.isEmpty() || CrashGuard.tripped) return orig;
        if (orig != null && ours.contains(orig)) return orig;
        Typeface b = base();
        if (b == null) return orig;

        int style = orig == null ? Typeface.NORMAL : orig.getStyle();
        boolean italic = (style & Typeface.ITALIC) != 0;
        int weight = (style & Typeface.BOLD) != 0 ? 700 : 400;
        if (Build.VERSION.SDK_INT >= 28 && orig != null) weight = orig.getWeight();

        int key = weight * 2 + (italic ? 1 : 0);
        Typeface t = cache.get(key);
        if (t == null) {
            if (Build.VERSION.SDK_INT >= 28) {
                t = Typeface.create(b, weight, italic);
            } else {
                boolean bold = weight >= 600;
                t = Typeface.create(b, bold ? (italic ? Typeface.BOLD_ITALIC : Typeface.BOLD)
                        : (italic ? Typeface.ITALIC : Typeface.NORMAL));
            }
            if (t == null) return orig;
            cache.put(key, t);
            ours.add(t);
        }
        return t;
    }

    private static Typeface base() {
        Typeface b = base;
        if (b != null || baseFailed) return b;
        synchronized (FontHooks.class) {
            if (base != null || baseFailed) return base;
            try {
                File f = file();
                if (f == null || !f.isFile()) {
                    baseFailed = true;
                    return null;
                }
                base = Typeface.createFromFile(f);
                ours.add(base);
            } catch (Throwable t) {
                baseFailed = true;
                FlagStore.log(t);
            }
            return base;
        }
    }

    private static File file() {
        File d = FlagStore.dir();
        return d == null ? null : new File(new File(d, "fonts"), "custom.ttf");
    }

    private static void forget() {
        synchronized (FontHooks.class) {
            base = null;
            baseFailed = false;
            cache.clear();
            ours.clear();
        }
    }

    // ---------------------------------------------------------------- выбор и сброс

    /**
     * Копирует выбранный файл как шрифт мода.
     *
     * @return название для показа в настройках
     * @throws IOException если это не шрифт или он не открывается
     */
    static String importFont(Context ctx, Uri uri) throws IOException {
        File dir = new File(FlagStore.dir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, "custom.tmp");
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("файл не открывается");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("файл больше 25 МБ");
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }

        try {
            checkFontFile(tmp);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        File dst = new File(dir, "custom.ttf");
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IOException("не удалось сохранить шрифт");
        forget();

        String name = displayName(ctx.getContentResolver(), uri);
        Prefs.setFontName(name);
        return name;
    }

    static void reset() {
        File f = file();
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        forget();
        Prefs.setFontName("");
    }

    /** Файл должен начинаться как шрифт (TTF, OTF, TTC) и открываться системой. */
    static void checkFontFile(File f) throws IOException {
        byte[] head = new byte[4];
        InputStream in = new FileInputStream(f);
        try {
            if (in.read(head) < 4) throw new IOException("файл слишком короткий");
        } finally {
            in.close();
        }
        if (!isFontMagic(head)) throw new IOException("это не файл шрифта (.ttf, .otf)");
        try {
            Typeface t = Typeface.createFromFile(f);
            if (t == null) throw new IOException("шрифт не открывается");
        } catch (RuntimeException e) {
            throw new IOException("шрифт не открывается: " + e.getMessage());
        }
    }

    static boolean isFontMagic(byte[] h) {
        if (h == null || h.length < 4) return false;
        int v = ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        return v == 0x00010000          // TrueType
                || v == 0x4F54544F      // "OTTO": OpenType с контурами CFF
                || v == 0x74727565      // "true": TrueType из macOS
                || v == 0x74746366;     // "ttcf": коллекция
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Throwable ignored) {
            // имя необязательно
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "Свой шрифт";
        return name.trim();
    }
}
