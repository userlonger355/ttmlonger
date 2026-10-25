package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Экран «Настройки разработчика TikTok (A/B)». Строится кодом и не использует ресурсы модуля:
 * внутри процесса TikTok ресурсов модуля нет.
 */
final class FlagsScreen {
    private static final int BG = 0xFF000000;
    private static final int CARD = 0xFF141414;
    private static final int FIELD = 0xFF2B2B2B;
    private static final int RED = 0xFFFF2D55;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFF8A8A8A;

    // Категории для чипов-фильтров.
    private static final int CAT_BOOL = 0;
    private static final int CAT_NUMBER = 1;
    private static final int CAT_JSON = 2;
    private static final int CAT_STRING = 3;

    private static Dialog current;

    static void show(Activity activity) {
        if (current != null && current.isShowing()) return;
        new FlagsScreen(activity).open();
    }

    private final Activity act;
    private final float density;
    private final int accent = Theme.accent();
    private final boolean[] typeSel = new boolean[4];
    private boolean onlyChanged;
    private boolean searchValues;
    private List<FlagStore.Flag> shown = new ArrayList<>();

    private Dialog dialog;
    private ListView list;
    private BaseAdapter adapter;
    private EditText search;
    private TextView stats;
    private TextView hooks;
    private TextView warn;
    private TextView footer;
    private LinearLayout warnRow;

