package dev.ttmlonger;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Открывает экран флагов, если двумя пальцами подержать экран около 1,2 секунды.
 * Встроить пункт в настройки самого TikTok можно позже, когда будет известна версия.
 */
final class Trigger {
    private static final long HOLD_MS = 1_200L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final float[] start = new float[4];
    private static Runnable pending;
    private static boolean installed;

    private Trigger() {
    }

    static synchronized void install() {
        if (installed) return;
        installed = true;
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            onTouch((Activity) param.thisObject, (MotionEvent) param.args[0]);
                        } catch (Throwable ignored) {
                            // Жест не должен мешать приложению.
                        }
                    }
                });
    }

    private static void onTouch(final Activity activity, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    arm(activity, e);
                } else {
                    cancel();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (pending != null && moved(activity, e)) cancel();
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
            default:
                break;
        }
    }

    private static void arm(final Activity activity, MotionEvent e) {
        cancel();
        for (int i = 0; i < 2; i++) {
            start[i * 2] = e.getX(i);
            start[i * 2 + 1] = e.getY(i);
        }
        pending = new Runnable() {
            @Override
            public void run() {
                pending = null;
                open(activity);
            }
        };
        HANDLER.postDelayed(pending, HOLD_MS);
    }

    private static void cancel() {
        if (pending != null) {
            HANDLER.removeCallbacks(pending);
            pending = null;
        }
    }

    private static boolean moved(Activity activity, MotionEvent e) {
        if (e.getPointerCount() < 2) return true;
        float slop = ViewConfiguration.get(activity).getScaledTouchSlop() * 2f;
        for (int i = 0; i < 2; i++) {
            float dx = e.getX(i) - start[i * 2];
            float dy = e.getY(i) - start[i * 2 + 1];
            if (dx * dx + dy * dy > slop * slop) return true;
        }
        return false;
    }

    private static void open(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            activity.getWindow().getDecorView()
                    .performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            FlagsScreen.show(activity);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
