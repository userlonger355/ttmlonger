package dev.ttmlonger;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Сохраняет все известные флаги в JSON-файл в «Загрузках». */
final class Exporter {
    private Exporter() {
    }

    static void exportAll(final Activity activity) {
        toast(activity, "Экспортирую…");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    byte[] data = FlagStore.exportJson(activity.getPackageName()).getBytes(StandardCharsets.UTF_8);
                    String name = "tiktok_flags_"
                            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
                    msg = "Сохранено: " + write(activity, name, data);
                } catch (Throwable e) {
                    FlagStore.log(e);
                    msg = "Не удалось экспортировать: " + e.getClass().getSimpleName();
                }
                toast(activity, msg);
            }
        }, "ttmlonger-export");
        t.start();
    }

    private static String write(Activity a, String name, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = a.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore вернул null");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IllegalStateException("не открылся поток записи");
            try {
                os.write(data);
            } finally {
                os.close();
            }
            return "Загрузки/" + name;
        }
        File dir = a.getExternalFilesDir(null);
        if (dir == null) throw new IllegalStateException("нет внешнего хранилища");
        File f = new File(dir, name);
        FileOutputStream os = new FileOutputStream(f);
        try {
            os.write(data);
        } finally {
            os.close();
        }
        return f.getAbsolutePath();
    }

    private static void toast(final Activity a, final String msg) {
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
