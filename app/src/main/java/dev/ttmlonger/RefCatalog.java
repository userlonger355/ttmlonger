package dev.ttmlonger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Встроенный каталог флагов TikTok: ключ, тип и значение по умолчанию для каждого из ~21,5 тыс.
 * флагов из экспорта референс-мода. Он нужен, чтобы экран «Настройки разработчика» показывал всё
 * сразу, а не только то, что приложение уже успело прочитать. Данные лежат в RefCatalogData
 * (gzip + base64 кусками); читаются один раз и только когда открыт экран флагов.
 */
final class RefCatalog {
    /** Одна запись каталога. */
    static final class Entry {
        final String key;
        final int type;
        final boolean json;
        final String def;

        Entry(String key, int type, boolean json, String def) {
            this.key = key;
            this.type = type;
            this.json = json;
            this.def = def;
        }
    }

    private static volatile boolean loaded;
    private static volatile int count;

    private RefCatalog() {
    }

    static boolean loaded() {
        return loaded;
    }

    static int count() {
        return count;
    }

    /** Читает каталог во FlagStore (один раз). Дорого (десятки миллисекунд): вызывать не с главного потока. */
    static synchronized int ensureLoaded() {
        if (loaded) return count;
        try {
            List<Entry> all = parse(gunzip(base64(RefCatalogData.CHUNKS)));
            for (Entry e : all) FlagStore.addKnown(e.key, e.type, e.def, e.json);
            count = all.size();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        loaded = true;
        return count;
    }

    // ------------------------------------------------------------ разбор

    static List<Entry> parse(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>(22_000);
        Map<String, String> cache = new HashMap<>();
        int n = text.length();
        int pos = 0;
        while (pos < n) {
            int end = text.indexOf('\n', pos);
            if (end < 0) end = n;
            Entry e = line(text, pos, end, cache);
            if (e != null) out.add(e);
            pos = end + 1;
        }
        return out;
    }

    private static Entry line(String s, int from, int to, Map<String, String> cache) {
        int t1 = s.indexOf('\t', from);
        if (t1 < 0 || t1 >= to || t1 + 1 >= to) return null;
        String key = s.substring(from, t1);
        char tc = s.charAt(t1 + 1);
        int type;
        boolean json = false;
        switch (tc) {
            case 'b':
                type = FlagStore.BOOL;
                break;
            case 'i':
                type = FlagStore.INT;
                break;
            case 'l':
                type = FlagStore.LONG;
                break;
            case 'f':
                type = FlagStore.FLOAT;
                break;
            case 'd':
                type = FlagStore.DOUBLE;
                break;
            case 's':
                type = FlagStore.STRING;
                break;
            case 'j':
                type = FlagStore.STRING;
                json = true;
                break;
            default:
                return null;
        }
        String def = null;
        int t2 = s.indexOf('\t', t1 + 1);
        if (t2 >= 0 && t2 < to) def = unescape(s.substring(t2 + 1, to));
        if (def != null && def.length() <= 16) {
            String c = cache.get(def);
            if (c == null) {
                cache.put(def, def);
            } else {
                def = c;
            }
        }
        return new Entry(key, type, json, def);
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                b.append(d == 't' ? '\t' : d == 'n' ? '\n' : d == 'r' ? '\r' : d);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------ gzip и base64

    static byte[] gunzip(byte[] gz) throws IOException {
        InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(gz.length * 4);
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Свой base64: java.util.Base64 есть только с Android 8, а минимальная версия мода ниже. */
    static byte[] base64(String[] chunks) {
        int len = 0;
        for (String c : chunks) len += c.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream(len / 4 * 3 + 3);
        int acc = 0;
        int bits = 0;
        for (String c : chunks) {
            for (int i = 0; i < c.length(); i++) {
                char ch = c.charAt(i);
                int v;
                if (ch >= 'A' && ch <= 'Z') v = ch - 'A';
                else if (ch >= 'a' && ch <= 'z') v = ch - 'a' + 26;
                else if (ch >= '0' && ch <= '9') v = ch - '0' + 52;
                else if (ch == '+') v = 62;
                else if (ch == '/') v = 63;
                else continue; // '=' и переводы строк
                acc = (acc << 6) | v;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((acc >> bits) & 0xFF);
                }
            }
        }
        return out.toByteArray();
    }
}
