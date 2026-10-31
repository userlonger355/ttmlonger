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

    /** Корень окна — окно одного из диалогов мода. */
    static boolean isOwnDecor(View root) {
        synchronized (OWN) {
            for (Dialog d : OWN) {
                Window w = d.getWindow();
                if (w != null && w.peekDecorView() == root) return true;
            }
        }
        return false;
    }

    Screen(Activity act) {
        this.act = act;
        this.ui = new Ui(act);
    }

    /** Наполняет страницу под заголовком. */
    abstract void build(LinearLayout body);

    final void open(String title, String description) {
        dialog = new Dialog(act, Ui.pageTheme());
        markOwn(dialog);

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Ui.BG);
        body.setPadding(0, 0, 0, ui.dp(32));

        if (description != null) {
            TextView d = ui.note(description);
            d.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
            body.addView(d);
        }

        build(body);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Шапка как в TikTok: стрелка слева, название по центру; линия появляется при прокрутке.
        FrameLayout bar = new FrameLayout(act);
        bar.setBackgroundColor(Ui.BG);
        TextView t = ui.text(title, 17f, Ui.INK, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tp.setMargins(ui.dp(56), 0, ui.dp(56), 0);
        bar.addView(t, tp);

        FrameLayout back = new FrameLayout(act);
        back.setBackground(ui.ripple());
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        android.widget.ImageView arrow = ui.icon(Icons.CHEVRON_LEFT, Ui.INK, 26);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER);
        back.addView(arrow, ap);
        bar.addView(back, new FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.START | Gravity.CENTER_VERTICAL));

        final View line = ui.divider();
        line.setVisibility(View.INVISIBLE);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                line.setVisibility(y > 0 ? View.VISIBLE : View.INVISIBLE);
            }
        });

        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);
        page.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
        page.addView(line);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        root.addView(page, new FrameLayout.LayoutParams(
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
            if (!Ui.DARK) {
                int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                w.getDecorView().setSystemUiVisibility(flags);
            }
        }
        dialog.show();
    }

    /** Строка с названием, описанием и переключателем справа. */
    final LinearLayout toggleRow(String title, String desc, boolean initial, ModSwitch.Listener l) {
        return ui.toggleRow(-1, title, desc, initial, l);
    }

    /** То же со значком слева. */
    final LinearLayout toggleRow(int icon, String title, String desc, boolean initial, ModSwitch.Listener l) {
        return ui.toggleRow(icon, title, desc, initial, l);
    }
}
