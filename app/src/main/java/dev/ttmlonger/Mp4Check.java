package dev.ttmlonger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Проверка скачанного файла перед тем, как положить его в галерею. Битый файл возникает, когда по
 * адресу приходит не видео (страница ошибки, список HLS) или загрузка оборвалась на середине.
 * mp4 состоит из блоков «размер + тип»; у целого файла блоки идут друг за другом ровно до конца,
 * среди них есть moov (описание) и mdat (данные).
 */
final class Mp4Check {
    /** Меньше этого размера настоящее видео не бывает. */
    static final long MIN_BYTES = 8 * 1024;

    private static final int MOOV = 0x6D6F6F76;
    private static final int MDAT = 0x6D646174;
    private static final int MOOF = 0x6D6F6F66;

    private Mp4Check() {
    }

    /** Что за формат по первым байтам: «mp4», «webm» или null. */
    static String formatOf(byte[] h, int n) {
        if (h == null || n < 8) return null;
        if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return "mp4";
        if (n >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF
                && (h[3] & 0xFF) == 0xA3) return "webm";
        return null;
    }

    /** null — файл целый; иначе причина, по которой его нельзя сохранять. */
    static String check(File f) {
        long len = f.length();
        if (len < MIN_BYTES) return "файл слишком короткий (" + len + " байт)";
        RandomAccessFile r = null;
        try {
            r = new RandomAccessFile(f, "r");
            byte[] head = new byte[16];
            r.readFully(head);
            String fmt = formatOf(head, head.length);
            if (fmt == null) return looksLikeText(head) ? "вместо видео пришла страница или ошибка"
                    : "это не видео: нет заголовка mp4";
            if (fmt.equals("webm")) return null; // глубже не смотрим

            long pos = 0;
            boolean moov = false;
            boolean mdat = false;
            boolean moof = false;
            int boxes = 0;
            while (pos + 8 <= len && boxes++ < 200_000) {
                r.seek(pos);
                long size = r.readInt() & 0xFFFFFFFFL;
                int type = r.readInt();
                long header = 8;
                if (size == 1) {
                    size = r.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = len - pos;
                }
                if (size < header) return "повреждён заголовок блока на позиции " + pos;
                if (pos + size > len) {
                    return "файл обрезан: блок «" + name(type) + "» занимает " + size
                            + " байт, а осталось " + (len - pos);
                }
                if (type == MOOV) moov = true;
                else if (type == MDAT) mdat = true;
                else if (type == MOOF) moof = true;
                pos += size;
            }
            if (!moov && !moof) return "в файле нет описания видео (moov)";
            if (!mdat) return "в файле нет данных видео (mdat)";
            return null;
        } catch (IOException e) {
            return "не удалось прочитать файл: " + e.getMessage();
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                    // не страшно
                }
            }
        }
    }

    private static boolean looksLikeText(byte[] h) {
        int printable = 0;
        for (byte b : h) {
            int c = b & 0xFF;
            if ((c >= 0x20 && c < 0x7F) || c == '\n' || c == '\r' || c == '\t') printable++;
        }
        return printable >= h.length - 1;
    }

    private static String name(int type) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int b = (type >> (24 - 8 * i)) & 0xFF;
            c[i] = b >= 0x20 && b < 0x7F ? (char) b : '?';
        }
        return new String(c);
    }
}
