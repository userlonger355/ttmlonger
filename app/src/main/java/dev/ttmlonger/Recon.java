package dev.ttmlonger;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Разведка кода TikTok для функций, у которых нет флага: «Невидимка» (скрыть онлайн, «печатает…»,
 * просмотры профиля) и просмотр заблокированного профиля. Чтобы подключить такую функцию к нужному
 * методу, надо знать, как он называется в этой версии приложения. Имена внутри TikTok обфусцированы,
 * но строки в коде остаются: по ним и по настоящим названиям модели пользователя мод собирает отчёт.
 *
 * Второй режим разведки — под массовое удаление своих репостов, лайков и избранного: там нужны адреса
 * внутренних запросов TikTok (список своих лайков, отмена лайка и так далее) и классы ответа.
 *
 * В отчёт попадают только названия классов, методов и полей TikTok и строки из его кода. Ни ваших
 * данных, ни текстов с экрана в нём нет. Разбор идёт только по кнопке, а не при запуске приложения.
 */
final class Recon {
    /** Куски строк в коде, по которым ищутся нужные места. */
    static final String[] PARTS = {"input_status", "inputstatus", "is_typing", "typing_status", "set_typing",
            "activity_status", "active_status", "presence", "user_online", "online_report", "profile_visitor",
            "visit_record", "view_record", "viewed_profile", "visitor_report"};

    /** Настоящие (не обфусцированные) классы, у которых смотрим поля и методы. */
    static final String[] CLASSES = {"com.ss.android.ugc.aweme.profile.model.User",
            "com.ss.android.ugc.profile.platform.base.data.UserProfileInfo"};

    /**
     * Настоящие классы, найденные первой разведкой: их показываем целиком (все методы и поля), потому что
     * по именам методов видно, где TikTok сообщает статус «в сети» и просмотры профиля.
     */
    static final String[] FULL = {
            "com.ss.android.ugc.aweme.im.saas.host.impl.feature.activestatus.ActiveStatusFeatureImpl",
            "com.ss.android.ugc.profile.business.ci.viewer.viewmodel.ProfileViewerViewModel",
            "com.ss.android.ugc.aweme.profile.model.BlockStatus",
            "com.bytedance.im.core.proto.SendInputStatusRequestBody",
            "com.bytedance.im.core.proto.RequestBody$Builder",
            "com.ss.android.ugc.aweme.requesttask.idle.ReportActivityStatusTask",
            "com.ss.android.ugc.aweme.im.saas.host.impl.feature.privacy.IMPrivacySettingsImpl"};

    /**
     * Куски строк для режима «удаление». Порядок важен: строка относится к первому подошедшему куску, поэтому
     * точные адреса стоят раньше общих слов. Адреса запросов лежат в аннотациях методов (Retrofit), поэтому
     * рядом с ними печатаются имена параметров запроса. Репосты внутри TikTok называются «upvote».
     */
    static final String[] DELETE_PARTS = {
            // репосты
            "repost/list", "aweme/repost", "repost/delete", "delete_repost", "cancel_repost", "remove_repost",
            "unrepost", "repost_list", "repost/",
            "upvote/", "/upvote", "upvote_list", "upvote_delete", "upvote",
            // лайки
            "item/digg", "aweme/favorite", "favorite/list", "digg_list", "user_digg", "cancel_digg", "undigg",
            // избранное («коллекции»)
            "listcollection", "aweme/collect", "collect/list", "collect_list", "collection/", "collect/",
            "uncollect", "unfavorite", "cancel_collect", "collect_stat"};

    /**
     * Куски строк для режима «личные сообщения»: голосовые сообщения, обои чата и пузырь чата. Это ключи
     * флагов и названия функций; по местам, где они читаются, видно, какие проверки стоят перед функцией.
     */
    static final String[] DM_PARTS = {
            "im_voice", "voice_message", "voice_msg", "audio_message", "chat_wallpaper", "wallpaper",
            "chat_background", "chat_bg", "customize_message_bubble", "message_bubble", "chat_bubble",
            "bubble_style", "dm_customize", "chat_theme", "im_region", "region_limit"};

    /** Члены классов, найденных по строкам личных сообщений: проверки региона, доступности и сами функции. */
    static final String DM_MEMBER = "(?i).*(region|country|support|enable|allow|avail|voice|wallpaper|bubble|theme|"
            + "background|permit|limit).*";