    private FlagsScreen(Activity activity) {
        this.act = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- окно

    private void open() {
        dialog = new Dialog(act, android.R.style.Theme_Material_NoActionBar);
        current = dialog;

        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setFastScrollEnabled(true);
        list.setBackgroundColor(BG);
        list.addHeaderView(buildHeader(), null, false);

        footer = text("", 15, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(27), dp(24), dp(27), dp(64));
        list.addFooterView(footer, null, false);

        adapter = new FlagAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int i = position - list.getHeaderViewsCount();
                if (i >= 0 && i < shown.size()) edit(shown.get(i));
            }
        });

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(BG);
        root.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(BG));
            w.setStatusBarColor(BG);
            w.setNavigationBarColor(BG);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                current = null;
            }
        });

        refilter();
        dialog.show();
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(act);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(BG);

        TextView back = text("←", 30, WHITE, false);
        back.setPadding(dp(27), dp(18), dp(27), dp(18));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        h.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Настройки разработчика TikTok (A/B)", 32, WHITE, true);
        title.setPadding(dp(27), dp(8), dp(27), dp(8));
        h.addView(title);

        TextView desc = text("Внутренние флаги функций TikTok для тестирования. "
                + "Позволяет вручную переключать экспериментальные функции", 16, MUTED, true);
        desc.setPadding(dp(27), dp(8), dp(27), dp(8));
        h.addView(desc);

        // Предупреждение защиты от крашей + кнопка «Применить снова».
        warnRow = new LinearLayout(act);
        warnRow.setOrientation(LinearLayout.HORIZONTAL);
        warnRow.setGravity(Gravity.CENTER_VERTICAL);
        warnRow.setPadding(dp(27), dp(12), dp(14), dp(12));
        warn = text("Пользовательские значения временно отключены: TikTok аварийно закрылся "
                + "три раза подряд вскоре после запуска", 17, RED, true);
        warnRow.addView(warn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView apply = text("Применить снова", 17, WHITE, true);
        apply.setGravity(Gravity.CENTER);
        apply.setPadding(dp(24), dp(16), dp(24), dp(16));
        apply.setBackground(round(accent, 40));
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CrashGuard.reenable();
                toast("Значения снова применяются");
                updateHeader();
                adapter.notifyDataSetChanged();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(14);
        warnRow.addView(apply, alp);
        h.addView(warnRow);

        hooks = text("", 13, MUTED, false);
        hooks.setPadding(dp(27), dp(8), dp(27), dp(4));
        h.addView(hooks);

        stats = text("", 16, MUTED, true);
        stats.setPadding(dp(27), dp(12), dp(27), dp(12));
        h.addView(stats);

        search = new EditText(act);
        search.setHint("Поиск флагов");
        search.setHintTextColor(MUTED);
        search.setTextColor(WHITE);
        search.setTextSize(17);
        search.setSingleLine(true);
        search.setBackground(round(FIELD, 30));
        search.setPadding(dp(24), dp(14), dp(24), dp(14));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refilter();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(7), dp(4), dp(7), dp(12));
        h.addView(search, slp);

        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(7), 0, dp(7), dp(8));
        String[] names = {"Boolean", "Integer", "JSON", "String"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            final TextView c = chip(names[i]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    typeSel[idx] = !typeSel[idx];
                    styleChip(c, typeSel[idx]);
                    refilter();
                }
            });
            chips.addView(c, chipParams());
        }
        final TextView changed = chip("Изменённые");
        changed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyChanged = !onlyChanged;
                styleChip(changed, onlyChanged);
                refilter();
            }
        });
        chips.addView(changed, chipParams());
        final TextView values = chip("Поиск значений");
        values.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchValues = !searchValues;
                styleChip(values, searchValues);
                refilter();
            }
        });
        chips.addView(values, chipParams());
        scroll.addView(chips);
        h.addView(scroll);

        h.addView(action("Экспорт всех флагов в JSON", accent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Exporter.exportAll(act);
            }
        }));
        h.addView(action("Добавить флаг вручную", accent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addManual();
            }
        }));
        h.addView(action("Сбросить все переопределения", RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        }));
        View gap = new View(act);
        h.addView(gap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        return h;
    }

    // ---------------------------------------------------------------- список и фильтры

    private void refilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase();
        boolean anyType = !(typeSel[0] || typeSel[1] || typeSel[2] || typeSel[3]);

        List<FlagStore.Flag> out = new ArrayList<>();
        for (FlagStore.Flag f : FlagStore.snapshot()) {
            FlagStore.Patch o = FlagStore.rawOverride(f.key);
            if (onlyChanged && o == null) continue;
            if (!anyType && !typeSel[category(f)]) continue;
            if (!q.isEmpty()) {
                boolean hit = f.lower.contains(q);
                if (!hit && searchValues) {
                    String v = o != null ? o.raw : f.value;
                    hit = v != null && v.toLowerCase().contains(q);
                }
                if (!hit) continue;
            }
            out.add(f);
        }
        // Изменённые наверху, дальше по алфавиту.
        Collections.sort(out, new Comparator<FlagStore.Flag>() {
            @Override
            public int compare(FlagStore.Flag a, FlagStore.Flag b) {
                boolean ao = FlagStore.rawOverride(a.key) != null;
                boolean bo = FlagStore.rawOverride(b.key) != null;
                if (ao != bo) return ao ? -1 : 1;
                return a.lower.compareTo(b.lower);
            }
        });
        shown = out;
        if (adapter != null) adapter.notifyDataSetChanged();
        updateHeader();
    }

    private void updateHeader() {
        stats.setText(FlagStore.total() + " всего · " + FlagStore.readThisSession()
                + " прочитано · " + FlagStore.overrideCount() + " переопределено");
        warnRow.setVisibility(CrashGuard.tripped ? View.VISIBLE : View.GONE);
        hooks.setText(FlagHooks.status());
        hooks.setTextColor(FlagHooks.count() > 0 ? MUTED : RED);

        if (!shown.isEmpty()) {
            footer.setVisibility(View.GONE);
        } else {
            footer.setVisibility(View.VISIBLE);
            footer.setText(FlagStore.total() == 0
                    ? "Флагов пока нет: приложение ещё не прочитало ни одной настройки."
                    : "Ничего не найдено");
        }
    }

    private static int category(FlagStore.Flag f) {
        switch (f.type) {
            case FlagStore.BOOL:
                return CAT_BOOL;
            case FlagStore.INT:
            case FlagStore.LONG:
            case FlagStore.FLOAT:
            case FlagStore.DOUBLE:
                return CAT_NUMBER;
            default:
                return isJson(f.value) ? CAT_JSON : CAT_STRING;
        }
    }

    private static boolean isJson(String v) {
        if (v == null) return false;
        String s = v.trim();
        return s.length() > 1 && ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")));
    }

    private final class FlagAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                h = new Holder();
                h.outer = new FrameLayout(act);
                h.outer.setPadding(dp(7), 0, dp(7), 0);
                h.card = new LinearLayout(act);
                h.card.setOrientation(LinearLayout.VERTICAL);
                h.card.setPadding(dp(20), dp(14), dp(20), dp(14));
                h.key = text("", 19, WHITE, true);
                h.sub = text("", 16, MUTED, true);
                h.sub.setPadding(0, dp(4), 0, 0);
                h.card.addView(h.key);
                h.card.addView(h.sub);
                h.outer.addView(h.card, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                h.outer.setTag(h);
                convertView = h.outer;
            } else {
                h = (Holder) convertView.getTag();
            }

            FlagStore.Flag f = shown.get(position);
            FlagStore.Patch o = FlagStore.rawOverride(f.key);
            h.key.setText(f.key);
            if (o != null) {
                boolean off = CrashGuard.tripped;
                h.sub.setText(FlagStore.typeName(f.type) + " · " + brief(o.raw, 48) + " · "
                        + (off ? "Отключено защитой" : "Включено модом"));
                h.sub.setTextColor(off ? MUTED : accent);
            } else {
                h.sub.setText(f.value == null
                        ? FlagStore.typeName(f.type)
                        : FlagStore.typeName(f.type) + " · " + brief(f.value, 48));
                h.sub.setTextColor(MUTED);
            }
            h.card.setBackground(corners(position == 0, position == getCount() - 1));
            return convertView;
        }
    }

    private static final class Holder {
        FrameLayout outer;
        LinearLayout card;
        TextView key;
        TextView sub;
    }

    // ---------------------------------------------------------------- диалоги

    private void edit(final FlagStore.Flag f) {
        final FlagStore.Patch cur = FlagStore.rawOverride(f.key);
        final int type = f.type;
        final boolean jsonLike = type == FlagStore.STRING && isJson(f.value);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        TextView info = text("Тип: " + FlagStore.typeName(type) + "\nРеальное значение: "
                + (f.value == null ? "ещё не читалось" : brief(f.value, 200)), 14, MUTED, false);
        info.setPadding(0, 0, 0, dp(12));
        box.addView(info);

        final Switch sw;
        final EditText input;
        if (type == FlagStore.BOOL) {
            sw = new Switch(act);
            sw.setText("Значение");
            sw.setChecked(parseBool(cur != null ? cur.raw : f.value));
            box.addView(sw);
            input = null;
        } else {
            sw = null;
            input = new EditText(act);
            input.setText(cur != null ? cur.raw : (f.value == null ? "" : f.value));
            input.setSelectAllOnFocus(true);
            switch (type) {
                case FlagStore.INT:
                case FlagStore.LONG:
                    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                    break;
                case FlagStore.FLOAT:
                case FlagStore.DOUBLE:
                    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                            | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    break;
                default:
                    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    input.setTypeface(Typeface.MONOSPACE);
                    input.setMinLines(3);
                    input.setGravity(Gravity.TOP);
                    break;
            }
            box.addView(input);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(f.key)
                .setView(box)
                .setPositiveButton("Применить", null)
                .setNegativeButton("Отмена", null);
        if (cur != null) {
            b.setNeutralButton("Сбросить", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    FlagStore.removeOverride(f.key);
                    refilter();
                }
            });
        }
        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String raw = sw != null ? String.valueOf(sw.isChecked()) : input.getText().toString();
                        String err = validate(type, raw, jsonLike);
                        if (err != null) {
                            toast(err);
                            return;
                        }
                        FlagStore.setOverride(f.key, type, raw);
                        dlg.dismiss();
                        refilter();
                    }
                });
            }
        });
        dlg.show();
    }

    private void addManual() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText key = new EditText(act);
        key.setHint("Имя флага, например tt_cmt_new_emoji");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(key);

        final Spinner kind = new Spinner(act);
        kind.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"bool", "int", "long", "float", "double", "string"}));
        box.addView(kind);

        final EditText value = new EditText(act);
        value.setHint("Значение: true/false, число или строка");
        value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(value);

        final AlertDialog dlg = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Новый флаг")
                .setView(box)
                .setPositiveButton("Добавить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String k = key.getText().toString().trim();
                        if (k.isEmpty()) {
                            toast("Укажите имя флага");
                            return;
                        }
                        // Порядок в списке совпадает с константами FlagStore.BOOL…STRING.
                        int type = kind.getSelectedItemPosition();
                        String raw = value.getText().toString();
                        String err = validate(type, raw, false);
                        if (err != null) {
                            toast(err);
                            return;
                        }
                        FlagStore.setOverride(k, type, raw);
                        dlg.dismiss();
                        refilter();
                    }
                });
            }
        });
        dlg.show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Сбросить все переопределения?")
                .setMessage("Будет снято переопределений: " + FlagStore.overrideCount())
                .setPositiveButton("Сбросить", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        FlagStore.clearOverrides();
                        refilter();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** @return текст ошибки или null, если значение подходит */
    private static String validate(int type, String raw, boolean jsonLike) {
        String s = raw.trim();
        try {
            switch (type) {
                case FlagStore.BOOL:
                    if (!(s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false") || s.equals("1") || s.equals("0"))) {
                        return "Для bool нужно true или false";
                    }
                    break;
                case FlagStore.INT:
                    Integer.parseInt(s);
                    break;
                case FlagStore.LONG:
                    Long.parseLong(s);
                    break;
                case FlagStore.FLOAT:
                    Float.parseFloat(s);
                    break;
                case FlagStore.DOUBLE:
                    Double.parseDouble(s);
                    break;
                default:
                    if (jsonLike) {
                        if (s.startsWith("{")) {
                            new JSONObject(s);
                        } else if (s.startsWith("[")) {
                            new JSONArray(s);
                        } else {
                            return "Это JSON-флаг: значение должно быть корректным JSON";
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            return "Некорректное значение";
        }
        return null;
    }

    private static boolean parseBool(String s) {
        return s != null && (s.trim().equalsIgnoreCase("true") || s.trim().equals("1"));
    }

    // ---------------------------------------------------------------- мелочи оформления

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView chip(String s) {
        TextView c = text(s, 15, WHITE, true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(20), dp(11), dp(20), dp(11));
        styleChip(c, false);
        return c;
    }

    private void styleChip(TextView c, boolean on) {
        c.setBackground(round(on ? accent : CARD, 26));
        c.setTextColor(on ? Theme.onColor(accent) : WHITE);
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(8);
        return p;
    }

    private TextView action(String s, int color, View.OnClickListener l) {
        TextView t = text(s, 19, color, true);
        t.setPadding(dp(27), dp(16), dp(27), dp(16));
        t.setOnClickListener(l);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable corners(boolean top, boolean bottom) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        float r = dp(24);
        float t = top ? r : 0f;
        float b = bottom ? r : 0f;
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return g;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    private static String brief(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
