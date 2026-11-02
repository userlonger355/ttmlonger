package dev.ttmlonger;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * «Возможности»: функции TikTok, которые уже есть в приложении, но выключены флагом для вашего
 * аккаунта. Каждая функция — группа флагов со своим выключателем. Значения флагов взяты из экспорта
 * флагов TikTok 47.5.3 (там видны тип и значение по умолчанию), поэтому подставляется ровно то,
 * что приложение само читает у себя.
 *
 * Группа главнее «Набора флагов мода»: пока группа включена, её флаги подставляются, даже если
 * набор целиком выключен; выключенная группа отдаёт свои флаги TikTok как есть. Своя подмена на
 * экране флагов главнее и того и другого.
 */
final class Unlocks {
    /** Одна функция: заголовок и подпись в стиле строк настроек, флаги {ключ, тип, значение}. */
    static final class Group {
        final String id;
        final int icon;
        final String title;
        final String note;
        final String[][] flags;

        Group(String id, int icon, String title, String note, String[][] flags) {
            this.id = id;
            this.icon = icon;
            this.title = title;
            this.note = note;
            this.flags = flags;
        }
    }

    static final Group[] ALL = {
            new Group("voice_dm", Icons.MIC, "Голосовые в ЛС", "Микрофон в чатах",
                    new String[][]{{"im_voice_message", "int", "1"}}),
            new Group("long_repost", Icons.SEND, "Длинные репосты", "Лимит текста репоста 99 999 символов",
                    new String[][]{{"repost_text_max_length", "int", "99999"}}),
            new Group("hashtags", Icons.FLAG, "Хештеги без лимита", "Лимит хештегов при публикации снят",
                    new String[][]{{"studio_publish_limit_hashtag_switch", "bool", "false"}}),
            new Group("read_all", Icons.CHECK, "Прочитать все", "Кнопка в мультивыборе чатов",
                    new String[][]{
                            {"pro_inbox_enable_read_all_in_multiselect_mode", "int", "1"},
                            {"business_inbox_enable_read_all_in_multiselect_mode", "int", "1"}}),
            new Group("photos9", Icons.IMAGE, "9 фото в комментарии", "До девяти изображений + Live Photo",
                    new String[][]{
                            {"comment_multi_photo_group", "int", "2"},
                            {"comment_enable_live_photo", "int", "1"},
                            {"comment_photo_publish", "int", "5"}}),
            new Group("teleprompter", Icons.VIDEO, "Телесуфлёр", "Текст ползёт по экрану при съёмке",
                    new String[][]{{"camera_teleprompter_mode", "int", "1"}}),
    };

    private static volatile Map<String, Group> owner;
    private static volatile Map<String, FlagStore.Patch> patches;

    private Unlocks() {
    }

    private static void build() {
        if (owner != null) return;
        Map<String, Group> o = new HashMap<>();
        Map<String, FlagStore.Patch> p = new HashMap<>();
        for (Group g : ALL) {
            for (String[] f : g.flags) {
                int type = FlagStore.typeByName(f[1]);
                if (type < 0 || FlagStore.coerce(f[2], type) == null) continue;
                o.put(f[0], g);
                p.put(f[0], new FlagStore.Patch(type, f[2]));
            }
        }
        patches = p;
        owner = o;
    }

    /** Группа, которой принадлежит флаг, или null. */
    static Group groupOf(String key) {
        build();
        return owner.get(key);
    }

    /** Подмена флага из группы (включена ли группа, здесь не проверяется) или null. */
    static FlagStore.Patch patchOf(String key) {
        build();
        return patches.get(key);
    }

    /** Все ключи, которыми управляют группы. */
    static Set<String> keys() {
        build();
        return new LinkedHashSet<>(owner.keySet());
    }

    static Group byId(String id) {
        for (Group g : ALL) {
            if (g.id.equals(id)) return g;
        }
        return null;
    }

    static boolean on(String id) {
        return !has(Prefs.unlockOff, id);
    }

    static int onCount() {
        int n = 0;
        for (Group g : ALL) {
            if (on(g.id)) n++;
        }
        return n;
    }

    /** Есть ли идентификатор в списке «a,b,c». */
    static boolean has(String list, String id) {
        if (list == null || list.isEmpty()) return false;
        return ("," + list + ",").contains("," + id + ",");
    }

    /** Список с добавленным или убранным идентификатором; порядок и повторы не копятся. */
    static String with(String list, String id, boolean present) {
        StringBuilder sb = new StringBuilder();
        if (list != null && !list.isEmpty()) {
            for (String part : list.split(",")) {
                part = part.trim();
                if (part.isEmpty() || part.equals(id)) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(part);
            }
        }
        if (present) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    /** Сколько флагов группы отсутствует в экспорте набора (их мод добавляет сам). */
    static int extraKeys() {
        build();
        int n = 0;
        for (String k : owner.keySet()) {
            if (RefSet.base(k) == null) n++;
        }
        return n;
    }
}
