package dev.ttmlonger;

import java.lang.reflect.Method;

/**
 * Всё, что зависит от версии TikTok, собрано здесь.
 *
 * Имена методов в TikTok обфусцированы и меняются от сборки к сборке, поэтому геттеры флагов
 * ищутся не по имени, а по сигнатуре: [Class] String ключ, T значение_по_умолчанию -> T.
 * Имена классов ниже — предположение, оно не проверено на конкретной версии.
 */
final class Targets {
    private Targets() {
    }

    static final String VERSION = "0.7.2";

    /**
     * Заголовок страницы настроек TikTok на разных языках. По нему мод понимает, что открыта
     * нужная страница, и показывает блок «Настройки мода». Своего языка нет — допишите сюда.
     */
    static final String[] SETTINGS_TITLES = {
            "Настройки и конфиденциальность",
            "Settings and privacy",
            "Settings & privacy",
            "Налаштування та конфіденційність",
            "Einstellungen und Datenschutz",
            "Paramètres et confidentialité",
            "Configuración y privacidad",
            "Ajustes y privacidad",
            "Configurações e privacidade",
            "Impostazioni e privacy",
            "Ayarlar ve gizlilik",
            "Ustawienia i prywatność",
            "Instellingen en privacy",
            "Pengaturan dan privasi",
            "الإعدادات والخصوصية",
    };

    /** Подпись «Поделиться профилем» на экране просмотра аватарки (по ней он и узнаётся). */
    static final String[] SHARE_PROFILE_TITLES = {
            "Поделиться профилем",
            "Share profile",
            "Поділитися профілем",
            "Profil teilen",
            "Partager le profil",
            "Compartir perfil",
            "Compartilhar perfil",
            "Condividi profilo",
            "Profili paylaş",
            "Udostępnij profil",
            "Profiel delen",
            "Bagikan profil",
    };

    /**
     * Пункты самой страницы настроек. Если заголовок не нашёлся (например, он нарисован не
     * TextView), страница всё равно узнаётся по тому, что на экране сразу несколько этих подписей.
     * Здесь только достаточно специфичные подписи: общие вроде «Язык» или «Реклама» встречаются
     * и на других страницах.
     */
    static final String[] SETTINGS_ROWS = {
            "Предпочитаемый контент",
            "Управление публикациями",
            "Время и благополучие",
            "Привязка аккаунтов семьи",
            "Безопасность и разрешения",
            "Приватный аккаунт",
            "Заблокированные аккаунты",
            "Мой аккаунт",
            "Контент и отображение",
            "Входящие и обмен сообщениями",
            "Кэш и мобильный трафик",
            "Освобождение места",
            "Экономия трафика",
            "Preferred content",
            "My account",
            "Content & Display",
            "Content and display",
            "Cache & Cellular data",
            "Cache and cellular data",
            "Free up space",
            "Data Saver",
    };

    /** Сколько разных подписей из {@link #SETTINGS_ROWS} должно быть на экране. */
    static final int SETTINGS_ROWS_MIN = 3;

    private static final String[] SETTINGS_TITLES_N = normAll(SETTINGS_TITLES);
    private static final String[] SHARE_PROFILE_N = normAll(SHARE_PROFILE_TITLES);
    private static final String[] SETTINGS_ROWS_N = normAll(SETTINGS_ROWS);

