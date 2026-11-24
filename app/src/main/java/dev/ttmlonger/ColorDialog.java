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
        box.setBackground(ui.glassCard(0xff8562bd,24));

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

        LinearLayout sample=new LinearLayout(act);
        sample.setOrientation(LinearLayout.VERTICAL);
        sample.setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(12));
        sample.setBackground(ui.glassCard(0xff8562bd,18));
        sample.addView(ui.text("Предпросмотр",14,Ui.INK,true));
        LinearLayout tabs=new LinearLayout(act);
        String[] names={"Подписки","Рекомендации"};
        final TextView active=ui.medium(names[1],13,Ui.INK);
        tabs.addView(ui.medium(names[0],13,Ui.MUTED),new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(active,new LinearLayout.LayoutParams(0,-2,1));
        sample.addView(tabs,ui.matchWrap());
        final View underline=new View(act);
        sample.addView(underline,new LinearLayout.LayoutParams(-1,ui.dp(3)));
        sample.addView(ui.space(10));
        LinearLayout controls=new LinearLayout(act);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        final TextView sampleButton=ui.text("Кнопка",14,Color.WHITE,true);
        sampleButton.setGravity(Gravity.CENTER);
        controls.addView(sampleButton,new LinearLayout.LayoutParams(0,ui.dp(36),1));
        final TextView heart=ui.text("  ♥  ",25,Ui.INK,true);
        controls.addView(heart);
        final TextView sampleSwitch=ui.text("●",20,Color.WHITE,true);
        sampleSwitch.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        sampleSwitch.setPadding(0,0,ui.dp(5),0);
        controls.addView(sampleSwitch,new LinearLayout.LayoutParams(ui.dp(46),ui.dp(26)));
        sample.addView(controls,ui.matchWrap());
        box.addView(sample,ui.matchWrap());
        box.addView(ui.space(12));
        TextView apply = TtStyle.accent(act, "Применить", 48f, 16f);
        box.addView(apply, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));

        java.util.function.IntConsumer render=rgb->{
            int color=0xff000000|rgb;
            int contrast=Color.luminance(color)>0.40?Color.BLACK:Color.WHITE;
            active.setTextColor(Ui.blend(color,Ui.INK,.30f));
            underline.setBackgroundColor(color);
            heart.setTextColor(color);
            sampleButton.setBackground(ui.round(color,18));
            sampleButton.setTextColor(contrast);
            sampleSwitch.setBackground(ui.round(color,14));
            sampleSwitch.setTextColor(contrast);
            apply.setBackground(ui.round(color,24));
            apply.setTextColor(contrast);
        };
        final boolean[] busy = {false};
        final int start = initialRgb & 0xFFFFFF;

        picker.setListener(new ColorPickerView.Listener() {
            @Override
            public void onColor(int rgb) {
                if (busy[0]) return;
                busy[0] = true;
                hex.setText(String.format("#%06X", rgb & 0xFFFFFF));
                preview.setBackground(ui.oval(0xFF000000 | rgb));
                render.accept(rgb);
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
                render.accept(v);
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

        android.widget.ScrollView scroll=new android.widget.ScrollView(act);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.addView(box);
        dialog.setContentView(scroll);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if(w!=null) {
            w.setLayout((int)(act.getResources().getDisplayMetrics().widthPixels*.92f),ViewGroup.LayoutParams.WRAP_CONTENT);
            Ui.blurBehind(w,ui.dp(36));
        }
        render.accept(start);
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
