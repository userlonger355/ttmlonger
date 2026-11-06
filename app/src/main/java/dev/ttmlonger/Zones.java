package dev.ttmlonger;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Часовой пояс, по которому мод считает время: расписание огонька, время публикации видео.
 *
 * <p>{@code TimeZone.getDefault()} — пояс процесса, а не телефона: его может сменить само приложение
 * ({@code TimeZone.setDefault}) или он может не обновиться после смены пояса в настройках. Если пояс
 * телефона удаётся прочитать (системное свойство {@code persist.sys.timezone}), берётся он: это то
 * время, которое человек видит на часах. Иначе пояс процесса.
 */
final class Zones {
    private static final long CACHE_MS = 30_000L;
    private static Method propGet;
    private static boolean propTried;
    private static long readAt;
    private static TimeZone cached;
    private static String cachedId = "";

    private Zones() {
    }

    /** Идентификатор пояса из текста системного свойства или null, если такого пояса нет. */
    static TimeZone parse(String id) {
        if (id == null) return null;
        String s = id.trim();
        if (s.isEmpty()) return null;
        for (String known : TimeZone.getAvailableIDs()) {
            if (known.equals(s)) return TimeZone.getTimeZone(s);
        }
        return null;
    }

    /** Пояс телефона из системного свойства или null, если свойство недоступно. */
    static synchronized TimeZone readSystem() {
        long now = System.currentTimeMillis();
        if (now - readAt < CACHE_MS && readAt != 0) return cached;
        readAt = now;
        cached = null;
        try {
            if (!propTried) {
                propTried = true;
                propGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            }
            if (propGet != null) {
                Object v = propGet.invoke(null, "persist.sys.timezone");
                cached = parse(v == null ? null : v.toString());
            }
        } catch (Throwable ignored) {
            // свойство закрыто: останется пояс процесса
        }
        return cached;
    }

    /** Пояс для расчётов мода. */
    static TimeZone current() {
        TimeZone def = TimeZone.getDefault();
        TimeZone sys = readSystem();
        if (sys != null && !sys.getID().equals(def.getID())) return sys;
        return def;
    }

    /** «UTC+7», «UTC+5:30», «UTC-3», «UTC». */
    static String offsetLabel(int offsetMs) {
        if (offsetMs == 0) return "UTC";
        int total = Math.abs(offsetMs) / 60000;
        int h = total / 60;
        int m = total % 60;
        return "UTC" + (offsetMs < 0 ? "-" : "+") + h + (m != 0 ? ":" + (m < 10 ? "0" : "") + m : "");
    }

    /** «Asia/Krasnoyarsk, UTC+7, сейчас 15:44». */
    static String describe(TimeZone tz, long now) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(tz);
        return tz.getID() + ", " + offsetLabel(tz.getOffset(now)) + ", сейчас " + f.format(new Date(now));
    }

    /** Пояс для экрана: что используется и, если процесс TikTok живёт по другому поясу, что именно. */
    static String note() {
        long now = System.currentTimeMillis();
        TimeZone used = current();
        TimeZone def = TimeZone.getDefault();
        String s = describe(used, now);
        if (!def.getID().equals(used.getID())) {
            s += ". Внутри TikTok пояс другой (" + describe(def, now) + "), мод берёт пояс телефона";
        }
        return s;
    }

    static void resetForTest() {
        readAt = 0;
        cached = null;
        propTried = false;
        propGet = null;
    }
}
