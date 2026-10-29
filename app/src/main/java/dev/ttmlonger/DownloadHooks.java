package dev.ttmlonger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Скачивание видео без ограничений.
 *
 * TikTok сам хранит у видео несколько адресов, и все нужные методы называются по-настоящему
 * (не обфусцированы):
 *
 * - Video.getDownloadAddr() — адрес, который берёт кнопка «Сохранить»; рядом лежит
 *   getDownloadNoWatermarkAddr() — тот же файл без водяного знака, а сам поток воспроизведения
 *   (getPlayAddr) водяного знака не содержит вовсе: его рисуют только для скачивания;
 * - Video.getRawBitRate() — все размеры, в которых видео опубликовано (1080p, 720p...);
 * - ACLCommonShare (getCode, getShowType, getTranscode), Aweme.isPreventDownload,
 *   User.isPreventDownload, VideoControl.allowDownload — разрешение автора на скачивание.
 *   Приложение читает их, прежде чем показать кнопку.
 *
 * Мод подменяет ответы этих методов. Любой сбой тихий: если метод в этой версии TikTok
 * устроен иначе, остаётся то, что вернул сам TikTok (сохранится видео с водяным знаком,
 * но не вылет).
 */
final class DownloadHooks {
    private static final String FEED = "com.ss.android.ugc.aweme.feed.model.";
    static final String VIDEO = FEED + "Video";
    static final String ACL = FEED + "ACLCommonShare";
    static final String AWEME = FEED + "Aweme";
    static final String VIDEO_CONTROL = FEED + "VideoControl";

    private static final StringBuilder STATUS = new StringBuilder();
    private static int hooked;

    /** Установлен на время, пока сам мод читает getDownloadAddr: хук тогда ничего не меняет. */
    static final ThreadLocal<Boolean> QUIET = new ThreadLocal<>();
    private static volatile String lastCaller = "-";
    private static volatile int requests;

    private DownloadHooks() {
    }

