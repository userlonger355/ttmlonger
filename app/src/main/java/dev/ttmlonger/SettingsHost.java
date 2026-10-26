package dev.ttmlonger;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * Блок «Мод → Настройки мода» на странице «Настройки и конфиденциальность».
 *
 * Страница настроек TikTok нарисована на Jetpack Compose: в ней нет ни TextView, ни списка, по
 * которым можно было бы её опознать или куда можно было бы вставить пункт. Поэтому:
 *
 * 1. Страница опознаётся по имени активности (SettingContainerActivity, настоящее имя класса).
 * 2. Блок встаёт над содержимым: контейнер фрагментов активности (его id отдаёт её же метод
 *    getFragmentContainer) заворачивается в вертикальный LinearLayout «блок + контейнер». Сам
 *    контейнер остаётся тем же объектом, так что менеджер фрагментов ничего не замечает.
 * 3. Подстраницы настроек открываются теми же контейнером: когда в нём больше одного видимого
 *    ребёнка, блок прячется (отступ под строку состояния остаётся, иначе подстраница уедет вверх).
 * 4. Отступ под строку состояния блок забирает себе, а странице отдаёт отступы без верхнего:
 *    иначе промежуток нарисовался бы дважды.
 *
 * Любой сбой тихий: страница, устроенная иначе, значит «блока нет», но не вылет TikTok.
 */
final class SettingsHost {
    static final String ACTIVITY = "com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity";

    /** Ключ тега на decor-view: Injector живёт ровно столько же, сколько окно активности. */
    private static final int TAG = 0x7ff0ad02;
    private static final int MAX_TRIES = 80;
    private static final long[] SAMPLE_AT_MS = {700L, 2500L};

    private SettingsHost() {
    }