    static final int MODE_CHAT = 0;
    static final int MODE_DELETE = 1;
    static final int MODE_DM = 2;

    /** Классы запросов TikTok (сетевая библиотека не обфусцирована): как создаётся и вызывается запрос. */
    static final String[] DELETE_FULL = {
            "com.ss.android.ugc.aweme.favorites.business.aweme.AwemeCollectionAgent",
            "com.ss.android.ugc.aweme.feed.assem.digg.request.VideoDiggApi"};

    /** Классы модели видео и пользователя: смотрим только поля и методы про лайки, избранное, репосты и страницы. */
    static final String[] FOCUS = {
            "com.ss.android.ugc.aweme.feed.model.Aweme",
            "com.ss.android.ugc.aweme.feed.model.AwemeStatistics",
            "com.ss.android.ugc.aweme.feed.model.FeedItemList",
            "com.ss.android.ugc.aweme.profile.model.User"};

    static final String FOCUS_MEMBER = "(?i).*(digg|like|collect|favor|repost|delete|remove|cancel|cursor|hasmore|"
            + "awemeid|getaid|createtime|secuid|sec_uid|items|list).*";

    private static final String MEMBER = "(?i).*(block|status|online|visit|viewer|typing|privacy).*";
    private static final int PER_NEEDLE = 25;
    private static final int FULL_LINES = 70;
    private static final int FOCUS_LINES = 40;
    /** Отчёт длиннее обрезается: вложение длиннее примерно 20 тысяч знаков доходит не целиком. */
    static final int REPORT_MAX = 17_500;
    private static final int LINE_MAX = 420;
    private static final int AUTO_CLASSES = 8;
    private static final int SITES = 3;
    private static final long LIMIT_MS = 150_000L;

    private static volatile String state = "ещё не запускалась";
    private static volatile String report = "";
    private static volatile boolean running;

    private Recon() {
    }

    static String state() {
        return state;
    }

    static String report() {
        return report;
    }

    static boolean running() {
        return running;
    }

    /** Запускает разбор в фоне; повторный запуск, пока идёт первый, ничего не делает. */
    static void start(Context ctx) {
        start(ctx, MODE_CHAT);
    }