    static void install(ClassLoader cl) {
        Class<?> video = XposedHelpers.findClassIfExists(VIDEO, cl);
        Class<?> acl = XposedHelpers.findClassIfExists(ACL, cl);
        Class<?> aweme = XposedHelpers.findClassIfExists(AWEME, cl);
        Class<?> control = XposedHelpers.findClassIfExists(VIDEO_CONTROL, cl);
        Class<?> user = XposedHelpers.findClassIfExists(AvatarHooks.USER, cl);

        if (video != null) {
            count("Video.getDownloadAddr", hookAll(video, "getDownloadAddr", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (CrashGuard.tripped || param.hasThrowable() || QUIET.get() != null) return;
                        Object stamped = param.getResult();
                        Object v = param.thisObject;
                        boolean blocked = !hasUrl(stamped) || VideoTracker.isProhibited(v);
                        requests++;
                        lastCaller = callerOf(new Throwable());
                        Object better = choose(v, stamped, blocked);
                        if (better != null && better != stamped) param.setResult(better);
                        if (blocked && Prefs.dlAlways && Prefs.dlPill) NativeOffer.maybe(v);
                    } catch (Throwable ignored) {
                        // остаётся ответ TikTok
                    }
                }
            }));
            // Какие видео TikTok показывал: для экрана «Видео и истории».
            count("Video.getPlayAddr→трекер", hookAll(video, "getPlayAddr", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!CrashGuard.tripped) VideoTracker.played(param.thisObject);
                    } catch (Throwable ignored) {
                        // трекер не должен мешать
                    }
                }
            }));
        } else {
            note("Video не найден");
        }

        if (aweme != null) {
            count("Aweme.getVideo→трекер", hookAll(aweme, "getVideo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!CrashGuard.tripped && !param.hasThrowable()) {
                            VideoTracker.seen(param.thisObject, param.getResult());
                        }
                    } catch (Throwable ignored) {
                        // трекер не должен мешать
                    }
                }
            }));
        }

        if (acl != null) {
            count("ACLCommonShare.getCode", hookAll(acl, "getCode", fixed(0)));
            count("ACLCommonShare.getShowType", hookAll(acl, "getShowType", fixed(2)));
            count("ACLCommonShare.getTranscode", hookAll(acl, "getTranscode", fixed(1)));
        } else {
            note("ACLCommonShare не найден");
        }

        XC_MethodHook notPrevented = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Prefs.dlAlways && !CrashGuard.tripped && !param.hasThrowable()) {
                    param.setResult(Boolean.FALSE);
                }
            }
        };
        XC_MethodHook notPreventedPost = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    // Запоминаем, что автор запретил скачивание: такие видео получают особое обращение.
                    if (!param.hasThrowable() && Boolean.TRUE.equals(param.getResult())) {
                        VideoTracker.markProhibited(param.thisObject);
                    }
                } catch (Throwable ignored) {
                    // не страшно
                }
                if (Prefs.dlAlways && !CrashGuard.tripped && !param.hasThrowable()) {
                    param.setResult(Boolean.FALSE);
                }
            }
        };
        if (aweme != null) count("Aweme.isPreventDownload", hookAll(aweme, "isPreventDownload", notPreventedPost));
        if (user != null) count("User.isPreventDownload", hookAll(user, "isPreventDownload", notPrevented));

        // VideoControl.allowDownload — поле, а не геттер, поэтому его ставят в «да» на выходе
        // из метода Aweme, который отдаёт VideoControl (метод находим по типу, а не по имени).
        if (aweme != null && control != null) {
            final Field allow = findField(control, "allowDownload");
            if (allow != null) {
                int n = 0;
                for (Method m : aweme.getDeclaredMethods()) {
                    if (m.getReturnType() != control || m.getParameterTypes().length != 0) continue;
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object vc = param.getResult();
                                    if (vc != null && Prefs.dlAlways && !CrashGuard.tripped) {
                                        allow.set(vc, Boolean.TRUE);
                                    }
                                } catch (Throwable ignored) {
                                    // поле осталось как было
                                }
                            }
                        });
                        n++;
                    } catch (Throwable t) {
                        FlagStore.log(t);
                    }
                }
                count("Aweme→VideoControl.allowDownload", n);
            } else {
                note("VideoControl.allowDownload не найдено");
            }
        }

        // Фотопосты: у каждой картинки три копии: с подписью автора, с подписью смотрящего и
        // чистая. Подменяем две подписанные чистой, когда включено «без водяного знака».
        if (aweme != null) {
            count("Aweme.getPhotoModeImageInfo", hookAll(aweme, "getPhotoModeImageInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (Prefs.dlNoWatermark && !CrashGuard.tripped) cleanPhotos(param.getResult());
                    } catch (Throwable ignored) {
                        // фото остаются как были
                    }
                }
            }));
        }
        Diag.note("скачивание: " + status());
    }

    private static XC_MethodHook fixed(final int value) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Prefs.dlAlways && !CrashGuard.tripped && !param.hasThrowable()) {
                    param.setResult(value);
                }
            }
        };
    }

    // ------------------------------------------------------------ выбор адреса

    /** Какой адрес отдать вместо адреса TikTok; null — оставить как есть. */
    static Object choose(Object video, Object stamped) {
        return choose(video, stamped, false);
    }

    /**
     * @param blocked автор запретил скачивание: собственного адреса для скачивания у видео нет.
     *                Берём то, что играет плеер, в самом простом виде: основной адрес воспроизведения
     *                (или размер из обычных h264, без HEVC и DASH). Раньше здесь мог выбраться
     *                размер, который приложение сохраняло битым.
     */
    static Object choose(Object video, Object stamped, boolean blocked) {
        if (video == null) return null;
        if (blocked) {
            Object play = call(video, "getPlayAddr");
            if (Prefs.dlQuality != 0) {
                Object q = byQualityStrict(video, Prefs.dlQuality);
                if (q != null) return q;
            }
            if (hasUrl(play)) return play;
        }
        Object base = stamped;
        if (Prefs.dlNoWatermark) {
            Object clean = call(video, "getDownloadNoWatermarkAddr");
            if (hasUrl(clean)) {
                base = clean;
            } else {
                // Чистого адреса нет: поток воспроизведения тоже без водяного знака.
                Object play = call(video, "getPlayAddr");
                if (hasUrl(play)) base = play;
            }
        }
        if (Prefs.dlQuality != 0) {
            Object q = byQuality(video, Prefs.dlQuality);
            if (q != null) return q;
        }
        return base;
    }

    /** Как byQuality, но только обычные h264: без HEVC; null, если таких нет. */
    static Object byQualityStrict(Object video, int mode) {
        List<Option> pool = new ArrayList<>();
        for (Option o : options(video)) if (!o.hevc) pool.add(o);
        Option o = pick(pool, mode);
        return o == null ? null : o.addr;
    }

    /** Один размер видео из списка TikTok. */
    static final class Option {
        final Object addr;
        final long rate;
        final int height;
        final boolean hevc;

        Option(Object addr, long rate, int height, boolean hevc) {
            this.addr = addr;
            this.rate = rate;
            this.height = height;
            this.hevc = hevc;
        }
    }

    /**
     * Выбирает размер: -1 лучший, -2 наименьший, иначе самый крупный не выше заданной высоты.
     * HEVC берётся, только если других нет (в галерее открывается не везде).
     */
    static Object byQuality(Object video, int mode) {
        List<Option> all = options(video);
        Option o = pick(all, mode);
        return o == null ? null : o.addr;
    }

    static List<Option> options(Object video) {
        List<Option> out = new ArrayList<>();
        for (String name : new String[]{"getRawBitRate", "getBitRate"}) {
            Object list = call(video, name);
            if (!(list instanceof List)) continue;
            int n = 0;
            for (Object opt : (List<?>) list) {
                if (n++ >= 256 || opt == null) break;
                if (Boolean.TRUE.equals(call(opt, "isDash"))) continue;
                Object addr = call(opt, "getPlayAddr");
                if (!hasUrl(addr)) continue;
                long rate = number(call(opt, "getBitRate"));
                boolean hevc = number(call(opt, "isBytevc1")) != 0;
                int height = heightOf(opt, addr, rate);
                out.add(new Option(addr, rate, height, hevc));
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** Чистая логика выбора (проверяется тестами). */
    static Option pick(List<Option> all, int mode) {
        if (all == null || all.isEmpty()) return null;
        List<Option> pool = new ArrayList<>();
        for (Option o : all) if (!o.hevc) pool.add(o);
        if (pool.isEmpty()) pool = all;

        Option best = null;
        if (mode == -2) {
            for (Option o : pool) {
                if (best == null || o.rate < best.rate || (o.rate == best.rate && o.height < best.height)) best = o;
            }
            return best;
        }
        if (mode > 0) {
            List<Option> under = new ArrayList<>();
            for (Option o : pool) if (o.height > 0 && o.height <= mode) under.add(o);
            if (under.isEmpty()) {
                // Всё крупнее потолка: берём наименьшее из имеющегося.
                return pick(pool, -2);
            }
            pool = under;
        }
        for (Option o : pool) {
            if (best == null || o.height > best.height || (o.height == best.height && o.rate > best.rate)) best = o;
        }
        return best;
    }

    /**
     * Высота кадра. Берётся из размера; если его нет, из названия «gear» (например «normal_720_...»),
     * иначе оценивается по битрейту. У вертикального видео меньшая сторона и есть «p».
     */
    static int heightOf(Object opt, Object addr, long rate) {
        int h = (int) number(call(opt, "getHeight"));
        int w = (int) number(call(opt, "getWidth"));
        if (h <= 0) {
            h = (int) number(call(addr, "getHeight"));
            w = (int) number(call(addr, "getWidth"));
        }
        if (h > 0) return w > 0 && w < h ? w : h;
        Object gear = call(opt, "getGearName");
        if (gear instanceof String) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?<![0-9])(2160|1440|1080|960|720|576|540|480|360|240)(?![0-9])")
                    .matcher((String) gear);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        // Грубая оценка по битрейту (около 0,07 бит на пиксель при 30 кадрах/с, кадр 9:16);
        // нужна только для порядка размеров, когда TikTok не сообщил высоту.
        if (rate > 0) return (int) Math.max(144, Math.min(4320, Math.round(Math.sqrt(rate * 0.268))));
        return 0;
    }

    // ------------------------------------------------------------ фотопосты

    private static void cleanPhotos(Object info) throws Exception {
        if (info == null) return;
        Object list = call(info, "getImageList");
        if (!(list instanceof List)) {
            Field f = findField(info.getClass(), "imageList");
            list = f == null ? null : f.get(info);
        }
        if (!(list instanceof List)) return;
        int n = 0;
        for (Object image : (List<?>) list) {
            if (image == null || n++ > 256) continue;
            Field clean = findField(image.getClass(), "displayImageNoWatermark");
            if (clean == null) continue;
            Object plain = clean.get(image);
            if (plain == null) continue;
            for (String name : new String[]{"ownerWatermarkImage", "userWatermarkImage"}) {
                Field f = findField(image.getClass(), name);
                if (f != null) f.set(image, plain);
            }
        }
    }

    // ------------------------------------------------------------ мелочи

    static boolean hasUrl(Object model) {
        return AvatarHooks.firstUrl(model) != null;
    }

    /** Вызов метода без параметров по имени; null при любой неудаче. */
    static Object call(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = null;
            for (Class<?> k = o.getClass(); k != null && m == null; k = k.getSuperclass()) {
                try {
                    m = k.getDeclaredMethod(name);
                } catch (NoSuchMethodException ignored) {
                    // выше по иерархии
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    static long number(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        return 0;
    }

    static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // выше по иерархии
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static int hookAll(Class<?> c, String method, XC_MethodHook hook) {
        try {
            return XposedBridge.hookAllMethods(c, method, hook).size();
        } catch (Throwable t) {
            FlagStore.log(t);
            return 0;
        }
    }

    private static void count(String what, int n) {
        hooked += n;
        STATUS.append(what).append(n > 0 ? " ✓" : " ✗").append("; ");
    }

    private static void note(String s) {
        STATUS.append(s).append("; ");
    }

    /** Первые «чужие» кадры стека: кто попросил адрес скачивания (для диагностики). */
    private static String callerOf(Throwable t) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : t.getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("dev.ttmlonger.") || c.startsWith("de.robv.") || c.startsWith("java.lang.reflect.")
                    || c.startsWith("LSPHooker") || c.contains("LSPHooker") || c.startsWith("org.lsposed.")
                    || c.startsWith("dalvik.") || c.startsWith("android.os.") || c.startsWith("com.android.internal.os.")) {
                continue;
            }
            if (b.length() > 0) b.append(" < ");
            b.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName());
            if (++n >= 6) break;
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    /** Для диагностики: сколько раз TikTok просил адрес скачивания и кто. */
    static String requests() {
        return requests + " раз(а); последний вызов: " + lastCaller;
    }

    static String status() {
        return STATUS.length() == 0 ? "не запускалось" : "хуков " + hooked + ": " + STATUS;
    }
}
