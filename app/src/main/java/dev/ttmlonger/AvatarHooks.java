package dev.ttmlonger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Откуда взять ссылку на аватар. На экране просмотра аватарки поле с аккаунтом называется
 * по-разному в каждой версии TikTok (имена обфусцированы), а вот класс User и его геттеры
 * аватара имеют настоящие имена. Поэтому экран не расспрашиваем, а слушаем: каждый раз, когда
 * приложение читает адрес аватара, запоминаем его. К моменту, когда картинка открыта на весь
 * экран, последний прочитанный адрес принадлежит именно ей.
 */
final class AvatarHooks {
    static final String USER = "com.ss.android.ugc.aweme.profile.model.User";
    private static final String[] GETTERS = {"getAvatarLarger", "getAvatar300", "getAvatarMedium"};

    /** Три размера аватара последнего прочитанного аккаунта. */
    private static final class Seen {
        WeakReference<Object> user;
        Object larger;
        Object a300;
        Object medium;
    }

    private static Seen seen = new Seen();
    private static volatile int hooked;
    private static Method urlList;
    private static Method uidGetter;

    private AvatarHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> user = XposedHelpers.findClassIfExists(USER, cl);
        if (user == null) {
            Diag.note("аватар: класс User не найден");
            return;
        }
        for (final String g : GETTERS) {
            try {
                hooked += XposedBridge.hookAllMethods(user, g, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            if (r != null && !CrashGuard.tripped) remember(param.thisObject, g, r);
                        } catch (Throwable ignored) {
                            // запоминание не должно ронять TikTok
                        }
                    }
                }).size();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        Diag.note("аватар: перехвачено методов User: " + hooked);
    }

    private static synchronized void remember(Object user, String getter, Object model) {
        Object cur = seen.user == null ? null : seen.user.get();
        if (cur != user) {
            seen = new Seen();
            seen.user = new WeakReference<>(user);
        }
        if ("getAvatarLarger".equals(getter)) seen.larger = model;
        else if ("getAvatar300".equals(getter)) seen.a300 = model;
        else seen.medium = model;
    }

    static int hookedCount() {
        return hooked;
    }

    /** Лучшая известная ссылка: крупный размер, иначе средний. Null — ничего не прочитано. */
    static synchronized String bestUrl() {
        String u = firstUrl(seen.larger);
        if (u == null) u = firstUrl(seen.a300);
        if (u == null) u = firstUrl(seen.medium);
        return u;
    }

    /** Идентификатор аккаунта последнего аватара (для имени файла), если его удалось прочитать. */
    static synchronized String lastUid() {
        try {
            Object user = seen.user == null ? null : seen.user.get();
            if (user == null) return null;
            if (uidGetter == null) uidGetter = user.getClass().getMethod("getUid");
            Object r = uidGetter.invoke(user);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Строка для диагностики. */
    static synchronized String status() {
        return "перехвачено геттеров: " + hooked + ", последний аватар: "
                + (bestUrl() == null ? "не читался" : "известен");
    }

    /** Первый адрес из UrlModel.getUrlList(), либо null. */
    static String firstUrl(Object model) {
        if (model == null) return null;
        try {
            Method m = urlList;
            if (m == null || !m.getDeclaringClass().isInstance(model)) {
                m = model.getClass().getMethod("getUrlList");
                urlList = m;
            }
            Object r = m.invoke(model);
            if (!(r instanceof List) || ((List<?>) r).isEmpty()) return null;
            Object first = ((List<?>) r).get(0);
            String s = first == null ? null : String.valueOf(first).trim();
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Только https: на cleartext приложение и так не ходит, а CDN отвечает и по https. */
    static String secure(String url) {
        if (url == null) return null;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("http://")) return "https://" + url.substring("http://".length());
        return url.startsWith("https://") ? url : null;
    }
}
