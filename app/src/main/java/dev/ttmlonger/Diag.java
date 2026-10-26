package dev.ttmlonger;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Диагностика: что мод видит на экране TikTok. Без ПК и root единственный способ понять, почему
 * элемент не появился, это отчёт, который можно скопировать и прислать. Отчёт содержит названия
 * классов, тексты подписей на экране и состояние хуков. Личные данные в него не попадают, но
 * тексты экрана (например, имя в профиле) могут: просмотрите перед отправкой.
 */
final class Diag {
    /** Метка на view мода: в дереве экрана они помечаются и не раскрываются. */
    static final String OWN_TAG = "ttmlonger";

    private static final int MAX_EVENTS = 80;
    private static final int MAX_LINES = 450;
    private static final int MAX_DEPTH = 40;
    private static final int MAX_TEXT = 60;

    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static volatile String lastScan = "обхода ещё не было";
    private static boolean announced;

    private Diag() {
    }

    static void note(String s) {
        FlagStore.log(s);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        synchronized (EVENTS) {
            if (EVENTS.size() >= MAX_EVENTS) EVENTS.removeFirst();
            EVENTS.addLast(line);
        }
    }

    static void setLastScan(String s) {
        lastScan = s;
    }

    /** Один раз за запуск показывает всплывающую подсказку: видно, что мод загрузился. */
    static void announce(Activity a) {
        if (announced) return;
        announced = true;
        note("мод активен, экран: " + a.getClass().getName());
        if (!Prefs.startupToast) return;
        try {
            Toast.makeText(a, "TTMLonger " + Targets.VERSION
                    + " активен. Меню: два пальца на экране на 1,2 с", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    // ---------------------------------------------------------------- отчёт

    static String report(Activity a) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("TTMLonger ").append(Targets.VERSION).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append("), ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Пакет: ").append(a.getPackageName()).append(", ").append(tiktokVersion(a)).append('\n');
        sb.append("Экран: ").append(a.getClass().getName()).append('\n');
        String frags = fragments(a);
        if (frags != null) sb.append("Фрагменты: ").append(frags).append('\n');
        sb.append("A/B: ").append(FlagHooks.status()).append('\n');
        sb.append("Защита от крашей сработала: ").append(CrashGuard.tripped).append('\n');
        sb.append("Регион: ").append(Prefs.region.isEmpty() ? "не подменяется" : Prefs.region)
                .append(", акцент: ").append(Prefs.accent == 0 ? "стандартный" : Integer.toHexString(Prefs.accent))
                .append(", Amoled: ").append(Prefs.amoled).append('\n');
        sb.append("Последний обход: ").append(lastScan).append('\n');

        sb.append("\n--- события ---\n");
        synchronized (EVENTS) {
            for (String e : EVENTS) sb.append(e).append('\n');
        }

        sb.append("\n--- дерево view экрана ---\n");
        try {
            int[] lines = {0};
            dump(a.getWindow().getDecorView(), a.getResources(), sb, 0, lines);
            if (lines[0] >= MAX_LINES) sb.append("... (обрезано)\n");
        } catch (Throwable t) {
            sb.append("не удалось снять дерево: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String tiktokVersion(Activity a) {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return "версия " + pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "версия неизвестна";
        }
    }

    /** Имена классов фрагментов страницы (по ним потом можно опознавать экраны точнее). */
    private static String fragments(Activity a) {
        try {
            Method get = a.getClass().getMethod("getSupportFragmentManager");
            Object fm = get.invoke(a);
            if (fm == null) return null;
            Method list = null;
            for (Class<?> c = fm.getClass(); c != null && list == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("getFragments") && m.getParameterTypes().length == 0) {
                        list = m;
                        break;
                    }
                }
            }
            if (list == null) return null;
            list.setAccessible(true);
            Object r = list.invoke(fm);
            if (!(r instanceof List)) return null;
            StringBuilder b = new StringBuilder();
            for (Object f : (List<?>) r) {
                if (f == null) continue;
                if (b.length() > 0) b.append(", ");
                b.append(f.getClass().getName());
            }
            return b.length() == 0 ? null : b.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dump(View v, Resources res, StringBuilder sb, int depth, int[] lines) {
        if (lines[0] >= MAX_LINES || depth > MAX_DEPTH) return;
        lines[0]++;

        for (int i = 0; i < depth; i++) sb.append(' ');
        String cn = v.getClass().getName();
        sb.append(cn.startsWith("android.") ? cn.substring(cn.lastIndexOf('.') + 1) : cn);

        if (OWN_TAG.equals(v.getTag())) {
            sb.append("  [элемент мода]\n");
            return;
        }

        String id = idName(v, res);
        if (id != null) sb.append(" #").append(id);

        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        sb.append(' ').append(loc[0]).append(',').append(loc[1]).append(' ')
                .append(v.getWidth()).append('x').append(v.getHeight());

        int vis = v.getVisibility();
        if (vis != View.VISIBLE) sb.append(vis == View.GONE ? " GONE" : " INVISIBLE");
        if (v.isClickable()) sb.append(" click");

        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(" \"").append(clip(t.toString())).append('"');
        }
        CharSequence d = v.getContentDescription();
        if (d != null && d.length() > 0) sb.append(" desc=\"").append(clip(d.toString())).append('"');
        sb.append('\n');

        if (vis == View.GONE || !(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), res, sb, depth + 1, lines);
    }

    private static String idName(View v, Resources res) {
        int id = v.getId();
        if (id == View.NO_ID || id == 0) return null;
        try {
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return Integer.toHexString(id);
        }
    }

    private static String clip(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "…" : t;
    }
}
