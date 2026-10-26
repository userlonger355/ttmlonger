package dev.ttmlonger;

import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * Следит за экранами TikTok и добавляет в них элементы мода:
 *
 * 1. Страница «Настройки и конфиденциальность» — блок «Мод → Настройки мода».
 * 2. Экран просмотра аватарки — кнопка «Сохранить аватар» над «Поделиться профилем».
 *
 * Нужные экраны находятся по содержимому, а не по именам классов: в TikTok они обфусцированы.
 * Страница настроек — по тексту заголовка, экран аватарки — по большому квадратному ImageView
 * плюс подписи «Поделиться профилем» внизу экрана. Когда признаки пропадают (пользователь ушёл
 * дальше), элементы прячутся, а сдвинутые отступы возвращаются.
 */
final class ScreenWatcher {
    /** Ключ тега на корневом view: Watcher живёт ровно столько же, сколько окно активности. */
    private static final int TAG_KEY = 0x7ff0ad01;
    private static final long CHECK_DELAY_MS = 450L;

    private ScreenWatcher() {
    }

    static void install() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onResumed((Activity) param.thisObject);
            }
        });
    }

    private static WeakReference<Activity> lastSeen;

    /**
     * Вызывается и хуком на Activity.onResume, и жизненным циклом приложения (второй путь
     * работает, даже если хук не встал): повторный вызов безвреден.
     */
    static void onResumed(Activity a) {
        try {
            MainHook.ensureBoot(a);
            Diag.announce(a);
            Activity prev = lastSeen == null ? null : lastSeen.get();
            if (prev != a) {
                lastSeen = new WeakReference<>(a);
                Diag.note("экран: " + a.getClass().getName());
            }
            watch(a);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static void watch(Activity a) {
        View decor = a.getWindow().getDecorView();
        Object existing = decor.getTag(TAG_KEY);
        if (existing instanceof Watcher) {
            ((Watcher) existing).schedule();
            return;
        }
        Watcher w = new Watcher(a);
        decor.setTag(TAG_KEY, w);
        w.start();
    }

    /** Что нашли на экране за один обход. */
    private static final class Scan {
        boolean settingsTitle;
        /** Разные подписи пунктов страницы настроек, найденные на экране. */
        final Set<String> rows = new HashSet<>();
        /** Цвет текста найденной подписи страницы настроек: по нему понятно, светлая тема или тёмная. */
        int sampleColor;
        boolean hasSample;
        View shareLabel;
        ImageView avatar;
        long avatarArea;
    }

    private static final class Watcher implements Runnable, ViewTreeObserver.OnGlobalLayoutListener {
        private final Activity act;
        private final Ui ui;
        private boolean scheduled;

        // Блок «Мод» и сдвиг содержимого под ним.
        private View block;
        private boolean blockLight;
        private boolean lastPage;
        private int lastKey = -1;
        private View root;
        private int origTop;
        private int addedTop;

        // Кнопка «Сохранить аватар».
        private TextView avatarButton;
        private ImageView avatarTarget;

        Watcher(Activity a) {
            this.act = a;
            this.ui = new Ui(a);
        }

        void start() {
            View decor = act.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(this);
            schedule();
        }

        @Override
        public void onGlobalLayout() {
            schedule();
        }

        void schedule() {
            if (scheduled) return;
            scheduled = true;
            act.getWindow().getDecorView().postDelayed(this, CHECK_DELAY_MS);
        }

        @Override
        public void run() {
            scheduled = false;
            try {
                if (act.isFinishing() || act.isDestroyed()) return;
                check();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }

        private void check() {
            View decor = act.getWindow().getDecorView();
            DisplayInfo di = new DisplayInfo(act);
            Scan s = new Scan();
            scan(decor, s, 0, di);

            boolean page = s.settingsTitle || s.rows.size() >= Targets.SETTINGS_ROWS_MIN;
            report(s, page);

            if (page) showSettingsBlock(s.hasSample && luminance(s.sampleColor) < 0.5f);
            else hideSettingsBlock();

            if (s.avatar != null && s.shareLabel != null) showAvatarButton(s);
            else hideAvatarButton();
        }

        /** Записывает в диагностику, что нашли; строка пересобирается, только когда результат изменился. */
        private void report(Scan s, boolean page) {
            int key = (s.settingsTitle ? 1 : 0) | (s.avatar != null ? 2 : 0) | (s.shareLabel != null ? 4 : 0)
                    | (page ? 8 : 0) | (Math.min(s.rows.size(), 15) << 4);
            if (key == lastKey) return;
            lastKey = key;
            Diag.setLastScan("заголовок настроек: " + s.settingsTitle + ", пунктов настроек: " + s.rows.size()
                    + ", страница настроек: " + page + ", аватар: " + (s.avatar != null)
                    + ", «Поделиться профилем»: " + (s.shareLabel != null));
            if (page != lastPage) {
                lastPage = page;
                Diag.note("страница настроек " + (page ? "опознана" : "пропала") + " на " + act.getClass().getName());
            }
        }

        private float luminance(int c) {
            return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
        }

        // ------------------------------------------------------------ поиск по экрану

        private void scan(View v, Scan s, int depth, DisplayInfo di) {
            if (depth > 60 || v == block || v == avatarButton || v.getVisibility() != View.VISIBLE) return;

            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t == null || t.length() == 0 || t.length() > 60) return;
                String n = Targets.norm(t);
                boolean settings = Targets.isSettingsTitle(n);
                boolean share = !settings && Targets.isShareProfile(n);
                boolean row = !settings && !share && Targets.isSettingsRowNorm(n);
                if ((!settings && !share && !row) || !v.isShown()) return;
                Rect r = new Rect();
                if (!v.getGlobalVisibleRect(r)) return;
                if (settings && r.top < di.height * 0.4f) {
                    s.settingsTitle = true;
                    s.sampleColor = ((TextView) v).getCurrentTextColor();
                    s.hasSample = true;
                }
                if (row) {
                    s.rows.add(n);
                    if (!s.hasSample) {
                        s.sampleColor = ((TextView) v).getCurrentTextColor();
                        s.hasSample = true;
                    }
                }
                if (share && r.top > di.height * 0.45f && s.shareLabel == null) s.shareLabel = v;
                return;
            }

            if (v instanceof ImageView) {
                int w = v.getWidth();
                int h = v.getHeight();
                // Большая почти квадратная картинка: аватар на экране просмотра.
                if (w >= di.width * 0.55f && Math.abs(w - h) <= w * 0.12f && v.isShown()) {
                    long area = (long) w * h;
                    if (area > s.avatarArea) {
                        s.avatarArea = area;
                        s.avatar = (ImageView) v;
                    }
                }
                return;
            }

            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) scan(g.getChildAt(i), s, depth + 1, di);
            }
        }

        // ------------------------------------------------------------ блок «Мод»

        private void showSettingsBlock(boolean light) {
            ViewGroup content = act.findViewById(android.R.id.content);
            if (content == null) return;

            if (block == null || blockLight != light) {
                if (block != null && block.getParent() instanceof ViewGroup) {
                    ((ViewGroup) block.getParent()).removeView(block);
                }
                block = buildBlock(light);
                blockLight = light;
            }
            attach(content, block, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

            View first = null;
            for (int i = 0; i < content.getChildCount(); i++) {
                View c = content.getChildAt(i);
                if (c != block && c != avatarButton) {
                    first = c;
                    break;
                }
            }

            // Верхняя часть блока уходит под строку состояния.
            int status = 0;
            WindowInsets wi = content.getRootWindowInsets();
            if (wi != null) status = Math.max(0, wi.getSystemWindowInsetTop());
            block.setPadding(0, status, 0, 0);
            block.setVisibility(View.VISIBLE);

            int width = content.getWidth() > 0 ? content.getWidth()
                    : act.getResources().getDisplayMetrics().widthPixels;
            block.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int want = block.getMeasuredHeight();

            if (first == null) return;
            if (first != root) {
                restorePadding();
                root = first;
                origTop = first.getPaddingTop();
                addedTop = 0;
            } else if (first.getPaddingTop() != origTop + addedTop) {
                // TikTok сам поменял отступ — считаем новое значение исходным.
                origTop = first.getPaddingTop();
                addedTop = 0;
            }
            if (addedTop != want) {
                first.setPadding(first.getPaddingLeft(), origTop + want,
                        first.getPaddingRight(), first.getPaddingBottom());
                addedTop = want;
            }
        }

        private void hideSettingsBlock() {
            if (block != null && block.getVisibility() != View.GONE) block.setVisibility(View.GONE);
            restorePadding();
        }

        private void restorePadding() {
            if (root != null && addedTop != 0 && root.getPaddingTop() == origTop + addedTop) {
                root.setPadding(root.getPaddingLeft(), origTop, root.getPaddingRight(), root.getPaddingBottom());
            }
            addedTop = 0;
        }

        /** @param light true на светлой странице TikTok: блок рисуется светлым, а не чёрным */
        private View buildBlock(boolean light) {
            int bg = light ? 0xFFF4F4F4 : Ui.BG;
            int card = light ? 0xFFFFFFFF : Ui.CARD;
            int ink = light ? 0xFF000000 : Ui.WHITE;

            LinearLayout col = new LinearLayout(act);
            col.setTag(Diag.OWN_TAG);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackgroundColor(bg);
            col.setClickable(true); // не пропускать касания к содержимому под блоком
            col.setPadding(0, 0, 0, ui.dp(6));

            TextView label = ui.text("Мод", 15, Ui.MUTED, true);
            label.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8));
            col.addView(label);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(20), ui.dp(16), ui.dp(16), ui.dp(16));
            row.setBackground(ui.round(card, 20));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ModMenu.show(act);
                }
            });

            TextView gear = ui.text("⚙", 24, Ui.MUTED, false);
            gear.setPadding(0, 0, ui.dp(16), 0);
            row.addView(gear);
            TextView name = ui.text("Настройки мода", 19, ink, true);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(ui.text("›", 28, Ui.MUTED, false));

            LinearLayout.LayoutParams lp = ui.matchWrap();
            lp.setMargins(ui.dp(7), 0, ui.dp(7), 0);
            col.addView(row, lp);
            return col;
        }

        // ------------------------------------------------------------ кнопка «Сохранить аватар»

        private void showAvatarButton(Scan s) {
            ViewGroup content = act.findViewById(android.R.id.content);
            if (content == null) return;

            avatarTarget = s.avatar;
            if (avatarButton == null) avatarButton = buildAvatarButton();

            // Кнопка стоит на 28dp выше подписи «Поделиться профилем».
            int[] contentPos = new int[2];
            int[] labelPos = new int[2];
            content.getLocationInWindow(contentPos);
            s.shareLabel.getLocationInWindow(labelPos);
            int labelTop = labelPos[1] - contentPos[1];
            int bottom = Math.max(0, content.getHeight() - labelTop + ui.dp(28));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = bottom;

            boolean added = attach(content, avatarButton, lp);
            if (!added) {
                ViewGroup.LayoutParams cur = avatarButton.getLayoutParams();
                if (cur instanceof FrameLayout.LayoutParams
                        && ((FrameLayout.LayoutParams) cur).bottomMargin != bottom) {
                    ((FrameLayout.LayoutParams) cur).bottomMargin = bottom;
                    avatarButton.setLayoutParams(cur);
                }
            }
            avatarButton.setVisibility(View.VISIBLE);
        }

        private void hideAvatarButton() {
            if (avatarButton != null && avatarButton.getVisibility() != View.GONE) {
                avatarButton.setVisibility(View.GONE);
            }
        }

        private TextView buildAvatarButton() {
            int accent = Theme.accent();
            TextView b = ui.text("Сохранить аватар", 17, Theme.onColor(accent), false);
            b.setTag(Diag.OWN_TAG);
            b.setGravity(Gravity.CENTER);
            b.setPadding(ui.dp(34), ui.dp(15), ui.dp(34), ui.dp(15));
            b.setBackground(ui.round(accent, 40));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AvatarSaver.save(act, avatarTarget);
                }
            });
            return b;
        }

        // ------------------------------------------------------------ общее

        /**
         * Кладёт view в content последним (поверх остального), если его там ещё нет.
         *
         * @return true, если view был добавлен прямо сейчас
         */
        private boolean attach(ViewGroup content, View v, ViewGroup.LayoutParams lp) {
            ViewGroup parent = v.getParent() instanceof ViewGroup ? (ViewGroup) v.getParent() : null;
            if (parent != content) {
                if (parent != null) parent.removeView(v);
                content.addView(v, lp);
                return true;
            }
            // Если TikTok добавил что-то поверх, возвращаем себя наверх (один раз: дальше мы уже последние).
            if (content.indexOfChild(v) != content.getChildCount() - 1) v.bringToFront();
            return false;
        }
    }

    /** Размер экрана для долей в проверках. */
    private static final class DisplayInfo {
        final int width;
        final int height;

        DisplayInfo(Activity a) {
            width = a.getResources().getDisplayMetrics().widthPixels;
            height = a.getResources().getDisplayMetrics().heightPixels;
        }
    }
}
