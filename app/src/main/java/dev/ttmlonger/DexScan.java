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
    static final int VERSION = 4;

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
         * его обфусцированный класс (служба огоньков). Дескриптор, начинающийся с «L*» и слэша, означает
         * «любой пакет»: подходит всякий тип, чей дескриптор оканчивается на слэш и имя. Реализациями
         * считаются и наследники таких классов, и классы, реализующие интерфейс-наследник.
         */
        String[][] interfaces = new String[0][];
        /**
         * Строки, которые встречаются в коде как const-string, например имена констант enum:
         * метод, где строка найдена, попадает в результат как Ref("const:строка", класс, метод, сигнатура).
         */
        String[] consts = new String[0];
        /**
         * Куски строк (строчные латинские буквы, цифры, «_»), по которым ищутся const-string в коде без
         * учёта регистра: подходит любая короткая строка (до 80 знаков), где кусок встречается. Метод, где
         * строка найдена, попадает в результат как Ref("const:~кусок|строка", класс, метод, сигнатура).
         * Это разведка: на каждый кусок берётся не больше partsPerNeedle разных строк.
         */
        String[] parts = new String[0];
        /**
         * Сколько разных строк на один кусок из parts (в каждом dex). В таблице строк рядом с настоящими
         * const-string лежат и имена методов, полей и типов, поэтому предел задаётся с запасом.
         */
        int partsPerNeedle = 400;
        /**
         * Имена методов, которые не обфусцированы (например, getFavouriteStickersFlow): каждый
         * найденный метод попадает в результат как Ref("method:имя", класс, имя, сигнатура).
         */
        String[] methods = new String[0];
        /**
         * Числа-ресурсы (например, номера строк 0x7f14…): метод, где такое число загружается командой const,
         * попадает в результат как Ref("const:id:0x…", класс, метод, сигнатура + « @[вызовы и строки метода]»).
         */
        int[] ints = new int[0];
        /**
         * Для методов, где найдена точная строка из consts, тоже записывать вызовы и строки метода
         * (« @[…]» в конце сигнатуры): по ним видно, какие проверки стоят рядом с чтением ключа.
         */
        boolean notes;
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
        Map<String, Integer> shared = new HashMap<>();
        for (int i = 0; i < src.count(); i++) {
            if (o.deadline != 0 && System.currentTimeMillis() > o.deadline) {
                res.notes.add("лимит времени: разобрано " + i + " из " + src.count() + " dex");
                break;
            }
            try {
                byte[] bytes = src.read(i);
                Dex d = new Dex(bytes, o);
                d.sharedSub = shared;
                d.scan(res, anchored, readers, refs);
                d.exportSubs(shared);
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
        /** Таблица полей (для заметок о методе); 0, если в заголовке мусор. */
        int fldN, fldOff;
        /** Числа, которые ищем в const; null, если не нужны. */
        int[] lits;
        /** Заметки о последнем разобранном методе (вызовы, строки, поля) или null. */
        String litNotes;
        /** Курсор для чтения ULEB128 из class_data. Строковые помощники его не трогают. */
        int pos;

        // Заполняется в prepare().
        int stringType = -1;
        int clickType = -1;
        int templateType = -1;
        /** Для каждого типа: номер интерфейса из Options.interfaces или -1; null, если интерфейсы не нужны. */
        int[] ifaceOf;
        /** Для каждого типа: номер интерфейса из Options.interfaces, если тип его реализует хоть как-то, иначе -1. */
        int[] subOf;
        /** Типы, найденные как реализации в уже разобранных dex (общий для всех dex набор, дескриптор → номер). */
        Map<String, Integer> sharedSub;
        boolean[] isConst;
        String[] constName;
        /** Сколько мест со строками из Options.parts уже записано (предел против разрастания). */
        int partRefs;
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
            int fn = u32(0x50);
            int fo = u32(0x54);
            if (fn > 0 && fo > 0 && (long) fo + (long) fn * 8 <= b.length) {
                fldN = fn;
                fldOff = fo;
            }
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

        private boolean containsSlash(int from, int to) {
            for (int i = from; i < to; i++) {
                if (b[i] == '/') return true;
            }
            return false;
        }

        /** Есть ли в b[from, to) подстрока need без учёта регистра (need уже в нижнем регистре). */
        private boolean containsFold(int from, int to, byte[] need) {
            for (int i = from; i + need.length <= to; i++) {
                int k = 0;
                while (k < need.length) {
                    int c = b[i + k];
                    if (c >= 'A' && c <= 'Z') c += 32;
                    if (c != need[k]) break;
                    k++;
                }
                if (k == need.length) return true;
            }
            return false;
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

        /** Запоминает найденные реализации для следующих dex (класс из одного dex может наследовать из другого). */
        void exportSubs(Map<String, Integer> out) {
            if (subOf == null) return;
            for (int t = 0; t < typN; t++) {
                if (subOf[t] >= 0) out.put(typeDesc(t), subOf[t]);
            }
        }

        /** Оканчивается ли строка на заданные байты (строка Dex оканчивается нулём). */
        boolean strEndsWith(int idx, byte[] tail) {
            int p = strStart(idx);
            int end = p;
            while (end < b.length && b[end] != 0) end++;
            int n = end - p;
            if (n < tail.length) return false;
            for (int i = 0; i < tail.length; i++) {
                if (b[end - tail.length + i] != tail[i]) return false;
            }
            return true;
        }

        /**
         * Отмечает все типы, которые реализуют искомый интерфейс не напрямую: через суперкласс,
         * через интерфейс-наследник, а также через типы, найденные в предыдущих dex. Повторяет проход,
         * пока что-то добавляется (цепочки обычно короткие).
         */
        private void closeSubtypes() {
            subOf = new int[typN];
            java.util.Arrays.fill(subOf, -1);
            if (ifaceOf != null) {
                for (int t = 0; t < typN; t++) subOf[t] = ifaceOf[t];
            }
            if (sharedSub != null && !sharedSub.isEmpty()) {
                for (int t = 0; t < typN; t++) {
                    if (typeFirst[t] != 'L' || subOf[t] >= 0) continue;
                    Integer w = sharedSub.get(typeDesc(t));
                    if (w != null) subOf[t] = w;
                }
            }
            for (int round = 0; round < 12; round++) {
                boolean changed = false;
                for (int ci = 0; ci < clsN; ci++) {
                    int def = clsOff + 32 * ci;
                    int t = u32(def);
                    if (t < 0 || t >= typN || subOf[t] >= 0) continue;
                    int w = -1;
                    int sup = u32(def + 8);
                    if (sup >= 0 && sup < typN && subOf[sup] >= 0) w = subOf[sup];
                    int ifaceOff = u32(def + 12);
                    if (w < 0 && ifaceOff != 0 && ifaceOff < b.length) {
                        int n = u32(ifaceOff);
                        for (int k = 0; k < n && k < 64; k++) {
                            int it = u16(ifaceOff + 4 + 2 * k);
                            if (it < typN && subOf[it] >= 0) {
                                w = subOf[it];
                                break;
                            }
                        }
                    }
                    if (w >= 0) {
                        subOf[t] = w;
                        changed = true;
                    }
                }
                if (!changed) break;
            }
        }

        private void prepare() {
            lits = o.ints != null && o.ints.length > 0 ? o.ints : null;
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
                boolean[] any = new boolean[want.length];
                for (int i = 0; i < want.length; i++) {
                    String d = o.interfaces[i][1];
                    if (d.startsWith("L*")) {
                        any[i] = true;
                        d = d.substring(2);
                    }
                    want[i] = d.getBytes(LATIN1);
                }
                for (int t = 0; t < typN; t++) {
                    if (typeFirst[t] != 'L') continue;
                    int s = u32(typOff + 4 * t);
                    for (int i = 0; i < want.length; i++) {
                        if (any[i] ? strEndsWith(s, want[i]) : strIs(s, want[i])) {
                            if (ifaceOf == null) {
                                ifaceOf = new int[typN];
                                java.util.Arrays.fill(ifaceOf, -1);
                            }
                            ifaceOf[t] = i;
                            break;
                        }
                    }
                }
                if (ifaceOf != null || (sharedSub != null && !sharedSub.isEmpty())) closeSubtypes();
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

            if (o.parts.length > 0) {
                int[] taken = new int[o.parts.length];
                byte[][] need = new byte[o.parts.length][];
                for (int i = 0; i < need.length; i++) need[i] = o.parts[i].getBytes(LATIN1);
                for (int s = 0; s < strN; s++) {
                    int p = strStart(s);
                    int e = p;
                    boolean plain = true;
                    while (e < b.length && b[e] != 0 && e - p <= 80) {
                        if ((b[e] & 0x80) != 0) plain = false;
                        e++;
                    }
                    if (!plain || e >= b.length || b[e] != 0 || e - p > 80 || e == p) continue;
                    if (b[p] == 'L' && b[e - 1] == ';' && containsSlash(p, e)) continue;  // дескриптор типа
                    for (int i = 0; i < need.length; i++) {
                        if (taken[i] >= o.partsPerNeedle || !containsFold(p, e, need[i])) continue;
                        taken[i]++;
                        if (isConst == null) {
                            isConst = new boolean[strN];
                            constName = new String[strN];
                        }
                        isConst[s] = true;
                        constName[s] = "~" + o.parts[i] + "|" + new String(b, p, e - p, LATIN1);
                        break;
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

                if (subOf != null) {
                    int ct = u32(def);
                    int want = ct >= 0 && ct < typN ? subOf[ct] : -1;
                    // Интерфейсы (0x200) в реализации не берём: хукать нужны классы с кодом.
                    if (want >= 0 && (u32(def + 4) & 0x200) == 0) {
                        Ref r = new Ref("impl:" + o.interfaces[want][0], typeDesc(ct), "", "");
                        if (!refs.containsKey(r.id())) refs.put(r.id(), r);
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
                        if ((local != null || isConst != null || lits != null) && code != 0 && code < b.length) {
                            scanCode(code, res, local);
                            if (constHits != null && !constHits.isEmpty()) {
                                if (clsName == null) clsName = typeDesc(u32(def));
                                String mname = str(u32(mthOff + 8 * mid + 4));
                                String pd = protoDesc(pro);
                                for (String h : constHits) {
                                    if (h.charAt(0) == '~' && ++partRefs > 3000) continue;
                                    String shown = litNotes != null && h.charAt(0) != '~' ? pd + " @[" + litNotes + "]" : pd;
                                    Ref r = new Ref("const:" + h, clsName, mname, shown);
                                    if (!refs.containsKey(r.id())) refs.put(r.id(), r);
                                }
                                constHits.clear();
                            }
                        }
                    }
                }
                if (isConst != null && o.parts.length > 0) {
                    int annOff = u32(def + 20);
                    if (annOff != 0 && annOff < b.length - 16) scanAnnotated(annOff, refs);
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

        // ---- аннотации методов

        /**
         * Строки из аннотаций методов класса. Адреса запросов TikTok (Retrofit: @GET("/aweme/v1/…")) лежат
         * только там, в коде их нет, поэтому const-string их не видит. Метод, у которого в аннотации есть
         * строка с куском из Options.parts, попадает в результат как Ref("const:~кусок|строка", класс, метод,
         * сигнатура + « @[остальные строки аннотаций метода и его параметров]»): по ним видно имена
         * параметров запроса (count, max_cursor…).
         */
        private void scanAnnotated(int dir, Map<String, Ref> refs) {
            int save = pos;
            try {
                int fields = u32(dir + 4);
                int methods = u32(dir + 8);
                int params = u32(dir + 12);
                if (fields < 0 || fields > 8192 || methods < 0 || methods > 8192 || params < 0 || params > 8192) return;
                int base = dir + 16 + 8 * fields;
                if (base + 8 * methods + 8 * params > b.length) return;
                for (int i = 0; i < methods; i++) {
                    int mid = u32(base + 8 * i);
                    int set = u32(base + 8 * i + 4);
                    if (mid < 0 || mid >= mthN || set == 0 || set >= b.length - 4) continue;
                    List<Integer> own = new ArrayList<>();
                    annotationStrings(set, own);
                    List<String> hits = new ArrayList<>();
                    List<String> rest = new ArrayList<>();
                    for (int s : own) {
                        if (s < 0 || s >= strN) continue;
                        if (isConst[s] && constName[s] != null && constName[s].charAt(0) == '~') hits.add(constName[s]);
                        else rest.add(str(s));
                    }
                    if (hits.isEmpty()) continue;
                    for (int j = 0; j < params; j++) {
                        if (u32(base + 8 * methods + 8 * j) != mid) continue;
                        int list = u32(base + 8 * methods + 8 * j + 4);
                        if (list == 0 || list >= b.length - 4) break;
                        int n = u32(list);
                        if (n < 0 || n > 32) break;
                        for (int k = 0; k < n; k++) {
                            int ps = u32(list + 4 + 4 * k);
                            if (ps == 0 || ps >= b.length - 4) continue;
                            List<Integer> strs = new ArrayList<>();
                            annotationStrings(ps, strs);
                            for (int s : strs) {
                                if (s >= 0 && s < strN) rest.add(str(s));
                            }
                        }
                        break;
                    }
                    StringBuilder extra = new StringBuilder();
                    int shown = 0;
                    for (String r : rest) {
                        if (r.isEmpty() || r.length() > 40 || r.indexOf('\t') >= 0 || r.indexOf('\n') >= 0) continue;
                        if (shown++ >= 10) break;
                        extra.append(shown == 1 ? " @[" : ", ").append(r);
                    }
                    if (shown > 0) extra.append(']');
                    String cls = typeDesc(u16(mthOff + 8 * mid));
                    String name = str(u32(mthOff + 8 * mid + 4));
                    String proto = protoDesc(u16(mthOff + 8 * mid + 2)) + extra;
                    for (String h : hits) {
                        if (++partRefs > 3000) return;
                        Ref r = new Ref("const:" + h, cls, name, proto);
                        if (!refs.containsKey(r.id())) refs.put(r.id(), r);
                    }
                }
            } catch (RuntimeException broken) {
                // испорченные аннотации просто пропускаем
            } finally {
                pos = save;
            }
        }

        /** Индексы строк из всех аннотаций набора (annotation_set_item), с запасом по глубине и числу. */
        private void annotationStrings(int set, List<Integer> out) {
            int n = u32(set);
            if (n < 0 || n > 32) return;
            for (int k = 0; k < n; k++) {
                int a = u32(set + 4 + 4 * k);
                if (a <= 0 || a >= b.length - 4) continue;
                pos = a + 1;                // видимость
                uleb();                     // тип аннотации
                int cnt = uleb();
                annotationElements(cnt, out, 0);
            }
        }

        private void annotationElements(int cnt, List<Integer> out, int depth) {
            if (cnt < 0 || cnt > 64 || depth > 3) throw new IllegalStateException("annotation");
            for (int i = 0; i < cnt; i++) {
                uleb();                     // имя элемента
                encodedValue(out, depth);
            }
        }

        private void encodedValue(List<Integer> out, int depth) {
            int h = b[pos++] & 0xff;
            int type = h & 0x1f;
            int arg = h >> 5;
            if (type == 0x17) {
                int idx = 0;
                for (int i = 0; i <= arg; i++) idx |= (b[pos++] & 0xff) << (8 * i);
                if (out.size() < 64) out.add(idx);
            } else if (type == 0x1c) {
                int n = uleb();
                if (n < 0 || n > 64 || depth > 3) throw new IllegalStateException("annotation");
                for (int i = 0; i < n; i++) encodedValue(out, depth + 1);
            } else if (type == 0x1d) {
                uleb();
                annotationElements(uleb(), out, depth + 1);
            } else if (type != 0x1e && type != 0x1f) {
                pos += arg + 1;
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
            litNotes = null;
            if (out == null && isConst == null && lits == null) return;
            int size = u32(code + 12);
            int base = code + 16;
            if (size <= 0 || (long) base + 2L * size > b.length) return;
            int i = 0;
            int n = 0;
            int pending = 0;
            boolean litHit = false;
            boolean exactHit = false;
            while (i < size) {
                int w = u16(base + 2 * i);
                int op = w & 0xff;
                int len = insnLen(base, i, size, w);
                if (len < 0) break;
                if (op == 0x14 && lits != null) {
                    int v = u32(base + 2 * (i + 1));
                    for (int l : lits) {
                        if (l != v) continue;
                        if (constHits == null) constHits = new ArrayList<>();
                        String h = "id:0x" + Integer.toHexString(v);
                        if (!constHits.contains(h)) constHits.add(h);
                        litHit = true;
                        break;
                    }
                } else if (op == 0x1a || op == 0x1b) {
                    int sidx = op == 0x1a ? u16(base + 2 * (i + 1)) : u32(base + 2 * (i + 1));
                    if (isConst != null && sidx >= 0 && sidx < strN && isConst[sidx]) {
                        if (constHits == null) constHits = new ArrayList<>();
                        if (!constHits.contains(constName[sidx])) constHits.add(constName[sidx]);
                        if (constName[sidx].charAt(0) != '~') exactHit = true;
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
            if (litHit || (exactHit && o.notes)) litNotes = notesOf(base, size);
        }

        private static final int NOTE_MAX = 44;
        private static final int NOTE_CHARS = 900;

        /** «Lx/y/Z;» → «Z» (обфусцированные «LX/0abc;» → «X.0abc»), массивы и примитивы как есть. */
        private static String noteType(String d) {
            if (d == null || d.isEmpty()) return "";
            int dims = 0;
            while (dims < d.length() && d.charAt(dims) == '[') dims++;
            String t = d.substring(dims);
            if (t.length() > 2 && t.charAt(0) == 'L' && t.endsWith(";")) {
                String inner = t.substring(1, t.length() - 1);
                t = inner.startsWith("X/") ? inner.replace('/', '.') : inner.substring(inner.lastIndexOf('/') + 1);
            }
            StringBuilder sb = new StringBuilder(t);
            for (int k = 0; k < dims; k++) sb.append("[]");
            return sb.toString();
        }

        /** «(Lx/A;I)Z» → «(A,I)Z». */
        private static String noteProto(String p) {
            int close = p.indexOf(')');
            if (close < 0 || p.charAt(0) != '(') return p;
            StringBuilder sb = new StringBuilder("(");
            int i = 1;
            boolean first = true;
            while (i < close) {
                int st = i;
                while (p.charAt(i) == '[') i++;
                if (p.charAt(i) == 'L') i = p.indexOf(';', i);
                if (i < 0 || i >= close) {
                    sb.append(p, st, close);
                    break;
                }
                i++;
                if (!first) sb.append(',');
                first = false;
                sb.append(noteType(p.substring(st, i)));
            }
            return sb.append(')').append(noteType(p.substring(close + 1))).toString();
        }

        private static boolean plainText(String t) {
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                if (c < 0x20 || c > 0x7e) return false;
            }
            return true;
        }

        /**
         * Что делает метод: вызываемые методы, короткие строки, номера ресурсов и поля, в порядке появления.
         * По этому видно, какие проверки стоят рядом с искомым местом. Без табуляций и переводов строки.
         */
        private String notesOf(int base, int size) {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            int i = 0;
            while (i < size && set.size() < NOTE_MAX) {
                int w = u16(base + 2 * i);
                int op = w & 0xff;
                int len = insnLen(base, i, size, w);
                if (len < 0) break;
                if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                    int mid = u16(base + 2 * (i + 1));
                    if (mid < mthN) {
                        String name = str(u32(mthOff + 8 * mid + 4));
                        if (plainText(name)) {
                            set.add(noteType(typeDesc(u16(mthOff + 8 * mid))) + "." + name
                                    + noteProto(protoDesc(u16(mthOff + 8 * mid + 2))));
                        }
                    }
                } else if (op == 0x1a || op == 0x1b) {
                    int sidx = op == 0x1a ? u16(base + 2 * (i + 1)) : u32(base + 2 * (i + 1));
                    if (sidx >= 0 && sidx < strN) {
                        String t = str(sidx);
                        if (t.length() >= 2 && t.length() <= 48 && plainText(t)) set.add("\"" + t + "\"");
                    }
                } else if (op == 0x14) {
                    int v = u32(base + 2 * (i + 1));
                    if ((v >>> 24) == 0x7f) set.add("0x" + Integer.toHexString(v));
                } else if (fldOff != 0 && ((op >= 0x52 && op <= 0x58) || (op >= 0x60 && op <= 0x66))) {
                    int fid = u16(base + 2 * (i + 1));
                    if (fid < fldN) {
                        String name = str(u32(fldOff + 8 * fid + 4));
                        if (plainText(name)) {
                            set.add(op >= 0x60 ? noteType(typeDesc(u16(fldOff + 8 * fid))) + "." + name : "." + name);
                        }
                    }
                }
                i += len;
            }
            StringBuilder sb = new StringBuilder();
            for (String e : set) {
                if (sb.length() + e.length() + 3 > NOTE_CHARS) break;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(e);
            }
            return sb.length() == 0 ? null : sb.toString();
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
