package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Небольшая плавающая кнопка поверх экрана TikTok на несколько секунд: отдельное окно, которое не
 * берёт фокус и не мешает нажимать то, что под ним. Показывается из главного потока.
 */
final class Pill {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Dialog current;

    private Pill() {
    }

    /**
     * @param yFraction положение по высоте экрана (0 — верх, 1 — низ)
     * @param ms        через сколько закрыть
     */
    static void show(final Activity act, String text, float yFraction, long ms, final Runnable onTap) {
        if (act == null || act.isFinishing()) return;
        try {
            dismiss();
            float d = act.getResources().getDisplayMetrics().density;
            TextView t = TtStyle.floating(act, text);
            t.setTag(Diag.OWN_TAG);

            final Dialog dlg = new Dialog(act);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dlg.setContentView(t);
            Screen.markOwn(dlg);
            Window w = dlg.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setWindowAnimations(android.R.style.Animation_Toast);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            WindowManager.LayoutParams lp = w.getAttributes();
            // Явный размер: иначе диалог растягивается до минимальной ширины темы и невидимая
            // часть окна перехватывала бы нажатия.
            t.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            lp.width = t.getMeasuredWidth();
            lp.height = t.getMeasuredHeight();
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = Math.round(12 * d);
            lp.y = Math.round(act.getResources().getDisplayMetrics().heightPixels * yFraction);
            w.setAttributes(lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        dlg.dismiss();
                    } catch (Throwable ignored) {
                        // уже закрыта
                    }
                    if (onTap != null) onTap.run();
                }
            });
            dlg.show();
            current = dlg;
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (dlg.isShowing()) dlg.dismiss();
                    } catch (Throwable ignored) {
                        // уже закрыта
                    }
                }
            }, ms);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    static void dismiss() {
        Dialog d = current;
        current = null;
        if (d != null) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
                // уже закрыта
            }
        }
    }
}
