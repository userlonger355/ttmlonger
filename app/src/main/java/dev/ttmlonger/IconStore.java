package dev.ttmlonger;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Иконки для ярлыка: свои картинки (files/ttmlonger/icons/*.png, квадрат 512x512)
 * и закрепление ярлыка на рабочем столе.
 *
 * Значок самого TikTok в меню приложений сменить нельзя: для этого нужны псевдонимы
 * activity-alias в манифесте, а LSPatch манифест не меняет. Поэтому создаётся ярлык.
 */
final class IconStore {
    static final int MAX = 12;
    private static final int SIZE = 512;

    private IconStore() {
    }

    // ---------------------------------------------------------------- хранилище

    private static File dir() {
        File base = FlagStore.dir();
        File d = new File(base, "icons");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static boolean validId(String id) {
        return id != null && !id.isEmpty() && id.matches("[A-Za-z0-9_]+");
    }

    /** Идентификаторы своих иконок в порядке добавления. */
    static List<String> ids() {
        List<File> files = new ArrayList<>();
        File[] all = dir().listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().endsWith(".png")) files.add(f);
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int c = Long.compare(a.lastModified(), b.lastModified());
                return c != 0 ? c : a.getName().compareTo(b.getName());
            }
        });
        List<String> out = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            out.add(n.substring(0, n.length() - 4));
        }
        return out;
    }

    static Bitmap load(String id) {
        if (!validId(id)) return null;
        File f = new File(dir(), id + ".png");
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    static String add(Bitmap square) throws IOException {
        String id = "i" + System.currentTimeMillis();
        FileOutputStream os = new FileOutputStream(new File(dir(), id + ".png"));
        try {
            if (!square.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IOException("PNG не записался");
        } finally {
            os.close();
        }
        return id;
    }

    static void delete(String id) {
        if (!validId(id)) return;
        //noinspection ResultOfMethodCallIgnored
        new File(dir(), id + ".png").delete();
    }

    // ---------------------------------------------------------------- чтение картинки

    /** Читает выбранную картинку, поворачивает по EXIF, обрезает по центру до квадрата 512x512. */
    static Bitmap decodeSquare(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("нет данных");
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            in.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("это не изображение");

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > 2048) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bm;
        in = cr.openInputStream(uri);
        if (in == null) throw new IOException("нет данных");
        try {
            bm = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            in.close();
        }
        if (bm == null) throw new IOException("не удалось прочитать изображение");

        int rotation = 0;
        try {
            InputStream exifIn = cr.openInputStream(uri);
            if (exifIn != null) {
                try {
                    int o = new ExifInterface(exifIn).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                    else if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                    else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                } finally {
                    exifIn.close();
                }
            }
        } catch (Throwable ignored) {
            // Нет EXIF — оставляем как есть.
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        }

        int side = Math.min(bm.getWidth(), bm.getHeight());
        Bitmap square = Bitmap.createBitmap(bm, (bm.getWidth() - side) / 2, (bm.getHeight() - side) / 2,
                side, side);
        return Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
    }

    // ---------------------------------------------------------------- ярлык

    /**
     * Просит систему закрепить ярлык на рабочем столе. Пользователь подтверждает окном системы.
     *
     * @param id    пустая строка — стандартная иконка TikTok, иначе своя картинка
     * @param label название ярлыка; пустое — название приложения
     * @return сообщение для пользователя
     */
    static String pin(Activity act, String id, String label) {
        if (Build.VERSION.SDK_INT < 26) return "Для ярлыков нужен Android 8 или новее";
        try {
            ShortcutManager sm = act.getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                return "Лаунчер не поддерживает закрепление ярлыков";
            }
            PackageManager pm = act.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(act.getPackageName());
            if (launch == null) return "Не нашёл, чем запускать TikTok";
            launch.setAction(Intent.ACTION_MAIN);

            Icon icon;
            if (id == null || id.isEmpty()) {
                int res = act.getApplicationInfo().icon;
                if (res != 0) {
                    icon = Icon.createWithResource(act, res);
                } else {
                    icon = Icon.createWithBitmap(drawableToBitmap(pm.getApplicationIcon(act.getApplicationInfo()), 192));
                }
            } else {
                Bitmap square = load(id);
                if (square == null) return "Файл иконки не найден";
                icon = Icon.createWithAdaptiveBitmap(adaptive(square));
            }

            String name = label == null ? "" : label.trim();
            if (name.isEmpty()) name = String.valueOf(pm.getApplicationLabel(act.getApplicationInfo()));

            ShortcutInfo info = new ShortcutInfo.Builder(act, "ttm_" + System.currentTimeMillis())
                    .setShortLabel(name)
                    .setLongLabel(name)
                    .setIcon(icon)
                    .setIntent(launch)
                    .build();
            return sm.requestPinShortcut(info, null)
                    ? "Подтвердите добавление ярлыка в окне системы"
                    : "Не удалось запросить ярлык";
        } catch (Throwable t) {
            FlagStore.log(t);
            return "Не удалось создать ярлык: " + t.getClass().getSimpleName();
        }
    }

    /**
     * Адаптивная иконка 108dp: лаунчер показывает только центральные 72dp и накладывает свою
     * маску, поэтому картинку вписываем в центр, а поля заливаем её средним цветом.
     */
    private static Bitmap adaptive(Bitmap square) {
        final int full = 432;
        final int inner = 320;
        Bitmap out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int avg = Bitmap.createScaledBitmap(square, 1, 1, true).getPixel(0, 0);
        c.drawColor(0xFF000000 | avg);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        int off = (full - inner) / 2;
        c.drawBitmap(square, null, new Rect(off, off, off + inner, off + inner), p);
        return out;
    }

    static Bitmap drawableToBitmap(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }
}
