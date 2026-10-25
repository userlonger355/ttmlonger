package dev.ttmlonger;

import android.app.Activity;
import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Получение результата startActivityForResult внутри чужого приложения.
 * У TikTok нельзя дописать onActivityResult, поэтому ловим результат хуком на Activity:
 * основной путь — dispatchActivityResult (его вызывает система), запасной — onActivityResult.
 */
final class ActivityResults {
    interface Callback {
        void onResult(int resultCode, Intent data);
    }

    /** Меньше 0x10000: старшие 16 бит зарезервированы под фрагменты. */
    private static final int REQUEST = 0x4E31;

    private static volatile Callback pending;

    private ActivityResults() {
    }

    static void install() {
        // Первые четыре параметра dispatchActivityResult одинаковы во всех версиях Android:
        // (String who, int requestCode, int resultCode, Intent data[, String reason]).
        hook("dispatchActivityResult", 1, 2, 3);
        // Запасной путь на случай, если первый метод не нашёлся: (int, int, Intent).
        hook("onActivityResult", 0, 1, 2);
    }

    static boolean start(Activity act, Intent intent, Callback cb) {
        pending = cb;
        try {
            act.startActivityForResult(intent, REQUEST);
            return true;
        } catch (Throwable t) {
            pending = null;
            FlagStore.log(t);
            return false;
        }
    }

    private static void hook(String method, final int reqIdx, final int resIdx, final int dataIdx) {
        try {
            XposedBridge.hookAllMethods(Activity.class, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object[] a = param.args;
                        if (a == null || a.length <= dataIdx) return;
                        if (!(a[reqIdx] instanceof Integer) || (Integer) a[reqIdx] != REQUEST) return;
                        Callback cb = pending;
                        if (cb == null) return;
                        pending = null;
                        // Это наш запрос: приложению TikTok его показывать не нужно.
                        param.setResult(null);
                        int result = a[resIdx] instanceof Integer ? (Integer) a[resIdx] : Activity.RESULT_CANCELED;
                        Intent data = a[dataIdx] instanceof Intent ? (Intent) a[dataIdx] : null;
                        cb.onResult(result, data);
                    } catch (Throwable t) {
                        FlagStore.log(t);
                    }
                }
            });
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
