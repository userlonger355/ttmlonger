package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Полноэкранная страница мода: стрелка назад, крупный заголовок, описание и содержимое. */
abstract class Screen {
    final Activity act;
    final Ui ui;
    Dialog dialog;

    Screen(Activity act) {
        this.act = act;
        this.ui = new Ui(act);
    }

    /** Наполняет страницу под заголовком. */
    abstract void build(LinearLayout body);

    final void open(String title, String description) {
        dialog = new Dialog(act, android.R.style.Theme_Material_NoActionBar);

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Ui.BG);
        body.setPadding(0, 0, 0, ui.dp(40));

        TextView back = ui.text("←", 30, Ui.WHITE, false);
        back.setPadding(ui.dp(27), ui.dp(18), ui.dp(27), ui.dp(18));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = ui.text(title, 32, Ui.WHITE, true);
        t.setPadding(ui.dp(27), ui.dp(8), ui.dp(27), ui.dp(8));
        body.addView(t);

        if (description != null) {
            TextView d = ui.text(description, 16, Ui.MUTED, true);
            d.setPadding(ui.dp(27), ui.dp(4), ui.dp(27), ui.dp(12));
            body.addView(d);
        }

        build(body);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Ui.BG));
            w.setStatusBarColor(Ui.BG);
            w.setNavigationBarColor(Ui.BG);
        }
        dialog.show();
    }

    final LinearLayout.LayoutParams sidePadded() {
        LinearLayout.LayoutParams lp = ui.matchWrap();
        lp.setMargins(ui.dp(7), ui.dp(6), ui.dp(7), ui.dp(6));
        return lp;
    }
}