    /** mode: MODE_CHAT («Невидимка», блок), MODE_DELETE (репосты, лайки, избранное), MODE_DM (голосовые, обои, пузырь). */
    static synchronized void start(final Context ctx, final int mode) {
        if (running) return;
        running = true;
        state = "идёт разбор кода TikTok (до пары минут)…";
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DexScan.Options o = new DexScan.Options();
                    o.parts = mode == MODE_DELETE ? DELETE_PARTS : mode == MODE_DM ? DM_PARTS : PARTS;
                    o.deadline = System.currentTimeMillis() + LIMIT_MS;
                    DexScan.Result r = DexHooks.scanApp(ctx, o);
                    ClassLoader cl = StreakHooks.loader();
                    report = cap(build(r, cl, mode), REPORT_MAX);
                    state = "готово: " + r.dexes + " dex, " + r.classes + " классов, " + r.ms + " мс";
                } catch (Throwable e) {
                    state = "сбой: " + e;
                    FlagStore.log(e);
                } finally {
                    running = false;
                }
            }
        }, "ttmlonger-recon");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Собирает отчёт нужного режима: сначала самое важное, потому что длинный отчёт обрезается с конца. */
    static String build(DexScan.Result r, ClassLoader cl, int mode) {
        StringBuilder sb = new StringBuilder();
        if (mode == MODE_DELETE) {
            sb.append("режим: удаление репостов, лайков и избранного\n");
            sb.append(render(r, new KnownKeys(), DELETE_PARTS, 12));
            List<String> names = new ArrayList<>(Arrays.asList(DELETE_FULL));
            for (String f : realClasses(r, new KnownKeys(), 4)) {
                if (!names.contains(f)) names.add(f);
            }
            sb.append(full(cl, names));
            sb.append(focused(cl, Arrays.asList(FOCUS), FOCUS_MEMBER));
        } else if (mode == MODE_DM) {
            sb.append("режим: личные сообщения (голосовые, обои чата, пузырь чата)\n");
            sb.append(render(r, null, DM_PARTS, 14));
            sb.append(focused(cl, realClasses(r, null, 6), DM_MEMBER));
        } else {
            sb.append(render(r, new KnownKeys()));
            sb.append('\n').append(classes(cl));
            List<String> names = new ArrayList<>(Arrays.asList(FULL));
            for (String f : realClasses(r, new KnownKeys())) {
                if (!names.contains(f)) names.add(f);
            }
            sb.append(full(cl, names));
        }
        return sb.toString();
    }

    /** Обрезает текст до max знаков по границе строки и говорит об этом. */
    static String cap(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        int cut = text.lastIndexOf('\n', max);
        if (cut < max / 2) cut = max;
        return text.substring(0, cut) + "\n… обрезано: отчёт был длиннее " + max + " знаков\n";
    }

    /** Отсев строк, которые уже известны как ключи флагов. */
    interface Known {
        boolean is(String s);
    }

    private static final class KnownKeys implements Known {
        @Override
        public boolean is(String s) {
            return FlagStore.knownKey(s);
        }
    }

    // ------------------------------------------------------------ чистая логика (проверяется без телефона)

    /** «Lx/y/Z;» → «x.y.Z»; всё остальное возвращается как есть. */
    static String pretty(String desc) {
        if (desc == null) return "";
        if (desc.length() > 2 && desc.charAt(0) == 'L' && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
        return desc;
    }

    /** Отчёт по найденным строкам: по куску, затем по строке и местам, где она используется. */
    static String render(DexScan.Result r, Known known) {
        return render(r, known, PARTS);
    }

    static String render(DexScan.Result r, Known known, String[] parts) {
        return render(r, known, parts, PER_NEEDLE);
    }

    static String render(DexScan.Result r, Known known, String[] parts, int perNeedle) {
        Map<String, Map<String, List<String>>> byNeedle = new LinkedHashMap<>();
        for (String n : parts) byNeedle.put(n, new LinkedHashMap<String, List<String>>());
        int total = 0;
        for (DexScan.Ref ref : r.refs) {
            if (!ref.label.startsWith("const:~")) continue;
            String h = ref.label.substring(7);
            int bar = h.indexOf('|');
            if (bar < 0) continue;
            String needle = h.substring(0, bar);
            String text = h.substring(bar + 1);
            if (known != null && known.is(text)) continue;
            Map<String, List<String>> strings = byNeedle.get(needle);
            if (strings == null) {
                strings = new LinkedHashMap<>();
                byNeedle.put(needle, strings);
            }
            List<String> sites = strings.get(text);
            if (sites == null) {
                sites = new ArrayList<>();
                strings.put(text, sites);
            }
            sites.add(pretty(ref.cls) + "." + ref.name + ref.proto);
            total++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("разведка кода: ").append(r.summary()).append("; мест со строками ").append(total).append('\n');
        for (Map.Entry<String, Map<String, List<String>>> e : byNeedle.entrySet()) {
            Map<String, List<String>> strings = e.getValue();
            sb.append("\n[").append(e.getKey()).append("] строк ").append(strings.size()).append('\n');
            int shown = 0;
            for (Map.Entry<String, List<String>> s : strings.entrySet()) {
                if (shown++ >= perNeedle) break;
                StringBuilder line = new StringBuilder("  \"").append(s.getKey()).append("\"");
                List<String> sites = s.getValue();
                for (int i = 0; i < sites.size() && i < SITES; i++) {
                    line.append(i == 0 ? " ← " : "; ").append(sites.get(i));
                }
                if (sites.size() > SITES) line.append("; ещё ").append(sites.size() - SITES);
                if (line.length() > LINE_MAX) line.setLength(LINE_MAX);
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Настоящие (не обфусцированные) классы, в чьих методах встретились искомые строки: по ним сразу
     * видно, какой класс отвечает за функцию. Класс обфусцированного кода (X.…) сюда не попадает.
     */
    static List<String> realClasses(DexScan.Result r, Known known) {
        return realClasses(r, known, AUTO_CLASSES);
    }

    static List<String> realClasses(DexScan.Result r, Known known, int limit) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (DexScan.Ref ref : r.refs) {
            if (!ref.label.startsWith("const:~")) continue;
            String h = ref.label.substring(7);
            int bar = h.indexOf('|');
            if (bar < 0 || (known != null && known.is(h.substring(bar + 1)))) continue;
            String name = pretty(ref.cls);
            if (name.startsWith("com.") && !name.startsWith("com.google.") && !name.startsWith("com.facebook.")
                    && !name.startsWith("com.bytedance.ies.abmock") && !name.contains("$")) {
                out.add(name);
            }
            if (out.size() >= limit * 3) break;
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /** «имя(типы): результат» для метода; типы короткие. */
    static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getSimpleName());
        }
        return sb.append("): ").append(m.getReturnType().getSimpleName()).toString();
    }

    /** Все объявленные методы и поля класса, до FULL_LINES строк; для классов, которые уже известны по имени. */
    static String full(ClassLoader cl, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append('\n').append(name).append(": ");
            Class<?> c = null;
            try {
                c = cl == null ? Class.forName(name) : Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // нет такого класса
            }
            if (c == null) {
                sb.append("класса нет\n");
                continue;
            }
            Set<String> lines = new TreeSet<>();
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.isSynthetic()) lines.add("метод " + signature(m));
                }
                for (Field f : c.getDeclaredFields()) {
                    if (!f.isSynthetic()) lines.add("поле " + f.getName() + ": " + f.getType().getSimpleName());
                }
            } catch (Throwable ignored) {
                // класс ссылается на то, чего нет
            }
            sb.append(lines.size()).append(" членов");
            Class<?> sup = c.getSuperclass();
            if (sup != null && sup != Object.class) sb.append(", родитель ").append(sup.getName());
            sb.append('\n');
            int n = 0;
            for (String l : lines) {
                if (n++ >= FULL_LINES) {
                    sb.append("  ещё ").append(lines.size() - FULL_LINES).append('\n');
                    break;
                }
                sb.append("  ").append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** Как full, но только члены, чьё имя подходит под regex (у больших моделей остальное — шум). */
    static String focused(ClassLoader cl, List<String> names, String regex) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append('\n').append(name).append(": ");
            Class<?> c = null;
            try {
                c = cl == null ? Class.forName(name) : Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // нет такого класса
            }
            if (c == null) {
                sb.append("класса нет\n");
                continue;
            }
            Set<String> lines = new TreeSet<>();
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.isSynthetic() && m.getName().matches(regex)) lines.add("метод " + signature(m));
                }
                for (Field f : c.getDeclaredFields()) {
                    if (!f.isSynthetic() && f.getName().matches(regex)) {
                        lines.add("поле " + f.getName() + ": " + f.getType().getSimpleName());
                    }
                }
            } catch (Throwable ignored) {
                // класс ссылается на то, чего нет
            }
            sb.append(lines.size()).append(" подходящих\n");
            int n = 0;
            for (String l : lines) {
                if (n++ >= FOCUS_LINES) {
                    sb.append("  ещё ").append(lines.size() - FOCUS_LINES).append('\n');
                    break;
                }
                sb.append("  ").append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** Подходит ли имя поля или метода под разведку. */
    static boolean wanted(String name) {
        return name != null && name.matches(MEMBER);
    }

    /** Поля и методы без параметров, имя которых говорит про блокировку, статус или просмотры. */
    static List<String> members(Class<?> c) {
        Set<String> out = new TreeSet<>();
        int depth = 0;
        for (Class<?> k = c; k != null && k != Object.class && depth < 4; k = k.getSuperclass(), depth++) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (wanted(f.getName())) out.add("поле " + f.getName() + ": " + f.getType().getSimpleName());
                }
                for (Method m : k.getDeclaredMethods()) {
                    if (wanted(m.getName()) && m.getParameterTypes().length == 0) {
                        out.add("метод " + m.getName() + "(): " + m.getReturnType().getSimpleName());
                    }
                }
            } catch (Throwable ignored) {
                // класс ссылается на то, чего нет
            }
        }
        return new ArrayList<>(out);
    }

    static String classes(ClassLoader cl) {
        StringBuilder sb = new StringBuilder();
        for (String name : CLASSES) {
            sb.append('\n').append(name).append(": ");
            Class<?> c = null;
            try {
                c = cl == null ? Class.forName(name) : Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // нет такого класса
            }
            if (c == null) {
                sb.append("класса нет\n");
                continue;
            }
            List<String> m = members(c);
            sb.append(m.size()).append(" подходящих\n");
            for (int i = 0; i < m.size() && i < 40; i++) sb.append("  ").append(m.get(i)).append('\n');
        }
        return sb.toString();
    }
}
