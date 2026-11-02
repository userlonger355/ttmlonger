package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Плашки над именем автора в ленте: точное время публикации («26.09 02:32:00») и регион видео
 * (флаг и код страны). Данные берутся из самой модели видео TikTok (Aweme.getCreateTime и
 * getRegion), которую трекер уже запоминает, поэтому новых хуков здесь нет и перезапуск не нужен.
 *
 * Как найти место. TikTok не говорит, какое видео сейчас на экране, и его экраны обфусцированы,
 * поэтому видео узнаётся по тексту: среди недавно виденных видео ищется то, чьё имя автора (или
 * начало описания) видно на экране в нижней части. Плашки кладутся в окно активности над этой
 * подписью и следуют за ней при каждой перерисовке, поэтому едут вместе с лентой. Пока подпись не
 * найдена, плашек нет.
 *
 * Чистые функции вверху файла (время, регион, сопоставление текста) не зависят от Android и
 * проверяются обычными тестами.
 */
final class VideoInfo {
    static final int MODE_BOTH = 0;
    static final int MODE_FLAG = 1;
    static final int MODE_CODE = 2;
    private static final String[] MODE_LABELS = {"Флаг и код", "Только флаг", "Только код"};

    // ---------------------------------------------------------------- чистая логика

    static String modeLabel(int mode) {
        return MODE_LABELS[mode < 0 || mode > 2 ? 0 : mode];
    }

    static int nextMode(int mode) {
        return ((mode < 0 || mode > 2 ? 0 : mode) + 1) % 3;
    }

    /** Секунды или миллисекунды Unix-времени, приведённые к секундам; 0, если на время не похоже (до 2014 и после 2100). */
    static long toSeconds(long raw) {
        if (raw <= 0) return 0;
        if (raw > 100_000_000_000L) raw /= 1000L;
        if (raw < 1_388_534_400L || raw > 4_102_444_800L) return 0;
        return raw;
    }

