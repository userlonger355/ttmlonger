package dev.ttmlonger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Общие бейджики: файл {@code badges.json} в репозитории мода на GitHub. Мод читает его при запуске и
 * каждые 10 минут, пока TikTok запущен; последний ответ лежит на телефоне, так что бейджики есть и без
 * сети. Выдать бейджик другому человеку = добавить его номер в файл (с телефона, в GitHub): все, у кого
 * стоит мод, увидят его после следующего обновления.
 *
 * <p>Формат: {@code {"version": 1, "accounts": [{"uid": "123", "name": "Аня", "badges": ["star"]}]}}.
 * Из файла берутся только номера аккаунтов и номера бейджиков из встроенного каталога ({@link BadgeSet}),
 * имя нужно только для вашей подсказки. Неизвестные бейджики пропускаются, файл больше 256 КБ не читается.
 *
 * <p>Запрос один: GET на raw.githubusercontent.com с условием If-None-Match. Ничего о вас в нём нет, кроме
 * того, что видит любой сайт (адрес сети и название клиента «TTMLonger/версия»).
 */
final class BadgeRemote {
    static final String OWNER = "userlonger355";
    static final String REPO = "ttmlonger";
    static final String BRANCH = "main";
    static final String FILE = "badges.json";
    static final String URL = "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/" + BRANCH + "/" + FILE;
    /** Страница правки файла: открывается в браузере или приложении GitHub. */
    static final String EDIT_URL = "https://github.com/" + OWNER + "/" + REPO + "/edit/" + BRANCH + "/" + FILE;
    static final String CACHE = "badges_remote.json";
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ACCOUNTS = 5000;
    private static final long EVERY_MS = 10 * 60 * 1000L;

    /** Одна запись общего списка. */
    static final class Entry {
        final String name;
        final List<String> ids;

        Entry(String name, List<String> ids) {
            this.name = name;
            this.ids = ids;
        }
    }

    /** Ответ сервера: тело, метка версии, признак «не менялось» или описание ошибки. */
    static final class Answer {
        final String body;
        final String etag;
        final boolean unchanged;
        final String error;

        Answer(String body, String etag, boolean unchanged, String error) {
            this.body = body;
            this.etag = etag;
            this.unchanged = unchanged;
            this.error = error;
        }
    }

    /** Откуда брать файл; в проверках подменяется. */
    interface Http {
        Answer get(String url, String etag);
    }

    private static volatile Map<String, Entry> accounts = new LinkedHashMap<>();
    private static volatile String etag;
    private static volatile long lastOkAt;
    private static volatile String note = "ещё не загружался";
    private static volatile boolean started;
    private static volatile boolean busy;
    static volatile Http http = new Real();

    private BadgeRemote() {
    }

    // ------------------------------------------------------------ чистая часть: разбор и запись

