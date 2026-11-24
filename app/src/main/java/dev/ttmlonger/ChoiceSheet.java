package dev.ttmlonger;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;

/** Shared single-choice bottom sheet. Selection is a draft until the explicit Save action. */
final class ChoiceSheet {
    interface Pick {void selected(int i);}
    static void show(Activity a,String title,String[] options,int current,Pick callback){
        open(a,title,options,null,current,false,callback);
    }
    static void quality(Activity a,String[] options,String[] descriptions,int current,Pick callback){
        open(a,"Качество видео",options,descriptions,current,true,callback);
    }
    private static void open(Activity a,String title,String[] options,String[] details,int current,boolean quality,Pick callback){
        if(options==null||options.length==0)return;
        StreakSheet s=new StreakSheet(a,title);Ui ui=s.ui;
        int[] pick={Math.max(0,Math.min(options.length-1,current))};
        LinearLayout preview=new LinearLayout(a);preview.setGravity(Gravity.CENTER_VERTICAL);preview.setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));preview.setBackground(ui.wash(Ui.DARK?0xff303037:0xffe9edf6,Ui.DARK?0xff22232a:0xfff7f3f8));
        Preview sample=new Preview(a,quality);preview.addView(sample,new LinearLayout.LayoutParams(ui.dp(68),ui.dp(62)));
        LinearLayout info=new LinearLayout(a);info.setOrientation(1);info.setPadding(ui.dp(12),0,0,0);info.addView(ui.text(quality?"Разрешение · схема":"Выбранный вариант",12,Ui.MUTED,false));
        TextView example=ui.medium("",17,Ui.INK);info.addView(example);
        TextView description=ui.text("",12,Ui.MUTED,false);info.addView(description);preview.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams pp=ui.matchWrap();pp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(10));s.root.addView(preview,pp);
        ScrollView scroll=new ScrollView(a);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows=new LinearLayout(a);rows.setOrientation(1);rows.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(4));scroll.addView(rows);
        LinearLayout[] cells=new LinearLayout[options.length];Mark[] marks=new Mark[options.length];
        for(int i=0;i<options.length;i++){
            final int n=i;String sub=details!=null&&i<details.length?details[i]:null;
            LinearLayout row=ui.row(-1,options[i],sub,null,false,null);row.setMinimumHeight(ui.dp(48));row.setPadding(ui.dp(14),ui.dp(9),ui.dp(14),ui.dp(9));
            Mark mark=new Mark(a);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(ui.dp(23),ui.dp(23));mp.setMarginStart(ui.dp(10));row.addView(mark,mp);
            cells[i]=row;marks[i]=mark;
            LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(5);rows.addView(row,lp);
        }
        Runnable update=()->{
            example.setText(options[pick[0]]);description.setText(details!=null&&pick[0]<details.length?details[pick[0]]:"Применится после сохранения");
            sample.index=pick[0];sample.invalidate();
            for(int i=0;i<cells.length;i++){
                boolean selected=i==pick[0];LinearLayout row=cells[i];row.setSelected(selected);
                GradientDrawable bg=new GradientDrawable();bg.setColor(selected?Ui.alpha(Theme.accent(),0x24):Ui.FIELD);bg.setCornerRadius(ui.dp(14));
                if(selected)bg.setStroke(ui.dp(1),Ui.alpha(Theme.accent(),0x90));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.alpha(Theme.accent(),0x30)),bg,null));
                marks[i].checked=selected;marks[i].invalidate();
            }
        };
        for(int i=0;i<cells.length;i++){final int n=i;cells[i].setOnClickListener(v->{pick[0]=n;update.run();if(Ui.motionEnabled()){sample.animate().cancel();sample.setAlpha(.6f);sample.animate().alpha(1f).setDuration(160).start();}});}
        update.run();
        int screen=a.getResources().getDisplayMetrics().heightPixels;
        s.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(options.length*(details!=null?69:55)),(int)(screen*.47f))));
        s.button("Сохранить",true,()->{callback.selected(pick[0]);s.dialog.dismiss();});s.show();
    }
    private static final class Mark extends View{
        boolean checked;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(Activity a){super(a);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)*.81f;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(r*.16f);p.setColor(checked?Theme.accent():Ui.MUTED);c.drawCircle(x,y,r,p);if(!checked)return;p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,r,p);p.setColor(Theme.onColor(Theme.accent()));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(r*.20f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path tick=new Path();tick.moveTo(x-r*.48f,y);tick.lineTo(x-r*.13f,y+r*.34f);tick.lineTo(x+r*.51f,y-r*.34f);c.drawPath(tick,p);p.setStyle(Paint.Style.FILL);}
    }
    private static final class Preview extends View{
        final boolean quality;int index;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Preview(Activity a,boolean q){super(a);quality=q;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
            float r=Math.min(w,h)*.13f;p.setStyle(Paint.Style.FILL);p.setColor(0xff17191f);c.drawRoundRect(0,0,w,h,r,r,p);
            if(quality){
                int[] bars={4,6,5,4,3,2,1,1};int count=bars[Math.min(index,bars.length-1)];
                for(int i=0;i<6;i++){float x=w*(.12f+i*.13f),top=h*(.68f-i*.075f);p.setColor(i<count?Theme.accent():0xff43454b);c.drawRoundRect(x,top,x+w*.085f,h*.84f,2,2,p);}
                p.setColor(0xffdddddd);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,w*.025f));c.drawRoundRect(w*.12f,h*.11f,w*.44f,h*.34f,3,3,p);p.setStyle(Paint.Style.FILL);
            }else{
                for(int i=0;i<3;i++){p.setColor(i==index%3?Theme.accent():0xff4c4e55);float y=h*(.22f+i*.27f);c.drawCircle(w*.20f,y,w*.055f,p);c.drawRoundRect(w*.33f,y-h*.025f,w*.83f,y+h*.025f,2,2,p);}
            }
        }
    }
}
