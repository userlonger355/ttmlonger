package dev.ttmlonger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Разбор dex-файлов TikTok без загрузки классов.
 *
 * Имена внутренних классов TikTok обфусцированы и меняются в каждой версии (читатель флагов
 * называется X.09tK в одной сборке и как-то иначе в следующей). Зато остаётся то, что
 * обфускатор не трогает: типы параметров, их порядок и строки в коде. По ним этот разбор и ищет:
 *
 *  1. «читатель флагов» TikTok: класс, у которого есть по методу на тип значения
 *     (boolean, int, long, float, double, String), каждый принимает ключ-строку, перед которой
 *     стоит число (область действия);
 *  2. методы, в которые код передаёт строку-ключ флага (например "profile_bg_in_allow_list"):
 *     находятся по const-string и ближайшему за ним invoke с этим регистром;
 *  3. методы нажатия на стикер: по интерфейсу, который реализует класс, и по точной сигнатуре.
 *
 * Класс ничего не знает про Android: он работает на обычной JVM, поэтому его можно проверить
 * на настоящем dex без телефона.
 */
final class DexScan {
    /** Меняется при любом изменении правил поиска: старый кэш тогда не используется. */
    static final int VERSION = 3;

    private DexScan() {
    }

    // ---------------------------------------------------------------- входные и выходные данные

    /** Откуда брать dex: по одному, чтобы в памяти лежал только текущий. */
    interface Source {
        int count();

        String name(int i);

        byte[] read(int i) throws IOException;
    }

    static final class Options {
        /** Ключи флагов, которые нас интересуют. */
        String[] anchors = new String[0];
        /** Дескриптор интерфейса, который реализует класс обработчика нажатий на стикер. */
        String clickInterface;
        /** Дескриптор типа стикера в личных сообщениях. */
        String stickerTemplate;
        /** {метка, точная сигнатура метода}, например {"comment-tap", "(Landroid/view/View;...)V"}. */
        String[][] shapes = new String[0][];
        /**
         * {метка, дескриптор интерфейса}: классы, которые его реализуют, попадают в результат как
         * Ref("impl:метка", класс, "", ""). Нужно, когда интерфейс назван по-настоящему, а реализует
         * его обфусцированный класс (служба огоньков).
         */
        String[][] interfaces = new String[0][];
        /**
         * Строки, которые встречаются в коде как const-string, например имена констант enum:
         * метод, где строка найдена, попадает в результат как Ref("const:строка", класс, метод, сигнатура).
         */
        String[] consts = new String[0];
        /**
         * Имена методов, которые не обфусцированы (например, getFavouriteStickersFlow): каждый
         * найденный метод попадает в результат как Ref("method:имя", класс, имя, сигнатура).
         */
        String[] methods = new String[0];
        /** Момент (System.currentTimeMillis), после которого разбор прекращается; 0 — без лимита. */
        long deadline;
    }

    /** Класс, похожий на читателя флагов TikTok. */
    static final class ReaderClass {
        final String cls;
        final int methods;
        final int kinds;

        ReaderClass(String cls, int methods, int kinds) {
            this.cls = cls;
            this.methods = methods;
            this.kinds = kinds;
        }
    }

    /** Метод, в который передаётся строка-ключ одного из искомых флагов. */
    static final class Anchored {
        final String cls;
        final String name;
        final String proto;
        final boolean isStatic;
        final int keyParam;
        final Set<String> keys = new TreeSet<>();
        int uses;

        Anchored(String cls, String name, String proto, boolean isStatic, int keyParam) {
            this.cls = cls;
            this.name = name;
            this.proto = proto;
            this.isStatic = isStatic;
            this.keyParam = keyParam;
        }

        String id() {
            return cls + "." + name + proto;
        }
    }

    /** Метод, найденный по форме или по интерфейсу. */
    static final class Ref {
        final String label;
        final String cls;
        final String name;
        final String proto;

        Ref(String label, String cls, String name, String proto) {
            this.label = label;
            this.cls = cls;
            this.name = name;
            this.proto = proto;
        }

        String id() {
            return label + " " + cls + "." + name + proto;
        }
    }

    static final class Result {
        final List<ReaderClass> readers = new ArrayList<>();
        final List<Anchored> anchored = new ArrayList<>();
        final List<Ref> refs = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int dexes;
        int classes;
        int methods;
        long ms;
        /** Сколько const-string с искомыми ключами встретилось. */
        int anchorStrings;

        List<Ref> refs(String label) {
            List<Ref> out = new ArrayList<>();
            for (Ref r : refs) {
                if (r.label.equals(label)) out.add(r);
            }
            return out;
        }

