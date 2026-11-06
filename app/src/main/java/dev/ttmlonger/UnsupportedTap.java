package dev.ttmlonger;

import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Перехват надписи «Этот тип сообщений не поддерживается в вашем регионе».
 *
 * <p>Когда в чат приходит голосовое (или другое новое) сообщение, а TikTok считает его неподдерживаемым
 * для региона, вместо него рисуется эта заглушка. Где именно TikTok принимает такое решение, из имён в
 * коде не видно: они обфусцированы. Поэтому мод ловит момент, когда такой текст попадает в TextView, и
 * записывает, откуда это вызвано: класс вида, его родителей и цепочку вызовов. По цепочке видно класс,
 * который рисует заглушку, и тех, кто его позвал: в одном из них и стоит проверка.
 *
 * <p>Записываются только названия классов и идентификаторов TikTok и сама эта надпись. Переписки и
 * имена людей не читаются: проверяется лишь текст, подходящий под «не поддерживается… в регионе».
 */
final class UnsupportedTap {
    private static final int MIN_LEN = 12;
    private static final int MAX_LEN = 160;
    private static final int MAX_ENTRIES = 6;
    private static final int MAX_FRAMES = 14;
    private static final int MAX_PARENTS = 8;
    /** Сколько классов из цепочки показываем списком их методов. */
    private static final int DUMP_CLASSES = 4;
    private static final int DUMP_LINES = 26;
    private static final String[] SKIP_FRAMES = {"android.", "java.", "javax.", "dalvik.", "libcore.", "sun.",
            "com.android.", "de.robv.android.xposed.", "org.lsposed.", "LSPHooker", "dev.ttmlonger.", "$Proxy",
            "kotlin.", "kotlinx."};

    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static int total;
    private static boolean installed;
    private static String note = "хук не ставился";

    private UnsupportedTap() {
    }

    private static final class Entry {
        final String text;
        final String view;
        final String parents;
        final List<String> frames;
        int count = 1;

        Entry(String text, String view, String parents, List<String> frames) {
            this.text = text;
            this.view = view;
            this.parents = parents;
            this.frames = frames;
        }
    }

    // ------------------------------------------------------------ установка

