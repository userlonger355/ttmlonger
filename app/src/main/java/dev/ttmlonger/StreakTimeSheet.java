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
        StreakSheet sh=new StreakSheet(a,"Время отправки");Ui ui=sh.ui;
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER);
        NumberPicker hours=wheel(a,ui,23,initial/60,"Часы");
        NumberPicker minutes=wheel(a,ui,59,initial%60,"Минуты");
        int wheelHeight=ui.dp(Math.min(174,Math.max(100,a.getResources().getDisplayMetrics().heightPixels/a.getResources().getDisplayMetrics().density*.24f)));
        row.addView(hours,new LinearLayout.LayoutParams(ui.dp(76),wheelHeight));
        TextView colon=ui.text(":",30,Ui.INK,true);colon.setGravity(Gravity.CENTER);row.addView(colon,new LinearLayout.LayoutParams(ui.dp(24),wheelHeight));
        row.addView(minutes,new LinearLayout.LayoutParams(ui.dp(76),wheelHeight));
        sh.root.addView(row,new LinearLayout.LayoutParams(-1,-2));
        sh.root.addView(sh.note("Без времени огоньки проверяются раз в несколько часов"));
        sh.button("Без времени",false,()->{sh.dialog.dismiss();callback.time(-1);});
        sh.button("Готово",true,()->{hours.clearFocus();minutes.clearFocus();int value=hours.getValue()*60+minutes.getValue();sh.dialog.dismiss();callback.time(value);});
        sh.show();
    }
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
        return p;
    }
}
