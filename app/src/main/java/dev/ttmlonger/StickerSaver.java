package dev.ttmlonger;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Сохранение стикера из переписки или из комментариев. TikTok такой кнопки не даёт: на листе
 * стикера есть «Поделиться» и «Сохранить» (в избранное), а картинку в галерею не положить.
 *
 * Лист стикера — отдельное окно (Dialog) с картинкой и рядом из двух кнопок под ней. Мод ловит
 * показ любого диалога (Dialog.show), и пока лист наполняется (TikTok подгружает его
 * содержимое не сразу), несколько секунд ищет такую пару «картинка + ряд кнопок». Нашёл —
 * дописывает в ряд третью, квадратную кнопку «⬇». Она сохраняет именно эту картинку: адрес
 * берётся из самого view картинки (обходом его полей), а если адрес не нашёлся или по нему
 * не скачалась картинка, снимается то, что нарисовано на экране.
 *
 * Классы листа в TikTok обфусцированы, поэтому узнаём его по устройству, а не по имени. Если в
 * вашей версии лист устроен иначе, кнопка не появится; «Диагностика» покажет, какой диалог с
 * картинкой видел мод, по этому можно подстроить правило.
 */
final class StickerSaver {
    private static final int TAG_PLACED = 0x7ff0ad05;
    private static final int TAG_POLL = 0x7ff0ad06;
    private static final long POLL_MS = 100L;
    private static final int POLL_TRIES = 30;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int unknownLogged;

    private StickerSaver() {
    }

