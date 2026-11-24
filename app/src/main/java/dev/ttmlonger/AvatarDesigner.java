package dev.ttmlonger;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import android.content.res.ColorStateList;

/** Draft editor: handles stay still during editing; the small preview can rotate independently. */
final class AvatarDesigner {
    final Activity act;final StreakSheet sheet;final Ui ui;
    AvatarShape draft;boolean rotate;int seconds;
    final CanvasSample edit,small;
    TextView stats;SeekBar nodes,round,speed;
    boolean syncing;
    static void show(Activity a){new AvatarDesigner(a).open();}
    private AvatarDesigner(Activity a){
        act=a;sheet=new StreakSheet(a,"Конструктор формы");ui=sheet.ui;
        draft=AvatarContours.customShape();rotate=AvatarContours.rotating();seconds=AvatarContours.speed();
        edit=new CanvasSample(true);small=new CanvasSample(false);
    }
    private void open(){
        ScrollView scroll=new ScrollView(act);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content=new LinearLayout(act);content.setOrientation(1);content.setPadding(ui.dp(14),0,ui.dp(14),ui.dp(8));scroll.addView(content);
        LinearLayout preview=new LinearLayout(act);preview.setOrientation(1);preview.setGravity(Gravity.CENTER_HORIZONTAL);preview.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(12));preview.setBackground(ui.round(Ui.FIELD,22));
        preview.addView(ui.text("Потяните точки контура",14,Ui.INK,true));preview.addView(edit,new LinearLayout.LayoutParams(-1,ui.dp(166)));
        LinearLayout bottom=new LinearLayout(act);bottom.setGravity(Gravity.CENTER_VERTICAL);bottom.addView(small,new LinearLayout.LayoutParams(ui.dp(54),ui.dp(54)));
        LinearLayout info=new LinearLayout(act);info.setOrientation(1);info.setPadding(ui.dp(10),0,0,0);info.addView(ui.medium("В ленте и чатах",14,Ui.INK));stats=ui.text("",12,Ui.MUTED,false);info.addView(stats);bottom.addView(info,new LinearLayout.LayoutParams(0,-2,1));preview.addView(bottom,ui.matchWrap());content.addView(preview,ui.matchWrap());
        content.addView(ui.header("Основа контура"));
        LinearLayout presets=new LinearLayout(act);String[] titles={"Звезда","Волна","Многоугольник"};int[] count={10,12,6},depth={45,18,0},r={20,90,15};
        for(int i=0;i<titles.length;i++){final int n=i;TextView b=TtStyle.neutral(act,titles[i],40,12);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(40),1);lp.setMargins(ui.dp(2),0,ui.dp(2),0);presets.addView(b,lp);b.setOnClickListener(v->{draft=AvatarShape.preset(count[n],depth[n],r[n]);sync();});}
        content.addView(presets,ui.matchWrap());
        nodes=slider(content,"Количество точек",3,16,draft.count,v->{draft=draft.resized(v);redraw();});
        round=slider(content,"Скругление",0,100,draft.rounding,v->{draft=draft.withRounding(v);redraw();});
        content.addView(ui.header("Движение"));
        content.addView(ui.toggleRow(-1,"Вращать контур","Фото остаётся неподвижным",rotate,on->{rotate=on;redraw();}));
        speed=slider(content,"Оборот, сек.",3,20,seconds,v->{seconds=v;redraw();});
        sheet.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(545),(int)(act.getResources().getDisplayMetrics().heightPixels*.67f))));
        sheet.button("Сбросить форму",false,()->{draft=AvatarShape.initial();sync();});
        sheet.button("Сохранить и применить",true,()->{AvatarContours.saveCustom(act,draft,rotate,seconds);sheet.dialog.dismiss();Restart.offer(act);});
        sync();sheet.show();
    }
    interface Change{void value(int v);}
    private SeekBar slider(LinearLayout host,String name,int min,int max,int initial,Change fn){
        LinearLayout row=new LinearLayout(act);row.setOrientation(1);row.setPadding(ui.dp(8),ui.dp(5),ui.dp(8),ui.dp(2));
        TextView label=ui.text(name+" · "+initial,14,Ui.INK,true);row.addView(label);SeekBar bar=new SeekBar(act);bar.setMax(max-min);bar.setProgress(initial-min);bar.setContentDescription(name);bar.setProgressTintList(ColorStateList.valueOf(Theme.accent()));bar.setThumbTintList(ColorStateList.valueOf(Theme.accent()));row.addView(bar,ui.matchWrap());host.addView(row,ui.matchWrap());
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
            public void onProgressChanged(SeekBar b,int p,boolean user){label.setText(name+" · "+(p+min));if(user&&!syncing)fn.value(p+min);}
        });return bar;
    }
    private void sync(){syncing=true;if(nodes!=null)nodes.setProgress(draft.count-3);if(round!=null)round.setProgress(draft.rounding);syncing=false;redraw();}
    private void redraw(){stats.setText(draft.count+" точек · "+draft.rounding+"% скругления");edit.invalidate();small.invalidate();}
    private final class CanvasSample extends View{
        final boolean controls;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int active=-1;
        CanvasSample(boolean c){super(act);controls=c;setContentDescription(c?"Редактор контура: перетащите точки от центра или к центру":"Предпросмотр аватарки");}
        float radius(){return Math.min(getWidth(),getHeight())*(controls?.40f:.46f);}
        @Override protected void onDraw(Canvas c){
            float x=getWidth()/2f,y=getHeight()/2f,r=radius();if(r<=0)return;
            boolean motion=!controls&&rotate&&Ui.motionEnabled();float angle=motion?(android.os.SystemClock.uptimeMillis()%(seconds*1000L))*360f/(seconds*1000f):0f;
            Path path=draft.path(x,y,r,angle);
            if(controls){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(ui.dp(1));p.setColor(Ui.alpha(Ui.INK,45));c.drawCircle(x,y,r,p);c.drawLine(x-r,y,x+r,y,p);c.drawLine(x,y-r,x,y+r,p);}
            int save=c.save();c.clipPath(path);p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(x-r,y-r,x+r,y+r,new int[]{0xff6559a6,0xffb78ad1,0xffee839e},null,Shader.TileMode.CLAMP));c.drawRect(x-r,y-r,x+r,y+r,p);p.setShader(null);
            p.setColor(0xfff5d9e6);c.drawCircle(x,y-r*.22f,r*.29f,p);c.drawOval(new RectF(x-r*.63f,y+r*.12f,x+r*.63f,y+r*1.05f),p);c.restoreToCount(save);
            if(controls){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(ui.dp(1.7f));p.setColor(Theme.accent());c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
                for(int i=0;i<draft.count;i++){float px=x+draft.x(i,r,0),py=y+draft.y(i,r,0);p.setColor(i==active?Theme.accent():0xffffffff);c.drawCircle(px,py,ui.dp(i==active?6:4),p);p.setColor(Theme.accent());c.drawCircle(px,py,ui.dp(2),p);}
            }
            if(motion&&isShown())postInvalidateDelayed(33);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(!controls)return false;float x=e.getX()-getWidth()/2f,y=e.getY()-getHeight()/2f,r=radius();if(r<=0)return false;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:float best=ui.dp(27);active=-1;for(int i=0;i<draft.count;i++){float d=(float)Math.hypot(x-draft.x(i,r,0),y-draft.y(i,r,0));if(d<best){best=d;active=i;}}
                    if(active<0)return false;getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;
                case MotionEvent.ACTION_MOVE:if(active<0)return false;double theta=Math.PI*2*active/draft.count-Math.PI/2;draft=draft.withRadius(active,(float)((x*Math.cos(theta)+y*Math.sin(theta))/r));redraw();return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:active=-1;getParent().requestDisallowInterceptTouchEvent(false);invalidate();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
            }return active>=0;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
