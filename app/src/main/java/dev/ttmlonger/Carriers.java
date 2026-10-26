package dev.ttmlonger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Оператор связи для подмены региона: код сети (MCC+MNC по ITU E.212) и название крупного
 * оператора страны. Нужен, чтобы код страны SIM и код сети не противоречили друг другу: проверка
 * «страна SIM совпадает с MCC» в приложении иначе заметила бы подмену.
 *
 * Страны вне таблицы подменяются только по ISO-коду, а код сети остаётся настоящим.
 */
final class Carriers {
    /** iso, MCC+MNC, название. */
    private static final String[][] TABLE = {
            {"nl", "20408", "KPN"}, {"be", "20601", "Proximus"}, {"fr", "20801", "Orange"},
            {"es", "21407", "Movistar"}, {"hu", "21630", "Telekom"}, {"ba", "21803", "BH Telecom"},
            {"hr", "21901", "Hrvatski Telekom"}, {"rs", "22003", "mts"}, {"it", "22201", "TIM"},
            {"ro", "22601", "Vodafone"}, {"ch", "22801", "Swisscom"}, {"cz", "23001", "T-Mobile"},
            {"sk", "23101", "Orange"}, {"at", "23201", "A1"}, {"gb", "23430", "EE"},
            {"dk", "23801", "TDC"}, {"se", "24001", "Telia"}, {"no", "24201", "Telenor"},
            {"fi", "24405", "Elisa"}, {"ee", "24801", "Telia"}, {"lv", "24701", "LMT"},
            {"lt", "24601", "Telia"}, {"ua", "25503", "Kyivstar"}, {"by", "25701", "A1"},
            {"md", "25901", "Orange"}, {"pl", "26003", "Orange"}, {"de", "26201", "Telekom"},
            {"pt", "26801", "Vodafone"}, {"lu", "27001", "POST"}, {"ie", "27201", "Vodafone"},
            {"is", "27401", "Siminn"}, {"al", "27601", "One"}, {"mt", "27801", "Vodafone"},
            {"cy", "28001", "Cyta"}, {"ge", "28202", "Magti"}, {"am", "28301", "Beeline"},
            {"bg", "28401", "A1"}, {"tr", "28601", "Turkcell"}, {"si", "29340", "A1"},
            {"mk", "29401", "Makedonski Telekom"}, {"me", "29701", "Telenor"},
            {"ru", "25001", "MTS"}, {"gr", "20201", "Cosmote"},
            {"us", "310410", "AT&T"}, {"ca", "302220", "Telus"}, {"mx", "334020", "Telcel"},
            {"pr", "330110", "Claro"}, {"gt", "70401", "Claro"},
            {"ve", "73402", "Digitel"}, {"co", "732101", "Claro"}, {"ec", "74001", "Claro"},
            {"pe", "71606", "Movistar"}, {"cl", "73001", "Entel"}, {"ar", "72234", "Personal"},
            {"uy", "74801", "Antel"}, {"br", "72406", "Vivo"},
            {"jp", "44010", "NTT Docomo"}, {"kr", "45005", "SK Telecom"}, {"cn", "46000", "China Mobile"},
            {"tw", "46692", "Chunghwa Telecom"}, {"hk", "45400", "CSL"}, {"vn", "45204", "Viettel"},
            {"kh", "45601", "Cellcard"}, {"th", "52001", "AIS"}, {"my", "50212", "Maxis"},
            {"sg", "52501", "Singtel"}, {"id", "51010", "Telkomsel"}, {"ph", "51502", "Globe"},
            {"au", "50501", "Telstra"}, {"nz", "53001", "Vodafone"},
            {"in", "40410", "Airtel"}, {"pk", "41001", "Mobilink"}, {"bd", "47001", "Grameenphone"},
            {"lk", "41302", "Dialog"}, {"np", "42902", "Ncell"},
            {"kz", "40101", "Beeline"}, {"az", "40001", "Azercell"}, {"uz", "43404", "Beeline"},
            {"kg", "43701", "Beeline"}, {"tj", "43604", "Babilon-M"},
            {"il", "42501", "Partner"}, {"jo", "41677", "Orange"}, {"lb", "41501", "Alfa"},
            {"iq", "41805", "Asiacell"}, {"ir", "43211", "MCI"}, {"sa", "42001", "STC"},
            {"kw", "41902", "Zain"}, {"qa", "42701", "Ooredoo"}, {"ae", "42402", "Etisalat"},
            {"om", "42202", "Omantel"},
            {"eg", "60201", "Orange"}, {"dz", "60301", "Mobilis"}, {"ma", "60401", "Maroc Telecom"},
            {"tn", "60502", "Tunisie Telecom"}, {"ng", "62130", "MTN"}, {"ke", "63902", "Safaricom"},
            {"za", "65501", "Vodacom"},
    };

    private static final Map<String, String[]> BY_ISO = new HashMap<>();

    static {
        for (String[] r : TABLE) BY_ISO.put(r[0], r);
    }

    private Carriers() {
    }

    /** Код сети (MCC+MNC) страны или null, если страны нет в таблице. */
    static String mccMnc(String iso) {
        String[] r = row(iso);
        return r == null ? null : r[1];
    }

    /** Название оператора страны или null. */
    static String name(String iso) {
        String[] r = row(iso);
        return r == null ? null : r[2];
    }

    static int size() {
        return TABLE.length;
    }

    /** Все строки таблицы (для проверок). */
    static String[][] table() {
        return TABLE;
    }

    private static String[] row(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        return BY_ISO.get(iso.toLowerCase(Locale.ROOT));
    }
}
