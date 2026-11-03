package dev.ttmlonger;

import android.app.Activity;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Просмотр заблокированного профиля. У модели пользователя TikTok (настоящий, не обфусцированный
 * класс com.ss.android.ugc.aweme.profile.model.User) есть isBlock, isBlocked, getBlockStatus,
 * getBlockingByType и getFpBlockByType: по ним экран профиля решает, показывать ли страницу или
 * заглушку «аккаунт заблокирован». Мод отвечает «не заблокирован» только там, где ответ настоящий
 * «заблокирован», и только на экранах профиля (по имени активности), чтобы не трогать списки
 * заблокированных, переписку и настройки.
 *
 * Если человек заблокировал вас, а не вы его, содержимое профиля решает сервер TikTok: этого мод
 * обойти не может. Что именно происходит, видно в сведениях: сколько раз TikTok сказал
 * «заблокирован» и на каких экранах.
 */
final class BlockedProfile {
    static final String USER = "com.ss.android.ugc.aweme.profile.model.User";

    /** Методы, которые отдают «заблокирован»: boolean true или число не ноль. */
    static final String[] METHODS = {"isBlock", "isBlocked", "getBlockStatus", "getBlockingByType", "getFpBlockByType"};

    private static final int MAX_SCREENS = 6;

    private static final List<String> screens = new ArrayList<>();
    private static volatile int hooked;
    private static volatile int seen;
    private static volatile int hidden;
    private static volatile int outside;
    private static volatile String note = "ещё не ставился";
    /** Только для проверок: имя «текущего экрана» вместо настоящей активности. */
    static volatile String screenForTest;

    private BlockedProfile() {
    }

    // ------------------------------------------------------------ чистая логика

    /** Похож ли экран на профиль: в имени класса активности есть «profile». */
    static boolean isProfileScreen(String activityClass) {
        return activityClass != null && activityClass.toLowerCase(java.util.Locale.ROOT).contains("profile");
    }

    /** Нужно ли прятать блокировку на этом экране при таких настройках. */
    static boolean applies(boolean on, boolean everywhere, String activityClass) {
        return on && (everywhere || isProfileScreen(activityClass));
    }

    /** Ответ TikTok означает «заблокирован»: true или число не ноль. */
    static boolean saysBlocked(Object result) {
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).longValue() != 0L;
        return false;
    }

    /** Что ответить вместо «заблокирован», в том же типе; null, если тип незнакомый. */
    static Object notBlocked(Object result) {
        if (result instanceof Boolean) return Boolean.FALSE;
        if (result instanceof Integer) return Integer.valueOf(0);
        if (result instanceof Long) return Long.valueOf(0L);
        if (result instanceof Short) return Short.valueOf((short) 0);
        if (result instanceof Byte) return Byte.valueOf((byte) 0);
        return null;
    }

    // ------------------------------------------------------------ хук

    static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (CrashGuard.tripped) return;
                Object r = param.getResult();
                if (!saysBlocked(r)) return;
                seen++;
                String screen = currentScreen();
                if (!applies(Prefs.viewBlocked, Prefs.viewBlockedEverywhere, screen)) {
                    outside++;
                    remember(screen, false);
                    return;
                }
                Object zero = notBlocked(r);
                if (zero == null) return;
                param.setResult(zero);
                hidden++;
                remember(screen, true);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
    };

    private static String currentScreen() {
        String t = screenForTest;
        if (t != null) return t;
        Activity a = ScreenWatcher.top();
        return a == null ? "" : a.getClass().getName();
    }

    private static void remember(String screen, boolean hid) {
        if (screen == null || screen.isEmpty()) return;
        String tag = (hid ? "скрыто на " : "оставлено на ") + screen;
        synchronized (screens) {
            if (screens.contains(tag) || screens.size() >= MAX_SCREENS) return;
            screens.add(tag);
        }
    }

    static void install(ClassLoader cl) {
        Class<?> user = XposedHelpers.findClassIfExists(USER, cl);
        if (user == null) {
            note = "класс User не найден";
            return;
        }
        int n = 0;
        StringBuilder names = new StringBuilder();
        for (String name : METHODS) {
            try {
                boolean has = false;
                for (Method m : user.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                        has = true;
                        break;
                    }
                }
                if (!has) continue;
                n += XposedBridge.hookAllMethods(user, name, HOOK).size();
                if (names.length() > 0) names.append(", ");
                names.append(name);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        hooked = n;
        note = n == 0 ? "методов блокировки у User нет" : "хуков " + n + ": " + names;
    }

    static String status() {
        if (!Prefs.viewBlocked) return "Просмотр заблокированного профиля выключен";
        if (hooked == 0) return "Не работает: " + note;
        return "Работает: " + note + ". TikTok сказал «заблокирован» " + seen + " раз(а), скрыто " + hidden
                + (outside > 0 ? ", оставлено вне профиля " + outside : "");
    }

    static String details() {
        String where;
        synchronized (screens) {
            where = screens.isEmpty() ? "-" : screens.toString();
        }
        return "заблокированный профиль: " + (Prefs.viewBlocked ? "вкл" : "выкл") + (Prefs.viewBlockedEverywhere ? ", везде" : ", только профиль")
                + "; " + note + "; ответов «заблокирован» " + seen + ", скрыто " + hidden + ", вне профиля " + outside
                + "; экраны: " + where;
    }

    static int hooked() {
        return hooked;
    }

    static int hiddenCount() {
        return hidden;
    }

    static void resetForTest() {
        seen = 0;
        hidden = 0;
        outside = 0;
        synchronized (screens) {
            screens.clear();
        }
    }
}
