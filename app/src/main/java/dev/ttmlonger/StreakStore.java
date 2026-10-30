package dev.ttmlonger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Что мод помнит про огоньки: какие чаты TikTok уже показывал (их идентификаторы приходят в
 * вызовах службы огоньков) и когда в каждый чат отправлялся стикер. Файлы лежат рядом с остальными
 * данными мода. Отметки об отправке нужны, чтобы после перезапуска TikTok не отправить второй раз.
 */
final class StreakStore {
    private static final int MAX_CONVS = 300;
    private static final String CONVS = "streak_convs.txt";
    private static final String SENT = "streak_sent.txt";

    private static final LinkedHashSet<String> convs = new LinkedHashSet<>();
    private static final Map<String, Long> sent = new LinkedHashMap<>();
    private static final Map<String, Long> failed = new HashMap<>();
    private static boolean loaded;
    private static boolean dirtyConvs;
    private static boolean dirtySent;

    private StreakStore() {
    }

    private static File file(String name) {
        File d = FlagStore.dir();
        return d == null ? null : new File(d, name);
    }

    private static synchronized void load() {
        if (loaded) return;
        File d = FlagStore.dir();
        if (d == null) return; // каталог данных ещё не готов: попробуем в следующий раз
        loaded = true;
        try {
            File f = file(CONVS);
            if (f != null && f.isFile()) convs.addAll(StreakLogic.decodeConvs(FlagStore.readFile(f), MAX_CONVS));
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            File f = file(SENT);
            if (f != null && f.isFile()) sent.putAll(StreakLogic.decodeSent(FlagStore.readFile(f)));
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    /** Запоминает чат; true, если он новый. */
    static synchronized boolean note(String conv) {
        if (!StreakLogic.isConvId(conv)) return false;
        load();
        if (convs.contains(conv)) return false;
        if (convs.size() >= MAX_CONVS) {
            Iterator<String> it = convs.iterator();
            it.next();
            it.remove();
        }
        convs.add(conv);
        dirtyConvs = true;
        return true;
    }

    static synchronized List<String> conversations() {
        load();
        return new ArrayList<>(convs);
    }

    static synchronized int count() {
        load();
        return convs.size();
    }

    static synchronized long lastSent(String conv) {
        load();
        Long t = sent.get(conv);
        return t == null ? 0 : t;
    }

    static synchronized void markSent(String conv, long now) {
        load();
        sent.put(conv, now);
        failed.remove(conv);
        dirtySent = true;
    }

    static synchronized long lastFail(String conv) {
        Long t = failed.get(conv);
        return t == null ? 0 : t;
    }

    static synchronized void markFail(String conv, long now) {
        failed.put(conv, now);
    }

    /** Сколько чатов получили стикер сегодня (по записям). */
    static synchronized int sentToday(long now, java.util.TimeZone tz) {
        load();
        int n = 0;
        for (Long t : sent.values()) {
            if (t != null && StreakLogic.sameDay(t, now, tz)) n++;
        }
        return n;
    }

    /** Записывает изменённое на диск. */
    static synchronized void flush() {
        if (!loaded) return;
        try {
            if (dirtyConvs) {
                File f = file(CONVS);
                if (f != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String c : convs) sb.append(c).append('\n');
                    FlagStore.writeAtomic(f, sb.toString());
                    dirtyConvs = false;
                }
            }
            if (dirtySent) {
                File f = file(SENT);
                if (f != null) {
                    FlagStore.writeAtomic(f, StreakLogic.encodeSent(sent, System.currentTimeMillis()));
                    dirtySent = false;
                }
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
