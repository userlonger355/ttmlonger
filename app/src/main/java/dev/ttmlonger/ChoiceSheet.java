package dev.ttmlonger;
import android.app.Activity;
import android.view.*;
import android.widget.*;
final class ChoiceSheet {
    interface Pick {void selected(int i);}
    static void show(Activity a,String title,String[] options,int current,Pick callback){
        StreakSheet s=new StreakSheet(a,title);Ui ui=s.ui;
        ScrollView scroll=new ScrollView(a);LinearLayout rows=new LinearLayout(a);rows.setOrientation(1);rows.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(8));scroll.addView(rows);
        int[] pick={Math.max(0,Math.min(options.length-1,current))};
        final Runnable[] draw=new Runnable[1];draw[0]=()->{rows.removeAllViews();for(int i=0;i<options.length;i++){
            final int n=i;boolean selected=i==pick[0];LinearLayout row=ui.row(-1,options[i],null,null,false,null);row.setBackground(ui.round(selected?Ui.alpha(Theme.accent(),0x25):Ui.FIELD,14));
            if(selected)row.addView(ui.icon(Icons.CHECK,Theme.accent(),20),new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)));
            row.setOnClickListener(v->{pick[0]=n;draw[0].run();});
            LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(5);rows.addView(row,lp);
        }};draw[0].run();
        s.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(options.length*61), (int)(a.getResources().getDisplayMetrics().heightPixels*.57f))));
        s.button("Сохранить",true,()->{callback.selected(pick[0]);s.dialog.dismiss();});s.show();
    }
}
