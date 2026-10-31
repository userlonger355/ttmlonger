package dev.ttmlonger;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Значки мода: в приложениях TikTok нет ресурсов мода, поэтому значки рисуются кодом. Это линейные
 * значки на сетке 24×24 с закруглёнными концами, как в самом TikTok. Описание значка (строка ниже)
 * разбирается чистым кодом без Android, поэтому его можно отрисовать и проверить на обычной JVM.
 *
 * Команды (числа через пробел): M x y, L x y, C x1 y1 x2 y2 x y, Z (замкнуть), S (обвести контур),
 * F (залить контур), O cx cy r (круг-контур), D cx cy r (круг-заливка), R x y w h r (скруглённый
 * прямоугольник-контур).
 */
final class Icons {
    static final int GLOBE = 0;
    static final int PALETTE = 1;
    static final int DOWNLOAD = 2;
    static final int VIDEO = 3;
    static final int STICKER = 4;
    static final int FLAME = 5;
    static final int IMAGE = 6;
    static final int MIC = 7;
    static final int FLAG = 8;
    static final int SLIDERS = 9;
    static final int CHEVRON_RIGHT = 10;
    static final int CHEVRON_LEFT = 11;
    static final int CHECK = 12;
    static final int CLOCK = 13;
    static final int REFRESH = 14;
    static final int TRASH = 15;
    static final int CLOSE = 16;
    static final int SEARCH = 17;
    static final int COUNT = 18;

    static final String[] NAMES = {"globe", "palette", "download", "video", "sticker", "flame", "image",
            "mic", "flag", "sliders", "chevron-right", "chevron-left", "check", "clock", "refresh", "trash",
            "close", "search"};

    /** Описания значков в порядке констант. */
    private static final String[] DATA = {
            // GLOBE: круг, меридиан и две параллели
            "O 12 12 9  M 12 3 C 8.2 7 8.2 17 12 21 S  M 12 3 C 15.8 7 15.8 17 12 21 S  M 3 12 L 21 12 S"
                    + "  M 4.6 7.6 C 9 9.2 15 9.2 19.4 7.6 S  M 4.6 16.4 C 9 14.8 15 14.8 19.4 16.4 S",
            // PALETTE: круг, правая половина залита
            "O 12 12 9  M 12 3 C 16.97 3 21 7.03 21 12 C 21 16.97 16.97 21 12 21 Z F",
            // DOWNLOAD: стрелка вниз и лоток
            "M 12 4 L 12 15 S  M 7.2 10.6 L 12 15.4 L 16.8 10.6 S  M 5 19.5 L 19 19.5 S",
            // VIDEO: экран и треугольник «играть»
            "R 3 5.5 18 13 3.2  M 10.2 9.3 L 15 12 L 10.2 14.7 Z F",
            // STICKER: лицо с улыбкой
            "O 12 12 9  D 9.2 10.4 1.1  D 14.8 10.4 1.1  M 8.2 14.2 C 9.3 16.4 14.7 16.4 15.8 14.2 S",
            // FLAME: пламя
            "M 12 3 C 12.6 6.2 16.8 8.6 16.8 13.4 C 16.8 17.2 14.8 20.4 12 20.4 C 9.2 20.4 7.2 17.6 7.2 14.4"
                    + " C 7.2 12.4 8 11 9.2 9.8 C 9.6 11.2 10.2 11.8 11 12 C 10.4 9 10.6 5.6 12 3 Z S",
            // IMAGE: рамка, солнце и горы
            "R 3 5 18 14 3.2  O 8.6 9.8 1.6  M 3.6 17 L 9.2 12.6 L 13 15.6 L 16 13.4 L 20.4 17 S",
            // MIC: капсула, дуга и подставка
            "R 9 3 6 11 3  M 5.8 11 C 5.8 14.4 8.6 17.2 12 17.2 C 15.4 17.2 18.2 14.4 18.2 11 S  M 12 17.2 L 12 21 S"
                    + "  M 8.6 21 L 15.4 21 S",
            // FLAG: древко и полотнище
            "M 6 21 L 6 3.4 S  M 6 4.6 L 18 4.6 L 15 8.8 L 18 13 L 6 13 Z S",
            // SLIDERS: три линии с ползунками
            "M 4 7 L 6.6 7 S  M 11.4 7 L 20 7 S  O 9 7 2.4  M 4 12 L 12.6 12 S  M 17.4 12 L 20 12 S  O 15 12 2.4"
                    + "  M 4 17 L 5.6 17 S  M 10.4 17 L 20 17 S  O 8 17 2.4",
            // CHEVRON_RIGHT
            "M 9 5 L 16 12 L 9 19 S",
            // CHEVRON_LEFT
            "M 15 5 L 8 12 L 15 19 S",
            // CHECK
            "M 4.8 12.6 L 9.8 17.6 L 19.4 7.2 S",
            // CLOCK
            "O 12 12 9  M 12 7 L 12 12.2 L 15.6 14.4 S",
            // REFRESH: две дуги со стрелками
            "M 20 12 C 20 16.4 16.4 20 12 20 C 9.4 20 7.2 18.8 5.6 17 S  M 4 12 C 4 7.6 7.6 4 12 4 C 14.6 4 16.8 5.2"
                    + " 18.4 7 S  M 18.8 3 L 18.8 7.4 L 14.4 7.4 S  M 5.2 21 L 5.2 16.6 L 9.6 16.6 S",
            // TRASH: крышка, корзина и две полоски
            "M 4 7 L 20 7 S  M 9 7 L 9 4.4 L 15 4.4 L 15 7 S  M 6.2 7 L 7.2 20 L 16.8 20 L 17.8 7 S"
                    + "  M 10.2 11 L 10.2 16.4 S  M 13.8 11 L 13.8 16.4 S",
            // CLOSE: крестик
            "M 6.2 6.2 L 17.8 17.8 S  M 17.8 6.2 L 6.2 17.8 S",
            // SEARCH: лупа
            "O 10.8 10.8 6.6  M 15.6 15.6 L 20.2 20.2 S",
    };

