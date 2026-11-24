package dev.ttmlonger;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class FontPicker {
    static void show(Activity a,Runnable changed,Runnable custom) {
        Ui ui=new Ui(a);
        Dialog d=new Dialog(a);Screen.markOwn(d);
        LinearLayout box=new LinearLayout(a);box.setOrientation(1);box.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(20));
        box.setBackground(ui.round(Ui.BG,24));
        TextView title=ui.text("Шрифт",21,Ui.INK,true);title.setGravity(Gravity.CENTER);box.addView(title,ui.matchWrap());
        ScrollView scroll=new ScrollView(a);LinearLayout rows=new LinearLayout(a);rows.setOrientation(1);scroll.addView(rows);
        box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=-1;i<FontHooks.PRESET_IDS.length;i++){
            final int index=i;String name=i<0?"TikTok Sans (по умолчанию)":FontHooks.PRESET_NAMES[i];
            LinearLayout row=new LinearLayout(a);row.setOrientation(1);row.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));
            boolean selected=i<0?Prefs.fontName.isEmpty():Prefs.fontName.equals("builtin:"+FontHooks.PRESET_IDS[i]);
            TextView label=ui.text(name+(selected?"  ✓":""),17,selected?Theme.accent():Ui.INK,true);
            TextView sample=ui.text("Aa Bb Cc · Аа Бб Вв · 0123",14,Ui.MUTED,false);
            if(i>=0){Typeface face=FontHooks.preset(FontHooks.PRESET_IDS[i]);label.setTypeface(face);sample.setTypeface(face);}
            row.addView(label);row.addView(sample);rows.addView(row,ui.matchWrap());
            row.setOnClickListener(v->{if(index<0)FontHooks.reset();else FontHooks.choose(FontHooks.PRESET_IDS[index]);changed.run();d.dismiss();});
        }
        rows.addView(ui.note("Другие гарнитуры (Montserrat, Nunito, Comfortaa, Caveat, Lobster, Monocraft) можно подключить своим файлом. Они не встроены."));
        rows.addView(ui.menuRow(Icons.SLIDERS,"Выбрать файл шрифта","TTF / OTF",null,v->{d.dismiss();custom.run();}));
        TextView close=TtStyle.accent(a,"Закрыть",48,16);close.setOnClickListener(v->d.dismiss());box.addView(close,ui.matchWrap());
        d.setContentView(box);Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);}
        d.show();if(w!=null){w.setLayout(-1,Math.min(a.getResources().getDisplayMetrics().heightPixels*4/5,ui.dp(650)));Ui.blurBehind(w,ui.dp(32));}
    }
}
