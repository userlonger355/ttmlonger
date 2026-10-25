package dev.ttmlonger;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;

/**
 * Защита от крашей: если приложение три раза подряд умерло вскоре после запуска,
 * пользовательские значения отключаются, пока их не включат вручную.
 *
 * Запуск считается неудачным, если процесс не дожил до STABLE_AFTER_MS. Это же значит,
 * что три раза подряд закрыть приложение в первые секунды после старта тоже сработает как краш.
 */
final class CrashGuard {
    static final int LIMIT = 3;
    private static final long STABLE_AFTER_MS = 15_000L;

    /** true — переопределения сейчас не применяются. */
    static volatile boolean tripped;

    private static File file;
    private static int crashes;
    private static int generation;

    private CrashGuard() {
    }

    static synchronized void start(File dir) {
        file = new File(dir, "guard.json");
        boolean wasLaunching = false;
        boolean wasTripped = false;
        int count = 0;
        try {
            if (file.exists()) {
                JSONObject o = new JSONObject(FlagStore.readFile(file));
                wasLaunching = o.optBoolean("launching");
                wasTripped = o.optBoolean("tripped");
                count = o.optInt("crashes");
            }
        } catch (Throwable ignored) {
            // Повреждённый файл считаем чистым состоянием.
        }
        if (wasLaunching) count++;
        if (count >= LIMIT) wasTripped = true;
        crashes = count;
        tripped = wasTripped;
        arm();
    }

    /** Пользователь нажал «Применить снова». */
    static synchronized void reenable() {
        crashes = 0;
        tripped = false;
        arm();
    }

    static int crashes() {
        return crashes;
    }

    private static void arm() {
        write(true);
        final int mine = ++generation;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                markStable(mine);
            }
        }, STABLE_AFTER_MS);
    }

    private static synchronized void markStable(int mine) {
        if (mine != generation) return;
        if (!tripped) crashes = 0;
        write(false);
    }

    private static void write(boolean launching) {
        try {
            JSONObject o = new JSONObject();
            o.put("launching", launching);
            o.put("crashes", crashes);
            o.put("tripped", tripped);
            FlagStore.writeAtomic(file, o.toString());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