    /** «26.09 02:32:00»; для прошлых лет «26.09.25 02:32:00». */
    static String formatTime(long sec, boolean seconds, TimeZone tz, long nowMs) {
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(sec * 1000L);
        Calendar n = Calendar.getInstance(tz, Locale.US);
        n.setTimeInMillis(nowMs);
        int year = c.get(Calendar.YEAR);
        StringBuilder sb = new StringBuilder();
        sb.append(two(c.get(Calendar.DAY_OF_MONTH))).append('.').append(two(c.get(Calendar.MONTH) + 1));
        if (year != n.get(Calendar.YEAR)) sb.append('.').append(two(year % 100));
        sb.append(' ').append(two(c.get(Calendar.HOUR_OF_DAY))).append(':').append(two(c.get(Calendar.MINUTE)));
        if (seconds) sb.append(':').append(two(c.get(Calendar.SECOND)));
        return sb.toString();
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** Полная дата для подсказки по нажатию: «26 сентября 2026, 02:32:00 (GMT+07:00)». */
    static String fullTime(long sec, TimeZone tz) {
        Locale ru = new Locale("ru");
        SimpleDateFormat f = new SimpleDateFormat("d MMMM yyyy, HH:mm:ss", ru);
        f.setTimeZone(tz);
        Date d = new Date(sec * 1000L);
        return f.format(d) + " (" + tz.getDisplayName(tz.inDaylightTime(d), TimeZone.SHORT, ru) + ")";
    }

    /** «3 дн. назад»: сколько прошло с публикации. */
    static String ageText(long secondsAgo) {
        if (secondsAgo < 0) return "из будущего";
        if (secondsAgo < 60) return "только что";
        long min = secondsAgo / 60;
        if (min < 60) return min + " мин. назад";
        long h = min / 60;
        if (h < 24) return h + " ч. назад";
        long days = h / 24;
        if (days < 60) return days + " дн. назад";
        long months = days / 30;
        if (months < 24) return months + " мес. назад";
        return days / 365 + " г. назад";
    }

    /** Двухбуквенный код страны в верхнем регистре или null. */
    static String normRegion(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() != 2) return null;
        for (int i = 0; i < 2; i++) {
            char ch = s.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z'))) return null;
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /** Текст региональной плашки: флаг и/или код; null, если кода нет. */
    static String regionText(String iso, int mode) {
        String code = normRegion(iso);
        if (code == null) return null;
        if (mode == MODE_FLAG) return Regions.flag(code);
        if (mode == MODE_CODE) return code;
        return Regions.flag(code) + " " + code;
    }

    /** Пробелы подряд (в том числе переводы строк и неразрывные) в один, края обрезаны. */
    static String norm(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Начало описания видео для поиска на экране: не короче 8 знаков, иначе пусто. */
    static String descPrefix(String desc) {
        String n = norm(desc);
        if (n.length() < 8) return "";
        return n.substring(0, Math.min(n.length(), 20));
    }

    /**
     * Подходит ли подпись на экране этому видео. text, names и desc уже прошли norm.
     *
     * @return 1 — имя автора, 2 — начало описания, 0 — не подходит
     */
    static int match(String text, String[] names, String desc) {
        if (text == null || text.isEmpty()) return 0;
        for (String x : names) {
            if (x == null || x.length() < 2) continue;
            if (text.equalsIgnoreCase(x)) return 1;
            // «Имя ✓»: значок или пара знаков после имени
            if (text.length() > x.length() && text.length() <= x.length() + 3
                    && text.regionMatches(true, 0, x, 0, x.length())
                    && !Character.isLetterOrDigit(text.charAt(x.length()))) {
                return 1;
            }
        }
        if (desc != null && desc.length() >= 8 && text.startsWith(desc)) return 2;
        return 0;
    }

    // ---------------------------------------------------------------- вид и место плашек (чистая логика)

    static final int STYLE_AUTO = 0;
    static final int STYLE_DARK = 1;
    static final int STYLE_LIGHT = 2;
    private static final String[] STYLE_LABELS = {"По теме приложения", "Тёмные", "Светлые"};

    static String styleLabel(int style) {
        return STYLE_LABELS[style < 0 || style > 2 ? 0 : style];
    }

    static int nextStyle(int style) {
        return ((style < 0 || style > 2 ? 0 : style) + 1) % 3;
    }

    /** Цвета плашки: {фон, текст}. «По теме» даёт тёмные плашки в тёмной теме и светлые в светлой. */
    static int[] colorsFor(int style, boolean night) {
        boolean dark = style == STYLE_DARK || (style == STYLE_AUTO && night);
        return dark ? new int[]{0xD0121214, 0xFFFFFFFF} : new int[]{0xF2F2F2F5, 0xFF1B1B1F};
    }

    static final int POS_AUTO = 0;
    static final int POS_RIGHT = 1;
    static final int POS_ABOVE = 2;
    static final int POS_BELOW = 3;
    private static final String[] POS_LABELS = {"Авто: где свободно", "Справа от имени", "Над именем", "Под именем"};

    static String posLabel(int pos) {
        return POS_LABELS[pos < 0 || pos > 3 ? 0 : pos];
    }

    static int nextPos(int pos) {
        return ((pos < 0 || pos > 3 ? 0 : pos) + 1) % 4;
    }

    static int clampOpacity(int percent) {
        return Math.max(20, Math.min(100, percent));
    }

    /** Места относительно подписи: 0 справа от имени, 1 над ним, 2 под ним. В каком порядке пробовать. */
    static int[] slotOrder(int pos) {
        switch (pos) {
            case POS_ABOVE:
                return new int[]{1, 0, 2};
            case POS_BELOW:
                return new int[]{2, 0, 1};
            default:
                return new int[]{0, 1, 2};
        }
    }

    static long overlapArea(int l, int t, int r, int b, int[] o) {
        long w = Math.min(r, o[2]) - Math.max(l, o[0]);
        long h = Math.min(b, o[3]) - Math.max(t, o[1]);
        return w > 0 && h > 0 ? w * h : 0L;
    }

    /**
     * Куда класть плашки размером pw×ph рядом с подписью автора, чтобы не закрыть другие элементы.
     *
     * @param ax    левый край подписи
     * @param at    верх первой строки подписи
     * @param ar1   правый край первой строки
     * @param ab1   низ первой строки
     * @param ab    низ последней строки подписи
     * @param occ   занятые прямоугольники {лево, верх, право, низ} (без самой подписи)
     * @param order порядок мест (см. slotOrder)
     * @return {x, y, место, площадь наложения}; место -1, если ничего не поместилось и взят запасной вариант
     */
    static int[] pickSlot(int ax, int at, int ar1, int ab1, int ab, int pw, int ph, int gap, int[][] occ,
                          int sw, int sh, int[] order) {
        long area = (long) pw * ph;
        long tolerance = area / 50L;
        int bestX = 0;
        int bestY = 0;
        int bestSlot = -1;
        long bestOverlap = Long.MAX_VALUE;
        for (int s : order) {
            int x;
            int y;
            int rightLimit = sw;
            if (s == 0) {
                x = ar1 + gap;
                y = (at + ab1) / 2 - ph / 2;
                rightLimit = Math.round(sw * 0.84f);
            } else if (s == 1) {
                x = ax;
                y = at - ph - gap;
            } else {
                x = ax;
                y = ab + gap;
            }
            if (s != 0 && x + pw > sw - gap) x = Math.max(0, sw - gap - pw);
            if (x < 0 || y < 0 || x + pw > rightLimit || y + ph > sh) continue;
            long overlap = 0;
            if (occ != null) {
                for (int[] o : occ) overlap += overlapArea(x, y, x + pw, y + ph, o);
            }
            if (overlap <= tolerance) return new int[]{x, y, s, (int) Math.min(overlap, Integer.MAX_VALUE)};
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestX = x;
                bestY = y;
                bestSlot = s;
            }
        }
        if (bestSlot >= 0) return new int[]{bestX, bestY, bestSlot, (int) Math.min(bestOverlap, Integer.MAX_VALUE)};
        // ничего не поместилось: над подписью, прижав к краям экрана
        int x = Math.max(0, Math.min(ax, sw - gap - pw));
        int y = Math.max(0, at - ph - gap);
        return new int[]{x, y, -1, 0};
    }

    /** Ночная ли тема у этого контекста. */
    static boolean isNight(Context c) {
        try {
            return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------- данные видео

    /** Что TikTok знает про видео: время публикации и регион. */
    static final class Meta {
        final long created;
        final String region;
        /** Откуда взят регион: «видео», «автор» или пусто. */
        final String regionFrom;

        Meta(long created, String region, String regionFrom) {
            this.created = created;
            this.region = region;
            this.regionFrom = regionFrom;
        }
    }

    /** Что искать на экране про видео. */
    static final class Keys {
        final String[] names;
        final String desc;

        Keys(String[] names, String desc) {
            this.names = names;
            this.desc = desc;
        }
    }

    private static Object fieldOf(Object o, String name) {
        if (o == null) return null;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // выше по иерархии
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Результат геттера или, если такого нет, значение поля с одним из имён. */
    private static Object get(Object o, String getter, String... fields) {
        Object v = DownloadHooks.call(o, getter);
        if (v != null) return v;
        for (String f : fields) {
            v = fieldOf(o, f);
            if (v != null) return v;
        }
        return null;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    static Meta metaOf(VideoTracker.Entry e) {
        Meta have = e.meta;
        if (have != null) return have;
        Object aw = e.aweme;
        if (aw == null) return new Meta(0, null, "");
        long created = 0;
        Object c = get(aw, "getCreateTime", "createTime", "create_time", "mCreateTime");
        if (c instanceof Number) {
            created = toSeconds(((Number) c).longValue());
        } else if (c instanceof String) {
            try {
                created = toSeconds(Long.parseLong(((String) c).trim()));
            } catch (NumberFormatException ignored) {
                // не число
            }
        }
        String region = normRegion(str(get(aw, "getRegion", "region")));
        String from = region == null ? "" : "видео";
        if (region == null) {
            Object author = get(aw, "getAuthor", "author");
            region = normRegion(str(get(author, "getRegion", "region")));
            if (region != null) from = "автор";
        }
        Meta m = new Meta(created, region, from);
        // Пустое не запоминаем: модель могла быть ещё не заполнена.
        if (created > 0 || region != null) e.meta = m;
        return m;
    }

    static Keys keysOf(VideoTracker.Entry e) {
        Keys have = e.keys;
        if (have != null) return have;
        Object aw = e.aweme;
        List<String> names = new ArrayList<>();
        String desc = "";
        if (aw != null) {
            Object author = get(aw, "getAuthor", "author");
            String nick = norm(str(get(author, "getNickname", "nickname")));
            String uid = norm(str(get(author, "getUniqueId", "uniqueId")));
            String sid = norm(str(get(author, "getShortId", "shortId")));
            if (!nick.isEmpty()) names.add(nick);
            if (!uid.isEmpty()) {
                names.add("@" + uid);
                names.add(uid);
            }
            if (!sid.isEmpty() && !sid.equals("0")) {
                names.add("@" + sid);
                names.add(sid);
            }
            desc = descPrefix(str(get(aw, "getDesc", "desc")));
        }
        Keys k = new Keys(names.toArray(new String[0]), desc);
        if (!names.isEmpty() || !desc.isEmpty()) e.keys = k;
        return k;
    }

    // ---------------------------------------------------------------- поиск на экране

    private static final class Cand {
        final VideoTracker.Entry entry;
        final Keys keys;

        Cand(VideoTracker.Entry entry, Keys keys) {
            this.entry = entry;
            this.keys = keys;
        }
    }

    private static final class Hit {
        final VideoTracker.Entry entry;
        final TextView view;
        final boolean byName;

        Hit(VideoTracker.Entry entry, TextView view, boolean byName) {
            this.entry = entry;
            this.view = view;
            this.byName = byName;
        }
    }

    private static final class Walk {
        final List<Cand> cands;
        final int w;
        final int h;
        final Rect r = new Rect();
        final TextView[] nameView;
        final TextView[] descView;
        int texts;
        int visited;

        Walk(List<Cand> cands, int w, int h) {
            this.cands = cands;
            this.w = w;
            this.h = h;
            this.nameView = new TextView[cands.size()];
            this.descView = new TextView[cands.size()];
        }

        void test(TextView t) {
            CharSequence cs = t.getText();
            int len = cs == null ? 0 : cs.length();
            if (len == 0 || len > 600 || t.getHeight() <= 0) return;
            if (!t.getGlobalVisibleRect(r)) return;
            // Подпись должна быть видна почти целиком, в нижней части и не у правого края (там кнопки).
            if (r.height() * 10 < t.getHeight() * 8) return;
            if (r.centerY() < h * 0.30f || r.centerX() > w * 0.85f) return;
            texts++;
            String s = norm(cs.toString());
            for (int i = 0; i < cands.size(); i++) {
                Keys k = cands.get(i).keys;
                int m = match(s, k.names, k.desc);
                if (m == 1 && nameView[i] == null) nameView[i] = t;
                else if (m == 2 && descView[i] == null) descView[i] = t;
            }
        }
    }

    private static void walk(View v, Walk w, int depth) {
        if (w.visited++ > 4000 || depth > 60) return;
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f) return;
        if (Ui.OWN_TAG.equals(v.getTag())) return;
        if (v instanceof TextView) {
            w.test((TextView) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) walk(g.getChildAt(i), w, depth + 1);
        }
    }

    private static volatile String diag = "ещё не запускалось";
    private static volatile String chain = "";
    private static volatile String lastOk = "";
    private static volatile long lastOkAt;

    /** Какое из недавно виденных видео сейчас на экране; null, если не нашли. */
    private static Hit locate(Activity a) {
        List<Cand> cands = new ArrayList<>();
        for (VideoTracker.Entry e : VideoTracker.candidates()) {
            if (e.aweme == null) continue;
            Keys k = keysOf(e);
            if (k.names.length == 0 && k.desc.isEmpty()) continue;
            cands.add(new Cand(e, k));
        }
        if (cands.isEmpty()) {
            diag = "Пока не вижу ни одного видео: пролистайте ленту в TikTok";
            return null;
        }
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        Walk w = new Walk(cands, dm.widthPixels, dm.heightPixels);
        walk(a.getWindow().getDecorView(), w, 0);
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < cands.size(); i++) {
            int score = (w.nameView[i] != null ? 1 : 0) + (w.descView[i] != null ? 3 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            diag = "Видео известно (" + cands.size() + "), но подпись автора на экране не нашлась "
                    + "(подходящих текстов: " + w.texts + ")";
            return null;
        }
        boolean byName = w.nameView[best] != null;
        return new Hit(cands.get(best).entry, byName ? w.nameView[best] : w.descView[best], byName);
    }

    private static String chainOf(View v) {
        StringBuilder sb = new StringBuilder();
        View x = v;
        for (int i = 0; i < 4 && x != null; i++) {
            if (sb.length() > 0) sb.append(" < ");
            sb.append(x.getClass().getSimpleName());
            if (x instanceof LinearLayout) {
                sb.append(((LinearLayout) x).getOrientation() == LinearLayout.VERTICAL ? "(v)" : "(h)");
            }
            Object p = x.getParent();
            x = p instanceof View ? (View) p : null;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- плашки в окне

    /** Занятые места рядом с подписью: сюда плашки класть нельзя. */
    private static final class Occ {
        final List<int[]> rects = new ArrayList<>();
        final Rect tmp = new Rect();
        final View skip;
        final java.util.Set<View> parents;
        final int sw;
        final int sh;
        final int top;
        final int bottom;
        int visited;

        Occ(View skip, java.util.Set<View> parents, int sw, int sh, int top, int bottom) {
            this.skip = skip;
            this.parents = parents;
            this.sw = sw;
            this.sh = sh;
            this.top = top;
            this.bottom = bottom;
        }
    }

    /** Собирает видимые элементы интерфейса в полосе вокруг подписи: тексты, картинки, кнопки, плашки с фоном. */
    private static void collect(View v, Occ o, int depth) {
        if (o.visited++ > 2500 || depth > 60 || v == o.skip) return;
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f) return;
        if (Ui.OWN_TAG.equals(v.getTag())) return;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean huge = w > o.sw * 0.92f || h > o.sh * 0.35f;
        // Контейнеры, внутри которых лежит сама подпись, не мешают: мешает то, что рядом с ней.
        if (!huge && !o.parents.contains(v) && v.getGlobalVisibleRect(o.tmp)
                && o.tmp.bottom > o.top && o.tmp.top < o.bottom) {
            boolean content = v instanceof ImageView
                    || (v instanceof TextView && ((TextView) v).length() > 0)
                    || v.isClickable() || v.getBackground() != null;
            if (content) o.rects.add(new int[]{o.tmp.left, o.tmp.top, o.tmp.right, o.tmp.bottom});
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0, n = g.getChildCount(); i < n; i++) collect(g.getChildAt(i), o, depth + 1);
        }
    }

    private static volatile String slotNote = "-";

    /** Два плашки в ряд: время и регион. Один и тот же вид в ленте и в предпросмотре настроек. */
    static final class Pills {
        final Context ctx;
        final float d;
        final LinearLayout box;
        final TextView timeView;
        final TextView regionView;
        private int appliedKey = Integer.MIN_VALUE;

        Pills(Context ctx) {
            this.ctx = ctx;
            this.d = ctx.getResources().getDisplayMetrics().density;
            box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setTag(Ui.OWN_TAG);
            timeView = pill(true);
            regionView = pill(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(5));
            box.addView(timeView, lp);
            box.addView(regionView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int dp(float v) {
            return Math.round(v * d);
        }

        private TextView pill(boolean clock) {
            TextView t = new TextView(ctx);
            t.setTag(Ui.OWN_TAG);
            t.setTextSize(12f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setIncludeFontPadding(false);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(8), dp(4), dp(9), dp(4));
            if (clock) t.setCompoundDrawablePadding(dp(5));
            t.setClickable(true);
            return t;
        }

        /** Применяет цвета по стилю и теме и прозрачность всего ряда; повторно с теми же значениями ничего не делает. */
        void style(int style, boolean night, int opacity) {
            int op = clampOpacity(opacity);
            int key = (style << 16) | ((night ? 1 : 0) << 8) | op;
            if (key == appliedKey) return;
            appliedKey = key;
            int[] c = colorsFor(style, night);
            boolean darkPill = (c[0] & 0xFFFFFF) < 0x808080;
            for (TextView t : new TextView[]{timeView, regionView}) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(c[0]);
                g.setCornerRadius(dp(20));
                g.setStroke(Math.max(1, dp(0.5f)), darkPill ? 0x2EFFFFFF : 0x1F000000);
                t.setBackground(g);
                t.setTextColor(c[1]);
            }
            Drawable ic = Icons.of(Icons.CLOCK, c[1]);
            ic.setBounds(0, 0, dp(13), dp(13));
            timeView.setCompoundDrawables(ic, null, null, null);
            box.setAlpha(op / 100f);
        }

        void measure() {
            box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        }
    }

    /** Образец плашек для экрана настроек: тот же вид, пример данных. */
    static View preview(Context c) {
        Pills p = new Pills(c);
        p.style(Prefs.vtStyle, isNight(c), Prefs.vtOpacity);
        long sample = System.currentTimeMillis() / 1000L - 2 * 86400L - 3600L * 5;
        p.timeView.setText(formatTime(sample, Prefs.vtSeconds, TimeZone.getDefault(), System.currentTimeMillis()));
        String r = regionText("KZ", Prefs.vtRegionMode);
        p.regionView.setText(r == null ? "KZ" : r);
        p.timeView.setVisibility(Prefs.vtTime ? View.VISIBLE : View.GONE);
        p.regionView.setVisibility(Prefs.vtRegion ? View.VISIBLE : View.GONE);
        p.timeView.setClickable(false);
        p.regionView.setClickable(false);
        return p.box;
    }

    private static final class Overlay implements ViewTreeObserver.OnPreDrawListener {
        final Activity act;
        final ViewGroup decor;
        final Pills pills;
        final LinearLayout box;
        final TextView timeView;
        final TextView regionView;
        final float d;
        final int[] loc = new int[2];
        final int[] dloc = new int[2];
        final Rect rect = new Rect();
        TextView anchor;
        CharSequence anchorText;
        boolean listening;
        boolean attached;
        long created;
        String iso;
        // выбранное место относительно левого верхнего угла подписи
        boolean slotReady;
        int slotDx;
        int slotDy;
        int slotKey;
        long slotAt;

        Overlay(Activity act) {
            this.act = act;
            this.decor = (ViewGroup) act.getWindow().getDecorView();
            this.d = act.getResources().getDisplayMetrics().density;
            pills = new Pills(act);
            box = pills.box;
            timeView = pills.timeView;
            regionView = pills.regionView;
            timeView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (created <= 0) return;
                    TimeZone tz = TimeZone.getDefault();
                    long age = System.currentTimeMillis() / 1000L - created;
                    Toasts.show(act, "Опубликовано " + fullTime(created, tz) + " · " + ageText(age));
                }
            });
            regionView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (iso == null) return;
                    String name = Regions.nameOf(iso);
                    Toasts.show(act, "Регион видео: " + (name == null ? iso : name) + " (" + iso + ")");
                }
            });
        }

        int dp(float v) {
            return Math.round(v * d);
        }

        /** Показывает плашки рядом с подписью anchor. */
        void show(TextView anchor, Meta m, boolean wantTime, boolean wantRegion) {
            if (this.anchor != anchor) slotReady = false;
            this.anchor = anchor;
            this.anchorText = anchor.getText() == null ? "" : anchor.getText().toString();
            created = m.created;
            iso = m.region;
            pills.style(Prefs.vtStyle, isNight(act), Prefs.vtOpacity);
            if (wantTime) {
                timeView.setText(formatTime(m.created, Prefs.vtSeconds, TimeZone.getDefault(), System.currentTimeMillis()));
            }
            timeView.setVisibility(wantTime ? View.VISIBLE : View.GONE);
            if (wantRegion) regionView.setText(regionText(m.region, Prefs.vtRegionMode));
            regionView.setVisibility(wantRegion ? View.VISIBLE : View.GONE);
            if (!attached) {
                decor.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
                attached = true;
            }
            pills.measure();
            slotReady = false;
            if (!listening) {
                decor.getViewTreeObserver().addOnPreDrawListener(this);
                listening = true;
            }
            follow();
        }

        void hide() {
            anchor = null;
            slotReady = false;
            if (attached) box.setVisibility(View.GONE);
            stopListening();
        }

        private void stopListening() {
            if (!listening) return;
            listening = false;
            try {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
            } catch (Throwable ignored) {
                // окно уже закрыто
            }
        }

        void destroy() {
            hide();
            if (attached) {
                try {
                    decor.removeView(box);
                } catch (Throwable ignored) {
                    // уже убрано
                }
                attached = false;
            }
        }

        /** Ищет свободное место рядом с подписью и запоминает его относительно подписи. */
        private void chooseSlot(TextView a) {
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            int pw = box.getMeasuredWidth();
            int ph = box.getMeasuredHeight();
            if (pw <= 0 || ph <= 0) {
                pills.measure();
                pw = box.getMeasuredWidth();
                ph = box.getMeasuredHeight();
            }
            int gap = dp(5);
            int ax;
            int at;
            int ar1;
            int ab1;
            int ab;
            Layout lay = a.getLayout();
            if (lay != null && lay.getLineCount() > 0) {
                int pl = a.getTotalPaddingLeft();
                int pt = a.getTotalPaddingTop();
                ax = loc[0] + pl + Math.round(lay.getLineLeft(0)) - a.getScrollX();
                ar1 = loc[0] + pl + Math.round(lay.getLineRight(0)) - a.getScrollX();
                at = loc[1] + pt + lay.getLineTop(0);
                ab1 = loc[1] + pt + lay.getLineBottom(0);
                ab = loc[1] + pt + lay.getLineBottom(lay.getLineCount() - 1);
            } else {
                ax = loc[0];
                ar1 = loc[0] + a.getWidth();
                at = loc[1];
                ab1 = loc[1] + a.getHeight();
                ab = ab1;
            }
            // Подпись и все её родители не считаются занятыми, остальное вокруг — да.
            java.util.Set<View> parents = new java.util.HashSet<>();
            Object p = a.getParent();
            for (int i = 0; i < 80 && p instanceof View; i++) {
                parents.add((View) p);
                p = ((View) p).getParent();
            }
            Occ o = new Occ(a, parents, dm.widthPixels, dm.heightPixels, at - ph * 2 - gap, ab + ph * 2 + gap);
            collect(decor, o, 0);
            int[][] occ = o.rects.toArray(new int[0][]);
            int[] r = pickSlot(ax, at, ar1, ab1, ab, pw, ph, gap, occ, dm.widthPixels, dm.heightPixels,
                    slotOrder(Prefs.vtPos));
            slotDx = r[0] - loc[0];
            slotDy = r[1] - loc[1];
            slotKey = a.getWidth() * 7919 + a.getHeight() * 31 + Prefs.vtPos * 3 + pw * 13 + ph;
            slotAt = SystemClock.uptimeMillis();
            slotReady = true;
            slotNote = (r[2] == 0 ? "справа от имени" : r[2] == 1 ? "над именем" : r[2] == 2 ? "под именем"
                    : "запасное (над именем)") + ", занятых мест рядом " + occ.length
                    + (r[3] > 0 ? ", наложение " + Math.round(r[3] / (d * d)) + " dp²" : ", без наложения");
        }

        /** Ставит плашки рядом с подписью; вызывается при каждой перерисовке, пока плашки видны. */
        void follow() {
            TextView a = anchor;
            if (a == null) return;
            if (!a.isAttachedToWindow() || a.getVisibility() != View.VISIBLE) {
                hide();
                soon();
                return;
            }
            // Подпись теперь про другое видео (TikTok переиспользует view): плашки устарели.
            if (!TextUtils.equals(a.getText(), anchorText)) {
                hide();
                soon();
                return;
            }
            if (!a.getGlobalVisibleRect(rect) || rect.height() * 10 < a.getHeight() * 8) {
                box.setVisibility(View.INVISIBLE);
                return;
            }
            a.getLocationOnScreen(loc);
            decor.getLocationOnScreen(dloc);
            int key = a.getWidth() * 7919 + a.getHeight() * 31 + Prefs.vtPos * 3 + box.getMeasuredWidth() * 13
                    + box.getMeasuredHeight();
            if (!slotReady || key != slotKey || SystemClock.uptimeMillis() - slotAt > 700L) chooseSlot(a);
            float x = loc[0] - dloc[0] + slotDx;
            float y = loc[1] - dloc[1] + slotDy;
            if (y < 0 || x < 0) {
                box.setVisibility(View.INVISIBLE);
                return;
            }
            if (box.getVisibility() != View.VISIBLE) box.setVisibility(View.VISIBLE);
            if (box.getTranslationX() != x) box.setTranslationX(x);
            if (box.getTranslationY() != y) box.setTranslationY(y);
        }

        @Override
        public boolean onPreDraw() {
            try {
                follow();
            } catch (Throwable t) {
                FlagStore.log(t);
                hide();
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- цикл

    private static Handler mainHandler;

    /** Обработчик главного потока; создаётся при первом обращении, чтобы чистые функции выше работали и без Android. */
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
    private static boolean loop;
    private static Overlay overlay;
    private static WeakReference<Activity> overlayAct;

    static boolean enabled() {
        return (Prefs.vtTime || Prefs.vtRegion) && !CrashGuard.tripped;
    }

    /** Запустить (или сразу повторить) поиск на экране: при показе экрана и при смене настроек. Главный поток. */
    static void kick() {
        if (!enabled()) return;
        main().removeCallbacks(TICK);
        loop = true;
        main().postDelayed(TICK, 250L);
    }

    private static void soon() {
        if (!loop) return;
        main().removeCallbacks(TICK);
        main().postDelayed(TICK, 20L);
    }

    /** Экран прочитал новый пост: сразу искать его подпись, не дожидаясь очередной проверки. Любой поток. */
    static void nudge() {
        if (!enabled()) return;
        main().post(new Runnable() {
            @Override
            public void run() {
                if (!enabled()) return;
                main().removeCallbacks(TICK);
                loop = true;
                main().postDelayed(TICK, 20L);
            }
        });
    }

    private static void drop() {
        Overlay o = overlay;
        overlay = null;
        overlayAct = null;
        if (o != null) o.destroy();
    }

    private static void tick() {
        loop = false;
        long delay = 400L;
        boolean again = false;
        try {
            if (!enabled()) {
                drop();
                diag = "Выключено";
                return;
            }
            again = true;
            Activity a = ScreenWatcher.top();
            if (a == null) {
                drop();
                delay = 1500L;
                return;
            }
            View decor = a.getWindow().getDecorView();
            if (decor.getWindowVisibility() != View.VISIBLE) {
                if (overlay != null) overlay.hide();
                delay = 2000L;
                return;
            }
            Activity prev = overlayAct == null ? null : overlayAct.get();
            if (overlay != null && prev != a) drop();

            Hit hit = locate(a);
            if (hit == null) {
                if (overlay != null) overlay.hide();
                chain = "";
                return;
            }
            Meta m = metaOf(hit.entry);
            boolean wantTime = Prefs.vtTime && m.created > 0;
            boolean wantRegion = Prefs.vtRegion && m.region != null;
            chain = chainOf(hit.view);
            diag = "Нашёл видео на экране (по " + (hit.byName ? "имени автора" : "описанию") + ") · время: "
                    + (m.created > 0 ? "есть" : "в данных нет") + " · регион: "
                    + (m.region != null ? m.region + " (" + m.regionFrom + ")" : "в данных нет");
            lastOk = diag;
            lastOkAt = System.currentTimeMillis();
            if (!wantTime && !wantRegion) {
                if (overlay != null) overlay.hide();
                return;
            }
            if (overlay == null) {
                overlay = new Overlay(a);
                overlayAct = new WeakReference<>(a);
            }
            overlay.show(hit.view, m, wantTime, wantRegion);
            diag = diag + " · место: " + slotNote;
            lastOk = diag;
            delay = 1200L;
        } catch (Throwable t) {
            FlagStore.log(t);
            diag = "Сбой: " + t.getClass().getSimpleName();
            delay = 2500L;
            drop();
        } finally {
            if (again) {
                loop = true;
                main().postDelayed(TICK, delay);
            }
        }
    }

    // ---------------------------------------------------------------- для экрана настроек

    static String status() {
        if (!enabled()) return "Выключено";
        String now = diag;
        String ok = lastOk;
        if (ok.isEmpty() || ok.equals(now)) return now;
        long ago = Math.max(0, (System.currentTimeMillis() - lastOkAt) / 1000L);
        return "Сейчас: " + now + "\nПоследний раз находил " + ageText(ago) + ": " + ok;
    }

    /** Сведения для разработчика: что нашлось. Имён авторов и текстов описаний в них нет. */
    static String details() {
        StringBuilder sb = new StringBuilder();
        sb.append("TTMLonger ").append(Targets.VERSION).append(" · Android ").append(android.os.Build.VERSION.SDK_INT)
                .append('\n');
        sb.append("время: ").append(Prefs.vtTime ? "вкл" : "выкл").append(", секунды: ").append(Prefs.vtSeconds ? "да" : "нет")
                .append(", регион: ").append(Prefs.vtRegion ? "вкл" : "выкл").append(" (").append(modeLabel(Prefs.vtRegionMode))
                .append(")\n");
        sb.append("видео в трекере: ").append(VideoTracker.size()).append(", всего видено ").append(VideoTracker.total())
                .append('\n');
        int withTime = 0;
        int withRegion = 0;
        int withKeys = 0;
        for (VideoTracker.Entry e : VideoTracker.candidates()) {
            if (e.aweme == null) continue;
            Meta m = metaOf(e);
            if (m.created > 0) withTime++;
            if (m.region != null) withRegion++;
            Keys k = keysOf(e);
            if (k.names.length > 0 || !k.desc.isEmpty()) withKeys++;
        }
        sb.append("у недавних: время ").append(withTime).append(", регион ").append(withRegion)
                .append(", есть чем искать на экране ").append(withKeys).append('\n');
        sb.append("состояние: ").append(status()).append('\n');
        sb.append("вид: ").append(styleLabel(Prefs.vtStyle)).append(", место: ").append(posLabel(Prefs.vtPos))
                .append(", непрозрачность ").append(Prefs.vtOpacity).append("%\n");
        sb.append("выбранное место: ").append(slotNote).append('\n');
        sb.append("подпись: ").append(chain.isEmpty() ? "не найдена" : chain);
        return sb.toString();
    }
}