    static void install() {
        if (installed) return;
        try {
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("setText", CharSequence.class,
                    TextView.BufferType.class), HOOK);
            installed = true;
            note = "ждёт";
        } catch (Throwable t) {
            note = "хук не поставился: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
    }

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (CrashGuard.tripped || param.args == null || param.args.length == 0) return;
                Object a = param.args[0];
                if (!(a instanceof CharSequence)) return;
                CharSequence cs = (CharSequence) a;
                int n = cs.length();
                if (n < MIN_LEN || n > MAX_LEN) return;
                String text = cs.toString();
                if (!matches(text)) return;
                onText(text, param.thisObject, new Throwable().getStackTrace());
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
    };

    // ------------------------------------------------------------ чистая логика

    /** Похожа ли надпись на «не поддерживается / недоступно в вашем регионе» (русский и английский). */
    static boolean matches(String text) {
        if (text == null) return false;
        // Быстрый отсев без копий строки: setText вызывается тысячи раз при прокрутке.
        if (text.indexOf("егион") < 0 && text.indexOf("egion") < 0 && text.indexOf("ЕГИОН") < 0
                && text.indexOf("EGION") < 0) {
            return false;
        }
        String l = text.toLowerCase(Locale.ROOT);
        boolean region = l.contains("регион") || l.contains("region");
        boolean unsupported = l.contains("поддерж") || l.contains("support") || l.contains("недоступ")
                || l.contains("unavailable") || l.contains("not available");
        return region && unsupported;
    }

    /** Кадры цепочки вызовов без системных и без самого мода: «класс.метод:строка». */
    static List<String> appFrames(StackTraceElement[] stack) {
        List<String> out = new ArrayList<>();
        if (stack == null) return out;
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            boolean skip = false;
            for (String p : SKIP_FRAMES) {
                // «com.ss.android.ugc…» содержит «android.»: системные классы узнаём только по началу имени
                if (cls.startsWith(p) || ((p.startsWith("LSP") || p.startsWith("$")) && cls.contains(p))) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;
            out.add(cls + "." + e.getMethodName() + (e.getLineNumber() > 0 ? ":" + e.getLineNumber() : ""));
            if (out.size() >= MAX_FRAMES) break;
        }
        return out;
    }

    /** Ключ записи: первые три кадра цепочки; одна и та же надпись из одного места учитывается один раз. */
    static String keyOf(String text, List<String> frames) {
        StringBuilder sb = new StringBuilder(text).append('|');
        for (int i = 0; i < frames.size() && i < 3; i++) sb.append(frames.get(i)).append('>');
        return sb.toString();
    }

    /** Записывает показ надписи; view — TextView (или null в проверках). */
    static void onText(String text, Object view, StackTraceElement[] stack) {
        List<String> frames = appFrames(stack);
        String key = keyOf(text, frames);
        synchronized (LOCK) {
            total++;
            Entry e = ENTRIES.get(key);
            if (e != null) {
                e.count++;
                return;
            }
            if (ENTRIES.size() >= MAX_ENTRIES) return;
        }
        String viewInfo = "?";
        String parents = "";
        if (view instanceof View) {
            viewInfo = describe((View) view);
            parents = parentsOf((View) view);
        }
        synchronized (LOCK) {
            if (!ENTRIES.containsKey(key) && ENTRIES.size() < MAX_ENTRIES) {
                ENTRIES.put(key, new Entry(text, viewInfo, parents, frames));
            }
        }
    }

    private static String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getName());
        try {
            int id = v.getId();
            if (id != View.NO_ID && id != 0) {
                sb.append('#');
                try {
                    sb.append(v.getResources().getResourceEntryName(id));
                } catch (Throwable t) {
                    sb.append("0x").append(Integer.toHexString(id));
                }
            }
        } catch (Throwable ignored) {
            // у вида нет ресурсов
        }
        return sb.toString();
    }

    private static String parentsOf(View v) {
        StringBuilder sb = new StringBuilder();
        try {
            ViewParent p = v.getParent();
            for (int i = 0; i < MAX_PARENTS && p != null; i++) {
                if (sb.length() > 0) sb.append(" < ");
                sb.append(p instanceof View ? describe((View) p) : p.getClass().getName());
                p = p.getParent();
            }
        } catch (Throwable ignored) {
            // родителей не достать
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ отчёт

    static String status() {
        synchronized (LOCK) {
            if (!installed) return note;
            return ENTRIES.isEmpty() ? "ещё не встречалась" : "поймана (" + total + " раз)";
        }
    }

    static int caught() {
        synchronized (LOCK) {
            return ENTRIES.size();
        }
    }

    /** Часть отчёта: что поймано, откуда вызвано, и методы ближайших классов цепочки. */
    static String report(ClassLoader cl) {
        List<Entry> list;
        synchronized (LOCK) {
            list = new ArrayList<>(ENTRIES.values());
            if (list.isEmpty()) {
                return "[надпись «не поддерживается в регионе»] " + (installed
                        ? "с запуска TikTok на экране не появлялась: откройте чат, где она видна, потом вернитесь в меню мода\n"
                        : note + "\n");
            }
        }
        StringBuilder sb = new StringBuilder("[надпись «не поддерживается в регионе»] поймано мест: ")
                .append(list.size()).append('\n');
        List<String> classes = new ArrayList<>();
        for (Entry e : list) {
            sb.append("  «").append(e.text).append("» ×").append(e.count).append('\n');
            sb.append("  вид: ").append(e.view).append('\n');
            if (!e.parents.isEmpty()) sb.append("  родители: ").append(e.parents).append('\n');
            sb.append("  вызвали: ");
            for (int i = 0; i < e.frames.size(); i++) {
                if (i > 0) sb.append(" < ");
                sb.append(e.frames.get(i));
            }
            sb.append('\n');
            for (String f : e.frames) {
                int dot = f.lastIndexOf('.', f.indexOf(':') < 0 ? f.length() : f.indexOf(':'));
                String cls = dot > 0 ? f.substring(0, dot) : f;
                if (!classes.contains(cls) && classes.size() < DUMP_CLASSES) classes.add(cls);
            }
        }
        sb.append(Recon.compact(cl, classes, DUMP_LINES));
        return sb.toString();
    }

    static void resetForTest() {
        synchronized (LOCK) {
            ENTRIES.clear();
            total = 0;
            installed = false;
            note = "хук не ставился";
        }
    }

    static void markInstalledForTest() {
        synchronized (LOCK) {
            installed = true;
            note = "ждёт";
        }
    }
}
