package dev.ttmlonger;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.HashMap;
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
 *  - серый огонёк (у TikTok он зовётся SECONDARY_ACTIVE: серия ещё ждёт ответа сегодня) получает
 *    стикер сразу; уже поддержанный сегодня (ACTIVE) — только в режиме «каждый день» и не раньше
 *    выбранного времени; погасший (EXPIRED и всё остальное) пропускается;
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
    /** Имя статуса серого огонька в enum TikTok: серия ещё жива, но сегодня её не поддержали. */
    static final String GREY = "SECONDARY_ACTIVE";
    /** Старое условное имя; принимается, если встретится. */
    static final String GREY_ALIAS = "GREY";
    static final String ACTIVE = "ACTIVE";

    static boolean isGrey(String status) {
        return GREY.equals(status) || GREY_ALIAS.equals(status);
    }

    /** Есть ли смысл слать в чат с таким статусом: серый и поддержанный сегодня огоньки — да, погасший (EXPIRED и прочее) — нет. Статус не прочитан — да. */
    static boolean worthSending(String status) {
        return status == null || isGrey(status) || ACTIVE.equals(status);
    }

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
        if (isGrey(status)) return Verdict.SEND;
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

    /** Сколько миллисекунд до ближайшего момента «minute» (минут от полуночи) строго после now. */
    static long millisUntil(long now, int minute, TimeZone tz) {
        int m = Math.max(0, Math.min(1439, minute));
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, m / 60);
        c.set(Calendar.MINUTE, m % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis() - now;
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

    // ---------------------------------------------------------------- идентификаторы чатов и аккаунтов

    /** Номер аккаунта TikTok: только цифры, до 24 знаков. */
    static boolean isUid(String s) {
        if (s == null || s.isEmpty() || s.length() > 24) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    static String stripZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }

    /** Сравнение номеров как чисел (без ведущих нулей, без переполнения): меньше нуля, если a меньше b. */
    static int compareUid(String a, String b) {
        String x = stripZeros(a);
        String y = stripZeros(b);
        if (x.length() != y.length()) return x.length() < y.length() ? -1 : 1;
        return x.compareTo(y);
    }

    /**
     * Идентификатор личного чата двух аккаунтов в TikTok: «0:1:меньший:больший».
     *
     * @return null, если номера неверные или это один и тот же аккаунт
     */
    static String convId(String a, String b) {
        if (!isUid(a) || !isUid(b)) return null;
        int c = compareUid(a, b);
        if (c == 0) return null;
        return c < 0 ? "0:1:" + stripZeros(a) + ":" + stripZeros(b) : "0:1:" + stripZeros(b) + ":" + stripZeros(a);
    }

    /** Два номера из идентификатора личного чата или null. */
    static String[] uidsOf(String conv) {
        if (conv == null) return null;
        String[] p = conv.split(":");
        if (p.length != 4 || !p[0].equals("0") || !p[1].equals("1") || !isUid(p[2]) || !isUid(p[3])) return null;
        return new String[]{p[2], p[3]};
    }

    /**
     * Номер владельца аккаунта по известным чатам: он есть в каждом личном чате, а у собеседников
     * чаты разные. Нужно минимум два личных чата и один номер, общий для всех.
     */
    static String commonUid(List<String> convs) {
        Map<String, Integer> count = new HashMap<>();
        int n = 0;
        for (String c : convs) {
            String[] u = uidsOf(c);
            if (u == null) continue;
            n++;
            for (String x : u) {
                String k = stripZeros(x);
                Integer v = count.get(k);
                count.put(k, v == null ? 1 : v + 1);
            }
        }
        if (n < 2) return null;
        String best = null;
        int bestN = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
                tie = false;
            } else if (e.getValue() == bestN) {
                tie = true;
            }
        }
        // общий номер должен быть почти везде: допускаем один-два чужих чата (группы, старые данные)
        return !tie && best != null && bestN >= 2 && bestN * 10 >= n * 8 ? best : null;
    }

    // ---------------------------------------------------------------- выбранные люди

    /** Годится ли чат для отправки: без режима «только выбранным» — любой, с ним — только отмеченный. */
    static boolean allowed(boolean onlyPicked, java.util.Collection<String> picked, String conv) {
        return !onlyPicked || (picked != null && picked.contains(conv));
    }

    /** Чаты, которые остаются после выбора людей; порядок сохраняется. */
    static List<String> filterPicked(boolean onlyPicked, java.util.Collection<String> picked, List<String> convs) {
        if (!onlyPicked) return new ArrayList<>(convs);
        List<String> out = new ArrayList<>();
        for (String c : convs) {
            if (allowed(true, picked, c)) out.add(c);
        }
        return out;
    }

    /** Номер собеседника в личном чате (тот, что не свой) или null, если свой номер неизвестен или чат чужой. */
    static String friendUid(String conv, String own) {
        String[] u = uidsOf(conv);
        if (u == null || own == null || !isUid(own)) return null;
        String o = stripZeros(own);
        boolean first = stripZeros(u[0]).equals(o);
        boolean second = stripZeros(u[1]).equals(o);
        if (first == second) return null;
        return stripZeros(first ? u[1] : u[0]);
    }

    /** Подпись статуса огонька для списка людей. */
    static String statusLabel(String status) {
        if (status == null) return "";
        if (isGrey(status)) return "серый, пора отправить";
        if (ACTIVE.equals(status)) return "поддержан сегодня";
        if ("EXPIRED".equals(status)) return "погас";
        return "статус " + status.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Порядок в списке людей: сначала серые (их надо продлевать сейчас), затем по числу дней, самые длинные
     * первыми, затем по имени.
     */
    static int order(String statusA, int streakA, String nameA, String statusB, int streakB, String nameB) {
        boolean ga = isGrey(statusA);
        boolean gb = isGrey(statusB);
        if (ga != gb) return ga ? -1 : 1;
        if (streakA != streakB) return streakA > streakB ? -1 : 1;
        String x = nameA == null ? "" : nameA.toLowerCase(java.util.Locale.ROOT);
        String y = nameB == null ? "" : nameB.toLowerCase(java.util.Locale.ROOT);
        return x.compareTo(y);
    }

    /** «5 дней», «21 день», «2 дня» по-русски. */
    static String days(int n) {
        int m = Math.abs(n) % 100;
        int d = m % 10;
        String word = m >= 11 && m <= 14 ? "дней" : d == 1 ? "день" : d >= 2 && d <= 4 ? "дня" : "дней";
        return n + " " + word;
    }

    /** Имя для списка: из списка друзей, а если его нет, «Чат …последние четыре цифры номера». */
    static String displayName(String name, String uid) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        if (uid == null || uid.isEmpty()) return "Чат без имени";
        return "Чат …" + (uid.length() > 4 ? uid.substring(uid.length() - 4) : uid);
    }

    /** Строки «номер\tимя»; имя без табуляций и переводов строк, не длиннее 40 знаков. */
    static String encodeNames(Map<String, String> names) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            String name = cleanName(e.getValue());
            if (name.isEmpty() || !isUid(e.getKey())) continue;
            sb.append(e.getKey()).append('\t').append(name).append('\n');
        }
        return sb.toString();
    }

    static Map<String, String> decodeNames(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String uid = line.substring(0, tab);
            String name = cleanName(line.substring(tab + 1));
            if (isUid(uid) && !name.isEmpty()) out.put(uid, name);
        }
        return out;
    }

    static String cleanName(String s) {
        if (s == null) return "";
        String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 40 ? t.substring(0, 40).trim() : t;
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