    private Icons() {
    }

    /** Куда рисовать значок: Android Path или любой другой холст. */
    interface Sink {
        void move(float x, float y);

        void line(float x, float y);

        void cubic(float x1, float y1, float x2, float y2, float x, float y);

        void close();

        /** Закончить контур: обвести (fill=false) или залить (fill=true). */
        void flush(boolean fill);

        void circle(float cx, float cy, float r, boolean fill);

        void roundRect(float x, float y, float w, float h, float r);
    }

    /** Разбирает описание значка и выдаёт команды в Sink. Координаты на сетке 24×24. */
    static void parse(int id, Sink s) {
        String[] t = DATA[id].trim().split("\\s+");
        int i = 0;
        while (i < t.length) {
            String c = t[i++];
            switch (c) {
                case "M":
                    s.move(f(t[i]), f(t[i + 1]));
                    i += 2;
                    break;
                case "L":
                    s.line(f(t[i]), f(t[i + 1]));
                    i += 2;
                    break;
                case "C":
                    s.cubic(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5]));
                    i += 6;
                    break;
                case "Z":
                    s.close();
                    break;
                case "S":
                    s.flush(false);
                    break;
                case "F":
                    s.flush(true);
                    break;
                case "O":
                    s.circle(f(t[i]), f(t[i + 1]), f(t[i + 2]), false);
                    i += 3;
                    break;
                case "D":
                    s.circle(f(t[i]), f(t[i + 1]), f(t[i + 2]), true);
                    i += 3;
                    break;
                case "R":
                    s.roundRect(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]));
                    i += 5;
                    break;
                default:
                    throw new IllegalArgumentException("значок " + id + ": команда " + c);
            }
        }
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    /** Значок заданного цвета; размер берётся из границ при отрисовке. */
    static Drawable of(int id, int color) {
        return new IconDrawable(id, color);
    }

    /** Толщина линии на сетке 24. */
    static final float STROKE = 1.9f;

    private static final class IconDrawable extends Drawable implements Sink {
        private final int id;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private Canvas canvas;
        private float k = 1f;

        IconDrawable(int id, int color) {
            this.id = id;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            int save = c.save();
            c.translate(b.left, b.top);
            k = Math.min(b.width(), b.height()) / 24f;
            stroke.setStrokeWidth(STROKE * k);
            canvas = c;
            path.reset();
            parse(id, this);
            c.restoreToCount(save);
        }

        @Override
        public void move(float x, float y) {
            path.moveTo(x * k, y * k);
        }

        @Override
        public void line(float x, float y) {
            path.lineTo(x * k, y * k);
        }

        @Override
        public void cubic(float x1, float y1, float x2, float y2, float x, float y) {
            path.cubicTo(x1 * k, y1 * k, x2 * k, y2 * k, x * k, y * k);
        }

        @Override
        public void close() {
            path.close();
        }

        @Override
        public void flush(boolean f) {
            canvas.drawPath(path, f ? fill : stroke);
            path.reset();
        }

        @Override
        public void circle(float cx, float cy, float r, boolean f) {
            canvas.drawCircle(cx * k, cy * k, r * k, f ? fill : stroke);
        }

        @Override
        public void roundRect(float x, float y, float w, float h, float r) {
            rect.set(x * k, y * k, (x + w) * k, (y + h) * k);
            canvas.drawRoundRect(rect, r * k, r * k, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            fill.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }
    }
}
