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
    private static final int MAX_CONVS = 600;
    private static final String CONVS = "streak_convs.txt";
    private static final String SENT = "streak_sent.txt";
    private static final String PICKED = "streak_picked.txt";
    private static final String NAMES = "streak_names.txt";
    private static final int MAX_NAMES = 800;

    private static final LinkedHashSet<String> convs = new LinkedHashSet<>();
    private static final Map<String, Long> sent = new LinkedHashMap<>();
    private static final Map<String, Long> failed = new HashMap<>();
    private static boolean loaded;
    private static boolean dirtyConvs;
    private static boolean dirtySent;
    private static final LinkedHashSet<String> picked = new LinkedHashSet<>();
    private static final Map<String, String> names = new LinkedHashMap<>();
    private static boolean dirtyPicked;
    private static boolean dirtyNames;

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
        try {
            File f = file(PICKED);
            if (f != null && f.isFile()) picked.addAll(StreakLogic.decodeConvs(FlagStore.readFile(f), MAX_CONVS));
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            File f = file(NAMES);
            if (f != null && f.isFile()) names.putAll(StreakLogic.decodeNames(FlagStore.readFile(f)));
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    // ---------------------------------------------------------------- выбранные люди и их имена

    /** Копия набора отмеченных чатов. */
    static synchronized java.util.Set<String> picked() {
        load();
        return new LinkedHashSet<>(picked);
    }

    static synchronized boolean isPicked(String conv) {
        load();
        return picked.contains(conv);
    }

    static synchronized int pickedCount() {
        load();
        return picked.size();
    }

    static synchronized void setPicked(String conv, boolean on) {
        if (!StreakLogic.isConvId(conv)) return;
        load();
        if (on ? picked.add(conv) : picked.remove(conv)) dirtyPicked = true;
    }

    static synchronized void pickMany(java.util.Collection<String> list) {
        load();
        for (String c : list) {
            if (StreakLogic.isConvId(c) && picked.size() < MAX_CONVS && picked.add(c)) dirtyPicked = true;
        }
    }

    static synchronized void clearPicked() {
        load();
        if (!picked.isEmpty()) {
            picked.clear();
            dirtyPicked = true;
        }
    }

    /** Имя друга по номеру аккаунта или null. */
    static synchronized String name(String uid) {
        load();
        return uid == null ? null : names.get(uid);
    }

    /** Копия списка имён друзей: номер → имя. */
    static synchronized Map<String, String> names() {
        load();
        return new LinkedHashMap<>(names);
    }

    static synchronized void noteName(String uid, String name) {
        String clean = StreakLogic.cleanName(name);
        if (clean.isEmpty() || !StreakLogic.isUid(uid)) return;
        load();
        String have = names.get(uid);
        if (clean.equals(have)) return;
        if (have == null && names.size() >= MAX_NAMES) return;
        names.put(uid, clean);
        dirtyNames = true;
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
        while (convs.size() > MAX_CONVS) {
            java.util.Iterator<String> it = convs.iterator();
            it.next();
            it.remove();
        }
        dirtyConvs = true;
        return true;
    }

    /** Чат, придуманный по списку друзей: не вытесняет настоящие, а при полном списке не добавляется. */
    static synchronized boolean noteDerived(String conv) {
        if (!StreakLogic.isConvId(conv)) return false;
        load();
        if (convs.contains(conv) || convs.size() >= MAX_CONVS) return false;
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
            if (dirtyPicked) {
                File f = file(PICKED);
                if (f != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String c : picked) sb.append(c).append('\n');
                    FlagStore.writeAtomic(f, sb.toString());
                    dirtyPicked = false;
                }
            }
            if (dirtyNames) {
                File f = file(NAMES);
                if (f != null) {
                    FlagStore.writeAtomic(f, StreakLogic.encodeNames(names));
                    dirtyNames = false;
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