        String summary() {
            return dexes + " dex, " + classes + " классов, " + methods + " методов, " + ms + " мс; читателей "
                    + readers.size() + ", методов с ключами " + anchored.size() + ", найденных по форме " + refs.size();
        }

        // Простой текстовый формат: строки через \n, поля через \t.
        String serialize(String key) {
            StringBuilder sb = new StringBuilder();
            sb.append("dexscan\t").append(VERSION).append('\n');
            sb.append("key\t").append(key).append('\n');
            sb.append("stat\t").append(dexes).append('\t').append(classes).append('\t').append(methods)
                    .append('\t').append(ms).append('\t').append(anchorStrings).append('\n');
            for (ReaderClass r : readers) {
                sb.append("reader\t").append(r.cls).append('\t').append(r.methods).append('\t')
                        .append(r.kinds).append('\n');
            }
            for (Anchored a : anchored) {
                sb.append("anchor\t").append(a.cls).append('\t').append(a.name).append('\t').append(a.proto)
                        .append('\t').append(a.isStatic ? 1 : 0).append('\t').append(a.keyParam)
                        .append('\t').append(a.uses).append('\t').append(join(a.keys)).append('\n');
            }
            for (Ref r : refs) {
                sb.append("ref\t").append(r.label).append('\t').append(r.cls).append('\t').append(r.name)
                        .append('\t').append(r.proto).append('\n');
            }
            for (String n : notes) sb.append("note\t").append(n.replace('\n', ' ').replace('\t', ' ')).append('\n');
            return sb.toString();
        }