    /** Эта активность — страница настроек TikTok (или её наследник). */
    static boolean matches(Activity a) {
        for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (ACTIVITY.equals(c.getName())) return true;
        }
        return false;
    }

    static void attach(Activity a) {
        View decor = a.getWindow().getDecorView();
        if (decor.getTag(TAG) != null) return;
        Injector inj = new Injector(a);
        decor.setTag(TAG, inj);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(inj);
        decor.post(inj.tryNow);
    }

    /** Блок на этой активности ведёт хозяин (и не сдался): запасной поиск по тексту не нужен. */
    static boolean handles(Activity a) {
        Injector i = injector(a);
        return i != null && !i.failed;
    }

    /** Строка для диагностики. */
    static String status(Activity a) {
        if (!matches(a)) return "не страница настроек";
        Injector i = injector(a);
        return i == null ? "ещё не запускался" : i.state;
    }

    private static Injector injector(Activity a) {
        Object o = a.getWindow().getDecorView().getTag(TAG);
        return o instanceof Injector ? (Injector) o : null;
    }

    private static final class Injector implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity act;
        final Ui ui;
        final Handler main = new Handler(Looper.getMainLooper());

        LinearLayout column;
        LinearLayout block;
        ViewGroup container;
        ModBlock.Palette palette;
        boolean injected;
        boolean failed;
        boolean folded;
        int inset = -1;
        int tries;
        int samples;
        String state = "ожидает контейнер страницы";

        final Runnable tryNow = new Runnable() {
            @Override
            public void run() {
                onGlobalLayout();
            }
        };

        Injector(Activity a) {
            this.act = a;
            this.ui = new Ui(a);
        }

        @Override
        public void onGlobalLayout() {
            if (failed) return;
            try {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (!injected) {
                    tries++;
                    inject();
                    if (!injected && tries > MAX_TRIES) giveUp("контейнер страницы не найден");
                }
                if (injected) updateFold();
            } catch (Throwable t) {
                giveUp(t.toString());
            }
        }

        private void giveUp(String why) {
            failed = true;
            state = "не удалось: " + why;
            Diag.note("блок в настройках: " + state);
            FlagStore.log("блок в настройках: " + why);
        }

        // ------------------------------------------------------------ вставка

        private void inject() {
            ViewGroup c = findContainer();
            if (c == null || c.getWidth() == 0 || c.getHeight() == 0) return;
            if (!(c.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) c.getParent();
            if (parent.getTag(TAG) != null) return;

            int index = parent.indexOfChild(c);
            ViewGroup.LayoutParams params = c.getLayoutParams();

            palette = initialPalette(c);
            block = ModBlock.build(act, palette);
            column = new LinearLayout(act);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setTag(TAG, Boolean.TRUE);

            parent.removeViewAt(index);
            try {
                column.addView(block, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                column.addView(c, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                parent.addView(column, index, params);
            } catch (Throwable t) {
                // Вернуть всё как было: страница без блока лучше, чем страница без содержимого.
                try {
                    column.removeView(c);
                    if (column.getParent() == parent) parent.removeView(column);
                    if (c.getParent() == null) parent.addView(c, index, params);
                } catch (Throwable ignored) {
                    // дальше ничего не сделать
                }
                throw t;
            }

            container = c;
            inset = statusBar();
            installInsets();
            ModBlock.fold(block, false, inset, ui.dp(6));
            column.requestApplyInsets();
            injected = true;
            state = "добавлен над " + c.getClass().getName() + ", цвет страницы по теме (предварительно)";
            Diag.note("блок в настройках: " + state);
            for (long delay : SAMPLE_AT_MS) {
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sample();
                    }
                }, delay);
            }
        }

        /** Контейнер, в который TikTok кладёт страницы настроек. */
        private ViewGroup findContainer() {
            try {
                Method m = null;
                for (Class<?> k = act.getClass(); k != null && m == null; k = k.getSuperclass()) {
                    try {
                        m = k.getDeclaredMethod("getFragmentContainer");
                    } catch (NoSuchMethodException ignored) {
                        // поднимаемся выше
                    }
                }
                if (m != null) {
                    m.setAccessible(true);
                    Object id = m.invoke(act);
                    if (id instanceof Integer) {
                        View v = act.findViewById((Integer) id);
                        if (v instanceof ViewGroup) return (ViewGroup) v;
                    }
                }
            } catch (Throwable t) {
                if (tries == 1) Diag.note("getFragmentContainer недоступен: " + t);
            }
            // Запасной путь: первое, что активность положила в окно.
            View content = act.findViewById(android.R.id.content);
            if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
                View first = ((ViewGroup) content).getChildAt(0);
                if (first instanceof ViewGroup && first.getTag(TAG) == null) return (ViewGroup) first;
            }
            return null;
        }

        // ------------------------------------------------------------ отступы

        private void installInsets() {
            column.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    inset = Math.max(0, in.getSystemWindowInsetTop());
                    ModBlock.fold(block, folded, inset, ui.dp(6));
                    container.dispatchApplyWindowInsets(in.replaceSystemWindowInsets(
                            in.getSystemWindowInsetLeft(), 0, in.getSystemWindowInsetRight(),
                            in.getSystemWindowInsetBottom()));
                    return in.consumeSystemWindowInsets();
                }
            });
        }

        private int statusBar() {
            try {
                WindowInsets wi = act.getWindow().getDecorView().getRootWindowInsets();
                if (wi != null && wi.getSystemWindowInsetTop() > 0) return wi.getSystemWindowInsetTop();
            } catch (Throwable ignored) {
                // запасной путь ниже
            }
            try {
                int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) return act.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
                // без отступа
            }
            return 0;
        }

        // ------------------------------------------------------------ подстраницы

        private void updateFold() {
            int visible = 0;
            for (int i = 0; i < container.getChildCount(); i++) {
                if (container.getChildAt(i).getVisibility() == View.VISIBLE) visible++;
            }
            boolean want = visible > 1;
            if (want == folded) return;
            folded = want;
            ModBlock.fold(block, folded, inset, ui.dp(6));
            Diag.note(folded ? "блок уступил место подстранице" : "блок вернулся");
        }

        // ------------------------------------------------------------ цвета страницы

        /** Предварительный цвет: фон ближайшего родителя, иначе тема приложения. */
        private ModBlock.Palette initialPalette(View from) {
            for (View v = from; v != null; ) {
                Integer c = solid(v.getBackground());
                if (c != null) return ModBlock.Palette.of(c);
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
            Integer w = solid(act.getWindow().getDecorView().getBackground());
            if (w != null) return ModBlock.Palette.of(w);
            boolean night = (act.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return ModBlock.Palette.of(night ? 0xFF000000 : 0xFFFFFFFF);
        }

        private Integer solid(Drawable d) {
            if (d instanceof ColorDrawable) {
                int c = ((ColorDrawable) d).getColor();
                if ((c >>> 24) == 0xFF) return c;
            }
            return null;
        }

        /**
         * Снимает цвет страницы с экрана: полоска у левого края контейнера, самый частый цвет.
         * Только так можно узнать фон страницы, которую рисует Compose, а не view.
         */
        private void sample() {
            if (failed || !injected || Build.VERSION.SDK_INT < 26) return;
            try {
                View decor = act.getWindow().getDecorView();
                int[] loc = new int[2];
                container.getLocationInWindow(loc);
                final int x = Math.max(0, loc[0]);
                final int y = Math.max(0, loc[1]);
                final int w = Math.min(3, decor.getWidth() - x);
                final int h = Math.min(container.getHeight(), decor.getHeight() - y);
                if (w <= 0 || h < 8) return;
                final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                PixelCopy.request(act.getWindow(), new Rect(x, y, x + w, y + h), bmp,
                        new PixelCopy.OnPixelCopyFinishedListener() {
                            @Override
                            public void onPixelCopyFinished(int result) {
                                if (result != PixelCopy.SUCCESS) return;
                                try {
                                    int[] px = new int[w * h];
                                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                                    applySampled(ModBlock.dominant(px));
                                } catch (Throwable t) {
                                    FlagStore.log(t);
                                }
                            }
                        }, main);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }

        private void applySampled(int color) {
            if (color == 0 || failed || !injected) return;
            samples++;
            ModBlock.Palette np = ModBlock.Palette.of(color);
            if (np.sameAs(palette)) return;
            palette = np;
            int idx = column.indexOfChild(block);
            column.removeViewAt(idx);
            block = ModBlock.build(act, np);
            column.addView(block, idx, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ModBlock.fold(block, folded, inset, ui.dp(6));
            state = "добавлен над " + container.getClass().getName() + ", цвет страницы "
                    + Integer.toHexString(color & 0xFFFFFF) + " (снят с экрана)";
            Diag.note("блок в настройках: " + state);
        }
    }
}