    static void install() {
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!Prefs.saveStickers || CrashGuard.tripped) return;
                    Dialog d = (Dialog) param.thisObject;
                    if (Screen.isOwn(d) || d.getWindow() == null) return;
                    start(d);
                } catch (Throwable ignored) {
                    // чужие диалоги не должны ломаться из-за мода
                }
            }
        });
    }

    private static void start(final Dialog d) {
        final View decor = d.getWindow().getDecorView();
        Object running = decor.getTag(TAG_POLL);
        long now = android.os.SystemClock.uptimeMillis();
        if (running instanceof Long && now - (Long) running < 400L) return;
        decor.setTag(TAG_POLL, now);
        MAIN.post(new Runnable() {
            int n;
            boolean sawPicture;

            @Override
            public void run() {
                try {
                    if (!d.isShowing()) return;
                    Found f = find(decor);
                    if (f.row != null) {
                        if (f.row.getTag(TAG_PLACED) != null) return;
                        // Кнопки ещё не измерены: придём, когда у них будет высота.
                        if (f.row.getChildAt(0).getHeight() > 0) {
                            add(f.row, f.picture);
                            return;
                        }
                    }
                    sawPicture |= f.bigPicture;
                    if (++n < POLL_TRIES) {
                        MAIN.postDelayed(this, POLL_MS);
                    } else if (sawPicture && unknownLogged++ < 4) {
                        Diag.note("диалог с картинкой, но не лист стикера: " + d.getClass().getName()
                                + ", кнопки: " + labels(decor));
                    }
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
        });
    }

    // ------------------------------------------------------------ поиск листа

    private static final class Found {
        LinearLayout row;
        ImageView picture;
        boolean bigPicture;
    }

    static Found find(View decor) {
        Found f = new Found();
        float d = decor.getResources().getDisplayMetrics().density;
        walk(decor, f, Math.round(96 * d), 0, new int[]{3000});
        return f;
    }

    private static void walk(View v, Found f, int minPic, int depth, int[] budget) {
        if (f.row != null || v == null || depth > 40 || --budget[0] < 0
                || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ImageView && v.getWidth() >= minPic && v.getHeight() >= minPic) f.bigPicture = true;
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        if (g instanceof LinearLayout && ((LinearLayout) g).getOrientation() == LinearLayout.HORIZONTAL
                && looksLikeButtons(g)) {
            ImageView pic = pictureNear(g, minPic);
            if (pic != null) {
                f.row = (LinearLayout) g;
                f.picture = pic;
                return;
            }
        }
        for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), f, minPic, depth + 1, budget);
    }

    /** Две или три видимые кнопки-надписи в ряд. */
    private static boolean looksLikeButtons(ViewGroup g) {
        int visible = 0;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (!(c instanceof TextView) || !c.isClickable()) return false;
            CharSequence t = ((TextView) c).getText();
            if (t == null || t.length() == 0 || t.length() > 30) return false;
            visible++;
        }
        return visible >= 2 && visible <= 3;
    }

    /** Большая картинка рядом с рядом кнопок: у него в родителях, не глубже трёх уровней. */
    private static ImageView pictureNear(View row, int minPic) {
        View child = row;
        for (int up = 0; up < 3 && child.getParent() instanceof ViewGroup; up++) {
            ViewGroup parent = (ViewGroup) child.getParent();
            ImageView best = null;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c == child) continue;
                ImageView pic = firstImage(c, minPic, 0);
                if (pic != null && (best == null || area(pic) > area(best))) best = pic;
            }
            if (best != null) return best;
            child = parent;
        }
        return null;
    }

    private static ImageView firstImage(View v, int minPic, int depth) {
        if (v == null || depth > 6 || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof ImageView) {
            return v.getWidth() >= minPic && v.getHeight() >= minPic ? (ImageView) v : null;
        }
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        ImageView best = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            ImageView p = firstImage(g.getChildAt(i), minPic, depth + 1);
            if (p != null && (best == null || area(p) > area(best))) best = p;
        }
        return best;
    }

    private static long area(View v) {
        return (long) v.getWidth() * v.getHeight();
    }

    private static String labels(View root) {
        StringBuilder b = new StringBuilder();
        collectLabels(root, b, 0, new int[]{2000});
        return b.length() == 0 ? "-" : b.toString();
    }

    private static void collectLabels(View v, StringBuilder b, int depth, int[] budget) {
        if (v == null || depth > 40 || --budget[0] < 0 || b.length() > 160 || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView && v.isClickable()) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                if (b.length() > 0) b.append(" | ");
                b.append(t.length() > 24 ? t.subSequence(0, 24) : t);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectLabels(g.getChildAt(i), b, depth + 1, budget);
        }
    }

    // ------------------------------------------------------------ кнопка

    private static void add(LinearLayout row, final ImageView picture) {
        Context ctx = row.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView first = (TextView) row.getChildAt(0);
        View second = row.getChildAt(1);
        int tall = first.getHeight();
        int gap = Math.max(second.getLeft() - first.getRight(), Math.round(8 * d));

        // Две кнопки делят ряд поровну; если они растянуты не весом, делаем вес, и для
        // третьей остаётся место.
        for (int i = 0; i < row.getChildCount(); i++) {
            View one = row.getChildAt(i);
            if (one.getVisibility() != View.VISIBLE) continue;
            ViewGroup.LayoutParams lp = one.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) lp).weight <= 0f) {
                lp.width = 0;
                ((LinearLayout.LayoutParams) lp).weight = 1f;
                one.setLayoutParams(lp);
            }
        }

        TextView mine = new TextView(ctx);
        mine.setText("⬇");
        mine.setGravity(Gravity.CENTER);
        mine.setTextSize(20);
        mine.setTextColor(first.getCurrentTextColor());
        mine.setContentDescription("Сохранить стикер");
        Drawable ground = null;
        try {
            Drawable theirs = first.getBackground();
            if (theirs != null && theirs.getConstantState() != null) {
                ground = theirs.getConstantState().newDrawable(ctx.getResources()).mutate();
            }
        } catch (Throwable ignored) {
            // нарисуем свою подложку
        }
        if (ground == null) {
            GradientDrawable plain = new GradientDrawable();
            plain.setColor((first.getCurrentTextColor() & 0x00FFFFFF) | 0x1F000000);
            plain.setCornerRadius(4 * d);
            ground = plain;
        }
        mine.setBackground(ground);
        mine.setClickable(true);
        mine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(picture);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tall, tall);
        lp.setMarginStart(gap);
        ViewGroup.LayoutParams fl = first.getLayoutParams();
        if (fl instanceof LinearLayout.LayoutParams) lp.gravity = ((LinearLayout.LayoutParams) fl).gravity;
        row.addView(mine, lp);
        row.setTag(TAG_PLACED, Boolean.TRUE);
        Diag.note("кнопка «Сохранить стикер» добавлена в лист (" + picture.getClass().getName() + ")");
    }

    // ------------------------------------------------------------ сохранение

    private static void save(final ImageView picture) {
        final Context ctx = picture.getContext().getApplicationContext() != null
                ? picture.getContext().getApplicationContext() : picture.getContext();
        // Адрес и картинку с экрана берём на главном потоке, скачиваем в фоне.
        final String url = urlOf(picture);
        final Bitmap shot = bitmapOf(picture);
        Diag.note("стикер: ссылка " + (url == null ? "не найдена" : host(url)) + ", картинка с экрана "
                + (shot == null ? "недоступна" : shot.getWidth() + "x" + shot.getHeight()));
        toast(ctx, "Сохраняю стикер…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                try {
                    if (url != null) {
                        String secure = AvatarHooks.secure(url);
                        if (secure != null) {
                            try {
                                byte[] data = AvatarSaver.download(secure);
                                String ext = Media.imageExtension(data);
                                if (ext != null) {
                                    String where = Media.saveBytes(ctx, data, "sticker_" + stamp + "." + ext,
                                            Media.mimeFor(ext));
                                    toast(ctx, "Стикер сохранён: " + where);
                                    return;
                                }
                            } catch (Throwable t) {
                                FlagStore.log(t);
                            }
                        }
                    }
                    if (shot == null) throw new IllegalStateException("нет картинки");
                    Bitmap bmp = shot;
                    if (android.os.Build.VERSION.SDK_INT >= 26 && bmp.getConfig() == Bitmap.Config.HARDWARE) {
                        bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
                    }
                    toast(ctx, "Стикер сохранён: " + Media.savePng(ctx, bmp, "sticker_" + stamp + ".png"));
                } catch (Throwable t) {
                    FlagStore.log(t);
                    toast(ctx, "Не удалось сохранить стикер: " + t.getClass().getSimpleName());
                }
            }
        }, "ttmlonger-sticker").start();
    }

    private static void toast(final Context ctx, final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                    // нет окна для показа
                }
            }
        });
    }

    private static String host(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Битмап картинки: из Drawable, а если не вышло, рисуем view на холст. */
    static Bitmap bitmapOf(ImageView iv) {
        try {
            Bitmap b = AvatarSaver.bitmapFromDrawable(iv.getDrawable());
            if (b != null) return b;
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            if (iv.getWidth() > 0 && iv.getHeight() > 0) {
                Bitmap b = Bitmap.createBitmap(iv.getWidth(), iv.getHeight(), Bitmap.Config.ARGB_8888);
                iv.draw(new Canvas(b));
                return b;
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return null;
    }

    // ------------------------------------------------------------ адрес картинки

    private static final int MAX_DEPTH = 4;
    private static final int MAX_OBJECTS = 600;

    /**
     * Адрес картинки: обход полей view и его тега. Свои поля у view-картинки TikTok называются
     * по-разному в каждой версии, поэтому смотрим на значения, а не на имена: подходит любая
     * строка-адрес, внутри самого view или объектов, на которые он ссылается; из нескольких
     * берём самую длинную (одна картинка лежит в нескольких размерах, длиннее адрес крупнее).
     */
    static String urlOf(ImageView picture) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        int[] budget = {MAX_OBJECTS};
        String best = null;
        List<Object> roots = new ArrayList<>();
        roots.add(picture);
        try {
            Object tag = picture.getTag();
            if (tag != null) roots.add(tag);
        } catch (Throwable ignored) {
            // без тега
        }
        for (Object r : roots) {
            String u = find(r, seen, budget, 0, r == picture);
            if (u != null && (best == null || u.length() > best.length())) best = u;
        }
        return best;
    }

    /** @param viewRoot true для самого view: у него пропускаются поля классов android.* */
    private static String find(Object o, Map<Object, Boolean> seen, int[] budget, int depth, boolean viewRoot) {
        if (o == null || depth > MAX_DEPTH || budget[0] <= 0) return null;
        if (o instanceof String) {
            String s = (String) o;
            return (s.startsWith("http://") || s.startsWith("https://")) && s.length() < 2048 ? s : null;
        }
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) return null;
        String bestOfList = null;
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                String u = find(item, seen, budget, depth + 1, false);
                if (u != null && (bestOfList == null || u.length() > bestOfList.length())) bestOfList = u;
            }
            return bestOfList;
        }
        String cn = o.getClass().getName();
        if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("kotlin.")) return null;
        if (cn.startsWith("android.") && !viewRoot) return null;
        if (seen.put(o, Boolean.TRUE) != null) return null;
        budget[0]--;

        String best = null;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String kn = k.getName();
            // Поля самого View (родитель, контекст, дети) не раскрываем: это весь экран.
            if (kn.startsWith("android.") || kn.startsWith("java.") || kn.startsWith("androidx.")) break;
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    String u = find(f.get(o), seen, budget, depth + 1, false);
                    if (u != null && (best == null || u.length() > best.length())) best = u;
                } catch (Throwable ignored) {
                    // поле недоступно
                }
            }
        }
        return best;
    }
}