        /** Разбирает сохранённый результат; null, если формат другой или ключ не совпал. */
        static Result parse(String text, String expectKey) {
            if (text == null) return null;
            Result r = new Result();
            String[] lines = text.split("\n");
            boolean head = false;
            boolean keyOk = false;
            try {
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    String[] f = line.split("\t", -1);
                    switch (f[0]) {
                        case "dexscan":
                            if (Integer.parseInt(f[1]) != VERSION) return null;
                            head = true;
                            break;
                        case "key":
                            keyOk = f.length > 1 && f[1].equals(expectKey);
                            break;
                        case "stat":
                            r.dexes = Integer.parseInt(f[1]);
                            r.classes = Integer.parseInt(f[2]);
                            r.methods = Integer.parseInt(f[3]);
                            r.ms = Long.parseLong(f[4]);
                            r.anchorStrings = Integer.parseInt(f[5]);
                            break;
                        case "reader":
                            r.readers.add(new ReaderClass(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3])));
                            break;
                        case "anchor": {
                            Anchored a = new Anchored(f[1], f[2], f[3], "1".equals(f[4]), Integer.parseInt(f[5]));
                            a.uses = Integer.parseInt(f[6]);
                            if (f.length > 7 && !f[7].isEmpty()) Collections.addAll(a.keys, f[7].split(","));
                            r.anchored.add(a);
                            break;
                        }
                        case "ref":
                            r.refs.add(new Ref(f[1], f[2], f[3], f[4]));
                            break;
                        case "note":
                            r.notes.add(f.length > 1 ? f[1] : "");
                            break;
                        default:
                            break;
                    }
                }
            } catch (RuntimeException bad) {
                return null;
            }
            return head && keyOk ? r : null;
        }

        private static String join(Set<String> s) {
            StringBuilder sb = new StringBuilder();
            for (String x : s) {
                if (sb.length() > 0) sb.append(',');
                sb.append(x);
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- запуск

    static Result run(Source src, Options o) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        Map<String, Anchored> anchored = new LinkedHashMap<>();
        Map<String, ReaderClass> readers = new LinkedHashMap<>();
        Map<String, Ref> refs = new LinkedHashMap<>();
        for (int i = 0; i < src.count(); i++) {
            if (o.deadline != 0 && System.currentTimeMillis() > o.deadline) {
                res.notes.add("лимит времени: разобрано " + i + " из " + src.count() + " dex");
                break;
            }
            try {
                byte[] bytes = src.read(i);
                Dex d = new Dex(bytes, o);
                d.scan(res, anchored, readers, refs);
                res.dexes++;
            } catch (Throwable t) {
                res.notes.add(src.name(i) + ": " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? " " + t.getMessage() : ""));
            }
        }
        res.readers.addAll(readers.values());
        Collections.sort(res.readers, new Comparator<ReaderClass>() {
            @Override
            public int compare(ReaderClass a, ReaderClass b) {
                if (a.kinds != b.kinds) return b.kinds - a.kinds;
                return b.methods - a.methods;
            }
        });
        res.anchored.addAll(anchored.values());
        Collections.sort(res.anchored, new Comparator<Anchored>() {
            @Override
            public int compare(Anchored a, Anchored b) {
                return b.uses - a.uses;
            }
        });
        res.refs.addAll(refs.values());
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    // ---------------------------------------------------------------- таблица длин инструкций Dalvik

    /** Длина инструкции в 16-битных словах по её коду операции. */
    private static final int[] LEN = new int[256];

    static {
        for (int i = 0; i < 256; i++) LEN[i] = 1;
        LEN[0x02] = 2; LEN[0x03] = 3;                 // move/from16, move/16
        LEN[0x05] = 2; LEN[0x06] = 3;                 // move-wide/from16, /16
        LEN[0x08] = 2; LEN[0x09] = 3;                 // move-object/from16, /16
        LEN[0x13] = 2; LEN[0x14] = 3; LEN[0x15] = 2;  // const/16, const, const/high16
        LEN[0x16] = 2; LEN[0x17] = 3; LEN[0x18] = 5; LEN[0x19] = 2;
        LEN[0x1a] = 2; LEN[0x1b] = 3; LEN[0x1c] = 2;  // const-string, /jumbo, const-class
        LEN[0x1f] = 2; LEN[0x20] = 2;                 // check-cast, instance-of
        LEN[0x22] = 2; LEN[0x23] = 2;                 // new-instance, new-array
        LEN[0x24] = 3; LEN[0x25] = 3; LEN[0x26] = 3;  // filled-new-array(/range), fill-array-data
        LEN[0x29] = 2; LEN[0x2a] = 3;                 // goto/16, goto/32
        LEN[0x2b] = 3; LEN[0x2c] = 3;                 // packed-switch, sparse-switch
        for (int i = 0x2d; i <= 0x3d; i++) LEN[i] = 2; // cmp*, if-*, if-*z
        for (int i = 0x44; i <= 0x6d; i++) LEN[i] = 2; // aget/aput, iget/iput, sget/sput
        for (int i = 0x6e; i <= 0x72; i++) LEN[i] = 3; // invoke-*
        for (int i = 0x74; i <= 0x78; i++) LEN[i] = 3; // invoke-*/range
        for (int i = 0x90; i <= 0xaf; i++) LEN[i] = 2; // binop
        for (int i = 0xd0; i <= 0xe2; i++) LEN[i] = 2; // binop/lit16, /lit8
        LEN[0xfa] = 4; LEN[0xfb] = 4;                 // invoke-polymorphic(/range)
        LEN[0xfc] = 3; LEN[0xfd] = 3;                 // invoke-custom(/range)
        LEN[0xfe] = 2; LEN[0xff] = 2;                 // const-method-handle, const-method-type
    }

    /** Окно (в инструкциях) между const-string и invoke, в котором ключ считается аргументом вызова. */
    private static final int WINDOW = 12;
    private static final int PENDING = 8;

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    // ---------------------------------------------------------------- один dex

    private static final class Dex {
        final byte[] b;
        final Options o;
        final int strN, strOff, typN, typOff, proN, proOff, mthN, mthOff, clsN, clsOff;
        /** Курсор для чтения ULEB128 из class_data. Строковые помощники его не трогают. */
        int pos;

        // Заполняется в prepare().
        int stringType = -1;
        int clickType = -1;
        int templateType = -1;
        /** Для каждого типа: номер интерфейса из Options.interfaces или -1; null, если интерфейсы не нужны. */
        int[] ifaceOf;
        boolean[] isConst;
        String[] constName;
        /** Для каждой строки: искомое имя метода или null; null вместо массива, если искать нечего. */
        String[] methodName;
        /** Заполняется scanCode: найденные в методе искомые строки. */
        List<String> constHits;
        char[] typeFirst;
        byte[] readerKind;      // 0 — не читатель, иначе 1 + тип значения (0..5)
        boolean[] hasTemplate;
        int[] shapeOf;
        boolean[] isAnchor;
        String[] anchorName;
        final int[] pReg = new int[PENDING];
        final int[] pStr = new int[PENDING];
        final int[] pAt = new int[PENDING];

        Dex(byte[] b, Options o) throws IOException {
            if (b.length < 0x70 || b[0] != 'd' || b[1] != 'e' || b[2] != 'x' || b[3] != '\n') {
                throw new IOException("не dex");
            }
            this.b = b;
            this.o = o;
            strN = u32(0x38);
            strOff = u32(0x3c);
            typN = u32(0x40);
            typOff = u32(0x44);
            proN = u32(0x48);
            proOff = u32(0x4c);
            mthN = u32(0x58);
            mthOff = u32(0x5c);
            clsN = u32(0x60);
            clsOff = u32(0x64);
            check(strOff, strN, 4);
            check(typOff, typN, 4);
            check(proOff, proN, 12);
            check(mthOff, mthN, 8);
            check(clsOff, clsN, 32);
        }

        private void check(int off, int n, int size) throws IOException {
            if (n < 0 || off < 0 || (long) off + (long) n * size > b.length) throw new IOException("битый заголовок");
        }

        // ---- чтение

        int u16(int off) {
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
        }

        int u32(int off) {
            return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16)
                    | ((b[off + 3] & 0xff) << 24);
        }

        int uleb() {
            int r = 0;
            int shift = 0;
            while (true) {
                int x = b[pos++] & 0xff;
                r |= (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return r;
                shift += 7;
                if (shift > 35) throw new IllegalStateException("uleb");
            }
        }

        /** Начало байтов строки (после её длины). */
        int strStart(int idx) {
            int p = u32(strOff + 4 * idx);
            while ((b[p] & 0x80) != 0) p++;
            return p + 1;
        }

        boolean strIs(int idx, byte[] want) {
            int p = strStart(idx);
            if (p + want.length >= b.length) return false;
            for (int i = 0; i < want.length; i++) {
                if (b[p + i] != want[i]) return false;
            }
            return b[p + want.length] == 0;
        }

        String str(int idx) {
            if (idx < 0 || idx >= strN) return "";
            int start = strStart(idx);
            int end = start;
            boolean ascii = true;
            while (end < b.length && b[end] != 0) {
                if (b[end] < 0) ascii = false;
                end++;
            }
            if (ascii) return new String(b, start, end - start, LATIN1);
            StringBuilder sb = new StringBuilder(end - start);
            int i = start;
            while (i < end) {
                int c = b[i++] & 0xff;
                if (c < 0x80) {
                    sb.append((char) c);
                } else if ((c & 0xe0) == 0xc0 && i < end) {
                    sb.append((char) (((c & 0x1f) << 6) | (b[i++] & 0x3f)));
                } else if ((c & 0xf0) == 0xe0 && i + 1 < end) {
                    int c2 = b[i++] & 0x3f;
                    int c3 = b[i++] & 0x3f;
                    sb.append((char) (((c & 0x0f) << 12) | (c2 << 6) | c3));
                } else {
                    sb.append('?');
                }
            }
            return sb.toString();
        }

        String typeDesc(int t) {
            if (t < 0 || t >= typN) return "";
            return str(u32(typOff + 4 * t));
        }

        int[] paramsOf(int pro) {
            int off = u32(proOff + 12 * pro + 8);
            if (off == 0) return new int[0];
            int n = u32(off);
            if (n < 0 || n > 255) return new int[0];
            int[] r = new int[n];
            for (int i = 0; i < n; i++) r[i] = u16(off + 4 + 2 * i);
            return r;
        }

        String protoDesc(int pro) {
            StringBuilder sb = new StringBuilder("(");
            for (int t : paramsOf(pro)) sb.append(typeDesc(t));
            sb.append(')').append(typeDesc(u32(proOff + 12 * pro + 4)));
            return sb.toString();
        }

        // ---- подготовка таблиц

        private boolean prim(int typeIdx) {
            if (typeIdx < 0 || typeIdx >= typN) return false;
            char c = typeFirst[typeIdx];
            return c == 'Z' || c == 'B' || c == 'S' || c == 'C' || c == 'I' || c == 'J' || c == 'F' || c == 'D';
        }

        private void prepare() {
            byte[] sString = "Ljava/lang/String;".getBytes(LATIN1);
            byte[] sClick = o.clickInterface == null ? null : o.clickInterface.getBytes(LATIN1);
            byte[] sTemplate = o.stickerTemplate == null ? null : o.stickerTemplate.getBytes(LATIN1);
            typeFirst = new char[typN];
            for (int t = 0; t < typN; t++) {
                int s = u32(typOff + 4 * t);
                typeFirst[t] = (char) (b[strStart(s)] & 0xff);
                if (typeFirst[t] != 'L') continue;
                if (stringType < 0 && strIs(s, sString)) stringType = t;
                else if (sClick != null && clickType < 0 && strIs(s, sClick)) clickType = t;
                else if (sTemplate != null && templateType < 0 && strIs(s, sTemplate)) templateType = t;
            }

            if (o.interfaces.length > 0) {
                byte[][] want = new byte[o.interfaces.length][];
                for (int i = 0; i < want.length; i++) want[i] = o.interfaces[i][1].getBytes(LATIN1);
                for (int t = 0; t < typN; t++) {
                    if (typeFirst[t] != 'L') continue;
                    int s = u32(typOff + 4 * t);
                    for (int i = 0; i < want.length; i++) {
                        if (strIs(s, want[i])) {
                            if (ifaceOf == null) {
                                ifaceOf = new int[typN];
                                java.util.Arrays.fill(ifaceOf, -1);
                            }
                            ifaceOf[t] = i;
                            break;
                        }
                    }
                }
            }

            // Метки форм, сгруппированные по «короткой» записи (shorty): так дешёвый предварительный отбор.
            Map<String, List<Integer>> byShorty = new HashMap<>();
            for (int i = 0; i < o.shapes.length; i++) {
                String sh = shorty(o.shapes[i][1]);
                if (sh == null) continue;
                List<Integer> l = byShorty.get(sh);
                if (l == null) byShorty.put(sh, l = new ArrayList<>());
                l.add(i);
            }
            readerKind = new byte[proN];
            if (templateType >= 0) hasTemplate = new boolean[proN];
            if (!byShorty.isEmpty()) {
                shapeOf = new int[proN];
                java.util.Arrays.fill(shapeOf, -1);
            }
            for (int p = 0; p < proN; p++) {
                int[] params = paramsOf(p);
                int ret = u32(proOff + 12 * p + 4);
                readerKind[p] = kindOf(params, ret);
                if (hasTemplate != null) {
                    for (int t : params) {
                        if (t == templateType) {
                            hasTemplate[p] = true;
                            break;
                        }
                    }
                }
                if (shapeOf != null) {
                    List<Integer> cand = byShorty.get(str(u32(proOff + 12 * p)));
                    if (cand != null) {
                        String d = protoDesc(p);
                        for (int s : cand) {
                            if (o.shapes[s][1].equals(d)) {
                                shapeOf[p] = s;
                                break;
                            }
                        }
                    }
                }
            }

            if (o.anchors.length > 0) {
                int[] first = new int[256];
                for (String a : o.anchors) first[a.charAt(0) & 0xff]++;
                byte[][] want = new byte[o.anchors.length][];
                for (int i = 0; i < want.length; i++) want[i] = o.anchors[i].getBytes(LATIN1);
                for (int s = 0; s < strN; s++) {
                    int p = strStart(s);
                    if (first[b[p] & 0xff] == 0) continue;
                    for (int i = 0; i < want.length; i++) {
                        if (b[p] == want[i][0] && strIs(s, want[i])) {
                            if (isAnchor == null) {
                                isAnchor = new boolean[strN];
                                anchorName = new String[strN];
                            }
                            isAnchor[s] = true;
                            anchorName[s] = o.anchors[i];
                            break;
                        }
                    }
                }
            }

            if (o.methods.length > 0) {
                int[] first = new int[256];
                byte[][] want = new byte[o.methods.length][];
                for (int i = 0; i < want.length; i++) {
                    want[i] = o.methods[i].getBytes(LATIN1);
                    first[want[i][0] & 0xff]++;
                }
                for (int s = 0; s < strN; s++) {
                    int p = strStart(s);
                    if (first[b[p] & 0xff] == 0) continue;
                    for (int i = 0; i < want.length; i++) {
                        if (b[p] == want[i][0] && strIs(s, want[i])) {
                            if (methodName == null) methodName = new String[strN];
                            methodName[s] = o.methods[i];
                            break;
                        }
                    }
                }
            }

            if (o.consts.length > 0) {
                int[] first = new int[256];
                byte[][] want = new byte[o.consts.length][];
                for (int i = 0; i < want.length; i++) {
                    want[i] = o.consts[i].getBytes(LATIN1);
                    first[want[i][0] & 0xff]++;
                }
                for (int s = 0; s < strN; s++) {
                    int p = strStart(s);
                    if (first[b[p] & 0xff] == 0) continue;
                    for (int i = 0; i < want.length; i++) {
                        if (b[p] == want[i][0] && strIs(s, want[i])) {
                            if (isConst == null) {
                                isConst = new boolean[strN];
                                constName = new String[strN];
                            }
                            isConst[s] = true;
                            constName[s] = o.consts[i];
                            break;
                        }
                    }
                }
            }
        }

        /**
         * Подходит ли форма метода под «читателя флагов»: 3–6 параметров, первый — число
         * (область действия), среди остальных одна строка-ключ (или две, если метод возвращает
         * строку: ключ и значение по умолчанию), прочие параметры — числа.
         *
         * @return 0 — не подходит, иначе 1 + тип результата (boolean, int, long, float, double, String)
         */
        private byte kindOf(int[] params, int ret) {
            if (params.length < 3 || params.length > 6 || ret < 0 || ret >= typN) return 0;
            int kind;
            char r = typeFirst[ret];
            if (ret == stringType) kind = 5;
            else if (r == 'Z') kind = 0;
            else if (r == 'I') kind = 1;
            else if (r == 'J') kind = 2;
            else if (r == 'F') kind = 3;
            else if (r == 'D') kind = 4;
            else return 0;
            if (!prim(params[0])) return 0;
            int strings = 0;
            for (int t : params) {
                if (t == stringType) strings++;
                else if (!prim(t)) return 0;
            }
            if (strings < 1 || strings > 2 || (strings == 2 && kind != 5)) return 0;
            return (byte) (kind + 1);
        }

        /** Короткая запись сигнатуры «(Lx;Z)V» → «VLZ»; null, если разобрать нельзя. */
        private static String shorty(String desc) {
            int close = desc.indexOf(')');
            if (desc.charAt(0) != '(' || close < 0) return null;
            StringBuilder sb = new StringBuilder();
            sb.append(shortyOne(desc.substring(close + 1)));
            int i = 1;
            while (i < close) {
                int start = i;
                while (desc.charAt(i) == '[') i++;
                if (desc.charAt(i) == 'L') i = desc.indexOf(';', i) + 1;
                else i++;
                if (i <= start) return null;
                sb.append(shortyOne(desc.substring(start, i)));
            }
            return sb.toString();
        }

        private static char shortyOne(String d) {
            char c = d.charAt(0);
            return c == '[' ? 'L' : c;
        }

        // ---- проход по классам

        void scan(Result res, Map<String, Anchored> anchored, Map<String, ReaderClass> readers,
                  Map<String, Ref> refs) {
            prepare();
            Map<Integer, Anchored> local = isAnchor != null ? new HashMap<Integer, Anchored>() : null;

            for (int ci = 0; ci < clsN; ci++) {
                if ((ci & 0x3ff) == 0 && o.deadline != 0 && System.currentTimeMillis() > o.deadline) {
                    res.notes.add("лимит времени внутри dex");
                    break;
                }
                int def = clsOff + 32 * ci;
                int dataOff = u32(def + 24);
                if (dataOff == 0 || dataOff >= b.length) continue;
                res.classes++;

                boolean clicks = false;
                int ifaceOff = u32(def + 12);
                if (clickType >= 0 && ifaceOff != 0) {
                    int n = u32(ifaceOff);
                    for (int k = 0; k < n && k < 64; k++) {
                        if (u16(ifaceOff + 4 + 2 * k) == clickType) {
                            clicks = true;
                            break;
                        }
                    }
                }

                if (ifaceOf != null && ifaceOff != 0) {
                    int n = u32(ifaceOff);
                    for (int k = 0; k < n && k < 64; k++) {
                        int want = ifaceOf[u16(ifaceOff + 4 + 2 * k)];
                        if (want >= 0) {
                            Ref r = new Ref("impl:" + o.interfaces[want][0], typeDesc(u32(def)), "", "");
                            if (!refs.containsKey(r.id())) refs.put(r.id(), r);
                        }
                    }
                }

                pos = dataOff;
                int sf = uleb();
                int inf = uleb();
                int dm = uleb();
                int vm = uleb();
                for (int i = 0; i < sf + inf; i++) {
                    uleb();
                    uleb();
                }
                int mask = 0;
                int count = 0;
                String clsName = null;
                for (int g = 0; g < 2; g++) {
                    int n = g == 0 ? dm : vm;
                    int mid = 0;
                    for (int j = 0; j < n; j++) {
                        mid += uleb();
                        int acc = uleb();
                        int code = uleb();
                        if (mid < 0 || mid >= mthN) continue;
                        res.methods++;
                        int pro = u16(mthOff + 8 * mid + 2);
                        if (methodName != null) {
                            int nameIdx = u32(mthOff + 8 * mid + 4);
                            if (nameIdx >= 0 && nameIdx < strN && methodName[nameIdx] != null) {
                                if (clsName == null) clsName = typeDesc(u32(def));
                                Ref r = new Ref("method:" + methodName[nameIdx], clsName, methodName[nameIdx], protoDesc(pro));
                                if (!refs.containsKey(r.id())) refs.put(r.id(), r);
                            }
                        }
                        int rk = readerKind[pro];
                        if (rk > 0 && (acc & 0x8) == 0 && code != 0) {
                            mask |= 1 << (rk - 1);
                            count++;
                        }
                        boolean wantTemplate = clicks && hasTemplate != null && hasTemplate[pro];
                        int shape = shapeOf != null ? shapeOf[pro] : -1;
                        if (wantTemplate || shape >= 0) {
                            if (clsName == null) clsName = typeDesc(u32(def));
                            String name = str(u32(mthOff + 8 * mid + 4));
                            String label = wantTemplate ? "im-sticker" : o.shapes[shape][0];
                            Ref r = new Ref(label, clsName, name, protoDesc(pro));
                            if (!refs.containsKey(r.id())) refs.put(r.id(), r);
                        }
                        if ((local != null || isConst != null) && code != 0 && code < b.length) {
                            scanCode(code, res, local);
                            if (constHits != null && !constHits.isEmpty()) {
                                if (clsName == null) clsName = typeDesc(u32(def));
                                String mname = str(u32(mthOff + 8 * mid + 4));
                                for (String h : constHits) {
                                    Ref r = new Ref("const:" + h, clsName, mname, protoDesc(pro));
                                    if (!refs.containsKey(r.id())) refs.put(r.id(), r);
                                }
                                constHits.clear();
                            }
                        }
                    }
                }
                int kinds = Integer.bitCount(mask);
                if (kinds >= 4) {
                    if (clsName == null) clsName = typeDesc(u32(def));
                    readers.put(clsName, new ReaderClass(clsName, count, kinds));
                }
            }

            if (local != null) {
                for (Map.Entry<Integer, Anchored> e : local.entrySet()) {
                    int mid = e.getKey();
                    Anchored a = e.getValue();
                    int[] params = paramsOf(u16(mthOff + 8 * mid + 2));
                    if (a.keyParam < 0 || a.keyParam >= params.length || params[a.keyParam] != stringType) continue;
                    String id = a.id();
                    Anchored have = anchored.get(id);
                    if (have == null) anchored.put(id, a);
                    else {
                        have.keys.addAll(a.keys);
                        have.uses += a.uses;
                    }
                }
            }
        }

        // ---- разбор кода метода

        /** Длина инструкции в словах или -1, если код обрывается или испорчен. */
        int insnLen(int base, int i, int size, int w) {
            int op = w & 0xff;
            int len;
            if (op == 0 && (w >> 8) != 0) {
                int kind = w >> 8;
                if (kind == 1) {
                    if (i + 1 >= size) return -1;
                    len = u16(base + 2 * (i + 1)) * 2 + 4;
                } else if (kind == 2) {
                    if (i + 1 >= size) return -1;
                    len = u16(base + 2 * (i + 1)) * 4 + 2;
                } else if (kind == 3) {
                    if (i + 3 >= size) return -1;
                    long total = ((long) u16(base + 2 * (i + 1)) * (u32(base + 2 * (i + 2)) & 0xffffffffL) + 1) / 2 + 4;
                    if (total > size) return -1;
                    len = (int) total;
                } else {
                    len = 1;
                }
            } else {
                len = LEN[op];
            }
            return i + len > size ? -1 : len;
        }

        /** Сошлись ли длины всех инструкций ровно с размером кода (для проверки таблицы). */
        boolean decodesExactly(int code) {
            int size = u32(code + 12);
            int base = code + 16;
            if (size <= 0 || (long) base + 2L * size > b.length) return false;
            int i = 0;
            while (i < size) {
                int len = insnLen(base, i, size, u16(base + 2 * i));
                if (len < 0) return false;
                i += len;
            }
            return i == size;
        }

        private void scanCode(int code, Result res, Map<Integer, Anchored> out) {
            if (out == null && isConst == null) return;
            int size = u32(code + 12);
            int base = code + 16;
            if (size <= 0 || (long) base + 2L * size > b.length) return;
            int i = 0;
            int n = 0;
            int pending = 0;
            while (i < size) {
                int w = u16(base + 2 * i);
                int op = w & 0xff;
                int len = insnLen(base, i, size, w);
                if (len < 0) break;
                if (op == 0x1a || op == 0x1b) {
                    int sidx = op == 0x1a ? u16(base + 2 * (i + 1)) : u32(base + 2 * (i + 1));
                    if (isConst != null && sidx >= 0 && sidx < strN && isConst[sidx]) {
                        if (constHits == null) constHits = new ArrayList<>();
                        if (!constHits.contains(constName[sidx])) constHits.add(constName[sidx]);
                    }
                    if (isAnchor != null && sidx >= 0 && sidx < strN && isAnchor[sidx]) {
                        res.anchorStrings++;
                        int reg = (w >> 8) & 0xff;
                        int slot = -1;
                        for (int k = 0; k < pending; k++) {
                            if (pReg[k] == reg) {
                                slot = k;
                                break;
                            }
                        }
                        if (slot < 0) {
                            if (pending < PENDING) {
                                slot = pending++;
                            } else {
                                slot = 0;
                                for (int k = 1; k < PENDING; k++) {
                                    if (pAt[k] < pAt[slot]) slot = k;
                                }
                            }
                        }
                        pReg[slot] = reg;
                        pStr[slot] = sidx;
                        pAt[slot] = n;
                    }
                } else if (pending > 0 && ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78))) {
                    invoke(base, i, op, w, n, pending, out);
                }
                i += len;
                n++;
            }
        }

        private void invoke(int base, int i, int op, int w, int n, int pending, Map<Integer, Anchored> out) {
            int mid = u16(base + 2 * (i + 1));
            if (mid >= mthN) return;
            boolean range = op >= 0x74;
            boolean isStatic = op == 0x71 || op == 0x77;
            int count;
            int[] regs = new int[range ? Math.min(w >> 8, 255) : 5];
            if (range) {
                count = regs.length;
                int first = u16(base + 2 * (i + 2));
                for (int k = 0; k < count; k++) regs[k] = first + k;
            } else {
                count = (w >> 12) & 0xf;
                int w3 = u16(base + 2 * (i + 2));
                regs[0] = w3 & 0xf;
                regs[1] = (w3 >> 4) & 0xf;
                regs[2] = (w3 >> 8) & 0xf;
                regs[3] = (w3 >> 12) & 0xf;
                regs[4] = (w >> 8) & 0xf;
                if (count > 5) count = 5;
            }
            for (int k = 0; k < pending; k++) {
                if (n - pAt[k] > WINDOW) continue;
                for (int a = 0; a < count; a++) {
                    if (regs[a] != pReg[k]) continue;
                    int param = a - (isStatic ? 0 : 1);
                    if (param < 0) continue;
                    Anchored an = out.get(mid);
                    if (an == null) {
                        String cls = typeDesc(u16(mthOff + 8 * mid));
                        String name = str(u32(mthOff + 8 * mid + 4));
                        String proto = protoDesc(u16(mthOff + 8 * mid + 2));
                        an = new Anchored(cls, name, proto, isStatic, param);
                        out.put(mid, an);
                    }
                    if (an.keyParam == param) {
                        an.keys.add(anchorName[pStr[k]]);
                        an.uses++;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- проверка разбора инструкций

    /**
     * Проверка разбора на любом dex: проходит по коду всех методов и считает, у скольких длины
     * инструкций сошлись ровно с размером кода. Нужна для тестов; на телефоне не вызывается.
     *
     * @return {методов с кодом, методов, где длины не сошлись}
     */
    static int[] verifyDecoder(byte[] dex) throws IOException {
        Dex d = new Dex(dex, new Options());
        d.prepare();
        int total = 0;
        int bad = 0;
        for (int ci = 0; ci < d.clsN; ci++) {
            int def = d.clsOff + 32 * ci;
            int dataOff = d.u32(def + 24);
            if (dataOff == 0) continue;
            d.pos = dataOff;
            int sf = d.uleb();
            int inf = d.uleb();
            int dm = d.uleb();
            int vm = d.uleb();
            for (int i = 0; i < sf + inf; i++) {
                d.uleb();
                d.uleb();
            }
            for (int g = 0; g < 2; g++) {
                int n = g == 0 ? dm : vm;
                for (int j = 0; j < n; j++) {
                    d.uleb();
                    d.uleb();
                    int code = d.uleb();
                    if (code == 0) continue;
                    total++;
                    if (!d.decodesExactly(code)) bad++;
                }
            }
        }
        return new int[]{total, bad};
    }

    /** Список кодов, на которых длины не сошлись (для отладки таблицы). */
    static List<String> decoderProblems(byte[] dex, int max) throws IOException {
        Dex d = new Dex(dex, new Options());
        d.prepare();
        List<String> out = new ArrayList<>();
        for (int ci = 0; ci < d.clsN && out.size() < max; ci++) {
            int def = d.clsOff + 32 * ci;
            int dataOff = d.u32(def + 24);
            if (dataOff == 0) continue;
            d.pos = dataOff;
            int sf = d.uleb();
            int inf = d.uleb();
            int dm = d.uleb();
            int vm = d.uleb();
            for (int i = 0; i < sf + inf; i++) {
                d.uleb();
                d.uleb();
            }
            for (int g = 0; g < 2; g++) {
                int n = g == 0 ? dm : vm;
                int mid = 0;
                for (int j = 0; j < n; j++) {
                    mid += d.uleb();
                    d.uleb();
                    int code = d.uleb();
                    if (code == 0) continue;
                    if (!d.decodesExactly(code)) {
                        out.add(d.typeDesc(d.u32(def)) + "." + d.str(d.u32(d.mthOff + 8 * mid + 4)));
                    }
                }
            }
        }
        return out;
    }

    /** Все наборы ключей и форм, чтобы сравнить два результата в тестах. */
    static Set<String> ids(Result r) {
        Set<String> s = new LinkedHashSet<>();
        for (ReaderClass c : r.readers) s.add("reader " + c.cls);
        for (Anchored a : r.anchored) s.add("anchor " + a.id());
        for (Ref x : r.refs) s.add(x.id());
        return s;
    }
}
