package dev.ttmlonger;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.Window;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

/**
 * Снимает полосу окна над панелью в маленький снимок через PixelCopy (Android 8+). Копирование идёт в отдельный
 * снимок, а показывается прежний: два снимка меняются местами, поэтому шейдер никогда не читает недописанное.
 * Всё Android-зависимое лежит здесь, расчёт полосы и размытия — в {@link GlassBackdrop}.
 */
@TargetApi(26)
final class BackdropCopy {
    private final Window win;
    private final Handler h;
    private Bitmap shown;
    private Bitmap spare;
    private int[] sig;
    private double shownSigma = -1;
    private boolean busy;
    private int[] px = new int[0];
    private double sigmaTex;
    private int gen;
    private int fails;
    private String status = "ещё не снимался";
    private boolean lastChanged = true;
    private int shots;
    private int skipped;

    BackdropCopy(Window win, Handler h) {
        this.win = win;
        this.h = h;
    }

    /** Есть ли что показывать. */
    boolean ready() {
        return shown != null && !shown.isRecycled();
    }

    Bitmap bitmap() {
        return shown;
    }

    boolean lastChanged() {
        return lastChanged;
    }

    String status() {
        return status + (shown != null ? ", снимок " + shown.getWidth() + "×" + shown.getHeight() : "") + ", снято "
                + shots + ", без изменений " + skipped;
    }

    boolean busy() {
        return busy;
    }

    /**
     * Запрашивает снимок полосы pl (см. {@link GlassBackdrop#plan}); onChange вызывается, когда картинка обновилась.
     * sigmaPx — сила размытия в пикселях холста: снимок размывается здесь, до показа.
     */
    static SurfaceView findSurface(View root, int[] pl) {
        if (root == null || pl == null) return null;
        return findSurface(root, pl, new int[]{2000}, 0);
    }

    private static SurfaceView findSurface(View v, int[] pl, int[] budget, int depth) {
        if (v == null || --budget[0] < 0 || depth > 40 || !v.isShown()) return null;
        if (v instanceof SurfaceView) {
            SurfaceView s = (SurfaceView) v;
            return sourceRect(s, pl) != null ? s : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                SurfaceView s = findSurface(g.getChildAt(i), pl, budget, depth + 1);
                if (s != null) return s;
            }
        }
        return null;
    }

    /** Координаты полосы в буфере видео, только если вся полоса помещается в видимой поверхности. */
    private static Rect sourceRect(SurfaceView s, int[] pl) {
        if (s == null || !s.isShown() || s.getWidth() <= 0 || s.getHeight() <= 0
                || !s.getHolder().getSurface().isValid()) return null;
        int[] xy = new int[2];
        s.getLocationInWindow(xy);
        int left = pl[0] - xy[0], top = pl[1] - xy[1];
        int right = pl[2] - xy[0], bottom = pl[3] - xy[1];
        if (left < 0 || top < 0 || right > s.getWidth() || bottom > s.getHeight()) return null;
        Rect visible = new Rect();
        if (!s.getLocalVisibleRect(visible) || !visible.contains(left, top, right, bottom)) return null;
        Rect frame = s.getHolder().getSurfaceFrame();
        if (frame.width() <= 0 || frame.height() <= 0) return null;
        double sx = (double) frame.width() / s.getWidth(), sy = (double) frame.height() / s.getHeight();
        return new Rect((int) Math.floor(left * sx), (int) Math.floor(top * sy),
                Math.min(frame.width(), (int) Math.ceil(right * sx)),
                Math.min(frame.height(), (int) Math.ceil(bottom * sy)));
    }

    void request(int[] pl, double sigmaPx, final Runnable onChange) {
        request(pl, sigmaPx, onChange, null);
    }

    void request(int[] pl, double sigmaPx, final Runnable onChange, SurfaceView surface) {
        if (busy || pl == null || win == null) return;
        sigmaTex = sigmaPx / Math.max(1, pl[6]);
        int dw = pl[4];
        int dh = pl[5];
        Bitmap b = spare;
        spare = null;
        if (b == null || b.isRecycled() || b.getWidth() != dw || b.getHeight() != dh) {
            try {
                b = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                status = "не хватило памяти под снимок";
                return;
            }
        }
        final Bitmap dst = b;
        final int g = gen;
        busy = true;
        try {
            PixelCopy.OnPixelCopyFinishedListener listener = new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    if (g != gen) return;
                    busy = false;
                    done(dst, result, onChange);
                }
            };
            Rect src = sourceRect(surface, pl);
            // Штатный PixelCopy: защищённые/недоступные поверхности не обходятся.
            if (src != null) PixelCopy.request(surface, src, dst, listener, h);
            else PixelCopy.request(win, new Rect(pl[0], pl[1], pl[2], pl[3]), dst, listener, h);
        } catch (Throwable t) {
            busy = false;
            fails++;
            spare = dst;
            status = "PixelCopy: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
    }

    private void done(Bitmap dst, int result, Runnable onChange) {
        status = GlassBackdrop.resultName(result);
        if (result != PixelCopy.SUCCESS) {
            fails++;
            spare = dst;
            lastChanged = false;
            return;
        }
        fails = 0;
        shots++;
        int w = dst.getWidth();
        int hh = dst.getHeight();
        if (px.length != w * hh) px = new int[w * hh];
        dst.getPixels(px, 0, w, 0, 0, w, hh);
        int[] now = GlassBackdrop.sample(px, w, hh);
        boolean same = shown != null && shown.getWidth() == w && shown.getHeight() == hh
                && !GlassBackdrop.changed(sig, now) && sigmaTex == shownSigma;
        if (same) {
            skipped++;
            spare = dst;
            lastChanged = false;
            return;
        }
        sig = now;
        shownSigma = sigmaTex;
        GlassBackdrop.blur(px, w, hh, sigmaTex);
        dst.setPixels(px, 0, w, 0, 0, w, hh);
        spare = shown;
        shown = dst;
        lastChanged = true;
        try {
            onChange.run();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    /** Подряд неудачных попыток. */
    int fails() {
        return fails;
    }

    void release() {
        gen++;
        busy = false;
        shown = null;
        spare = null;
        sig = null;
    }
}
