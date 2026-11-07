package dev.ttmlonger;

import org.json.JSONObject;

import java.io.File;

/**
 * Настройки нижней панели: «Оригинал» или «Liquid Glass» и всё, что крутится в стекле.
 *
 * <p>Набор и пределы взяты из index.html (страница Country Spoof): непрозрачность, насыщенность и яркость
 * подложки, преломление при касании, блик по кромке, хроматическая аберрация, пружина (жёсткость, затухание,
 * желейность, надувание) и режим линзы «Как в iOS» / «Классика». Размытие того, что лежит позади панели, в
 * приложении недоступно: TikTok рисует ленту видео отдельной поверхностью, которую обычный вид снять не может.
 *
 * <p>Хранится в своём файле glass.json рядом с остальными настройками. Каждое изменение увеличивает
 * {@link #rev}: живая панель по нему понимает, что пора перерисоваться.
 */
final class GlassCfg {
    static final int ORIGINAL = 0;
    static final int GLASS = 1;

    static volatile int style = ORIGINAL;
    /** Непрозрачность подложки капсулы, %. */
    static volatile int tr = 55;
    /** Насыщенность линзы, % (100 — без изменений, 180 — как в index.html). */
    static volatile int sa = 180;
    /** Яркость, %. */
    static volatile int br = 106;
    /** Преломление при касании, % от 100. */
    static volatile int gs = 100;
    /** Блик по кромке линзы, %. */
    static volatile int sr = 100;
    /** Хроматическая аберрация по краю, %. */
    static volatile int tn = 100;
    /** Жёсткость пружины. */
    static volatile int pk = 900;
    /** Затухание пружины. */
    static volatile int pd = 52;
    /** Желейность, десятые доли (10 — 1,0). */
    static volatile int jl = 10;
    /** Надувание при касании, десятые доли. */
    static volatile int pz = 10;
    /** true — «Как в iOS»: в покое видна тёмная «таблетка», линза появляется при касании. */
    static volatile boolean ios = true;
    /** Номер изменения: растёт при каждой правке. */
    static volatile int rev;

    static final int TR_MIN = 5, TR_MAX = 90;
    static final int SA_MIN = 100, SA_MAX = 250;
    static final int BR_MIN = 90, BR_MAX = 130;
    static final int GS_MIN = 0, GS_MAX = 150;
    static final int SR_MIN = 0, SR_MAX = 200;
    static final int TN_MIN = 0, TN_MAX = 300;
    static final int PK_MIN = 100, PK_MAX = 1400;
    static final int PD_MIN = 6, PD_MAX = 90;
    static final int JL_MIN = 0, JL_MAX = 20;
    static final int PZ_MIN = 0, PZ_MAX = 20;

    /** «Прозрачное» и «Тонированное»: {непрозрачность, насыщенность, яркость}. */
    static final int[][] GLASS_PRESETS = {{18, 200, 112}, {55, 180, 106}};
    static final String[] GLASS_NAMES = {"Прозрачное", "Тонированное"};

    /** Пружина: {жёсткость, затухание, желейность×10, надувание×10}. */
    static final int[][] PHYS_PRESETS = {{900, 52, 10, 10}, {120, 12, 14, 12}, {190, 15, 10, 10},
            {260, 10, 16, 13}, {380, 24, 3, 6}};
    static final String[] PHYS_NAMES = {"Как в iOS", "Мягкая", "Стандарт", "Упругая", "Жёсткая"};

    private static File file;

