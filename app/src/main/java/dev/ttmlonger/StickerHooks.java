package dev.ttmlonger;

import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Хуки на нажатия по стикерам: личные сообщения и комментарии. Методы TikTok обфусцированы, поэтому
 * их находит DexScan (по интерфейсу, который реализует класс, и по точной сигнатуре), а здесь они
 * подключаются. Хук ничего не меняет: он только запоминает, какой стикер нажат, и передаёт
 * его StickerSaver.
 */
final class StickerHooks {
    private static final Set<String> hooked = new HashSet<>();
    private static volatile int count;
    private static volatile String status = "ещё не искали";

    private StickerHooks() {
    }

    static String status() {
        return status;
    }

    static void apply(DexScan.Result r, ClassLoader cl) {
        int found = 0;
        for (DexScan.Ref ref : r.refs) {
            // «impl:» и «const:» — находки для огоньков, не методы нажатия.
            if (ref.label.startsWith("impl:") || ref.label.startsWith("const:")) continue;
            found++;
            Method m = DexHooks.findMethod(cl, ref.cls, ref.name, ref.proto);
            if (m == null) {
                DexHooks.note("стикеры [" + ref.label + "] " + ref.cls + "." + ref.name + ": метод не найден");
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            // В личных сообщениях стикер встречается и в методах без нажатия (заполнение ячейки).
            // Нажатие всегда получает view, по которому тапнули, первым параметром.
            if (ref.label.equals("im-sticker") && (p.length < 2 || !View.class.isAssignableFrom(p[0]))) continue;
            synchronized (hooked) {
                if (!hooked.add(m.toString())) continue;
            }
            try {
                XposedBridge.hookMethod(m, new Tap(ref.label));
                count++;
                DexHooks.note("стикеры [" + ref.label + "]: хук " + ref.cls + "." + ref.name);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        status = count + " хуков из " + found + " найденных методов";
    }

    private static final class Tap extends XC_MethodHook {
        private final String label;

        Tap(String label) {
            this.label = label;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                StickerSaver.tapped(label, param.args);
                StreakSticker.offerAny(param.args, "нажатие [" + label + "]");
            } catch (Throwable t) {
                // Ошибка мода не должна ломать нажатие в TikTok.
            }
        }
    }
}
