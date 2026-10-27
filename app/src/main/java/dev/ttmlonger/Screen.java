package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Полноэкранная страница мода: стрелка назад, крупный заголовок, описание и содержимое. */
abstract class Screen {
    /** Диалоги самого мода: хуки на Dialog.show их не трогают. */
    private static final Set<Dialog> OWN = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Dialog, Boolean>()));

    final Activity act;
    final Ui ui;
    Dialog dialog;

    static boolean isOwn(Dialog d) {
        return OWN.contains(d);
    }

    static void markOwn(Dialog d) {
        OWN.add(d);
    }

    Screen(Activity act) {
        this.act = act;
        this.ui = new Ui(act);
    }

    /** Наполняет страницу под заголовком. */
    abstract void build(LinearLayout body);

    final void open(String title, String description) {
        dialog = new Dialog(act, android.R.style.Theme_Material_NoActionBar);
        markOwn(dialog);

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

    /** Карточка с названием, описанием и переключателем справа. */
    final LinearLayout toggleRow(String title, String desc, boolean initial, ModSwitch.Listener l) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(22), ui.dp(18), ui.dp(18), ui.dp(18));
        card.setBackground(ui.round(Ui.CARD, 24));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.text(title, 21, Ui.WHITE, true));
        TextView d = ui.text(desc, 16, Ui.MUTED, true);
        d.setPadding(0, ui.dp(6), ui.dp(12), 0);
        col.addView(d);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ModSwitch sw = new ModSwitch(act, initial);
        sw.setListener(l);
        card.addView(sw);

        card.setLayoutParams(sidePadded());
        return card;
    }

    final LinearLayout.LayoutParams sidePadded() {
        LinearLayout.LayoutParams lp = ui.matchWrap();
        lp.setMargins(ui.dp(7), ui.dp(6), ui.dp(7), ui.dp(6));
        return lp;
    }
}
