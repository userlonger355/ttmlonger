package dev.ttmlonger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Запись чтения флагов личных сообщений: голосовые, обои, пузырь чата и всё, где в имени есть «region».
 *
 * <p>Флаги этих функций у мода уже включены, но функции не работают. Чтобы понять почему, нужно знать:
 * читает ли TikTok такой флаг вообще, что он получает (своё значение или нашу подмену) и кто спрашивает.
 * Мод записывает для таких ключей первое и последнее значения, число чтений и цепочку вызовов. Содержимое
 * чатов не читается; значения флагов — настройки TikTok, а не данные человека.
 */
final class FlagTap {
    private static final int MAX_KEYS = 40;
    private static final int MAX_STACKS = 2;
    private static final int MAX_FRAMES = 8;
    private static final int VALUE_MAX = 60;

    private static final Map<String, Boolean> DECIDED = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    private static final Map<String, Seen> SEEN = new LinkedHashMap<>();

    private FlagTap() {
    }

    private static final class Seen {
        String first;
        String last;
        boolean patched;
        int count;
        final List<String> stacks = new ArrayList<>();
    }

    /** Нужен ли ключ разведке (результат запоминается, повторные чтения почти ничего не стоят). */
    static boolean watched(String key) {
        if (key == null) return false;
        Boolean d = DECIDED.get(key);
        if (d != null) return d;
        boolean w = decide(key);
        DECIDED.put(key, w);
        return w;
    }

    static boolean decide(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("voice_message") || k.contains("customize_message_bubble") || k.contains("chat_wallpaper")
                || k.contains("im_wallpaper")) {
            return true;
        }
        boolean im = k.startsWith("im_") || k.startsWith("dm_") || k.startsWith("imsdk") || k.startsWith("chat_");
        return im && (k.contains("voice") || k.contains("audio") || k.contains("region") || k.contains("wallpaper")
                || k.contains("unsupport") || k.contains("bubble"));
    }

    private static String show(Object v) {
        String s = String.valueOf(v);
        s = s.replace('\n', ' ');
        return s.length() > VALUE_MAX ? s.substring(0, VALUE_MAX) + "…" : s;
    }

    /** Записывает чтение: что вернул TikTok, что получил он в итоге и подменил ли это мод. */
    static void onRead(String key, Object original, Object result, boolean patched) {
        boolean needStack;
        synchronized (LOCK) {
            Seen s = SEEN.get(key);
            if (s == null) {
                if (SEEN.size() >= MAX_KEYS) return;
                s = new Seen();
                s.first = show(original);
                SEEN.put(key, s);
            }
            s.count++;
            s.last = show(result);
            s.patched |= patched;
            needStack = s.stacks.size() < MAX_STACKS;
        }
        if (!needStack) return;
        List<String> frames = UnsupportedTap.appFrames(new Throwable().getStackTrace());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < frames.size() && i < MAX_FRAMES; i++) {
            if (i > 0) sb.append(" < ");
            sb.append(frames.get(i));
        }
        String line = sb.toString();
        synchronized (LOCK) {
            Seen s = SEEN.get(key);
            if (s != null && s.stacks.size() < MAX_STACKS && !s.stacks.contains(line)) s.stacks.add(line);
        }
    }

    static int keys() {
        synchronized (LOCK) {
            return SEEN.size();
        }
    }

    static String report() {
        synchronized (LOCK) {
            if (SEEN.isEmpty()) {
                return "[чтение флагов личных сообщений] с запуска TikTok не читались (откройте чат и вернитесь в меню мода)\n";
            }
            StringBuilder sb = new StringBuilder("[чтение флагов личных сообщений] ключей ").append(SEEN.size())
                    .append('\n');
            for (Map.Entry<String, Seen> e : SEEN.entrySet()) {
                Seen s = e.getValue();
                sb.append("  ").append(e.getKey()).append(" = ").append(s.first);
                if (!s.first.equals(s.last) || s.patched) sb.append(" → ").append(s.last);
                sb.append(s.patched ? " (подмена мода)" : "").append(" ×").append(s.count).append('\n');
                for (String st : s.stacks) sb.append("    вызвали: ").append(st).append('\n');
            }
            return sb.toString();
        }
    }

    static void resetForTest() {
        synchronized (LOCK) {
            SEEN.clear();
        }
        DECIDED.clear();
    }
}
