package dev.ttmlonger;

/**
 * Бейджики, которые есть в моде. Рисуются кодом (в TikTok нет ресурсов мода) по тому же описанию, что и
 * значки меню: сетка 24×24, команды M L C Z, D (круг-заливка), F (залить), S (обвести). Заливка идёт
 * цветом бейджика, обводка (галочка, блик) белая.
 */
final class BadgeSet {
    /** Сколько бейджиков можно повесить на один аккаунт. */
    static final int PER_ACCOUNT = 3;

    static final class Def {
        final String id;
        final String title;
        final int color;
        final String art;

        Def(String id, String title, int color, String art) {
            this.id = id;
            this.title = title;
            this.color = color;
            this.art = art;
        }
    }

    private static final String TICK = "D 12 12 10  M 7.4 12.4 L 10.8 15.8 L 16.8 8.8 S";

    static final Def[] ALL = {
            new Def("verified", "Галочка синяя", 0xFF20A4F3, TICK),
            new Def("verified_gold", "Галочка золотая", 0xFFE8B100, TICK),
            new Def("verified_green", "Галочка зелёная", 0xFF2BB673, TICK),
            new Def("star", "Звезда", 0xFFFFC107,
                    "M 12 2.6 L 14.8 8.9 L 21.6 9.5 L 16.4 14 L 18 20.7 L 12 17.2 L 6 20.7 L 7.6 14 L 2.4 9.5"
                            + " L 9.2 8.9 Z F"),
            new Def("heart", "Сердце", 0xFFFE2C55,
                    "M 12 21 C 5 15.6 2.5 12.2 2.5 8.6 C 2.5 5.8 4.6 3.8 7.2 3.8 C 9.2 3.8 11 4.9 12 6.7"
                            + " C 13 4.9 14.8 3.8 16.8 3.8 C 19.4 3.8 21.5 5.8 21.5 8.6 C 21.5 12.2 19 15.6 12 21 Z F"),
            new Def("flame", "Огонь", 0xFFFF7A1A,
                    "M 12 2.4 C 12.7 6 17.4 8.4 17.4 13.6 C 17.4 18 15 21.6 12 21.6 C 8.9 21.6 6.6 18.4 6.6 14.6"
                            + " C 6.6 12.4 7.5 10.8 8.8 9.4 C 9.2 11 9.9 11.6 10.8 11.8 C 10.1 8.4 10.4 5.2 12 2.4 Z F"),
            new Def("crown", "Корона", 0xFFFFB800,
                    "M 2.8 8 L 7.6 12.2 L 12 4.8 L 16.4 12.2 L 21.2 8 L 19.4 19.4 L 4.6 19.4 Z F"),
            new Def("diamond", "Алмаз", 0xFF35D0E5,
                    "M 7 3.4 L 17 3.4 L 22 9.6 L 12 21 L 2 9.6 Z F  M 2.6 9.6 L 21.4 9.6 S"),
            new Def("bolt", "Молния", 0xFFFFD23F,
                    "M 13.6 2.4 L 5 13.4 L 11 13.4 L 10 21.6 L 19 10.4 L 13 10.4 Z F"),
    };

    private BadgeSet() {
    }

    /** Номер бейджика в списке (он же в метке) или -1. */
    static int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(id)) return i;
        }
        return -1;
    }

    static Def byIndex(int i) {
        return i >= 0 && i < ALL.length ? ALL[i] : null;
    }

    static Def byId(String id) {
        return byIndex(indexOf(id));
    }

    /** Названия бейджиков через запятую («Звезда, Огонь»). */
    static String titles(java.util.List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            Def d = byId(id);
            if (d == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.title);
        }
        return sb.toString();
    }
}
