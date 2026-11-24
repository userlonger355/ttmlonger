package dev.ttmlonger;

import android.app.Activity;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.EditText;
import java.util.Locale;

final class StreakTimeSheet {
    interface Chosen {void time(int value);}
    static void show(Activity a,int initial,Chosen callback){
        open(a,"Время отправки",initial,false,0,callback);
    }
    static void personal(Activity a,String name,int initial,int mode,Chosen callback){open(a,"Время · "+name,initial,true,mode,callback);}
    private static void open(Activity a,String title,int initial,boolean personal,int mode,Chosen callback){
        StreakSheet sh=new StreakSheet(a,title);Ui ui=sh.ui;
        if(personal)sh.root.addView(sh.note("Сейчас: "+StreakSchedule.label(mode)+". Настройка применяется только к этому чату."));
        initial=initial<0||initial>=1440?21*60:initial;
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER);
        NumberPicker hours=wheel(a,ui,23,initial/60,"Часы");
        NumberPicker minutes=wheel(a,ui,59,initial%60,"Минуты");
        int wheelHeight=ui.dp(Math.min(174,Math.max(100,a.getResources().getDisplayMetrics().heightPixels/a.getResources().getDisplayMetrics().density*.24f)));
        row.addView(hours,new LinearLayout.LayoutParams(ui.dp(76),wheelHeight));
        TextView colon=ui.text(":",30,Ui.INK,true);colon.setGravity(Gravity.CENTER);row.addView(colon,new LinearLayout.LayoutParams(ui.dp(24),wheelHeight));
        row.addView(minutes,new LinearLayout.LayoutParams(ui.dp(76),wheelHeight));
        sh.root.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout manual=new LinearLayout(a);manual.setGravity(Gravity.CENTER);manual.setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(10));manual.setVisibility(View.GONE);
        EditText hh=field(a,ui,"Часы"),mm=field(a,ui,"Минуты");
        manual.addView(hh,new LinearLayout.LayoutParams(ui.dp(84),ui.dp(62)));
        TextView sep=ui.text(":",30,Ui.INK,true);sep.setGravity(Gravity.CENTER);manual.addView(sep,new LinearLayout.LayoutParams(ui.dp(26),ui.dp(62)));
        manual.addView(mm,new LinearLayout.LayoutParams(ui.dp(84),ui.dp(62)));sh.root.addView(manual,new LinearLayout.LayoutParams(-1,-2));
        boolean[] typing={false};
        TextView modeButton=ui.medium("⌨  Ввести своё время",14,Theme.accent());modeButton.setGravity(Gravity.CENTER);modeButton.setPadding(0,ui.dp(8),0,ui.dp(8));sh.root.addView(modeButton,ui.matchWrap());
        modeButton.setOnClickListener(v->{
            if(!typing[0]){
                hh.setText(String.format(Locale.ROOT,"%02d",hours.getValue()));mm.setText(String.format(Locale.ROOT,"%02d",minutes.getValue()));
                typing[0]=true;row.setVisibility(View.GONE);manual.setVisibility(View.VISIBLE);modeButton.setText("Вернуться к колёсам");
                hh.requestFocus();hh.selectAll();hh.post(()->{android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);if(im!=null)im.showSoftInput(hh,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
            }else{
                int value=parseTime(hh.getText().toString(),mm.getText().toString());if(value<0){hh.setError("Часы 0–23, минуты 0–59");return;}
                hours.setValue(value/60);minutes.setValue(value%60);hideKeyboard(a,hh);hh.clearFocus();mm.clearFocus();typing[0]=false;manual.setVisibility(View.GONE);row.setVisibility(View.VISIBLE);modeButton.setText("⌨  Ввести своё время");
            }
        });
        sh.root.addView(sh.note("Без времени огоньки проверяются раз в несколько часов"));
        if(personal)sh.button("Общее время",false,()->{sh.dialog.dismiss();callback.time(StreakSchedule.INHERIT);});
        sh.button("Когда огонёк серый",false,()->{sh.dialog.dismiss();callback.time(-1);});
        sh.button("Готово",true,()->{hours.clearFocus();minutes.clearFocus();int value=typing[0]?parseTime(hh.getText().toString(),mm.getText().toString()):hours.getValue()*60+minutes.getValue();if(value<0){hh.setError("Часы 0–23, минуты 0–59");return;}hideKeyboard(a,hh);sh.dialog.dismiss();callback.time(value);});
        sh.show();
    }
    static int parseTime(String h,String m){
        try{if(h==null||m==null||!h.trim().matches("[0-9]{1,2}")||!m.trim().matches("[0-9]{1,2}"))return -1;int hour=Integer.parseInt(h.trim()),minute=Integer.parseInt(m.trim());return hour<24&&minute<60?hour*60+minute:-1;}catch(NumberFormatException e){return -1;}
    }
    private static void hideKeyboard(Activity a,View v){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);if(im!=null)im.hideSoftInputFromWindow(v.getWindowToken(),0);}
    private static EditText field(Activity a,Ui ui,String label){EditText e=new EditText(a);e.setSingleLine(true);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});e.setGravity(Gravity.CENTER);e.setTextSize(29);e.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);e.setTextColor(Ui.INK);e.setHintTextColor(Ui.MUTED);e.setHint("00");e.setContentDescription(label);e.setSelectAllOnFocus(true);e.setBackground(ui.round(Ui.FIELD,16));return e;}
    private static NumberPicker wheel(Activity a,Ui ui,int max,int value,String label){
        android.content.Context ctx=new android.view.ContextThemeWrapper(a,Ui.DARK?android.R.style.Theme_Material:android.R.style.Theme_Material_Light);
        NumberPicker p=new NumberPicker(ctx);
        p.setMinValue(0);p.setMaxValue(max);p.setFormatter(v->String.format(Locale.ROOT,"%02d",v));
        p.setValue(Math.max(0,Math.min(max,value)));p.setWrapSelectorWheel(true);
        p.setContentDescription(label);p.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        if(Build.VERSION.SDK_INT>=29){p.setTextColor(Ui.INK);p.setTextSize(ui.dp(29));p.setSelectionDividerHeight(ui.dp(1));}
        for(int i=0;i<p.getChildCount();i++){
            View v=p.getChildAt(i);if(v instanceof EditText){EditText e=(EditText)v;e.setTextColor(Ui.INK);e.setTextSize(29);e.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);}
        }
        p.setHapticFeedbackEnabled(true);
        final long[] last={-100};p.setOnValueChangedListener((picker,old,value)->{if(old==value||!picker.isShown())return;long now=android.os.SystemClock.uptimeMillis();if(now-last[0]>=35){last[0]=now;picker.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);}});
        return p;
    }
}