    private GlassCfg() {
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Включено ли стекло прямо сейчас (и защита от вылетов его не отключила). */
    static boolean glass() {
        return style == GLASS && !CrashGuard.tripped;
    }

    // ------------------------------------------------------------ файл

    static synchronized void init(File dir) {
        file = new File(dir, "glass.json");
        try {
            if (!file.exists()) return;
            load(new JSONObject(FlagStore.readFile(file)));
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    static void load(JSONObject o) {
        style = o.optInt("style", ORIGINAL) == GLASS ? GLASS : ORIGINAL;
        tr = clamp(o.optInt("tr", 55), TR_MIN, TR_MAX);
        sa = clamp(o.optInt("sa", 180), SA_MIN, SA_MAX);
        br = clamp(o.optInt("br", 106), BR_MIN, BR_MAX);
        gs = clamp(o.optInt("gs", 100), GS_MIN, GS_MAX);
        sr = clamp(o.optInt("sr", 100), SR_MIN, SR_MAX);
        tn = clamp(o.optInt("tn", 100), TN_MIN, TN_MAX);
        pk = clamp(o.optInt("pk", 900), PK_MIN, PK_MAX);
        pd = clamp(o.optInt("pd", 52), PD_MIN, PD_MAX);
        jl = clamp(o.optInt("jl", 10), JL_MIN, JL_MAX);
        pz = clamp(o.optInt("pz", 10), PZ_MIN, PZ_MAX);
        ios = o.optBoolean("ios", true);
    }

    static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("style", style);
            o.put("tr", tr);
            o.put("sa", sa);
            o.put("br", br);
            o.put("gs", gs);
            o.put("sr", sr);
            o.put("tn", tn);
            o.put("pk", pk);
            o.put("pd", pd);
            o.put("jl", jl);
            o.put("pz", pz);
            o.put("ios", ios);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return o;
    }

    /** Значение изменилось: живая панель перерисуется, файл не пишется (так двигают ползунок). */
    private static void touch() {
        rev++;
    }

    /** Записывает настройки в файл: когда ползунок отпустили или выбрали пресет. */
    static synchronized void save() {
        if (file == null) return;
        try {
            FlagStore.writeAtomic(file, toJson().toString());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static void saved() {
        touch();
        save();
    }

    // ------------------------------------------------------------ изменения

    static void setStyle(int v) {
        style = v == GLASS ? GLASS : ORIGINAL;
        saved();
    }

    static void setTr(int v) {
        tr = clamp(v, TR_MIN, TR_MAX);
        touch();
    }

    static void setSa(int v) {
        sa = clamp(v, SA_MIN, SA_MAX);
        touch();
    }

    static void setBr(int v) {
        br = clamp(v, BR_MIN, BR_MAX);
        touch();
    }

    static void setGs(int v) {
        gs = clamp(v, GS_MIN, GS_MAX);
        touch();
    }

    static void setSr(int v) {
        sr = clamp(v, SR_MIN, SR_MAX);
        touch();
    }

    static void setTn(int v) {
        tn = clamp(v, TN_MIN, TN_MAX);
        touch();
    }

    static void setPk(int v) {
        pk = clamp(v, PK_MIN, PK_MAX);
        touch();
    }

    static void setPd(int v) {
        pd = clamp(v, PD_MIN, PD_MAX);
        touch();
    }

    static void setJl(int v) {
        jl = clamp(v, JL_MIN, JL_MAX);
        touch();
    }

    static void setPz(int v) {
        pz = clamp(v, PZ_MIN, PZ_MAX);
        touch();
    }

    static void setIos(boolean v) {
        ios = v;
        saved();
    }

    /** Какой пресет стекла сейчас выбран: 0 «Прозрачное», 1 «Тонированное», -1 — свои значения. */
    static int glassPreset() {
        for (int i = 0; i < GLASS_PRESETS.length; i++) {
            int[] p = GLASS_PRESETS[i];
            if (tr == p[0] && sa == p[1] && br == p[2]) return i;
        }
        return -1;
    }

    static void applyGlassPreset(int i) {
        if (i < 0 || i >= GLASS_PRESETS.length) return;
        int[] p = GLASS_PRESETS[i];
        tr = p[0];
        sa = p[1];
        br = p[2];
        saved();
    }

    /** Какой пресет пружины выбран (индекс в PHYS_NAMES) или -1. */
    static int physPreset() {
        for (int i = 0; i < PHYS_PRESETS.length; i++) {
            int[] p = PHYS_PRESETS[i];
            if (pk == p[0] && pd == p[1] && jl == p[2] && pz == p[3]) return i;
        }
        return -1;
    }

    static void applyPhysPreset(int i) {
        if (i < 0 || i >= PHYS_PRESETS.length) return;
        int[] p = PHYS_PRESETS[i];
        pk = p[0];
        pd = p[1];
        jl = p[2];
        pz = p[3];
        saved();
    }

    /** «Сбросить оформление»: всё, кроме выбора «Оригинал / Liquid Glass». */
    static void resetLook() {
        tr = 55;
        sa = 180;
        br = 106;
        gs = 100;
        sr = 100;
        tn = 100;
        pk = 900;
        pd = 52;
        jl = 10;
        pz = 10;
        ios = true;
        saved();
    }

    static void resetForTest() {
        style = ORIGINAL;
        resetLook();
        file = null;
    }

    /** Строка для меню: «Оригинал» или «Liquid Glass». */
    static String summary() {
        return style == GLASS ? "Liquid Glass" : "Оригинал";
    }
}
