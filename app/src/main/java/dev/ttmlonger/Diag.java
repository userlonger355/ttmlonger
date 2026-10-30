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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final int MAX_LINES = 800;
    private static final int MAX_DEPTH = 100;
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

    /**
     * Короткий отчёт: только состояние хуков, поиск в dex и события, без дерева экрана и списка
     * флагов. Его удобно присылать, когда нужно понять, нашлись ли хуки и что сработало.
     */
    static String shortReport(Activity a) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("TTMLonger ").append(Targets.VERSION).append(" (кратко)\n");
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append("), ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Пакет: ").append(a.getPackageName()).append(", ").append(tiktokVersion(a)).append('\n');
        sb.append("Экран: ").append(a.getClass().getName()).append('\n');
        sb.append("A/B: ").append(FlagHooks.status()).append('\n');
        sb.append("Скачивание: ").append(DownloadHooks.status()).append('\n');
        sb.append("Запросы адреса скачивания: ").append(DownloadHooks.requests())
                .append(", подсказок «Сохранить через мод» показано ").append(NativeOffer.shown()).append('\n');
        sb.append("Видео и истории: ").append(VideoTracker.status())
                .append(", последняя ошибка загрузки: ").append(VideoSaver.lastError()).append('\n');
        sb.append("Стикеры: включено ").append(Prefs.saveStickers)
                .append(", хуки: ").append(StickerHooks.status())
                .append("\nСкан экрана: ").append(StickerScan.lastSummary()).append('\n');
        sb.append(DexHooks.report());
        sb.append(StreakRunner.report());
        sb.append("Баннер профиля: ").append(FlagStore.allOn(Targets.BANNER_FLAGS) ? "включён" : "выключен")
                .append(", голосовые комментарии: ")
                .append(FlagStore.allOn(Targets.VOICE_COMMENT_FLAGS) ? "включены" : "выключены").append('\n');
        sb.append("\n--- события ---\n");
        synchronized (EVENTS) {
            for (String e : EVENTS) sb.append(e).append('\n');
        }
        String dump = StickerSaver.dump();
        if (!dump.isEmpty()) sb.append("\n--- окна под меню: схема (лист стикера) ---\n").append(dump);
        return sb.toString();
    }

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
        sb.append("Глубокая подмена акцента: ").append(AccentHooks.status()).append('\n');
        sb.append("Блок в настройках: ").append(SettingsHost.status(a)).append('\n');
        sb.append("Аватар: ").append(AvatarHooks.status())
                .append(AvatarScreen.matches(a) ? " (этот экран — просмотр аватарки)" : "").append('\n');
        sb.append("Скачивание: без знака ").append(Prefs.dlNoWatermark).append(", всегда ").append(Prefs.dlAlways)
                .append(", качество ").append(DownloadScreen.qualityLabel(Prefs.dlQuality))
                .append(", стикеры ").append(Prefs.saveStickers).append('\n');
        sb.append("Хуки скачивания: ").append(DownloadHooks.status()).append('\n');
        sb.append("Запросы адреса скачивания: ").append(DownloadHooks.requests())
                .append(", подсказок «Сохранить через мод» показано ").append(NativeOffer.shown()).append('\n');
        sb.append("Видео и истории: ").append(VideoTracker.status())
                .append(", последняя ошибка загрузки: ").append(VideoSaver.lastError()).append('\n');
        sb.append("Хуки стикеров: ").append(StickerHooks.status()).append('\n');
        sb.append("Скан экрана «Стикеры»: ").append(StickerScan.lastSummary()).append('\n');
        sb.append(DexHooks.report());
        sb.append(StreakRunner.report());
        sb.append("Баннер профиля: ").append(FlagStore.allOn(Targets.BANNER_FLAGS) ? "включён" : "выключен")
                .append(", голосовые комментарии: ")
                .append(FlagStore.allOn(Targets.VOICE_COMMENT_FLAGS) ? "включены" : "выключены").append('\n');
        sb.append("Оператор для региона: ").append(Prefs.region.isEmpty() ? "-"
                : (Carriers.mccMnc(Prefs.region) == null ? "нет в таблице, настоящий"
                : Carriers.mccMnc(Prefs.region) + " " + Carriers.name(Prefs.region))).append('\n');
        sb.append("Последний обход: ").append(lastScan).append('\n');

        sb.append("\n--- флаги TikTok, прочитанные за последние 2 минуты (самые свежие первыми) ---\n");
        try {
            List<FlagStore.Flag> recent = FlagStore.recent(120_000L);
            int shownFlags = 0;
            for (FlagStore.Flag f : recent) {
                if (shownFlags++ >= 400) {
                    sb.append("... (ещё ").append(recent.size() - 400).append(")\n");
                    break;
                }
                FlagStore.Patch o = FlagStore.rawOverride(f.key);
                sb.append(f.key).append(" = ").append(clip(String.valueOf(f.value)))
                        .append(o == null ? "" : "  [мой: " + clip(o.raw) + "]").append('\n');
            }
            if (recent.isEmpty()) sb.append("(за последние 2 минуты флаги не читались)\n");
        } catch (Throwable t) {
            sb.append("не удалось собрать флаги: ").append(t).append('\n');
        }

        sb.append("\n--- события ---\n");
        synchronized (EVENTS) {
            for (String e : EVENTS) sb.append(e).append('\n');
        }

        String sheet = StickerSaver.dump();
        if (!sheet.isEmpty()) sb.append("\n--- окна под меню: схема (лист стикера) ---\n").append(sheet);

        sb.append("\n--- окна приложения ---\n");
        sb.append(windows());

        sb.append("\n--- надписи на экране (сверху вниз) ---\n");
        try {
            View decor = a.getWindow().getDecorView();
            List<Item> items = new ArrayList<>();
            Map<String, Integer> classes = new HashMap<>();
            collect(decor, items, classes, 0);
            Collections.sort(items, new Comparator<Item>() {
                @Override
                public int compare(Item x, Item y) {
                    return x.y != y.y ? Integer.compare(x.y, y.y) : Integer.compare(x.x, y.x);
                }
            });
            int shown = 0;
            for (Item it : items) {
                if (shown++ >= 150) {
                    sb.append("... (ещё ").append(items.size() - 150).append(")\n");
                    break;
                }
                sb.append(it.y).append(',').append(it.x).append("  ").append(it.cls).append("  \"")
                        .append(it.text).append("\"\n");
            }
            if (items.isEmpty()) sb.append("(ни одной TextView с текстом: текст рисуется не через TextView)\n");

            sb.append("\n--- самые частые классы view ---\n");
            List<Map.Entry<String, Integer>> top = new ArrayList<>(classes.entrySet());
            Collections.sort(top, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) {
                    return y.getValue() - x.getValue();
                }
            });
            for (int i = 0; i < Math.min(15, top.size()); i++) {
                sb.append(top.get(i).getValue()).append("  ").append(top.get(i).getKey()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("не удалось собрать надписи: ").append(t).append('\n');
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

    private static final class Item {
        final int y;
        final int x;
        final String cls;
        final String text;

        Item(int y, int x, String cls, String text) {
            this.y = y;
            this.x = x;
            this.cls = cls;
            this.text = text;
        }
    }

    /** Собирает надписи видимых TextView и считает, сколько каких классов view на экране. */
    private static void collect(View v, List<Item> out, Map<String, Integer> classes, int depth) {
        if (depth > MAX_DEPTH || v.getVisibility() != View.VISIBLE || OWN_TAG.equals(v.getTag())) return;
        String cn = v.getClass().getName();
        Integer n = classes.get(cn);
        classes.put(cn, n == null ? 1 : n + 1);
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0 && v.isShown()) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                out.add(new Item(loc[1], loc[0], cn, clip(t.toString())));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out, classes, depth + 1);
        }
    }

    /** Все окна приложения (основное, диалоги, всплывающие): так видно, не в отдельном ли окне страница. */
    private static String windows() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object g = wmg.getMethod("getInstance").invoke(null);
            String[] names = (String[]) wmg.getMethod("getViewRootNames").invoke(g);
            Method root = wmg.getMethod("getRootView", String.class);
            StringBuilder b = new StringBuilder();
            for (String name : names) {
                View v = (View) root.invoke(g, name);
                if (v == null) continue;
                b.append(name).append("  ").append(v.getClass().getName()).append("  ")
                        .append(v.getWidth()).append('x').append(v.getHeight())
                        .append(v.getVisibility() == View.VISIBLE ? "" : " скрыто").append('\n');
            }
            return b.length() == 0 ? "(список пуст)\n" : b.toString();
        } catch (Throwable t) {
            return "(список окон недоступен: " + t.getClass().getSimpleName() + ")\n";
        }
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

        if (vis != View.VISIBLE || !(v instanceof ViewGroup)) return;
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
