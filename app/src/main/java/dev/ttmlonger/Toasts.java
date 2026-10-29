package dev.ttmlonger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Всплывающие подсказки из любого потока. */
final class Toasts {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Toasts() {
    }

    static void show(final Context ctx, final String msg) {
        if (ctx == null) return;
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
}
