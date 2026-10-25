package dev.ttmlonger;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RotateDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.widget.ImageView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Сохраняет аватар с экрана его просмотра в галерею.
 *
 * Сначала пробуем достать исходный битмап из Drawable (так сохраняется оригинальное качество и
 * квадрат без круглой обрезки). Если Drawable устроен незнакомо — снимаем картинку с экрана.
 */
final class AvatarSaver {
    private static final int MIN_SIDE = 64;

    private AvatarSaver() {
    }

    static void save(final Activity act, final ImageView iv) {
        if (iv == null) {
            toast(act, "Аватар не найден на экране");
            return;
        }
        Bitmap bmp = null;
        try {
            bmp = fromDrawable(iv.getDrawable(), 0);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        if (bmp != null) {
            write(act, bmp);
            return;
        }
        capture(act, iv);
    }

    // ---------------------------------------------------------------- битмап из Drawable

    private static Bitmap fromDrawable(Drawable d, int depth) {
        if (d == null || depth > 8) return null;

        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (usable(b)) return b;
        }

        // Контейнеры и обёртки (Fresco, Glide и сам Android).
        Drawable cur = d.getCurrent();
        if (cur != null && cur != d) {
            Bitmap b = fromDrawable(cur, depth + 1);
            if (b != null) return b;
        }
        if (d instanceof LayerDrawable) {
            LayerDrawable l = (LayerDrawable) d;
            Bitmap best = null;
            for (int i = 0; i < l.getNumberOfLayers(); i++) {
                Bitmap b = fromDrawable(l.getDrawable(i), depth + 1);
                if (b != null && (best == null || area(b) > area(best))) best = b;
            }
            return best;
        }
        if (d instanceof InsetDrawable) return fromDrawable(((InsetDrawable) d).getDrawable(), depth + 1);
        if (d instanceof ScaleDrawable) return fromDrawable(((ScaleDrawable) d).getDrawable(), depth + 1);
        if (d instanceof ClipDrawable) return fromDrawable(((ClipDrawable) d).getDrawable(), depth + 1);
        if (d instanceof RotateDrawable) return fromDrawable(((RotateDrawable) d).getDrawable(), depth + 1);
        return null;
    }

    private static boolean usable(Bitmap b) {
        return b != null && !b.isRecycled() && b.getWidth() >= MIN_SIDE && b.getHeight() >= MIN_SIDE;
    }

    private static long area(Bitmap b) {
        return (long) b.getWidth() * b.getHeight();
    }

    // ---------------------------------------------------------------- снимок экрана

    private static void capture(final Activity act, final ImageView iv) {
        final int w = iv.getWidth();
        final int h = iv.getHeight();
        if (w < MIN_SIDE || h < MIN_SIDE) {
            toast(act, "Аватар ещё не загрузился");
            return;
        }
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            iv.draw(new Canvas(b));
            write(act, b);
            return;
        } catch (Throwable t) {
            // Аппаратный битмап нельзя нарисовать на обычном холсте — пробуем копию пикселей окна.
            FlagStore.log(t);
        }

        if (Build.VERSION.SDK_INT < 26) {
            toast(act, "Не удалось получить изображение аватара");
            return;
        }
        try {
            int[] loc = new int[2];
            iv.getLocationInWindow(loc);
            final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Rect src = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
            PixelCopy.request(act.getWindow(), src, b, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (result == PixelCopy.SUCCESS) write(act, b);
                    else toast(act, "Не удалось получить изображение аватара");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            FlagStore.log(t);
            toast(act, "Не удалось получить изображение аватара");
        }
    }

    // ---------------------------------------------------------------- запись

    private static void write(final Activity act, final Bitmap src) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap bmp = src;
                    // Config.HARDWARE появился в Android 8: на старых версиях к нему нельзя обращаться.
                    if (Build.VERSION.SDK_INT >= 26 && bmp.getConfig() == Bitmap.Config.HARDWARE) {
                        bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
                        if (bmp == null) throw new IllegalStateException("не удалось скопировать битмап");
                    }
                    String name = "avatar_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
                    toast(act, "Аватар сохранён: " + Media.savePng(act, bmp, name));
                } catch (Throwable t) {
                    FlagStore.log(t);
                    toast(act, "Не удалось сохранить аватар: " + t.getClass().getSimpleName());
                }
            }
        }, "ttmlonger-avatar").start();
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