    /**
     * Приводит текст к виду для сравнения: без регистра, без невидимых символов, любые пробелы
     * (в том числе неразрывные и переводы строк) сведены к одному обычному.
     */
    static String norm(CharSequence s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean space = true; // начало строки: пробелы не нужны
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u200b' || c == '\u200c' || c == '\u200d' || c == '\u200e' || c == '\u200f'
                    || c == '\u2060' || c == '\ufeff' || c == '\u00ad') {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!space) b.append(' ');
                space = true;
            } else {
                b.append(Character.toLowerCase(c));
                space = false;
            }
        }
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == ' ') b.setLength(n - 1);
        return b.toString();
    }

    private static String[] normAll(String[] src) {
        String[] r = new String[src.length];
        for (int i = 0; i < src.length; i++) r[i] = norm(src[i]);
        return r;
    }

    private static boolean in(String[] list, String n) {
        for (String s : list) {
            if (s.equals(n)) return true;
        }
        return false;
    }

    static boolean isShareProfile(String text) {
        return in(SHARE_PROFILE_N, norm(text));
    }

    /**
     * Заголовок страницы настроек. Допускается небольшая приписка после заголовка (например,
     * счётчик или значок в тексте): точное совпадение необязательно.
     */
    static boolean isSettingsTitle(String text) {
        String n = norm(text);
        for (String t : SETTINGS_TITLES_N) {
            if (n.equals(t)) return true;
            if (n.length() > t.length() && n.length() <= t.length() + 6 && n.startsWith(t)) return true;
        }
        return false;
    }

    /** @param normalized текст, уже пропущенный через {@link #norm} */
    static boolean isSettingsRowNorm(String normalized) {
        return in(SETTINGS_ROWS_N, normalized);
    }

    static final String[] PACKAGES = {
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
    };

    /** Классы менеджера A/B-настроек, которые пробуем в первую очередь. */
    static final String[] CLASSES = {
            "com.bytedance.ies.abmock.SettingsManager",
    };

    /** Если ни один класс выше не найден: перебираем классы с этими префиксами. */
    static final String[] SCAN_PREFIXES = {
            "com.bytedance.ies.abmock.",
    };

    /** Сколько геттеров должно быть в классе, найденном перебором, чтобы его хукать. */
    static final int SCAN_MIN_GETTERS = 2;

    /**
     * Флаги, которые мод включает сам: {ключ, тип, значение}. Подставляются один раз на ключ
     * (см. FlagStore.seedDefaults), поэтому ваш ручной выбор в экране флагов главнее.
     *
     * Сейчас здесь все выключенные переключатели «баннеров» из экспорта флагов TikTok
     * (в экспорте нет флага «установки баннера»; названия взяты по слову banner).
     */
    static final String[][] DEFAULT_FLAGS = {
            {"close_friend_profile_banner_switch", "bool", "true"},
            {"close_friend_debug_profile_banner_bypass_check", "bool", "true"},
            {"enable_feed_banner_reset_insert_times", "bool", "true"},
            {"feed_common_banner_reset_target_provider_enable", "bool", "true"},
            {"feed_banner_inactive_opt", "int", "1"},
            // Баннер профиля: установка своей картинки-шапки в «Изменить профиль» (флаги функции profile_bg).
            {"profile_bg_in_allow_list", "int", "1"},
            {"profile_bg_enable_consumption_group", "int", "1"},
            // Голосовые комментарии: включить запись и убрать запрет входа.
            {"audio_comment_publish", "int", "1"},
            {"comment_audio_publish_entry_forbidden", "bool", "false"},
    };

    /** Флаги функции «баннер профиля»: переключатель в меню мода включает и выключает их вместе. */
    static final String[][] BANNER_FLAGS = {
            {"profile_bg_in_allow_list", "int", "1"},
            {"profile_bg_enable_consumption_group", "int", "1"},
    };

    /** Флаги голосовых комментариев (запись голосом в комментарии). */
    static final String[][] VOICE_COMMENT_FLAGS = {
            {"audio_comment_publish", "int", "1"},
            {"comment_audio_publish_entry_forbidden", "bool", "false"},
    };

    // ---------------------------------------------------------------- поиск в dex (DexScan)

    /**
     * Ключи флагов, по которым в dex ищется «читатель флагов» TikTok: каждый из них мод
     * переопределяет, а значит, через какой метод его читает TikTok, надо знать. Список
     * собран из флагов функций мода (баннер, голосовые комментарии) и сопутствующих.
     */
    static final String[] SCAN_ANCHORS = {
            "profile_bg_in_allow_list",
            "profile_bg_enable_consumption_group",
            "audio_comment_publish",
            "comment_audio_publish_entry_forbidden",
            "close_friend_profile_banner_switch",
            "close_friend_debug_profile_banner_bypass_check",
            "enable_feed_banner_reset_insert_times",
            "feed_common_banner_reset_target_provider_enable",
            "feed_banner_inactive_opt",
            "tt_audio_comment_repost",
            "tt_comment_repost",
            "long_press_speed_up_lock",
            "comment_multi_photo_group",
            "profile_left_align",
            "circle_search_block",
            "enable_self_repost",
            "camera_teleprompter_mode",
    };

    /** Интерфейс обработчика нажатий на стикер в личных сообщениях (реальное имя, не обфусцировано). */
    static final String STICKER_CLICK_IFACE =
            "Lcom/ss/android/ugc/aweme/im/messagelist/api/ability/MessageListStickerClickAbility;";
    /** Модель стикера в личных сообщениях. */
    static final String STICKER_TEMPLATE =
            "Lcom/ss/android/ugc/aweme/im/message/template/card/StickerTemplate;";
    private static final String COMMENT_STICKER = "Lcom/ss/android/ugc/aweme/comment/model/CommentStickerStruct;";
    private static final String STICKER_ITEM = "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;";
    private static final String FN0 = "Lkotlin/jvm/functions/Function0;";

    /**
     * Точные сигнатуры методов-обработчиков, которые не привязаны к обфусцированному имени класса:
     * нажатие на стикер в комментарии и лист этого стикера. Если в вашей версии сигнатура другая,
     * метод не найдётся, а мод покажет кнопку запасным путём.
     */
    static final String[][] STICKER_SHAPES = {
            {"comment-tap", "(Landroid/view/View;" + COMMENT_STICKER
                    + "ZLjava/lang/String;Ljava/util/Map;Ljava/lang/String;)V"},
            {"comment-sheet", "(Ljava/lang/String;" + STICKER_ITEM + "Landroid/view/View;ZLjava/lang/String;"
                    + "Ljava/util/Map;" + FN0 + FN0 + FN0 + ")V"},
    };

    /**
     * Подходит ли метод под «читателя флагов» (отдельный класс TikTok, через который читается
     * большая часть флагов): метод экземпляра, первый параметр — число (область), затем ключ-строка
     * и числа; если вернуть надо строку, то допустима и вторая строка (значение по умолчанию).
     *
     * @return {индекс параметра-ключа, тип FlagStore.*} или null
     */
    static int[] matchReader(Method m) {
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) return null;
        int type = FlagStore.typeOf(m.getReturnType());
        if (type < 0) return null;
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 3 || p.length > 6 || !p[0].isPrimitive()) return null;
        int key = -1;
        int strings = 0;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == String.class) {
                strings++;
                if (key < 0) key = i;
            } else if (!p[i].isPrimitive()) {
                return null;
            }
        }
        if (key < 1 || strings > 2 || (strings == 2 && type != FlagStore.STRING)) return null;
        return new int[]{key, type};
    }

    static boolean isTarget(String pkg) {
        if (pkg == null) return false;
        for (String p : PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /**
     * Подходит ли метод под сигнатуру геттера флага.
     *
     * @return {индекс параметра-ключа, тип FlagStore.*} или null
     */
    static int[] match(Method m) {
        int type = FlagStore.typeOf(m.getReturnType());
        if (type < 0) return null;

        Class<?>[] p = m.getParameterTypes();
        if (p.length < 2 || p.length > 4) return null;

        int key = -1;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == String.class) {
                key = i;
                break;
            }
        }
        if (key < 0 || key + 1 >= p.length) return null;
        // Значение по умолчанию того же типа, что и результат.
        if (FlagStore.typeOf(p[key + 1]) != type) return null;

        for (int i = 0; i < p.length; i++) {
            if (i == key || i == key + 1) continue;
            if (p[i] != Class.class && p[i] != boolean.class) return null;
        }
        return new int[]{key, type};
    }
}
