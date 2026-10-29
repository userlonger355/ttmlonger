package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Когда TikTok сам запрашивает адрес скачивания видео с запретом, на несколько секунд показывается
 * кнопка «Сохранить через мод». Её скачивание проверяет файл и не оставляет битых. Встроенное
 * сохранение TikTok при этом идёт как обычно.
 */
final class NativeOffer {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SHOW_MS = 10_000L;
    private static final long QUIET_MS = 5_000L;
    private static volatile Object lastVideo;
    private static volatile long lastAt;
    private static volatile int shown;

    private NativeOffer() {
    }

    static int shown() {
        return shown;
    }

    /** Из любого потока. */
    static void maybe(final Object video) {
        if (video == null) return;
        long now = SystemClock.uptimeMillis();
        if (video == lastVideo && now - lastAt < QUIET_MS) return;
        lastVideo = video;
        lastAt = now;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Activity act = ScreenWatcher.top();
                    if (act == null) return;
                    final Context app = act.getApplicationContext() != null ? act.getApplicationContext() : act;
                    shown++;
                    Diag.note("запрет скачивания: предложена кнопка «Сохранить через мод»");
                    Pill.show(act, "↓  Сохранить через мод", 0.40f, SHOW_MS, new Runnable() {
                        @Override
                        public void run() {
                            VideoTracker.Entry e = VideoTracker.entryFor(video);
                            if (e == null) {
                                e = new VideoTracker.Entry(null);
                                e.video = video;
                            }
                            VideoSaver.start(app, e);
                        }
                    });
                } catch (Throwable t) {
                    FlagStore.log(t);
                }
            }
        });
    }
}
