package dev.ttmlonger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * «Невидимка»: что TikTok сообщает другим про вас.
 *
 * <p><b>«Печатает…».</b> Статус набора текста уходит в чат отдельным запросом к серверу сообщений
 * (команда SEND_INPUT_STATUS). Тело запроса — настоящий (не обфусцированный) класс
 * {@code com.bytedance.im.core.proto.SendInputStatusRequestBody}, и в запрос его кладёт
 * {@code RequestBody.Builder} или конструктор {@code RequestBody}. Мод ищет по форме те методы и
 * конструкторы, у которых есть параметр этого типа, и подменяет его на null: запрос уходит пустым,
 * и собеседник не видит «печатает…». Имена методов при этом не нужны.
 *
 * <p><b>«В сети».</b> Отчёт об активности отправляет задача {@code ReportActivityStatusTask.run(Context)}
 * (настоящий класс, а не обфусцированный). Мод пропускает её запуск. Сервер может показывать «в сети» ещё и
 * по самому подключению к сообщениям: его мод не трогает, иначе перестали бы приходить сообщения.
 *
 * <p>Просмотры профиля отдельным запросом не отправляются (в коде нет такого отчёта), поэтому здесь их
 * спрятать нельзя.
 */
final class Invisible {
    static final String BODY = "com.bytedance.im.core.proto.SendInputStatusRequestBody";
    static final String REQUEST = "com.bytedance.im.core.proto.RequestBody";
    static final String BUILDER = "com.bytedance.im.core.proto.RequestBody$Builder";
    static final String REPORT = "com.ss.android.ugc.aweme.requesttask.idle.ReportActivityStatusTask";

    /** Место, куда TikTok кладёт тело «печатает»: метод или конструктор и номер параметра. */
    static final class Slot {
        final Member member;
        final int arg;

        Slot(Member member, int arg) {
            this.member = member;
            this.arg = arg;
        }
    }

    private static volatile int typingHooks;
    private static volatile int onlineHooks;
    private static volatile int typingDropped;
    private static volatile int onlineSkipped;
    private static volatile String typingNote = "ещё не ставился";
    private static volatile String onlineNote = "ещё не ставился";

    private Invisible() {
    }

    // ------------------------------------------------------------ поиск мест по форме (чистая логика)

    /** Методы и конструкторы владельцев, у которых есть параметр точно типа body. Статические не берём. */
    static List<Slot> typingSlots(Class<?> body, Class<?>... owners) {
        List<Slot> out = new ArrayList<>();
        if (body == null || owners == null) return out;
        for (Class<?> owner : owners) {
            if (owner == null) continue;
            try {
                for (Constructor<?> c : owner.getDeclaredConstructors()) {
                    addSlots(out, c, c.getParameterTypes(), body);
                }
                for (Method m : owner.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers()) || m.isBridge()) continue;
                    addSlots(out, m, m.getParameterTypes(), body);
                }
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        return out;
    }

    private static void addSlots(List<Slot> out, Member member, Class<?>[] types, Class<?> body) {
        for (int i = 0; i < types.length; i++) {
            if (types[i] == body) out.add(new Slot(member, i));
        }
    }

    /** Методы run без возвращаемого значения (run(Context) и подобные) у задачи отчёта об активности. */
    static List<Method> reportTargets(Class<?> task) {
        List<Method> out = new ArrayList<>();
        if (task == null) return out;
        try {
            for (Method m : task.getDeclaredMethods()) {
                if (!m.getName().equals("run") || m.getReturnType() != void.class) continue;
                if (Modifier.isAbstract(m.getModifiers()) || m.isBridge()) continue;
                out.add(m);
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return out;
    }

    // ------------------------------------------------------------ хуки

    static final class DropArg extends XC_MethodHook {
        private final int index;

        DropArg(int index) {
            this.index = index;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!Prefs.hideTyping || CrashGuard.tripped) return;
                if (param.args == null || index >= param.args.length || param.args[index] == null) return;
                param.args[index] = null;
                typingDropped++;
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
    }

    static final XC_MethodHook SKIP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!Prefs.hideOnline || CrashGuard.tripped) return;
                param.setResult(null);
                onlineSkipped++;
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
    };

    static void install(ClassLoader cl) {
        installOn(XposedHelpers.findClassIfExists(BODY, cl), XposedHelpers.findClassIfExists(REQUEST, cl),
                XposedHelpers.findClassIfExists(BUILDER, cl), XposedHelpers.findClassIfExists(REPORT, cl));
    }

    /** Ставит хуки на найденные классы; null — класса нет. Вынесено отдельно, чтобы проверять на подставных классах. */
    static void installOn(Class<?> body, Class<?> request, Class<?> builder, Class<?> report) {
        // «печатает…»
        if (body == null) {
            typingNote = "класс SendInputStatusRequestBody не найден";
        } else if (request == null && builder == null) {
            typingNote = "класс RequestBody не найден";
        } else {
            int n = 0;
            for (Slot s : typingSlots(body, request, builder)) {
                try {
                    XposedBridge.hookMethod(s.member, new DropArg(s.arg));
                    n++;
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
            typingHooks = n;
            typingNote = n == 0 ? "в RequestBody нет места для тела «печатает»" : "хуков " + n;
        }
        // «в сети»
        if (report == null) {
            onlineNote = "задача ReportActivityStatusTask не найдена";
        } else {
            int n = 0;
            for (Method m : reportTargets(report)) {
                try {
                    XposedBridge.hookMethod(m, SKIP);
                    n++;
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
            onlineHooks = n;
            onlineNote = n == 0 ? "у задачи нет метода run" : "хуков " + n;
        }
    }

    // ------------------------------------------------------------ состояние

    static boolean typingReady() {
        return typingHooks > 0;
    }

    static boolean onlineReady() {
        return onlineHooks > 0;
    }

    static int enabledCount() {
        return (Prefs.hideTyping ? 1 : 0) + (Prefs.hideOnline ? 1 : 0);
    }

    /** Короткая строка для экрана. */
    static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("«Печатает…»: ").append(line(Prefs.hideTyping, typingHooks, typingNote, typingDropped, "скрыто"));
        sb.append('\n');
        sb.append("«В сети»: ").append(line(Prefs.hideOnline, onlineHooks, onlineNote, onlineSkipped, "пропущено отчётов"));
        return sb.toString();
    }

    private static String line(boolean on, int hooks, String note, int count, String what) {
        if (hooks == 0) return "не работает (" + note + ")";
        if (!on) return "выключено, готово к включению";
        return "работает, " + what + " " + count;
    }

    static String details() {
        return "невидимка: печатает " + (Prefs.hideTyping ? "вкл" : "выкл") + " (" + typingNote + ", скрыто " + typingDropped
                + "); в сети " + (Prefs.hideOnline ? "вкл" : "выкл") + " (" + onlineNote + ", пропущено " + onlineSkipped + ")";
    }

    static void resetForTest() {
        typingHooks = 0;
        onlineHooks = 0;
        typingDropped = 0;
        onlineSkipped = 0;
        typingNote = "ещё не ставился";
        onlineNote = "ещё не ставился";
    }

    static int typingDropped() {
        return typingDropped;
    }

    static int onlineSkipped() {
        return onlineSkipped;
    }

    static int typingHooks() {
        return typingHooks;
    }

    static int onlineHooks() {
        return onlineHooks;
    }
}
