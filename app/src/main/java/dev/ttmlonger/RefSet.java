package dev.ttmlonger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * «Набор мода»: флаги, которые мод подставляет сам, пока вы их не поменяли (данные из экспорта
 * референс-мода для TikTok 47.5.3: голосовые сообщения в личных, пузырь чата, отключённая реклама
 * и так далее). Это отдельный слой под вашими подменами:
 *   своя подмена (экран флагов, импорт) главнее набора;
 *   выключатель Prefs.refSet убирает набор целиком, ничего не стирая;
 *   флаги, у которых есть свой переключатель в меню (баннер, голосовые комментарии), и флаги из
 *   Targets.DEFAULT_FLAGS набором не управляются: ими занимаются они.
 * Флаги json-типа сюда не входят: хуки мода подменяют только bool/int/long/float/double/string.
 */
final class RefSet {
    private static volatile Map<String, FlagStore.Patch> base;

    private RefSet() {
    }

    private static Map<String, FlagStore.Patch> base() {
        Map<String, FlagStore.Patch> m = base;
        if (m == null) {
            synchronized (RefSet.class) {
                m = base;
                if (m == null) {
                    m = build(RefForcedData.FORCED);
                    base = m;
                }
            }
        }
        return m;
    }

    /** Строит таблицу набора; пропускает флаги со своим переключателем и значения, не подходящие к типу. */
    static Map<String, FlagStore.Patch> build(String[][] forced) {
        Set<String> own = new HashSet<>();
        for (String[] d : Targets.DEFAULT_FLAGS) own.add(d[0]);
        for (String[] d : Targets.BANNER_FLAGS) own.add(d[0]);
        for (String[] d : Targets.VOICE_COMMENT_FLAGS) own.add(d[0]);
        Map<String, FlagStore.Patch> m = new HashMap<>();
        for (String[] d : forced) {
            if (own.contains(d[0])) continue;
            int type = FlagStore.typeByName(d[1]);
            if (type < 0 || FlagStore.coerce(d[2], type) == null) continue;
            m.put(d[0], new FlagStore.Patch(type, d[2]));
        }
        return m;
    }

    /** Подмена из набора для этого флага или null (выключатель здесь не проверяется). */
    static FlagStore.Patch base(String key) {
        return base().get(key);
    }

    /** Подмена, которая действует сейчас: группа «Возможностей» главнее набора, набор — выключателя. */
    static FlagStore.Patch active(String key) {
        Unlocks.Group g = Unlocks.groupOf(key);
        if (g != null) return Unlocks.on(g.id) ? Unlocks.patchOf(key) : null;
        // Не оставляем рекламные флаги выключенными, когда пользователь отключил скрытие рекламы.
        // Явные пользовательские подмены по-прежнему имеют приоритет (FlagStore).
        if (!Prefs.hideAds && isAdSuppression(key)) return null;
        return Prefs.refSet ? base(key) : null;
    }

    static boolean isAdSuppression(String key) {
        if (key == null) return false;
        switch (key) {
            case "ad_banner_enable":
            case "enable_normal_splash_ad":
            case "enable_normal_splash_ad_ab":
            case "mini_drama_ad_single_mode_client":
            case "profile_ad_experiment":
            case "search_ad_refactor_enable":
            case "splash_ad_enable":
                return true;
            default:
                return false;
        }
    }

    /** Ключи набора и «Возможностей» вместе. */
    static Iterable<String> allKeys() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(base().keySet());
        all.addAll(Unlocks.keys());
        return all;
    }

    static Iterable<String> keys() {
        return base().keySet();
    }

    /** Сколько флагов в наборе. */
    static int size() {
        return base().size();
    }

    /** Сколько json-флагов референс-мод тоже менял, а этот мод пока не умеет. */
    static int jsonSkipped() {
        return RefForcedData.JSON_FORCED.length;
    }
}
