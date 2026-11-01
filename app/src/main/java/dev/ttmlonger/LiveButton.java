package dev.ttmlonger;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Скрытие кнопки LIVE в левом верхнем углу главной ленты.
 *
 * Кнопку не удаляют и не подменяют: её прячут (INVISIBLE), поэтому остальная шапка не сдвигается, а
 * при выключении настройки кнопка возвращается как была. Искать её по номеру ресурса нельзя: он
 * меняется от сборки к сборке. Поэтому мод опознаёт кнопку по тому, как TikTok её называет:
 * - тег «live» или слово «live» в имени ресурса (надёжно);
 * - текст «LIVE» или описание «LIVE entrance» / «Прямые эфиры» (слабее: требует, чтобы вид был
 *   в самой шапке);
 * и по месту: компактный вид в левой верхней части экрана.
 *
 * Мод ищет кнопку раз в секунду, пока открыт экран TikTok, и, найдя, следит за ней каждый кадр
 * (TikTok иногда сам возвращает видимость при обновлении шапки).
 */
final class LiveButton {
    /** Ключ тега, под которым у TikTok 47.2.41 лежит имя кнопки шапки; в другой сборке может быть пустым. */
    private static final int KEYED_TAG = 0x7f0a374f;

    /** Размер подходящего вида в dp. */
    private static final float MIN_DP = 8f;
    private static final float MAX_DP = 96f;
    /** Правый край кнопки не дальше этой доли ширины экрана. */
    private static final float LEFT_ZONE = 0.32f;
    /** Середина кнопки не ниже этой доли высоты: надёжное имя / слабое совпадение. */
    private static final float TOP_STRONG = 0.20f;
    private static final float TOP_WEAK = 0.115f;

    /** Выше этого размера родитель считается рядом кнопок, и подниматься к нему нельзя. */
    private static final float CLIMB_DP = 72f;

    private static final int NODE_BUDGET = 6000;
    private static final int MAX_DEPTH = 48;

    private LiveButton() {
    }

    // ------------------------------------------------------------ чистые функции (проверяются без Android)

