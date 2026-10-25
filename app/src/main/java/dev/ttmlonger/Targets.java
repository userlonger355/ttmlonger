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

    static final String VERSION = "0.3.0";

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

    static boolean isShareProfile(String text) {
        String t = text.trim();
        for (String s : SHARE_PROFILE_TITLES) {
            if (s.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    static boolean isSettingsTitle(String text) {
        String t = text.trim();
        for (String s : SETTINGS_TITLES) {
            if (s.equalsIgnoreCase(t)) return true;
        }
        return false;
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
