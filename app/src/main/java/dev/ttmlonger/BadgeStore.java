package dev.ttmlonger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Кому какие бейджики. Правила лежат в файле рядом с остальными данными мода (номер аккаунта, имя на
 * момент выбора, бейджики), а недавно виденные аккаунты (для списка выбора) хранятся только в памяти и на
 * диск не пишутся.
 */
final class BadgeStore {
    static final String FILE = "badges.txt";
    static final int MAX_RULES = 200;
    private static final int MAX_RECENT = 300;

    /** Бейджики одного аккаунта. */
    static final class Rule {
        final String uid;
        final String name;
        final List<String> ids;

        Rule(String uid, String name, List<String> ids) {
            this.uid = uid;
            this.name = name;
            this.ids = ids;
        }
    }

    /** Аккаунт из списка выбора. */
    static final class Account {
        final String uid;
        final String name;

        Account(String uid, String name) {
            this.uid = uid;
            this.name = name;
        }
    }

    private static final LinkedHashMap<String, Rule> rules = new LinkedHashMap<>();
    private static final LinkedHashMap<String, String> recent = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_RECENT;
        }
    };
    /** Общий список (badges.json): номер → бейджики. Заменяется целиком. */
    private static volatile Map<String, List<String>> remote = Collections.emptyMap();
    /** Больше стольких бейджиков после имени не рисуется, даже если свои и общие вместе дают больше. */
    static final int MAX_SHOWN = 4;
    /** uid → готовая метка из знаков нулевой ширины; заменяется целиком. */
    private static volatile Map<String, String> marks = Collections.emptyMap();
    private static boolean loaded;
    private static boolean dirty;
    /** Сколько раз имени приделана метка (для сведений). */
    static volatile int dressedCount;

    private BadgeStore() {
    }

    // ------------------------------------------------------------ файл (чистая часть)

    /** Строки «номер\tимя\tбейджик,бейджик». */
    static String encode(Collection<Rule> list) {
        StringBuilder sb = new StringBuilder();
        for (Rule r : list) {
            if (!StreakLogic.isUid(r.uid) || r.ids.isEmpty()) continue;
            sb.append(r.uid).append('\t').append(StreakLogic.cleanName(r.name)).append('\t');
            for (int i = 0; i < r.ids.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(r.ids.get(i));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static List<Rule> decode(String text) {
        List<Rule> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length != 3 || !StreakLogic.isUid(p[0])) continue;
            List<String> ids = new ArrayList<>();
            for (String id : p[2].split(",")) {
                if (BadgeSet.indexOf(id) >= 0 && !ids.contains(id) && ids.size() < BadgeSet.PER_ACCOUNT) ids.add(id);
            }
            if (ids.isEmpty()) continue;
            out.add(new Rule(p[0], StreakLogic.cleanName(p[1]), ids));
            if (out.size() >= MAX_RULES) break;
        }
        return out;
    }

    private static File file() {
        File d = FlagStore.dir();
        return d == null ? null : new File(d, FILE);
    }

    static synchronized void load() {
        if (loaded) return;
        File d = FlagStore.dir();
        if (d == null) return; // каталог данных ещё не готов: попробуем в следующий раз
        loaded = true;
        try {
            File f = file();
            if (f != null && f.isFile()) {
                for (Rule r : decode(FlagStore.readFile(f))) rules.put(r.uid, r);
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        rebuild();
    }

    static synchronized void flush() {
        if (!loaded || !dirty) return;
        try {
            File f = file();
            if (f == null) return;
            FlagStore.writeAtomic(f, encode(rules.values()));
            dirty = false;
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    static void saveAsync() {
        StreakRunner.async(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        });
    }

    // ------------------------------------------------------------ метки

    /** Бейджики, которые видны после имени: сначала общие, затем ваши, без повторов, не больше {@link #MAX_SHOWN}. */
    static List<String> shown(List<String> shared, List<String> mine) {
        List<String> out = new ArrayList<>();
        if (shared != null) {
            for (String id : shared) {
                if (BadgeSet.indexOf(id) >= 0 && !out.contains(id) && out.size() < MAX_SHOWN) out.add(id);
            }
        }
        if (mine != null) {
            for (String id : mine) {
                if (BadgeSet.indexOf(id) >= 0 && !out.contains(id) && out.size() < MAX_SHOWN) out.add(id);
            }
        }
        return out;
    }

    private static void rebuild() {
        Map<String, String> next = new HashMap<>();
        java.util.Set<String> uids = new java.util.LinkedHashSet<>(remote.keySet());
        uids.addAll(rules.keySet());
        for (String uid : uids) {
            Rule r = rules.get(uid);
            List<String> ids = shown(remote.get(uid), r == null ? null : r.ids);
            StringBuilder sb = new StringBuilder();
            for (String id : ids) sb.append(BadgeLogic.mark(BadgeSet.indexOf(id)));
            if (sb.length() > 0) next.put(uid, sb.toString());
        }
        marks = next;
    }

    /** Подставляет общий список (из файла на GitHub или кэша) и пересобирает метки. */
    static synchronized void setRemote(Map<String, List<String>> shared) {
        remote = shared == null ? Collections.<String, List<String>>emptyMap() : shared;
        rebuild();
    }

    static int remoteCount() {
        return remote.size();
    }

    /** Рисовать ли метки в тексте: бейджики включены и есть хотя бы одно правило. */
    static boolean marking() {
        return Prefs.badgesOn && !marks.isEmpty();
    }

    static String marksFor(String uid) {
        String m = uid == null ? null : marks.get(uid);
        return m == null ? "" : m;
    }

    /**
     * Имя с меткой: из имени убираются любые знаки метки (чужие и свои, чтобы не было двух бейджиков),
     * затем добавляется метка аккаунта. Если ничего не изменилось, возвращается та же строка.
     */
    static String dress(String name, String uid) {
        if (name == null || !Prefs.badgesOn) return name;
        String clean = BadgeLogic.strip(name);
        if (!StreakLogic.isUid(uid)) return clean;
        seen(uid, clean);
        String m = marks.get(uid);
        if (m == null) return clean;
        dressedCount++;
        return clean + m;
    }

    // ------------------------------------------------------------ недавние аккаунты

    static void seen(String uid, String name) {
        if (!StreakLogic.isUid(uid) || name == null) return;
        String n = StreakLogic.cleanName(name);
        if (n.isEmpty()) return;
        synchronized (recent) {
            recent.put(uid, n);
        }
    }

    /** Недавно виденные, самые свежие первыми. */
    static List<Account> recent() {
        List<Account> out = new ArrayList<>();
        synchronized (recent) {
            for (Map.Entry<String, String> e : recent.entrySet()) out.add(new Account(e.getKey(), e.getValue()));
        }
        Collections.reverse(out);
        return out;
    }

    static String recentName(String uid) {
        synchronized (recent) {
            return recent.get(uid);
        }
    }

    // ------------------------------------------------------------ правила

    static synchronized Rule rule(String uid) {
        load();
        return rules.get(uid);
    }

    static synchronized int count() {
        load();
        return rules.size();
    }

    /** Правила по именам. */
    static synchronized List<Rule> rules() {
        load();
        List<Rule> out = new ArrayList<>(rules.values());
        Collections.sort(out, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return a.name.toLowerCase(java.util.Locale.ROOT).compareTo(b.name.toLowerCase(java.util.Locale.ROOT));
            }
        });
        return out;
    }

    /**
     * Включает или выключает бейджик у аккаунта.
     *
     * @return +1 добавлен, -1 убран, 0 отказ (уже три бейджика, правил слишком много или бейджика нет)
     */
    static synchronized int toggle(String uid, String name, String id) {
        if (!StreakLogic.isUid(uid) || BadgeSet.indexOf(id) < 0) return 0;
        load();
        Rule have = rules.get(uid);
        List<String> ids = new ArrayList<>();
        if (have != null) ids.addAll(have.ids);
        int result;
        if (ids.contains(id)) {
            ids.remove(id);
            result = -1;
        } else {
            if (ids.size() >= BadgeSet.PER_ACCOUNT) return 0;
            if (have == null && rules.size() >= MAX_RULES) return 0;
            ids.add(id);
            result = 1;
        }
        if (ids.isEmpty()) {
            rules.remove(uid);
        } else {
            String shown = StreakLogic.cleanName(name);
            if (shown.isEmpty() && have != null) shown = have.name;
            if (shown.isEmpty()) shown = StreakLogic.displayName(null, uid);
            rules.put(uid, new Rule(uid, shown, ids));
        }
        dirty = true;
        rebuild();
        return result;
    }

    static synchronized void remove(String uid) {
        load();
        if (rules.remove(uid) != null) {
            dirty = true;
            rebuild();
        }
    }

    static void resetForTest() {
        synchronized (BadgeStore.class) {
            rules.clear();
            loaded = false;
            dirty = false;
            remote = Collections.emptyMap();
            marks = Collections.emptyMap();
        }
        synchronized (recent) {
            recent.clear();
        }
        dressedCount = 0;
    }
}
