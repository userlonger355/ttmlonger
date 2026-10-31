package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Окно «Цветовой акцент»: выбор цвета, поле с HEX-кодом и кнопка «Применить». */
final class ColorDialog {
    interface OnApply {
        void apply(int rgb);
    }

    private ColorDialog() {
    }

    static void show(final Activity act, int initialRgb, final OnApply cb) {
        final Ui ui = new Ui(act);
                final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Screen.markOwn(dialog);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
        box.setBackground(ui.round(Ui.DARK ? Ui.CARD : Ui.BG, 16));

        TextView title = ui.text("Цвет акцента", 17, Ui.INK, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title, ui.matchWrap());
        box.addView(ui.space(16));

        final ColorPickerView picker = new ColorPickerView(act);
        box.addView(picker, ui.matchWrap());
        box.addView(ui.space(8));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final View preview = new View(act);
        row.addView(preview, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));

        final EditText hex = new EditText(act);
        hex.setSingleLine(true);
        hex.setTextColor(Ui.INK);
        hex.setTextSize(17);
        hex.setTypeface(Typeface.DEFAULT_BOLD);
        hex.setBackground(ui.round(Ui.FIELD, 12));
        hex.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hp.leftMargin = ui.dp(14);
        row.addView(hex, hp);
        box.addView(row, ui.matchWrap());
        box.addView(ui.space(16));

        TextView apply = TtStyle.accent(act, "Применить", 48f, 16f);
        box.addView(apply, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));

        final boolean[] busy = {false};
        final int start = initialRgb & 0xFFFFFF;

        picker.setListener(new ColorPickerView.Listener() {
            @Override
            public void onColor(int rgb) {
                if (busy[0]) return;
                busy[0] = true;
                hex.setText(String.format("#%06X", rgb & 0xFFFFFF));
                preview.setBackground(ui.oval(0xFF000000 | rgb));
                busy[0] = false;
            }
        });
        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (busy[0]) return;
                Integer v = parse(s.toString());
                if (v == null) return;
                busy[0] = true;
                picker.setColor(v, false);
                preview.setBackground(ui.oval(0xFF000000 | v));
                busy[0] = false;
            }
        });
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Integer rgb = parse(hex.getText().toString());
                if (rgb == null) {
                    Toast.makeText(act, "Введите цвет в формате #RRGGBB", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                cb.apply(rgb);
            }
        });

        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        picker.setColor(start, true);
    }

    /** "#RRGGBB" или "RRGGBB"; иначе null. */
    static Integer parse(String s) {
        if (s == null) return null;
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6) return null;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
