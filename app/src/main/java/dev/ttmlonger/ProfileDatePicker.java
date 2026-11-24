package dev.ttmlonger;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Read-only index of publication models received by TikTok. Does not invent old pages or pagination. */
final class ProfileDatePicker {
    private static final int MAX=1800;
    private static final LinkedHashMap<String,Post> posts=new LinkedHashMap<String,Post>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Post> e){return size()>MAX;}};
    private static final ThreadLocal<Boolean> reading=new ThreadLocal<>();
    static final class Post {
        final String aid,user,desc;final long seconds;
        Post(String a,String u,String d,long t){aid=a;user=u;desc=d;seconds=t;}
    }
    static void install(ClassLoader cl){
        Class<?> post=XposedHelpers.findClassIfExists(DownloadHooks.AWEME,cl);if(post!=null){XC_MethodHook hook=new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())remember(p.thisObject);}};for(String g:new String[]{"getAuthor","getCreateTime","getDesc"})try{XposedBridge.hookAllMethods(post,g,hook);}catch(Throwable ignored){}}
        for(String n:new String[]{"com.ss.android.ugc.aweme.profile.model.AwemeList","com.ss.android.ugc.aweme.profile.model.ProfileAwemeList","com.ss.android.ugc.aweme.profile.model.UserAwemeList","com.ss.android.ugc.aweme.profile.model.ProfilePostList","com.ss.android.ugc.aweme.feed.model.AwemeList"}){
            Class<?> c=XposedHelpers.findClassIfExists(n,cl);if(c==null)continue;
            for(String getter:new String[]{"getItems","getAwemeList"})try{XposedBridge.hookAllMethods(c,getter,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable()&&p.getResult() instanceof List&&InterfaceHooks.on("profile_date_jump")){List<?> list=(List<?>)p.getResult();if(list.size()<=300)for(Object o:list)remember(o);}}});}catch(Throwable ignored){}
        }
    }
    static void remember(Object o){
        if(o==null||CrashGuard.tripped||reading.get()!=null||!InterfaceHooks.on("profile_date_jump")||!o.getClass().getName().equals(DownloadHooks.AWEME))return;
        reading.set(true);try{
            Object id=DownloadHooks.call(o,"getAid"),author=DownloadHooks.call(o,"getAuthor"),u=DownloadHooks.call(author,"getUniqueId");
            if(id==null||u==null||!id.toString().matches("[0-9]{5,24}")||!u.toString().matches("[A-Za-z0-9_.]{1,64}"))return;
            long t=VideoInfo.parseEventTime(DownloadHooks.call(o,"getCreateTime"));if(t<1262304000L||t>System.currentTimeMillis()/1000L)return;
            Object d=DownloadHooks.call(o,"getDesc");String text=d==null?"":d.toString().trim();if(text.length()>150)text=text.substring(0,150)+"…";
            String user=u.toString().toLowerCase(Locale.ROOT),aid=id.toString();synchronized(posts){posts.put(user+"/"+aid,new Post(aid,user,text,t));}
        }catch(Throwable ignored){}finally{reading.remove();}
    }
    private static List<Post> get(String user){
        for(VideoTracker.Entry e:VideoTracker.candidates())remember(e.aweme);
        List<Post> out=new ArrayList<>();synchronized(posts){for(Post p:posts.values())if(p.user.equalsIgnoreCase(user))out.add(p);}return out;
    }
    static void show(Activity a,String user){
        if(user==null||!user.matches("[A-Za-z0-9_.]{1,64}"))return;
        StreakSheet s=new StreakSheet(a,"Перейти к дате");Ui ui=s.ui;List<Post> loaded=get(user);
        int now=Calendar.getInstance().get(Calendar.YEAR);int[] year={now},month={-1};boolean[] newest={true};
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(1);panel.setPadding(ui.dp(14),0,ui.dp(14),0);
        HorizontalScrollView years=new HorizontalScrollView(a);years.setHorizontalScrollBarEnabled(false);LinearLayout yr=new LinearLayout(a);years.addView(yr);panel.addView(years,ui.matchWrap());
        TextView all=TtStyle.neutral(a,"Весь год",42,14);panel.addView(all,ui.matchWrap());
        String[] months={"Янв","Фев","Март","Апр","Май","Июнь","Июль","Авг","Сент","Окт","Нояб","Дек"};TextView[] buttons=new TextView[12];
        for(int row=0;row<3;row++){LinearLayout line=new LinearLayout(a);for(int col=0;col<4;col++){int m=row*4+col;TextView b=TtStyle.neutral(a,months[m],40,13);buttons[m]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(40),1);lp.setMargins(ui.dp(2),ui.dp(3),ui.dp(2),0);line.addView(b,lp);}panel.addView(line);}
        TextView order=TtStyle.neutral(a,"Сначала новые",42,14);panel.addView(order,ui.matchWrap());TextView status=s.note("");panel.addView(status);
        final Runnable[] draw=new Runnable[1];draw[0]=()->{yr.removeAllViews();for(int y=now;y>=2016;y--){final int v=y;TextView b=ui.medium(String.valueOf(y),14,y==year[0]?Theme.onColor(Theme.accent()):Ui.INK);b.setGravity(Gravity.CENTER);b.setBackground(ui.round(y==year[0]?Theme.accent():Ui.FIELD,12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(62),ui.dp(38));lp.rightMargin=ui.dp(5);yr.addView(b,lp);b.setOnClickListener(w->{year[0]=v;draw[0].run();});}
            all.setText("Весь "+year[0]+" год");all.setBackground(ui.round(month[0]<0?Theme.accent():Ui.FIELD,14));all.setTextColor(month[0]<0?Theme.onColor(Theme.accent()):Ui.INK);
            for(int m=0;m<12;m++){buttons[m].setBackground(ui.round(m==month[0]?Theme.accent():Ui.FIELD,14));buttons[m].setTextColor(m==month[0]?Theme.onColor(Theme.accent()):Ui.INK);}
            order.setText(newest[0]?"Сначала новые ↓":"Сначала старые ↑");
            status.setText("Найдено в загруженных публикациях: "+matches(loaded,year[0],month[0],newest[0]).size()+" из "+loaded.size());
        };
        all.setOnClickListener(v->{month[0]=-1;draw[0].run();});for(int m=0;m<12;m++){final int idx=m;buttons[m].setOnClickListener(v->{month[0]=idx;draw[0].run();});}order.setOnClickListener(v->{newest[0]=!newest[0];draw[0].run();});draw[0].run();
        s.root.addView(panel);s.root.addView(s.note("Старые видео появятся после загрузки в самом TikTok. Полный архив сервером не предоставлен."));
        s.button("Показать публикации",true,()->{s.dialog.dismiss();results(a,user,matches(loaded,year[0],month[0],newest[0]));});s.show();
    }
    static List<Post> matches(List<Post> all,int year,int month,boolean newest){
        ArrayList<Post> out=new ArrayList<>();Calendar date=Calendar.getInstance(Zones.current());for(Post p:all){date.setTimeInMillis(p.seconds*1000L);if(date.get(Calendar.YEAR)==year&&(month<0||date.get(Calendar.MONTH)==month))out.add(p);}
        out.sort((a,b)->newest?Long.compare(b.seconds,a.seconds):Long.compare(a.seconds,b.seconds));return out;
    }
    private static void results(Activity a,String user,List<Post> all){
        StreakSheet s=new StreakSheet(a,"Публикации · "+all.size());Ui ui=s.ui;ScrollView sc=new ScrollView(a);LinearLayout rows=new LinearLayout(a);rows.setOrientation(1);rows.setPadding(ui.dp(16),0,ui.dp(16),0);sc.addView(rows);
        if(all.isEmpty())rows.addView(s.note("В выбранном периоде нет загруженных видео. Прокрутите профиль, чтобы TikTok загрузил больше."));
        for(Post p:all){LinearLayout row=ui.row(Icons.VIDEO,p.desc.isEmpty()?"Видео":p.desc,VideoInfo.fullTime(p.seconds,Zones.current()),null,true,null);row.setBackground(ui.round(Ui.FIELD,14));row.setOnClickListener(v->{String url="https://www.tiktok.com/@"+user+"/video/"+p.aid;try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).setPackage(a.getPackageName()));s.dialog.dismiss();}catch(Throwable t){android.content.ClipboardManager cm=(android.content.ClipboardManager)a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(android.content.ClipData.newPlainText("Видео",url));Toasts.show(a,"Ссылка скопирована: открыть видео не удалось");}});LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(5);rows.addView(row,lp);}
        s.root.addView(sc,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(480),(int)(a.getResources().getDisplayMetrics().heightPixels*.60f))));s.button("Закрыть",false,()->s.dialog.dismiss());s.show();
    }
}