    /** «Живое» ли слово: LIVE целиком или описание входа в прямые эфиры. */
    static boolean isLiveWord(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 40) return false;
        return s.equals("live") || s.contains("live entrance") || s.contains("прямые эфиры")
                || s.contains("прямой эфир") || s.contains("прямі ефіри") || s.contains("прямий ефір");
    }

    /** Содержит ли имя ресурса слово live как отдельную часть (live_entrance, tab_live), а не alive/deliver. */
    static boolean isLiveName(String entry) {
        if (entry == null || entry.isEmpty() || entry.length() > 48) return false;
        boolean live = false;
        for (String t : entry.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.equals("live")) live = true;
            // значок на аватаре, счётчик и т. п. — это не кнопка входа
            if (t.equals("badge") || t.equals("ring") || t.equals("avatar") || t.equals("dot") || t.equals("count")
                    || t.equals("room") || t.equals("tag")) {
                return false;
            }
        }
        return live;
    }

    /** Чем опознана кнопка: «тег», «имя ресурса», «текст», «описание» или пустая строка. */
    static String how(String tag, String idName, CharSequence text, CharSequence desc) {
        if (tag != null && tag.trim().equalsIgnoreCase("live")) return "тег";
        if (isLiveName(idName)) return "имя ресурса";
        if (isLiveWord(text)) return "текст";
        if (isLiveWord(desc)) return "описание";
        return "";
    }

    /** 0 — не кнопка, 1 — слабое совпадение (текст, описание), 2 — сильное (тег, имя ресурса). */
    static int kindOf(String how) {
        if (how == null || how.isEmpty()) return 0;
        return how.equals("тег") || how.equals("имя ресурса") ? 2 : 1;
    }

    /** Подходит ли вид размером w×h в точке (left, top) на экране railW×railH (начало railLeft, railTop). */
    static boolean placeOk(boolean strong, int left, int top, int w, int h,
                           int railLeft, int railTop, int railW, int railH, float dp) {
        if (railW <= 0 || railH <= 0 || dp <= 0f) return false;
        if (!compact(w, h, dp)) return false;
        if (left < railLeft - dp) return false;
        if (left + w > railLeft + railW * LEFT_ZONE) return false;
        if (top < railTop - dp) return false;
        float centre = top + h / 2f;
        return centre <= railTop + railH * (strong ? TOP_STRONG : TOP_WEAK);
    }

    static boolean compact(int w, int h, float dp) {
        return w >= MIN_DP * dp && w <= MAX_DP * dp && h >= MIN_DP * dp && h <= MAX_DP * dp;
    }

    /** Можно ли подняться к родителю pw×ph: он ещё мал (не ряд из нескольких кнопок и не вся шапка). */
    static boolean climbOk(int pw, int ph, float dp) {
        return compact(pw, ph, dp) && pw <= CLIMB_DP * dp && ph <= CLIMB_DP * dp;
    }

    /** Названия классов, которые бывают только у временных плашек. */
    static boolean transientClass(String cls) {
        if (cls == null) return false;
        String n = cls.toLowerCase(Locale.ROOT);
        return n.contains("toast") || n.contains("notification") || n.contains("banner");
    }

    // ------------------------------------------------------------ состояние

    private static final class Item {
        final WeakReference<View> ref;
        final int was;

        Item(View v) {
            ref = new WeakReference<>(v);
            was = v.getVisibility();
        }
    }

    private static final class Found {
        View view;
        String how = "";
        int kind;
    }

    private static final class Scan {
        final int[] at = new int[2];
        final int[] origin = new int[2];
        int railW;
        int railH;
        float dp;
        int nodes;
        Found best;
        final ArrayList<String> misses = new ArrayList<>();
    }

    private static final ArrayList<Item> ours = new ArrayList<>();
    private static WeakReference<View> current = new WeakReference<>(null);
    private static String currentHow = "";
    private static String currentInfo = "";
    private static WeakReference<ViewTreeObserver> watched = new WeakReference<>(null);

    private static volatile String diag = "Ещё не проверял";
    private static volatile int scans;
    private static volatile int lastNodes;
    private static volatile int hiddenTimes;
    private static volatile String lastMisses = "";
    private static volatile String lastScreen = "-";

    // ------------------------------------------------------------ цикл

    private static Handler mainHandler;

    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };
    static boolean enabled() {
        return Prefs.hideLiveButton && !CrashGuard.tripped;
    }

    /** Запустить поиск: при показе экрана и при смене настройки. Главный поток. */
    static void kick() {
        if (!enabled()) {
            if (!ours.isEmpty()) restore();
            return;
        }
        main().removeCallbacks(TICK);
        main().postDelayed(TICK, 300L);
    }

    private static void tick() {
        long delay = 1000L;
        boolean again = false;
        try {
            if (!enabled()) {
                restore();
                diag = Prefs.hideLiveButton ? "Отключено защитой от вылетов" : "Выключено";
                return;
            }
            again = true;
            Activity a = ScreenWatcher.top();
            if (a == null) {
                delay = 1500L;
                return;
            }
            lastScreen = a.getClass().getName();
            View decor = a.getWindow().getDecorView();
            if (decor.getWindowVisibility() != View.VISIBLE) {
                delay = 2000L;
                return;
            }
            watch(decor);
            View cur = current.get();
            if (cur != null && cur.isAttachedToWindow() && cur.getRootView() == decor) {
                enforce();
                diag = "Кнопка скрыта (найдена по: " + currentHow + ")";
                delay = 2500L;
                return;
            }
            Found f = find(decor);
            if (f == null) {
                diag = "Кнопки LIVE на экране не нашёл" + (lastMisses.isEmpty() ? "" : " (похожее есть, но не в шапке)");
                return;
            }
            hide(f);
            diag = "Кнопка скрыта (найдена по: " + f.how + ")";
            delay = 2500L;
        } catch (Throwable t) {
            FlagStore.log(t);
            diag = "Сбой: " + t.getClass().getSimpleName();
            delay = 3000L;
        } finally {
            if (again) main().postDelayed(TICK, delay);
        }
    }

    // ------------------------------------------------------------ поиск

    private static Found find(View decor) {
        Scan s = new Scan();
        decor.getLocationOnScreen(s.origin);
        s.railW = decor.getWidth();
        s.railH = decor.getHeight();
        s.dp = decor.getResources().getDisplayMetrics().density;
        scans++;
        walk(decor, s, 0);
        lastNodes = s.nodes;
        StringBuilder sb = new StringBuilder();
        for (String m : s.misses) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(m);
        }
        lastMisses = sb.toString();
        Found b = s.best;
        if (b == null) return null;
        // подняться к контейнеру кнопки: прятать надо и подпись, и значок, и область нажатия
        View top = b.view;
        for (int i = 0; i < 3; i++) {
            Object p = top.getParent();
            if (!(p instanceof ViewGroup)) break;
            ViewGroup pg = (ViewGroup) p;
            if (pg.getChildCount() > 4) break;
            if (!climbOk(pg.getWidth(), pg.getHeight(), s.dp)) break;
            top = pg;
        }
        b.view = top;
        return b;
    }

    private static void walk(View v, Scan s, int depth) {
        if (v == null || depth > MAX_DEPTH || s.nodes++ > NODE_BUDGET) return;
        if (v.getVisibility() != View.VISIBLE) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (compact(w, h, s.dp)) {
            v.getLocationOnScreen(s.at);
            // грубая зона по самому мягкому условию; точное решение ниже
            if (s.at[0] <= s.origin[0] + s.railW * LEFT_ZONE
                    && s.at[1] + h / 2f <= s.origin[1] + s.railH * TOP_STRONG) {
                consider(v, s, w, h);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), s, depth + 1);
        }
    }

    private static void consider(View v, Scan s, int w, int h) {
        String tag = null;
        Object t = v.getTag();
        if (t instanceof String) tag = (String) t;
        if (tag == null) {
            Object k = v.getTag(KEYED_TAG);
            if (k instanceof String) tag = (String) k;
        }
        String idName = null;
        int id = v.getId();
        if (id != View.NO_ID && (id & 0xFF000000) == 0x7F000000) {
            try {
                idName = v.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
                // имени у номера нет
            }
        }
        CharSequence text = v instanceof TextView ? ((TextView) v).getText() : null;
        String how = how(tag, idName, text, v.getContentDescription());
        int kind = kindOf(how);
        if (kind == 0) return;
        boolean ok = placeOk(kind == 2, s.at[0], s.at[1], w, h, s.origin[0], s.origin[1], s.railW, s.railH, s.dp)
                && !transientView(v);
        if (!ok) {
            if (s.misses.size() < 3) {
                s.misses.add(v.getClass().getSimpleName() + " (" + how + ") "
                        + Math.round(100f * (s.at[0] - s.origin[0]) / s.railW) + "%/"
                        + Math.round(100f * (s.at[1] - s.origin[1]) / s.railH) + "% "
                        + Math.round(w / s.dp) + "×" + Math.round(h / s.dp) + "dp");
            }
            return;
        }
        if (s.best == null || kind > s.best.kind) {
            Found f = new Found();
            f.view = v;
            f.how = how;
            f.kind = kind;
            s.best = f;
        }
    }

    private static boolean transientView(View v) {
        View x = v;
        for (int i = 0; x != null && i < 40; i++) {
            if (transientClass(x.getClass().getName())) return true;
            Object tag = x.getTag();
            if (tag instanceof String) {
                String ts = ((String) tag).toLowerCase(Locale.ROOT);
                if (ts.equals("dm_notice") || ts.equals("notification")) return true;
            }
            CharSequence d = x.getContentDescription();
            if (d != null) {
                String ds = d.toString().toLowerCase(Locale.ROOT);
                if (ds.contains("notification") || ds.contains("уведомлен")) return true;
            }
            Object p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return false;
    }

    // ------------------------------------------------------------ скрытие

    private static void hide(Found f) {
        View v = f.view;
        restoreOthers(v);
        boolean known = false;
        for (Item it : ours) {
            if (it.ref.get() == v) {
                known = true;
                break;
            }
        }
        if (!known) {
            ours.add(new Item(v));
            hiddenTimes++;
        }
        current = new WeakReference<>(v);
        currentHow = f.how;
        currentInfo = v.getClass().getName() + " " + Math.round(v.getWidth() / v.getResources().getDisplayMetrics().density)
                + "×" + Math.round(v.getHeight() / v.getResources().getDisplayMetrics().density) + "dp";
        if (v.getVisibility() == View.VISIBLE) v.setVisibility(View.INVISIBLE);
    }

    /** Вернуть всё, что мод прятал раньше, кроме view keep. */
    private static void restoreOthers(View keep) {
        for (int i = ours.size() - 1; i >= 0; i--) {
            Item it = ours.get(i);
            View v = it.ref.get();
            if (v == keep) continue;
            if (v != null && v.getVisibility() == View.INVISIBLE) v.setVisibility(it.was);
            ours.remove(i);
        }
    }

    /** Каждый кадр: если TikTok снова показал спрятанное, спрятать. true — что-то поменяли. */
    private static boolean enforce() {
        boolean changed = false;
        for (int i = ours.size() - 1; i >= 0; i--) {
            Item it = ours.get(i);
            View v = it.ref.get();
            if (v == null) {
                ours.remove(i);
                continue;
            }
            if (v.getVisibility() == View.VISIBLE) {
                v.setVisibility(View.INVISIBLE);
                changed = true;
            }
        }
        return changed;
    }

    private static final ViewTreeObserver.OnPreDrawListener PRE_DRAW = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            try {
                if (!ours.isEmpty() && enabled()) {
                    // если что-то поменяли, кадр перерисуется ещё раз уже без кнопки
                    return !enforce();
                }
            } catch (Throwable ignored) {
                // кадр не должен ломаться из-за мода
            }
            return true;
        }
    };

    private static void watch(View decor) {
        ViewTreeObserver next = decor.getViewTreeObserver();
        ViewTreeObserver old = watched.get();
        if (next == old) return;
        if (old != null && old.isAlive()) old.removeOnPreDrawListener(PRE_DRAW);
        if (next.isAlive()) {
            next.addOnPreDrawListener(PRE_DRAW);
            watched = new WeakReference<>(next);
        }
    }

    /** Вернуть кнопку, как была, и перестать следить. */
    private static void restore() {
        restoreOthers(null);
        current = new WeakReference<>(null);
        currentHow = "";
        currentInfo = "";
        ViewTreeObserver old = watched.get();
        if (old != null && old.isAlive()) old.removeOnPreDrawListener(PRE_DRAW);
        watched = new WeakReference<>(null);
    }

    // ------------------------------------------------------------ для экрана настроек

    static String status() {
        if (!Prefs.hideLiveButton) return "Кнопка LIVE: не скрывается";
        if (CrashGuard.tripped) return "Кнопка LIVE: отключено защитой от вылетов";
        return "Кнопка LIVE: " + diag;
    }

    /** Сведения для разработчика: названия классов TikTok, размеры и положение; текстов с экрана нет. */
    static String details() {
        StringBuilder sb = new StringBuilder();
        sb.append("Кнопка LIVE: ").append(Prefs.hideLiveButton ? "скрывать" : "не скрывать").append('\n');
        sb.append("состояние: ").append(diag).append('\n');
        sb.append("экран: ").append(lastScreen).append('\n');
        sb.append("проверок ").append(scans).append(", видов в последней ").append(lastNodes).append(", спрятано раз ")
                .append(hiddenTimes).append('\n');
        sb.append("найденный вид: ").append(currentInfo.isEmpty() ? "нет" : currentInfo + " (по: " + currentHow + ")")
                .append('\n');
        sb.append("похожее не в шапке: ").append(lastMisses.isEmpty() ? "нет" : lastMisses);
        return sb.toString();
    }
}
