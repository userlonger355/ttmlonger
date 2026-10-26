package dev.ttmlonger;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Кнопка «Сохранить аватар» на экране, где аватарка открыта на весь экран. Экран опознаётся по
 * имени активности (настоящему, не обфусцированному), а кнопка кладётся прямо в окно активности:
 * у окна всегда есть content-view, и всё, что в него добавлено, лежит поверх прежнего.
 */
final class AvatarScreen {
    private static final String[] SCREENS = {
            "com.ss.android.ugc.profile.business.ur.enlarge.EnlargeAvatarActivity",
            "com.ss.android.ugc.profile.business.ur.enlarge.EnlargeAvatarOptActivity",
    };
    private static final int TAG = 0x7ff0ad03;

    private AvatarScreen() {
    }

    static boolean matches(Activity a) {
        for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName();
            for (String s : SCREENS) {
                if (s.equals(n)) return true;
            }
            // Переименование внутри пакета: имя вида ...enlarge.EnlargeAvatar*
            if (n.contains(".enlarge.") && n.substring(n.lastIndexOf('.') + 1).startsWith("EnlargeAvatar")) {
                return true;
            }
        }
        return false;
    }

    static void attach(final Activity act) {
        try {
            ViewGroup content = act.findViewById(android.R.id.content);
            if (content == null || content.getTag(TAG) != null) return;
            content.setTag(TAG, Boolean.TRUE);

            Ui ui = new Ui(act);
            int accent = Theme.accent();
            TextView b = ui.text("Сохранить аватар", 16, Theme.onColor(accent), true);
            b.setTag(Diag.OWN_TAG);
            b.setGravity(Gravity.CENTER);
            b.setPadding(ui.dp(26), ui.dp(13), ui.dp(26), ui.dp(13));
            b.setBackground(ui.round(accent, 40));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AvatarSaver.saveAvatar(act);
                }
            });

            // Чуть ниже середины: у самого низа кнопка уходит под собственные элементы экрана.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.74f);
            content.addView(b, lp);
            Diag.note("кнопка «Сохранить аватар» добавлена на " + act.getClass().getName()
                    + "; " + AvatarHooks.status());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
