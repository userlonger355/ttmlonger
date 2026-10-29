package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Импорт флагов из JSON: из файла экспорта этого же мода (в нём есть и значения, прочитанные
 * приложением, и подмены «override»), из простого словаря {"имя": значение} и из списка объектов
 * [{"key": "имя", "value": значение}]. Что именно применять, выбирает пользователь: только
 * подмены из файла, только отличающиеся от значений на этом телефоне или всё подряд.
 *
 * Разбор и отбор не зависят от Android (проверяются тестами); окна и чтение файла ниже.
 */
final class Importer {
    static final int MODE_OVERRIDE = 0;
    static final int MODE_DIFF = 1;
    static final int MODE_ALL = 2;

    private static final int MAX_ENTRIES = 200_000;
    private static final int MAX_KEY = 300;
    private static final int MAX_VALUE = 4096;
    private static final int MAX_FILE = 24 * 1024 * 1024;
    private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+\\.[0-9]+");
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    private Importer() {
    }

    // ------------------------------------------------------------ разбор

    /** Одна запись из файла. */
    static final class Item {
        final String key;
        /** Тип из файла (имя типа в экспорте) или -1. */
        final int fileType;
        /** Тип по виду значения в JSON. */
        final int inferred;
        /** Значение, которое прочитало приложение (у экспорта) или null. */
        final String value;
        /** Подмена из файла (у экспорта) либо значение «как задумано» (у простых форматов) или null. */
        final String override;

        Item(String key, int fileType, int inferred, String value, String override) {
            this.key = key;
            this.fileType = fileType;
            this.inferred = inferred;
            this.value = value;
            this.override = override;
        }
    }

    static final class Parsed {
        final List<Item> items = new ArrayList<>();
        /** Записей, которые не удалось разобрать. */
        int skipped;
        /** Флаги json-типа: мод их не подменяет, поэтому они не импортируются. */
        int json;
        String format = "?";
    }

    /** Разбирает текст JSON; бросает JSONException, если это не JSON или в нём нечего импортировать. */
    static Parsed parse(String text) throws JSONException {
        if (text == null) throw new JSONException("пусто");
        String s = text.trim();
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1).trim();
        if (s.isEmpty()) throw new JSONException("пусто");

