package dev.ttmlonger;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Чистая логика автопродления огонька: кому отправлять и как хранить отметки «уже отправляли».
 * Без Android и без TikTok, поэтому проверяется обычными тестами на JVM.
 *
 * Правила (их же пишет экран «Огонёк»):
 *  - огонёк есть только с третьего дня подряд (до этого серии для сохранения нет);
 *  - один стикер на чат в сутки, и после отправки в этот день повторов нет, даже если огонёк
 *    остался серым: он может оставаться серым, пока друг не ответит;
 *  - серый огонёк (статус GREY) получает стикер сразу; живой (ACTIVE) — только в режиме
 *    «каждый день» и не раньше выбранного времени;
 *  - если отправка не удалась, повтор не раньше чем через полчаса.
 */
final class StreakLogic {
    enum Verdict {
        /** Отправить стикер. */
        SEND,
        /** Серии ещё нет (меньше трёх дней). */
        NO_FLAME,
        /** Сегодня уже отправляли. */
        SENT_TODAY,
        /** Огонёк живой, отправлять рано или не нужно. */
        LIT,
        /** Статус не удалось прочитать. */
        UNKNOWN,
        /** Огонёк погас совсем или статус неизвестен мосту. */
        GONE,
        /** Недавняя отправка не удалась, ждём. */
        BACKOFF
    }

    static final int FLAME_FROM = 3;
    static final long FAIL_BACKOFF_MS = 30L * 60L * 1000L;
    static final String GREY = "GREY";
    static final String ACTIVE = "ACTIVE";
    /** Сколько стикеров максимум за один проход: страховка от ошибки в данных. */
    static final int MAX_PER_ROUND = 12;
    /** Варианты времени для ежедневной отправки, минут от полуночи. */
    static final int[] DAILY_CHOICES = {12 * 60, 18 * 60, 20 * 60, 22 * 60};

    private StreakLogic() {
    }

    static Verdict decide(boolean daily, int dailyMinute, String status, int streak,
                          long lastSent, long lastFail, long now, TimeZone tz) {
        if (streak < FLAME_FROM) return Verdict.NO_FLAME;
        if (status == null) return Verdict.UNKNOWN;
        if (lastSent > 0 && sameDay(lastSent, now, tz)) return Verdict.SENT_TODAY;
        if (lastFail > 0 && now >= lastFail && now - lastFail < FAIL_BACKOFF_MS) return Verdict.BACKOFF;
        if (GREY.equals(status)) return Verdict.SEND;
        if (ACTIVE.equals(status)) {
            return daily && minuteOfDay(now, tz) >= dailyMinute ? Verdict.SEND : Verdict.LIT;
        }
        return Verdict.GONE;
    }

    static boolean sameDay(long a, long b, TimeZone tz) {
        Calendar x = Calendar.getInstance(tz);
        x.setTimeInMillis(a);
        Calendar y = Calendar.getInstance(tz);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR)
                && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    static int minuteOfDay(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    static String formatMinute(int minute) {
        int m = Math.max(0, Math.min(1439, minute));
        return (m / 60 < 10 ? "0" : "") + (m / 60) + ":" + (m % 60 < 10 ? "0" : "") + (m % 60);
    }

    /** Следующее время из DAILY_CHOICES после текущего (по кругу). */
    static int nextChoice(int current) {
        for (int i = 0; i < DAILY_CHOICES.length; i++) {
            if (DAILY_CHOICES[i] == current) return DAILY_CHOICES[(i + 1) % DAILY_CHOICES.length];
        }
        return DAILY_CHOICES[0];
    }

    /** Похоже ли на идентификатор чата: непустой, короткий, без пробелов и управляющих символов. */
    static boolean isConvId(String s) {
        if (s == null || s.isEmpty() || s.length() > 128) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == 0x7f) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- хранение «когда отправляли»

    private static final long KEEP_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int KEEP_MAX = 500;

    /** Строки «чат\tвремя»; записи старше трёх дней отбрасываются. */
    static String encodeSent(Map<String, Long> sent, long now) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Long> e : sent.entrySet()) {
            long t = e.getValue();
            if (t <= 0 || now - t > KEEP_MS || !isConvId(e.getKey())) continue;
            if (n++ >= KEEP_MAX) break;
            sb.append(e.getKey()).append('\t').append(t).append('\n');
        }
        return sb.toString();
    }

    static Map<String, Long> decodeSent(String text) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String conv = line.substring(0, tab);
            if (!isConvId(conv)) continue;
            try {
                long t = Long.parseLong(line.substring(tab + 1).trim());
                if (t > 0) out.put(conv, t);
            } catch (NumberFormatException ignored) {
                // битая строка пропускается
            }
        }
        return out;
    }

    /** Чаты по одному в строке; порядок сохраняется, лишние и повторы отбрасываются. */
    static List<String> decodeConvs(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (isConvId(s) && !out.contains(s)) out.add(s);
            if (out.size() >= max) break;
        }
        return out;
    }
}
