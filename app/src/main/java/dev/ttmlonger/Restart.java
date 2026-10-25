package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/** Часть изменений вступает в силу только после перезапуска TikTok. */
final class Restart {
    private Restart() {
    }

    static void offer(final Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Перезапустить TikTok?")
                .setMessage("Изменения применятся после перезапуска приложения.")
                .setPositiveButton("Перезапустить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        now(a);
                    }
                })
                .setNegativeButton("Позже", null)
                .show();
    }

    static void now(Activity a) {
        try {
            Intent i = a.getPackageManager().getLaunchIntentForPackage(a.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                a.startActivity(i);
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        // Если TikTok сам не открылся, запустите его вручную.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Process.killProcess(Process.myPid());
            }
        }, 350L);
    }
}
