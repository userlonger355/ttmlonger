package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Local, bounded named shape library. No account data or external storage. */
final class AvatarLibrary {
    static final int MAX=32;
    static final class Saved {
        final String id,name;final AvatarShape shape;final boolean rotate;final int seconds;
        Saved(String i,String n,AvatarShape s,boolean r,int sec){id=i;name=n;shape=s;rotate=r;seconds=Math.max(3,Math.min(20,sec));}
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("ttmlonger_interface",0);}
    static String active(Context c){return prefs(c).getString("avatar_library_active","");}
    static List<Saved> list(Context c){
        ArrayList<Saved> out=new ArrayList<>();
        try{JSONArray all=new JSONArray(prefs(c).getString("avatar_library","[]"));for(int i=0;i<Math.min(MAX,all.length());i++){JSONObject o=all.optJSONObject(i);if(o==null)continue;String id=o.optString("id"),name=o.optString("name").trim();if(id.isEmpty()||name.isEmpty())continue;out.add(new Saved(id,name,AvatarShape.decode(o.optString("shape")),o.optBoolean("rotate"),o.optInt("seconds",9)));}}catch(Exception ignored){}
        return out;
    }
    static Saved find(Context c,String id){for(Saved s:list(c))if(s.id.equals(id))return s;return null;}
    private static boolean write(Context c,List<Saved> all){
        try{JSONArray a=new JSONArray();for(Saved s:all){JSONObject o=new JSONObject();o.put("id",s.id);o.put("name",s.name);o.put("shape",s.shape.encode());o.put("rotate",s.rotate);o.put("seconds",s.seconds);a.put(o);}return prefs(c).edit().putString("avatar_library",a.toString()).commit();}catch(Exception ignored){return false;}
    }
    static Saved save(Context c,String id,String name,AvatarShape shape,boolean rotate,int seconds){
        name=name==null?"":name.trim();if(name.isEmpty()||name.length()>40)return null;
        List<Saved> all=list(c);int index=-1;for(int i=0;i<all.size();i++)if(all.get(i).id.equals(id))index=i;
        if(index<0&&all.size()>=MAX)return null;
        Saved s=new Saved(index<0?UUID.randomUUID().toString():id,name,shape,rotate,seconds);
        if(index<0)all.add(s);else all.set(index,s);return write(c,all)?s:null;
    }
    static void apply(Context c,Saved s){prefs(c).edit().putString("avatar_library_active",s.id).apply();AvatarContours.saveCustom(c,s.shape,s.rotate,s.seconds);}
    private static boolean remove(Context c,String id){List<Saved> all=list(c);all.removeIf(s->s.id.equals(id));if(!write(c,all))return false;if(id.equals(active(c)))prefs(c).edit().remove("avatar_library_active").apply();return true;}
    static void show(Activity a){
        StreakSheet sh=new StreakSheet(a,"Мои формы");Ui ui=sh.ui;
        sh.root.addView(sh.note("Сохраняйте свои контуры и возвращайтесь к редактированию."));
        ScrollView scroll=new ScrollView(a);LinearLayout rows=new LinearLayout(a);rows.setOrientation(1);rows.setPadding(ui.dp(16),0,ui.dp(16),0);scroll.addView(rows);
        final Runnable[] draw=new Runnable[1];draw[0]=()->{
            rows.removeAllViews();List<Saved> all=list(a);
            if(all.isEmpty())rows.addView(sh.note("Пока нет сохранённых форм"));
            for(Saved s:all){
                LinearLayout card=new LinearLayout(a);card.setOrientation(1);card.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));card.setBackground(ui.round(Ui.FIELD,16));
                LinearLayout top=new LinearLayout(a);top.setGravity(Gravity.CENTER_VERTICAL);
                View sample=new View(a){final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)*.88f;Path path=s.shape.path(x,y,r,0);paint.setShader(new LinearGradient(0,0,getWidth(),getHeight(),new int[]{0xff6b54ad,0xffe98ba9},null,Shader.TileMode.CLAMP));c.drawPath(path,paint);paint.setShader(null);}};
                top.addView(sample,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
                LinearLayout labels=new LinearLayout(a);labels.setOrientation(1);labels.setPadding(ui.dp(8),0,0,0);TextView name=ui.medium(s.name,16,Ui.INK);name.setMaxLines(2);labels.addView(name);
                labels.addView(ui.text(s.shape.count+" точек · "+(s.rotate?"вращение":"без вращения")+(AvatarContours.isCustom()&&s.id.equals(active(a))?" · выбрана":""),12,Ui.MUTED,false));top.addView(labels,new LinearLayout.LayoutParams(0,-2,1));card.addView(top);
                LinearLayout actions=new LinearLayout(a);String[] titles={"Применить","Изменить","Удалить"};for(int i=0;i<3;i++){final int n=i;TextView b=TtStyle.neutral(a,titles[i],36,12);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(36),1);lp.setMargins(ui.dp(2),ui.dp(6),ui.dp(2),0);actions.addView(b,lp);b.setOnClickListener(v->{if(n==0){apply(a,s);sh.dialog.dismiss();Restart.offer(a);}else if(n==1){sh.dialog.dismiss();AvatarDesigner.edit(a,s);}else{StreakSheet confirm=new StreakSheet(a,"Удалить форму?");confirm.root.addView(confirm.note(s.name));confirm.button("Удалить",true,()->{if(remove(a,s.id))draw[0].run();else Toasts.show(a,"Не удалось удалить форму");confirm.dialog.dismiss();});confirm.button("Отмена",false,()->confirm.dialog.dismiss());confirm.show();}});}
                card.addView(actions);LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(8);rows.addView(card,lp);
            }
        };draw[0].run();
        sh.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(400),(int)(a.getResources().getDisplayMetrics().heightPixels*.53f))));
        sh.button("Создать форму",true,()->{sh.dialog.dismiss();AvatarDesigner.create(a);});sh.show();
    }
}
