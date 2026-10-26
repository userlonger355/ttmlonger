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

    /** Что записать в файл: PNG из битмапа или готовые байты. */
    private interface Sink {
        void write(OutputStream os) throws Exception;
    }

    /** Сохраняет PNG и возвращает путь, который можно показать пользователю. */
    static String savePng(Context ctx, final Bitmap bmp, String name) throws Exception {
        return save(ctx, name, "image/png", new Sink() {
            @Override
            public void write(OutputStream os) {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    throw new IllegalStateException("не удалось закодировать PNG");
                }
            }
        });
    }

    /** Сохраняет готовый файл (как есть, без перекодирования) и возвращает путь для показа. */
    static String saveBytes(Context ctx, final byte[] data, String name, String mime) throws Exception {
        return save(ctx, name, mime, new Sink() {
            @Override
            public void write(OutputStream os) throws Exception {
                os.write(data);
            }
        });
    }

    /** Расширение файла по первым байтам: JPEG, PNG, WebP; для остального (и пустого) — jpg. */
    static String extensionFor(byte[] d) {
        if (d == null || d.length < 12) return "jpg";
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        return "jpg";
    }

    static String mimeFor(String ext) {
        if ("png".equals(ext)) return "image/png";
        if ("webp".equals(ext)) return "image/webp";
        return "image/jpeg";
    }

    private static String save(Context ctx, String name, String mime, Sink sink) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TTMLonger");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore вернул null");
            try {
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) throw new IllegalStateException("не открылся поток записи");
                try {
                    sink.write(os);
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
            write(dir, file, sink);
        } catch (Exception e) {
            File alt = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (alt == null) throw e;
            file = new File(alt, name);
            write(alt, file, sink);
        }
        MediaScannerConnection.scanFile(ctx, new String[]{file.getAbsolutePath()},
                new String[]{mime}, null);
        return file.getAbsolutePath();
    }

    private static void write(File dir, File file, Sink sink) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        FileOutputStream os = new FileOutputStream(file);
        try {
            sink.write(os);
        } finally {
            os.close();
        }
    }
}
