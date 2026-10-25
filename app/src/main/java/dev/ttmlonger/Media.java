package dev.ttmlonger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Сохранение картинок в галерею (Pictures/TTMLonger). */
final class Media {
    private Media() {
    }

    /** Сохраняет PNG и возвращает путь, который можно показать пользователю. */
    static String savePng(Context ctx, Bitmap bmp, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TTMLonger");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore вернул null");
            try {
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) throw new IllegalStateException("не открылся поток записи");
                try {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                        throw new IllegalStateException("не удалось закодировать PNG");
                    }
                } finally {
                    os.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } catch (Exception e) {
                cr.delete(uri, null, null);
                throw e;
            }
            return "Pictures/TTMLonger/" + name;
        }

        // Android 9 и старше: обычный файл. Нет разрешения на запись — кладём в папку приложения.
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "TTMLonger");
        File file = new File(dir, name);
        try {
            write(dir, file, bmp);
        } catch (Exception e) {
            File alt = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (alt == null) throw e;
            file = new File(alt, name);
            write(alt, file, bmp);
        }
        MediaScannerConnection.scanFile(ctx, new String[]{file.getAbsolutePath()},
                new String[]{"image/png"}, null);
        return file.getAbsolutePath();
    }

    private static void write(File dir, File file, Bitmap bmp) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        FileOutputStream os = new FileOutputStream(file);
        try {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new IllegalStateException("не удалось закодировать PNG");
            }
        } finally {
            os.close();
        }
    }
}
