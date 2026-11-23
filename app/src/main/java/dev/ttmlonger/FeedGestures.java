package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;

final class FeedGestures {
    private static final String[] KEYS = {"double", "hold", "left"};
    private static final String[] TITLES = {"Двойное нажатие", "Долгое нажатие", "Свайп влево"};
    private static final String[] ACTIONS = {"Как в TikTok", "Лайк", "Комментарии", "Избранное", "Поделиться"};
    private static final FeedButtons.Button[] BUTTONS = {null, FeedButtons.Button.LIKE, FeedButtons.Button.COMMENT,
            FeedButtons.Button.FAVORITE, FeedButtons.Button.SHARE};
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable hold;
    private static float x, y, tapX, tapY;
    private static long previousTap;
    private static boolean tracking, consumed, cancelling;
    private static java.lang.ref.WeakReference<Activity> owner = new java.lang.ref.WeakReference<>(null);

    static void settings(InterfaceScreen screen, LinearLayout body) {
        for (int i = 0; i < KEYS.length; i++) {
            final int index = i;
            LinearLayout row = screen.ui.menuRow(-1, TITLES[i], null, ACTIONS[setting(i)], null);
            row.setOnClickListener(v -> {
                AlertDialog d = new AlertDialog.Builder(screen.act, Ui.dialogTheme()).setTitle(TITLES[index])
                        .setSingleChoiceItems(ACTIONS, setting(index), (dlg, which) -> {
                            InterfaceHooks.prefs.edit().putInt("gesture_" + KEYS[index], which).apply();
                            TextView value = Ui.valueOf(row);
                            if (value != null) value.setText(ACTIONS[which]);
                            dlg.dismiss();
                        }).setNegativeButton("Отмена", null).create();
                Screen.markOwn(d);
                d.show();
                Ui.style(d);
            });
            body.addView(row);
        }
    }

    private static int setting(int i) {
        if (InterfaceHooks.prefs == null) return 0;
        int n = InterfaceHooks.prefs.getInt("gesture_" + KEYS[i], 0);
        return n > 0 && n < ACTIONS.length ? n : 0;
    }

    private static void stopHold() {
        if (hold != null) handler.removeCallbacks(hold);
        hold = null;
    }

    private static void cancelNative(Activity a, long down) {
        MotionEvent e = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0);
        cancelling = true;
        try { a.dispatchTouchEvent(e); } finally { cancelling = false; e.recycle(); }
    }

    static boolean touch(Activity a, MotionEvent e) {
        if (cancelling || CrashGuard.tripped) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopHold();
            tracking = consumed = false;
            if (setting(0) + setting(1) + setting(2) == 0) return false;
            View root = a.getWindow().getDecorView();
            x = e.getX();
            y = e.getY();
            // Keep captions, navigation, seek bar and the action rail on their native touch path.
            if (!a.hasWindowFocus() || x < root.getWidth() * .06f || x > root.getWidth() * .77f
                    || y < root.getHeight() * .20f || y > root.getHeight() * .68f
                    || !FeedButtons.inFeed(a)) { previousTap = 0; return false; }
            if (owner.get() != a) previousTap = 0;
            owner = new java.lang.ref.WeakReference<>(a);
            int slop = ViewConfiguration.get(a).getScaledDoubleTapSlop();
            if (previousTap > 0 && e.getEventTime() - previousTap <= ViewConfiguration.getDoubleTapTimeout()
                    && Math.hypot(x - tapX, y - tapY) < slop && setting(0) > 0) {
                previousTap = 0;
                consumed = FeedButtons.click(a, BUTTONS[setting(0)]);
                if (consumed) return true;
            }
            tracking = true;
            long down = e.getDownTime();
            if (setting(1) > 0) {
                hold = () -> {
                    int which = setting(1);
                    if (!tracking || which == 0 || !a.hasWindowFocus()) return;
                    View button = FeedButtons.clickTarget(a, BUTTONS[which]);
                    if (button == null) return;
                    cancelNative(a, down);
                    consumed = true;
                    button.performClick();
                };
                handler.postDelayed(hold, Math.max(150, ViewConfiguration.getLongPressTimeout() - 40));
            }
        } else if (consumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                tracking = consumed = false;
                stopHold();
            }
            return true;
        } else if (tracking) {
            float dx = e.getX() - x, dy = e.getY() - y;
            int slop = ViewConfiguration.get(a).getScaledTouchSlop();
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL || owner.get() != a) {
                tracking = false;
                previousTap = 0;
                stopHold();
            } else if (action == MotionEvent.ACTION_MOVE && Math.hypot(dx, dy) > slop) {
                stopHold();
                if (Math.abs(dy) > Math.abs(dx)) { tracking = false; previousTap = 0; }
                else if (setting(2) > 0 && dx < -slop * 5 && -dx > Math.abs(dy) * 2) {
                    View button = FeedButtons.clickTarget(a, BUTTONS[setting(2)]);
                    if (button != null) {
                        cancelNative(a, e.getDownTime());
                        consumed = true;
                        previousTap = 0;
                        button.performClick();
                        return true;
                    }
                }
            } else if (action == MotionEvent.ACTION_UP) {
                stopHold();
                tracking = false;
                previousTap = Math.hypot(dx, dy) <= slop && e.getEventTime() - e.getDownTime()
                        < ViewConfiguration.getLongPressTimeout() ? e.getEventTime() : 0;
                tapX = x;
                tapY = y;
            }
        }
        return false;
    }
}