    /**
     * Разбирает файл. null — файл не разобрался (тогда прежний список остаётся); пустая карта — файл верный,
     * но бейджиков в нём нет.
     */
    static Map<String, Entry> parse(String text) {
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            JSONArray a = o.optJSONArray("accounts");
            if (a == null) return null;
            Map<String, Entry> out = new LinkedHashMap<>();
            int n = Math.min(a.length(), MAX_ACCOUNTS);
            for (int i = 0; i < n; i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null) continue;
                String uid = r.optString("uid", "");
                if (!StreakLogic.isUid(uid)) continue;
                JSONArray b = r.optJSONArray("badges");
                if (b == null) continue;
                Entry have = out.get(uid);
                List<String> ids = have == null ? new ArrayList<String>() : new ArrayList<>(have.ids);
                for (int j = 0; j < b.length(); j++) {
                    String id = b.optString(j, "");
                    if (BadgeSet.indexOf(id) >= 0 && !ids.contains(id) && ids.size() < BadgeSet.PER_ACCOUNT) ids.add(id);
                }
                if (ids.isEmpty()) continue;
                String name = StreakLogic.cleanName(r.optString("name", ""));
                out.put(uid, new Entry(name.isEmpty() && have != null ? have.name : name, ids));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Строка JSON: в кавычках, с экранированием. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static void line(StringBuilder sb, String uid, String name, List<String> ids) {
        sb.append("    {\"uid\": ").append(quote(uid)).append(", \"name\": ").append(quote(name)).append(", \"badges\": [");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(ids.get(i)));
        }
        sb.append("]}");
    }

    /** Один пункт списка для вставки в badges.json (без запятой на конце). */
    static String snippet(String uid, String name, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        line(sb, uid, StreakLogic.cleanName(name), ids);
        return sb.toString().substring(4);
    }

    /**
     * Весь файл целиком: общий список, поверх него ваши правила (для одного и того же аккаунта побеждает ваше).
     * Вставьте его в badges.json на GitHub, и всё, что вы выбрали, увидят остальные.
     */
    static String export(Map<String, Entry> shared, List<BadgeStore.Rule> mine) {
        Map<String, Entry> all = new LinkedHashMap<>();
        if (shared != null) all.putAll(shared);
        for (BadgeStore.Rule r : mine) all.put(r.uid, new Entry(r.name, r.ids));
        StringBuilder sb = new StringBuilder("{\n  \"version\": 1,\n  \"accounts\": [\n");
        int i = 0;
        for (Map.Entry<String, Entry> e : all.entrySet()) {
            if (e.getValue().ids.isEmpty()) continue;
            if (i++ > 0) sb.append(",\n");
            line(sb, e.getKey(), StreakLogic.cleanName(e.getValue().name), e.getValue().ids);
        }
        if (i > 0) sb.append('\n');
        return sb.append("  ]\n}\n").toString();
    }

    // ------------------------------------------------------------ применение

    /** Применяет ответ сервера. true, если список изменился. */
    static boolean apply(Answer a, long now) {
        if (a == null) {
            note = "нет ответа";
            return false;
        }
        if (a.error != null) {
            note = a.error;
            return false;
        }
        if (a.unchanged) {
            lastOkAt = now;
            note = "без изменений";
            return false;
        }
        Map<String, Entry> parsed = parse(a.body);
        if (parsed == null) {
            note = "файл не разобрался: формат badges.json неверный";
            return false;
        }
        accounts = parsed;
        etag = a.etag;
        lastOkAt = now;
        note = "загружено";
        BadgeStore.setRemote(idsOf(parsed));
        return true;
    }

    private static Map<String, List<String>> idsOf(Map<String, Entry> m) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : m.entrySet()) out.put(e.getKey(), e.getValue().ids);
        return out;
    }

    /** Один обмен с сервером; при успехе ответ кладётся и в файл-кэш. */
    static boolean refresh(long now) {
        Answer a;
        try {
            a = http.get(URL, etag);
        } catch (Throwable t) {
            a = new Answer(null, null, false, "сеть: " + t.getClass().getSimpleName());
        }
        boolean changed = apply(a, now);
        if (changed) saveCache(a.body);
        return changed;
    }

    private static void saveCache(String body) {
        try {
            File d = FlagStore.dir();
            if (d != null && body != null) FlagStore.writeAtomic(new File(d, CACHE), body);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    /** Читает последний ответ с диска: бейджики есть до того, как ответит сеть. */
    static void loadCache() {
        try {
            File d = FlagStore.dir();
            if (d == null) return;
            File f = new File(d, CACHE);
            if (!f.isFile()) return;
            Map<String, Entry> parsed = parse(FlagStore.readFile(f));
            if (parsed == null) return;
            accounts = parsed;
            BadgeStore.setRemote(idsOf(parsed));
            note = "из кэша";
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    // ------------------------------------------------------------ фон

    /** Запускает чтение в фоне: сразу и каждые 10 минут. Повторный вызов ничего не делает. */
    static synchronized void start() {
        if (started) return;
        started = true;
        loadCache();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if (Prefs.badgesOn && Prefs.badgesRemote && !CrashGuard.tripped) {
                            busy = true;
                            try {
                                refresh(System.currentTimeMillis());
                            } finally {
                                busy = false;
                            }
                        }
                        Thread.sleep(EVERY_MS);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable e) {
                        FlagStore.log(e);
                    }
                }
            }
        }, "ttmlonger-badges");
        t.setDaemon(true);
        t.start();
    }

    /** Обновить сейчас (кнопка на экране); done вызывается в главном потоке. */
    static void refreshNow(final Runnable done) {
        if (busy) {
            if (done != null) done.run();
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                busy = true;
                try {
                    refresh(System.currentTimeMillis());
                } catch (Throwable e) {
                    FlagStore.log(e);
                } finally {
                    busy = false;
                }
                if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
            }
        }, "ttmlonger-badges-now");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ состояние

    static Map<String, Entry> shared() {
        return new LinkedHashMap<>(accounts);
    }

    static int count() {
        return accounts.size();
    }

    static String status(long now) {
        if (!Prefs.badgesRemote) return "Общий список выключен";
        String ago = lastOkAt == 0 ? "" : ", обновлено " + age(now - lastOkAt);
        return "Общий список: " + accounts.size() + " акк., " + note + ago;
    }

    static String details() {
        return "общий список: " + (Prefs.badgesRemote ? "вкл" : "выкл") + ", аккаунтов " + accounts.size() + ", " + note
                + (etag != null ? ", есть метка версии" : "");
    }

    /** «только что», «3 мин назад», «2 ч назад». */
    static String age(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return "только что";
        long m = s / 60;
        if (m < 60) return m + " мин назад";
        return (m / 60) + " ч назад";
    }

    static void resetForTest() {
        accounts = new LinkedHashMap<>();
        etag = null;
        lastOkAt = 0;
        note = "ещё не загружался";
        http = new Real();
    }

    static String etagForTest() {
        return etag;
    }

    // ------------------------------------------------------------ настоящая сеть

    private static final class Real implements Http {
        @Override
        public Answer get(String url, String tag) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "TTMLonger/" + Targets.VERSION);
                if (tag != null) c.setRequestProperty("If-None-Match", tag);
                int code = c.getResponseCode();
                if (code == 304) return new Answer(null, tag, true, null);
                if (code != 200) return new Answer(null, null, false, "сервер ответил " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) return new Answer(null, null, false, "файл больше 256 КБ");
                }
                return new Answer(out.toString("UTF-8"), c.getHeaderField("ETag"), false, null);
            } catch (Throwable t) {
                return new Answer(null, null, false, "сеть: " + t.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }
}
