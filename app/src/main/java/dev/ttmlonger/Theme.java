package dev.ttmlonger;

import android.graphics.Color;

/** Цвета самого мода. Акцент мода совпадает с выбранным акцентом TikTok. */
final class Theme {
    static final int DEFAULT_ACCENT = 0xFF00C853;

    private Theme() {
    }

    static int accent() {
        int a = Prefs.accent;
        return a != 0 ? a : DEFAULT_ACCENT;
    }

    /** Чёрный или белый — смотря что читается лучше на заданном фоне. */
    static int onColor(int bg) {
        double lum = (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)) / 255.0;
        return lum > 0.6 ? Color.BLACK : Color.WHITE;
    }
}
