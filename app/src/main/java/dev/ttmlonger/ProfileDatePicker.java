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
        Calendar calendar=Calendar.getInstance(Zones.current());int now=calendar.get(Calendar.YEAR),latest=now;long max=0;
        for(Post post:loaded)max=Math.max(max,post.seconds);if(max>0){calendar.setTimeInMillis(max*1000L);latest=calendar.get(Calendar.YEAR);}
        int[] year={latest},month={-1};boolean[] newest={true};int accent=Theme.accent();
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(1);panel.setPadding(ui.dp(16),ui.dp(2),ui.dp(16),ui.dp(8));scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));
        panel.addView(label(ui,"Год"));
        HorizontalScrollView years=new HorizontalScrollView(a);years.setHorizontalScrollBarEnabled(false);LinearLayout yr=new LinearLayout(a);yr.setPadding(0,ui.dp(2),0,ui.dp(2));years.addView(yr);panel.addView(years,ui.matchWrap());
        Map<Integer,TextView> yearCells=new LinkedHashMap<>();
        final Runnable[] draw=new Runnable[1];
        for(int y=now;y>=2016;y--){final int value=y;TextView cell=ui.medium(String.valueOf(y),14,Ui.INK);cell.setGravity(Gravity.CENTER);cell.setIncludeFontPadding(false);cell.setPadding(0,0,0,0);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(62),ui.dp(44));lp.rightMargin=ui.dp(6);yr.addView(cell,lp);yearCells.put(y,cell);cell.setOnClickListener(v->{year[0]=value;month[0]=-1;draw[0].run();});}
        panel.addView(label(ui,"Период"));TextView all=ui.medium("",14,Ui.INK);all.setGravity(Gravity.CENTER);all.setIncludeFontPadding(false);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,ui.dp(44));ap.bottomMargin=ui.dp(6);panel.addView(all,ap);
        String[] months={"Янв","Фев","Март","Апр","Май","Июнь","Июль","Авг","Сент","Окт","Нояб","Дек"};LinearLayout[] cells=new LinearLayout[12];TextView[] names=new TextView[12],counts=new TextView[12];
        for(int row=0;row<3;row++){LinearLayout line=new LinearLayout(a);
            for(int col=0;col<4;col++){final int m=row*4+col;LinearLayout cell=new LinearLayout(a);cell.setOrientation(1);cell.setGravity(Gravity.CENTER);cell.setPadding(ui.dp(2),ui.dp(5),ui.dp(2),ui.dp(5));cells[m]=cell;
                names[m]=ui.medium(months[m],13,Ui.INK);names[m].setGravity(Gravity.CENTER);names[m].setIncludeFontPadding(false);cell.addView(names[m],ui.matchWrap());
                counts[m]=ui.text("",11,Ui.MUTED,false);counts[m].setGravity(Gravity.CENTER);counts[m].setIncludeFontPadding(false);cell.addView(counts[m],ui.matchWrap());
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(52),1);if(col>0)lp.leftMargin=ui.dp(6);lp.bottomMargin=ui.dp(6);line.addView(cell,lp);cell.setOnClickListener(v->{month[0]=m;draw[0].run();});
            }panel.addView(line,ui.matchWrap());
        }
        TextView order=ui.medium("",14,Ui.INK);order.setGravity(Gravity.CENTER);order.setBackground(ui.round(Ui.FIELD,14));LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,ui.dp(44));op.topMargin=ui.dp(4);panel.addView(order,op);
        TextView status=ui.medium("",14,Ui.INK);status.setGravity(Gravity.CENTER);status.setPadding(0,ui.dp(12),0,ui.dp(3));panel.addView(status,ui.matchWrap());
        TextView info=ui.text("Показаны только загруженные видео. Для старых публикаций прокрутите профиль.",12,Ui.MUTED,false);info.setGravity(Gravity.CENTER);info.setPadding(ui.dp(6),0,ui.dp(6),ui.dp(4));panel.addView(info,ui.matchWrap());
        int height=Math.min(ui.dp(408),(int)(a.getResources().getDisplayMetrics().heightPixels*.54f));s.root.addView(scroll,new LinearLayout.LayoutParams(-1,height));
        TextView submit=s.button("Показать публикации",true,()->{List<Post> hits=matches(loaded,year[0],month[0],newest[0]);if(hits.isEmpty())return;s.dialog.dismiss();results(a,user,hits);});
        draw[0]=()->{
            for(Map.Entry<Integer,TextView> e:yearCells.entrySet()){boolean on=e.getKey()==year[0];e.getValue().setBackground(ui.round(on?accent:Ui.FIELD,12));e.getValue().setTextColor(on?Theme.onColor(accent):Ui.INK);}
            int yearly=matches(loaded,year[0],-1,true).size();all.setText("Весь "+year[0]+" год  ·  "+yearly);all.setBackground(ui.round(month[0]<0?Ui.alpha(accent,0x30):Ui.FIELD,14));all.setTextColor(month[0]<0?accent:Ui.INK);
            for(int m=0;m<12;m++){int count=matches(loaded,year[0],m,true).size();boolean on=m==month[0];cells[m].setBackground(ui.round(on?accent:Ui.FIELD,12));names[m].setTextColor(on?Theme.onColor(accent):Ui.INK);counts[m].setTextColor(on?Theme.onColor(accent):Ui.MUTED);counts[m].setText(count==0?"—":String.valueOf(count));cells[m].setContentDescription(months[m]+", публикаций: "+count);}
            order.setText(newest[0]?"↓  Сначала новые":"↑  Сначала старые");int n=matches(loaded,year[0],month[0],newest[0]).size();status.setText(n==0?"В этом периоде ничего не загружено":"Найдено "+n+" из "+loaded.size()+" публикаций");submit.setEnabled(n>0);submit.setAlpha(n>0?1f:.45f);submit.setText(n>0?"Показать публикации · "+n:"Нет публикаций");
        };
        all.setOnClickListener(v->{month[0]=-1;draw[0].run();});order.setOnClickListener(v->{newest[0]=!newest[0];draw[0].run();});draw[0].run();s.show();
        years.post(()->{TextView selected=yearCells.get(year[0]);if(selected!=null)years.smoothScrollTo(Math.max(0,selected.getLeft()-years.getWidth()/2+selected.getWidth()/2),0);});
    }
    private static TextView label(Ui ui,String text){TextView t=ui.medium(text,12,Ui.MUTED);t.setPadding(0,ui.dp(7),0,ui.dp(6));return t;}
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
