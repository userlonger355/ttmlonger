package dev.ttmlonger;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Список стран: название по-русски, флаг-эмодзи, ISO-код. */
final class Regions {
    static final class Item {
        final String iso;
        final String name;
        final String flag;
        final String lowerName;

        Item(String iso, String name, String flag) {
            this.iso = iso;
            this.name = name;
            this.flag = flag;
            this.lowerName = name.toLowerCase(new Locale("ru"));
        }
    }

    private static List<Item> cache;

    private Regions() {
    }

    static synchronized List<Item> all() {
        if (cache != null) return cache;
        Locale ru = new Locale("ru");
        List<Item> out = new ArrayList<>();
        for (String iso : Locale.getISOCountries()) {
            String name = new Locale("", iso).getDisplayCountry(ru);
            // Для кодов без названия система возвращает сам код — такие пропускаем.
            if (name == null || name.isEmpty() || name.equalsIgnoreCase(iso)) continue;
            out.add(new Item(iso, name, flag(iso)));
        }
        final Collator collator = Collator.getInstance(ru);
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return collator.compare(a.name, b.name);
            }
        });
        cache = out;
        return out;
    }

    static String nameOf(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        for (Item i : all()) {
            if (i.iso.equalsIgnoreCase(iso)) return i.flag + " " + i.name;
        }
        return iso;
    }

    /** ISO-код -> флаг из двух региональных индикаторов Юникода. */
    static String flag(String iso) {
        if (iso == null || iso.length() != 2) return "🏳";
        int base = 0x1F1E6;
        int a = base + (Character.toUpperCase(iso.charAt(0)) - 'A');
        int b = base + (Character.toUpperCase(iso.charAt(1)) - 'A');
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }
}
