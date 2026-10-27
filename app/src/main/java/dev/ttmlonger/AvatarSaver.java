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

    private static final int MAX_DOWNLOAD = 20 * 1024 * 1024;

    private AvatarSaver() {
    }

    /**
     * Кнопка «Сохранить аватар»: сначала скачиваем оригинал по ссылке, которую TikTok читал для
     * этого аккаунта (AvatarHooks); не вышло — берём картинку, которая сейчас на экране.
     */
    static void saveAvatar(final Activity act) {
        final String url = AvatarHooks.secure(AvatarHooks.bestUrl());
        final String uid = AvatarHooks.lastUid();
        if (url == null) {
            Diag.note("аватар: ссылка не известна, снимаю картинку с экрана");
            save(act, findAvatarImage(act));
            return;
        }
        toast(act, "Скачиваю аватар…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = download(url);
                    String ext = Media.extensionFor(data);
                    String name = "avatar_" + (uid == null ? "tiktok" : uid.replaceAll("[^0-9A-Za-z_-]", ""))
                            + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                            + "." + ext;
                    String where = Media.saveBytes(act, data, name, Media.mimeFor(ext));
                    Diag.note("аватар сохранён: " + data.length + " байт, " + ext);
                    toast(act, "Аватар сохранён: " + where);
                } catch (Throwable t) {
                    FlagStore.log(t);
                    Diag.note("аватар: скачать не вышло (" + t + "), снимаю картинку с экрана");
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            save(act, findAvatarImage(act));
                        }
                    });
                }
            }
        }, "ttmlonger-avatar-net").start();
    }

    static byte[] download(String address) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            java.io.InputStream in = c.getInputStream();
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_DOWNLOAD) throw new java.io.IOException("файл больше 20 МБ");
                }
                if (out.size() < 512) throw new java.io.IOException("ответ слишком короткий для картинки");
                return out.toByteArray();
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Самая большая почти квадратная картинка на экране: это аватар. */
    static ImageView findAvatarImage(Activity act) {
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        ImageView[] best = new ImageView[1];
        walk(act.getWindow().getDecorView(), screenW, best, 0);
        return best[0];
    }

    private static void walk(android.view.View v, int screenW, ImageView[] best, int depth) {
        if (depth > 100 || v.getVisibility() != android.view.View.VISIBLE
                || Diag.OWN_TAG.equals(v.getTag())) return;
        if (v instanceof ImageView) {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w >= screenW * 0.4f && Math.abs(w - h) <= w * 0.15f
                    && (best[0] == null || (long) w * h > (long) best[0].getWidth() * best[0].getHeight())) {
                best[0] = (ImageView) v;
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), screenW, best, depth + 1);
        }
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

    /** Битмап из Drawable любой обёртки (для других экранов мода). */
    static Bitmap bitmapFromDrawable(Drawable d) {
        return fromDrawable(d, 0);
    }

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
