package dev.ttmlonger;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.SystemClock;
import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Открывает «Настройки мода», если двумя пальцами подержать экран около 1,2 секунды.
 * Запасной вход на случай, когда блок «Мод» на странице настроек TikTok не появился.
 */
final class Trigger {
    private static final long HOLD_MS = 1_200L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final float[] start = new float[4];
    private static Runnable pending;
    private static boolean installed;
    private static Runnable homeHold;
    private static WeakReference<Activity> homeOwner = new WeakReference<>(null);
    private static float homeX, homeY;
    private static boolean homeConsumed, cancellingHome;

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
                            Activity a = (Activity) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (cancellingHome) return;
                            if (homeTouch(a, event)) {
                                param.setResult(true);
                                return;
                            }
                            onTouch(a, event);
                            if (FeedGestures.touch(a, event)) param.setResult(true);
                        } catch (Throwable ignored) {
                            // Жест не должен мешать приложению.
                        }
                    }
                });
        installDialogs();
    }

    /**
     * Жест в диалогах TikTok (например, в панели комментариев, если она окно): Activity их
     * касания не получает, поэтому ловим и в Dialog.
     */
    private static void installDialogs() {
        try {
            XposedHelpers.findAndHookMethod(android.app.Dialog.class, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                android.app.Dialog d = (android.app.Dialog) param.thisObject;
                                if (Screen.isOwn(d)) return;
                                Activity a = d.getOwnerActivity();
                                if (a == null) a = ScreenWatcher.top();
                                if (a != null) onTouch(a, (MotionEvent) param.args[0]);
                            } catch (Throwable ignored) {
                                // Жест не должен мешать приложению.
                            }
                        }
                    });
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static void cancelHome() {
        if (homeHold != null) HANDLER.removeCallbacks(homeHold);
        homeHold = null;
    }

    private static boolean isHomeLabel(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("home") || s.equals("главная") || s.equals("главная страница");
    }

    private static boolean homeLabel(View v, int depth) {
        if (v == null || depth > 5 || v.getVisibility() != View.VISIBLE) return false;
        if (isHomeLabel(v.getContentDescription())) return true;
        if (v instanceof TextView && isHomeLabel(((TextView) v).getText())) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                if (homeLabel(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    private static View homeAt(Activity activity, float rawX, float rawY) {
        View root = activity.getWindow().getDecorView();
        int[] xy = new int[2];
        root.getLocationOnScreen(xy);
        if (rawY < xy[1] + root.getHeight() * .80f) return null;
        BarFinder.Found f = BarFinder.find(root);
        if (f == null || !f.bar.isShown()) return null;
        for (int i = 0; i < f.tabs.size(); i++) {
            View v = f.tabs.get(i);
            if (i == f.create || !v.isShown() || !homeLabel(v, 0)) continue;
            v.getLocationOnScreen(xy);
            if (rawX >= xy[0] && rawX < xy[0] + v.getWidth()
                    && rawY >= xy[1] && rawY < xy[1] + v.getHeight()) return v;
        }
        return null;
    }

    /** Обычный тап идёт в TikTok; удержание Главной отменяет нативный жест и открывает меню. */
    private static boolean homeTouch(final Activity a, MotionEvent e) {
        if (cancellingHome) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelHome();
            homeConsumed = false;
            homeOwner = new WeakReference<>(a);
            if (CrashGuard.tripped || !a.hasWindowFocus()) return false;
            final View home = homeAt(a, e.getRawX(), e.getRawY());
            if (home == null) return false;
            homeX = e.getX();
            homeY = e.getY();
            final long down = e.getDownTime();
            homeHold = () -> {
                homeHold = null;
                if (homeOwner.get() != a || CrashGuard.tripped || a.isFinishing() || a.isDestroyed()
                        || !a.hasWindowFocus() || !home.isShown() || !home.isAttachedToWindow()
                        || !homeLabel(home, 0) || home.getRootView() != a.getWindow().peekDecorView()) return;
                MotionEvent cancelEvent = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL, homeX, homeY, 0);
                cancellingHome = true;
                try {
                    a.dispatchTouchEvent(cancelEvent);
                } finally {
                    cancellingHome = false;
                    cancelEvent.recycle();
                }
                cancel();
                homeConsumed = true;
                open(a);
            };
            HANDLER.postDelayed(homeHold, Math.max(150L, ViewConfiguration.getLongPressTimeout() - 40L));
        } else if (homeOwner.get() != a) {
            cancelHome();
            homeConsumed = false;
        } else if (homeConsumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) homeConsumed = false;
            return true;
        } else if (homeHold != null) {
            int slop = ViewConfiguration.get(a).getScaledTouchSlop();
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - homeX, dy = e.getY() - homeY;
                if (dx * dx + dy * dy > slop * slop) cancelHome();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                cancelHome();
            }
        }
        return false;
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
            case MotionEvent.ACTION_UP:
                cancel();
                // Лист стикера открывается сразу после касания: смотрим, не появился ли он.
                StickerSaver.touched();
                break;
            case MotionEvent.ACTION_POINTER_UP:
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
            ModMenu.show(activity);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
