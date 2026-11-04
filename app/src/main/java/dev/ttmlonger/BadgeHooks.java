package dev.ttmlonger;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.widget.EditText;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Бейджики после имён. Своего экрана у имени нет: TikTok пишет имя аккаунта в десятке мест (профиль,
 * каждый комментарий, подпись под видео), и все они спрашивают одну модель {@code User}
 * (и {@code UserProfileInfo} на загруженном профиле) через {@code getNickname()}. Мод отвечает именем с
 * невидимой меткой на конце ({@link BadgeLogic}). Потом любой текст, который попадает в TextView,
 * проходит через {@code TextView.setText(CharSequence, BufferType)}: метка заменяется картинкой
 * ({@link BadgeSpan}). Раскладка текста не меняется: картинка занимает место невидимых знаков.
 *
 * <p>В поля ввода метка не попадает: из текста, который кладут в EditText, знаки метки вырезаются
 * (иначе при правке своего имени метка сохранилась бы в имени).
 *
 * <p>Хуки ставятся при запуске TikTok и только если бейджики включены (выключатель действует после
 * перезапуска): так у тех, кто их не использует, лишних хуков на всех текстах нет.
 */
final class BadgeHooks {
    static final String PROFILE = "com.ss.android.ugc.profile.platform.base.data.UserProfileInfo";

    private static volatile int hooks;
    private static volatile boolean startedOn;
    private static volatile String note = "ещё не ставилось";
    private static volatile int drawn;
    private static volatile int cleaned;

    private static final Map<Class<?>, Method> UID = new ConcurrentHashMap<>();
    private static final Method MISSING = missing();
    private static final BadgeArt[] ART = new BadgeArt[BadgeSet.ALL.length];

    private BadgeHooks() {
    }

    private static Method missing() {
        try {
            return BadgeHooks.class.getDeclaredMethod("missing");
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ имя и номер аккаунта

    /** Метод getNickname() без параметров, возвращающий строку. */
    static Method nickname(Class<?> c) {
        if (c == null) return null;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("getNickname") && m.getParameterTypes().length == 0
                        && m.getReturnType() == String.class && !Modifier.isStatic(m.getModifiers())) {
                    return m;
                }
            }
            Method m = c.getMethod("getNickname");
            return m.getReturnType() == String.class && !Modifier.isStatic(m.getModifiers()) ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Номер аккаунта у модели: getUid() строкой или числом; null, если не вышло. */
    static String uidOf(Object user) {
        if (user == null) return null;
        try {
            Class<?> c = user.getClass();
            Method m = UID.get(c);
            if (m == null) {
                try {
                    Method g = c.getMethod("getUid");
                    g.setAccessible(true);
                    m = g;
                } catch (Throwable t) {
                    m = MISSING;
                }
                UID.put(c, m);
            }
            if (m == MISSING || m == null) return null;
            Object v = m.invoke(user);
            String s = v instanceof String ? (String) v : v instanceof Number ? String.valueOf(((Number) v).longValue()) : null;
            return StreakLogic.isUid(s) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static final XC_MethodHook NICK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!Prefs.badgesOn || CrashGuard.tripped) return;
                Object r = param.getResult();
                if (!(r instanceof String)) return;
                String name = (String) r;
                String out = BadgeStore.dress(name, uidOf(param.thisObject));
                if (out != name) param.setResult(out);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
    };

    // ------------------------------------------------------------ текст

    /** Рисунок бейджика по номеру: один на все метки (рисуется только в главном потоке). */
    private static synchronized BadgeArt art(int index) {
        BadgeArt a = ART[index];
        if (a == null) {
            a = new BadgeArt(BadgeSet.ALL[index]);
            ART[index] = a;
        }
        return a;
    }

    /** Текст с картинками вместо меток или null, если в нём наших меток нет. */
    static CharSequence spans(CharSequence t) {
        List<BadgeLogic.Mark> list = BadgeLogic.marks(t);
        if (list.isEmpty()) return null;
        SpannableStringBuilder b = null;
        for (BadgeLogic.Mark m : list) {
            if (BadgeSet.byIndex(m.index) == null) continue;
            if (b == null) b = new SpannableStringBuilder(t);
            b.setSpan(new BadgeSpan(art(m.index)), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    static final XC_MethodHook TEXT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!BadgeStore.marking() || CrashGuard.tripped) return;
                if (param.args == null || param.args.length < 2 || !(param.args[0] instanceof CharSequence)) return;
                CharSequence text = (CharSequence) param.args[0];
                if (!BadgeLogic.hasSymbol(text)) return;
                if (param.thisObject instanceof EditText) {
                    param.args[0] = BadgeLogic.strip(text.toString());
                    cleaned++;
                    return;
                }
                CharSequence out = spans(text);
                if (out == null) return;
                param.args[0] = out;
                param.args[1] = TextView.BufferType.SPANNABLE;
                drawn++;
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
    };

    // ------------------------------------------------------------ установка

    /** Ставится при запуске (после чтения настроек); без выключателя ничего не делает. */
    static void install(ClassLoader cl) {
        if (!Prefs.badgesOn) {
            note = "выключено";
            return;
        }
        startedOn = true;
        BadgeStore.load();
        BadgeRemote.start();
        int n = 0;
        StringBuilder what = new StringBuilder();
        for (String cn : new String[]{BlockedProfile.USER, PROFILE}) {
            Class<?> c = XposedHelpers.findClassIfExists(cn, cl);
            String shortName = cn.substring(cn.lastIndexOf('.') + 1);
            if (c == null) {
                what.append(shortName).append(": класса нет; ");
                continue;
            }
            Method m = nickname(c);
            if (m == null) {
                what.append(shortName).append(": нет getNickname; ");
                continue;
            }
            try {
                XposedBridge.hookMethod(m, NICK);
                n++;
                what.append(shortName).append(" ✓; ");
            } catch (Throwable t) {
                FlagStore.log(t);
                what.append(shortName).append(": ").append(t.getClass().getSimpleName()).append("; ");
            }
        }
        try {
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("setText", CharSequence.class,
                    TextView.BufferType.class), TEXT);
            n++;
            what.append("TextView ✓");
        } catch (Throwable t) {
            FlagStore.log(t);
            what.append("TextView: ").append(t.getClass().getSimpleName());
        }
        hooks = n;
        note = what.toString().trim();
    }

    // ------------------------------------------------------------ состояние

    static String status() {
        if (!Prefs.badgesOn) return "Бейджики выключены";
        if (!startedOn) return "Включены, но нужен перезапуск TikTok: хуки ставятся при запуске";
        if (hooks == 0) return "Не работает: " + note;
        return "Работает: " + note + ". Аккаунтов с бейджиками " + BadgeStore.count() + ", имён с меткой "
                + BadgeStore.dressedCount + ", нарисовано " + drawn;
    }

    static String details() {
        return "бейджики: " + (Prefs.badgesOn ? "вкл" : "выкл") + (startedOn ? "" : " (не запускались)") + "; " + note
                + "; правил " + BadgeStore.count() + ", имён с меткой " + BadgeStore.dressedCount + ", нарисовано "
                + drawn + ", вычищено из полей ввода " + cleaned;
    }

    static boolean running() {
        return startedOn && hooks > 0;
    }

    static int drawn() {
        return drawn;
    }

    static int cleaned() {
        return cleaned;
    }

    static void resetForTest() {
        drawn = 0;
        cleaned = 0;
        hooks = 0;
        startedOn = false;
        note = "ещё не ставилось";
        UID.clear();
    }
}
