package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Нижняя шторка «Регион»: список стран, поиск и переключатель «Сохранять язык рекомендаций». */
final class RegionSheet {
    private final Activity act;
    private final Ui ui;
    private final Runnable onClose;
    private final int accent = TtStyle.primary();

    /** null в списке — пункт «Не подменять». */
    private List<Regions.Item> shown = new ArrayList<>();
    private BaseAdapter adapter;
    private boolean searching;
    private boolean changed;

    private RegionSheet(Activity act, Runnable onClose) {
        this.act = act;
        this.ui = new Ui(act);
        this.onClose = onClose;
    }

    static void show(Activity act, Runnable onClose) {
        new RegionSheet(act, onClose).open();
    }

    private void open() {
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Screen.markOwn(dialog);

        LinearLayout sheet = new LinearLayout(act);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ui.corners(Ui.DARK ? Ui.CARD : Ui.BG, 16, 0));

        // Ручка.
        View grab = new View(act);
        grab.setBackground(ui.round(Ui.alpha(Ui.INK, 0x40), 3));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.topMargin = ui.dp(8);
        sheet.addView(grab, gp);

        // Заголовок и крестик.
        FrameLayout head = new FrameLayout(act);
        TextView title = ui.text("Регион", 17, Ui.INK, true);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));
        FrameLayout close = new FrameLayout(act);
        close.setBackground(ui.ripple());
        close.setClickable(true);
        close.addView(ui.icon(Icons.CLOSE, Ui.INK, 22), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        head.addView(close, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END));
        sheet.addView(head, ui.matchWrap());

        TextView desc = ui.note("Регион SIM-карты: от него зависят лента и рекомендации. "
                + "Регион аккаунта для звонков и групп в личных сообщениях меняется в TikTok, а не здесь");
        sheet.addView(desc);

        // Переключатель языка.
        sheet.addView(ui.toggleRow(Icons.GLOBE, "Сохранять язык рекомендаций",
                "При подмене региона TikTok может изменить язык ленты. Включение сохраняет язык устройства",
                Prefs.keepLanguage, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setKeepLanguage(on);
                        changed = true;
                        if (!on && !Prefs.region.isEmpty()) {
                            Toast.makeText(act, "Страна локали подменяется после перезапуска TikTok",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }));

        // Поиск.
        EditText search = new EditText(act);
        search.setHint("Поиск региона");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.INK);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(ui.round(Ui.FIELD, 22));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        android.graphics.drawable.Drawable lens = Icons.of(Icons.SEARCH, Ui.MUTED);
        lens.setBounds(0, 0, ui.dp(20), ui.dp(20));
        search.setCompoundDrawablesRelative(lens, null, null, null);
        search.setCompoundDrawablePadding(ui.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });
        LinearLayout.LayoutParams sp = ui.matchWrap();
        sp.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        sheet.addView(search, sp);

        // Список.
        ListView list = new ListView(act);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setFastScrollEnabled(true);
        adapter = new RegionAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Regions.Item it = shown.get(position);
                Prefs.setRegion(it == null ? "" : it.iso);
                changed = true;
                adapter.notifyDataSetChanged();
            }
        });
        sheet.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Кнопка «Закрыть».
        TextView btn = TtStyle.accent(act, "Закрыть", 48f, 16f);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48));
        bp.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16));
        sheet.addView(btn, bp);

        filter("");

        dialog.setContentView(sheet);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (onClose != null) onClose.run();
                if (changed) Restart.offer(act);
            }
        });

        Window w = dialog.getWindow();
        if (w != null) {
            int height = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.92f);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.6f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(new Locale("ru"));
        searching = !q.isEmpty();
        List<Regions.Item> out = new ArrayList<>();
        if (!searching) out.add(null);
        for (Regions.Item i : Regions.all()) {
            if (!searching || i.lowerName.contains(q) || i.iso.toLowerCase(Locale.ROOT).equals(q)) out.add(i);
        }
        shown = out;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private final class RegionAdapter extends BaseAdapter {
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
            LinearLayout row;
            TextView flag;
            TextView name;
            View mark;
            if (convertView == null) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(ui.dp(52));
                row.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
                row.setBackground(ui.ripple());
                flag = ui.text("", 24, Ui.INK, false);
                flag.setGravity(Gravity.CENTER);
                row.addView(flag, new LinearLayout.LayoutParams(ui.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
                name = ui.medium("", 16f, Ui.INK);
                name.setPadding(ui.dp(12), 0, 0, 0);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                mark = ui.icon(Icons.CHECK, accent, 22);
                row.addView(mark);
                row.setTag(new View[]{flag, name, mark});
            } else {
                row = (LinearLayout) convertView;
                View[] t = (View[]) row.getTag();
                flag = (TextView) t[0];
                name = (TextView) t[1];
                mark = t[2];
            }

            Regions.Item it = shown.get(position);
            boolean selected;
            if (it == null) {
                flag.setText("🌍");
                name.setText("Не менять (реальный регион)");
                selected = Prefs.region.isEmpty();
            } else {
                flag.setText(it.flag);
                name.setText(it.name);
                selected = it.iso.equalsIgnoreCase(Prefs.region);
            }
            mark.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            return row;
        }
    }
}