        Parsed p = new Parsed();
        if (s.charAt(0) == '[') {
            p.format = "список объектов";
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length() && p.items.size() < MAX_ENTRIES; i++) {
                Object o = a.opt(i);
                String key = o instanceof JSONObject ? firstString((JSONObject) o, "key", "name", "flag", "id") : null;
                if (key == null) {
                    p.skipped++;
                    continue;
                }
                entry(p, key, o, true);
            }
        } else {
            JSONObject root = new JSONObject(s);
            JSONObject flags = root.optJSONObject("flags");
            boolean export = flags != null;
            JSONObject src = export ? flags : root;
            boolean reference = export && root.has("mod_built");
            p.format = reference ? "экспорт референс-мода" : export ? "экспорт флагов" : "словарь «имя: значение»";
            Iterator<String> it = src.keys();
            while (it.hasNext() && p.items.size() < MAX_ENTRIES) {
                String k = it.next();
                entry(p, k, src.opt(k), !export);
            }
        }
        if (p.items.isEmpty()) throw new JSONException("в файле нет флагов");
        return p;
    }

    private static String firstString(JSONObject o, String... names) {
        for (String n : names) {
            Object v = o.opt(n);
            if (v instanceof String && !((String) v).trim().isEmpty()) return ((String) v).trim();
        }
        return null;
    }

    /** @param intent true, если значение в файле — это то, что надо поставить (простые форматы). */
    private static void entry(Parsed p, String key, Object v, boolean intent) {
        if (key == null || key.trim().isEmpty() || key.length() > MAX_KEY) {
            p.skipped++;
            return;
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            // Экспорт референс-мода: флаги json-типа подменить нельзя; значение, которое мод
            // принудительно ставит, лежит в forced_by_mod.
            if ("json".equals(o.optString("type", ""))) {
                p.json++;
                return;
            }
            Object ov = o.opt("override");
            if (ov == null) ov = o.opt("forced_by_mod");
            Object val = o.opt("value");
            String override = scalar(ov);
            String value = scalar(val);
            if (override == null && value == null) {
                p.skipped++;
                return;
            }
            Object source = override != null ? ov : val;
            if (intent && override == null) override = value;
            int ftype = FlagStore.typeByName(o.optString("type", ""));
            int inferred = inferredType(source);
            p.items.add(new Item(key, ftype, inferred, value, override));
            return;
        }
        String raw = scalar(v);
        if (raw == null) {
            p.skipped++;
            return;
        }
        p.items.add(new Item(key, -1, inferredType(v), null, raw));
    }

    /** Строковое представление простого значения JSON; null для null, массивов и объектов. */
    private static String scalar(Object v) {
        if (v == null || v == JSONObject.NULL) return null;
        String s;
        if (v instanceof Boolean) s = ((Boolean) v) ? "true" : "false";
        else if (v instanceof Number || v instanceof String) s = String.valueOf(v);
        else return null;
        return s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) : s;
    }

    private static int inferredType(Object v) {
        if (v instanceof Boolean) return FlagStore.BOOL;
        if (v instanceof Integer) return FlagStore.INT;
        if (v instanceof Long) return FlagStore.LONG;
        if (v instanceof Number) return FlagStore.DOUBLE;
        return FlagStore.STRING;
    }

    // ------------------------------------------------------------ отбор

    static final class Chosen {
        final String key;
        final int type;
        final String raw;

        Chosen(String key, int type, String raw) {
            this.key = key;
            this.type = type;
            this.raw = raw;
        }
    }

    static final class Plan {
        final List<Chosen> chosen = new ArrayList<>();
        /** Подходили по режиму, но значение не подходит к типу флага. */
        int incompatible;
    }

    /** Что будет применено в этом режиме. */
    static Plan select(Parsed p, int mode) {
        Plan plan = new Plan();
        for (Item it : p.items) {
            String raw;
            FlagStore.Flag known = FlagStore.known(it.key);
            switch (mode) {
                case MODE_OVERRIDE:
                    raw = it.override;
                    break;
                case MODE_DIFF:
                    if (it.value == null || known == null || known.value == null
                            || FlagStore.sameRaw(known.value, it.value)) {
                        continue;
                    }
                    raw = it.value;
                    break;
                default:
                    raw = it.override != null ? it.override : it.value;
                    break;
            }
            if (raw == null) continue;

            int type = known != null && known.type >= 0 ? known.type
                    : it.fileType >= 0 ? it.fileType : it.inferred;
            raw = normalize(type, raw);
            if (FlagStore.coerce(raw, type) == null) {
                plan.incompatible++;
                continue;
            }
            plan.chosen.add(new Chosen(it.key, type, raw));
        }
        return plan;
    }

    /** «1.0» для целого флага превращается в «1»; логические остаются как есть. */
    static String normalize(int type, String raw) {
        String s = raw.trim();
        if ((type == FlagStore.INT || type == FlagStore.LONG) && DECIMAL.matcher(s).matches()) {
            try {
                double d = Double.parseDouble(s);
                if (d == Math.rint(d) && Math.abs(d) < 9.0e18) return String.valueOf((long) d);
            } catch (NumberFormatException ignored) {
                // останется как есть и не пройдёт проверку
            }
        }
        if (type == FlagStore.BOOL && INTEGER.matcher(s).matches()) {
            return s.equals("0") ? "false" : s.equals("1") ? "true" : s;
        }
        return type == FlagStore.STRING ? raw : s;
    }

    /** Ставит подмены разом; возвращает, сколько поставлено. */
    static int apply(Plan plan) {
        Map<String, FlagStore.Patch> map = new LinkedHashMap<>();
        for (Chosen c : plan.chosen) map.put(c.key, new FlagStore.Patch(c.type, c.raw));
        FlagStore.setOverrides(map);
        return map.size();
    }

    // ------------------------------------------------------------ окна

    /** Спрашивает, откуда брать JSON, и ведёт дальше. */
    static void start(final Activity act, final Runnable onChanged) {
        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Импорт флагов из JSON")
                .setMessage("Подходит файл экспорта из этого мода или референс-мода, словарь {\"имя\": значение} и список "
                        + "[{\"key\": \"имя\", \"value\": значение}]. Откуда взять?")
                .setPositiveButton("Из файла", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        fromFile(act, onChanged);
                    }
                })
                .setNeutralButton("Из буфера обмена", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        fromClipboard(act, onChanged);
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();
        Screen.markOwn(d);
        d.show();
    }

    private static void fromClipboard(Activity act, Runnable onChanged) {
        String text = null;
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence cs = clip.getItemAt(0).coerceToText(act);
                text = cs == null ? null : cs.toString();
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        if (text == null || text.trim().isEmpty()) {
            toast(act, "В буфере обмена пусто. Скопируйте JSON и попробуйте снова");
            return;
        }
        parseAndAsk(act, text, onChanged);
    }

    private static void fromFile(final Activity act, final Runnable onChanged) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        boolean ok = ActivityResults.start(act, i, new ActivityResults.Callback() {
            @Override
            public void onResult(int resultCode, Intent data) {
                if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
                final Uri uri = data.getData();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String text = readText(act, uri);
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    parseAndAsk(act, text, onChanged);
                                }
                            });
                        } catch (final Throwable e) {
                            FlagStore.log(e);
                            toast(act, "Не удалось прочитать файл: " + e.getMessage());
                        }
                    }
                }, "ttmlonger-import");
                t.setDaemon(true);
                t.start();
            }
        });
        if (!ok) toast(act, "Не удалось открыть выбор файла. Скопируйте JSON и выберите «Из буфера обмена»");
    }

    private static String readText(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("файл не открылся");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE) throw new IOException("файл больше 24 МБ");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void parseAndAsk(final Activity act, final String text, final Runnable onChanged) {
        final Parsed p;
        try {
            p = parse(text);
        } catch (JSONException e) {
            toast(act, "Это не подходящий JSON: " + e.getMessage());
            return;
        } catch (Throwable t) {
            FlagStore.log(t);
            toast(act, "Не удалось разобрать JSON: " + t.getClass().getSimpleName());
            return;
        }
        final Plan[] plans = {select(p, MODE_OVERRIDE), select(p, MODE_DIFF), select(p, MODE_ALL)};
        final String[] labels = {
                "Только подмены из файла (" + plans[0].chosen.size() + ")",
                "Только отличающиеся от моих значений (" + plans[1].chosen.size() + ")",
                "Все значения из файла (" + plans[2].chosen.size() + ")"};
        int first = !plans[0].chosen.isEmpty() ? 0 : !plans[1].chosen.isEmpty() ? 1 : 2;
        final int[] picked = {first};

        String info = "Формат: " + p.format + ". Флагов в файле: " + p.items.size()
                + (p.skipped > 0 ? ", не разобрано: " + p.skipped : "")
                + (p.json > 0 ? ", json-флагов пропущено (мод их не подменяет): " + p.json : "")
                + ".\nФлаги, которых телефон ещё не видел, в режиме «отличающиеся» не считаются. "
                + "Применённое можно снять кнопкой «Сбросить все переопределения».";
        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Что импортировать?")
                .setMessage(info)
                .setSingleChoiceItems(labels, first, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        picked[0] = which;
                    }
                })
                .setPositiveButton("Импортировать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        Plan plan = plans[picked[0]];
                        if (plan.chosen.isEmpty()) {
                            toast(act, "В этом режиме нечего применять"
                                    + (plan.incompatible > 0 ? " (не подошли по типу: " + plan.incompatible + ")" : ""));
                            return;
                        }
                        int n = apply(plan);
                        Diag.note("импорт флагов: применено " + n + ", не подошли по типу " + plan.incompatible);
                        toast(act, "Применено флагов: " + n
                                + (plan.incompatible > 0 ? ", не подошли по типу: " + plan.incompatible : ""));
                        if (onChanged != null) onChanged.run();
                        Restart.offer(act);
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();
        Screen.markOwn(d);
        d.show();
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                    // нет окна для показа
                }
            }
        });
    }
}
