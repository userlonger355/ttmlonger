package dev.ttmlonger;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import cat.narezany.margyt.plugin.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.ref.WeakReference;
import java.net.*;

/** In-process trusted-plugin host. This is not a security sandbox. */
public final class MtpRuntime {
    private static Context app;private static ClassLoader host;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<String,Slot> slots=new ConcurrentHashMap<>();
    private static volatile Slot[] active=new Slot[0];
    private static final ThreadLocal<Boolean> nested=new ThreadLocal<>();
    private static WeakReference<Activity> top=new WeakReference<>(null);
    private static String lastAccount;
    static final class Row{
        final int kind,min,max,def;final String title,detail,key;final String[] options;final PluginContext.Tapped action;
        Row(int k,String t,String d,String key,String[] o,int min,int max,int def,PluginContext.Tapped a){kind=k;title=t;detail=d;this.key=key;options=o;this.min=min;this.max=max;this.def=def;action=a;}
    }
    static final class Slot{
        final String id;final MtpArchive pkg;final List<Row> rows=new CopyOnWriteArrayList<>();
        volatile MargyPlugin plugin;volatile boolean enabled;volatile String status="Выключен";
        volatile TextView offer,progress;
        Slot(MtpArchive p){pkg=p;id=p.id;}
    }
    static void install(Context c,ClassLoader cl){
        app=c.getApplicationContext();if(app==null)app=c;host=cl;
        File[] files=dir().listFiles();if(files==null)return;
        for(File f:files){if(!f.isFile()||!f.getName().endsWith(".mtp"))continue;
            try{MtpArchive p=MtpArchive.read(new FileInputStream(f));if(!f.getName().equals(p.id+".mtp"))continue;Slot s=new Slot(p);slots.put(p.id,s);}
            catch(Throwable ignored){}
            if(slots.size()>=16)break;
        }
        // Only the exact previously approved bytes are eligible for automatic startup.
        if(!CrashGuard.tripped)for(Slot s:all())if(prefs().getBoolean("on:"+s.id,false)&&s.pkg.digest.equals(prefs().getString("approved:"+s.id,"")))start(s);
    }
    static File dir(){File f=new File(app.getFilesDir(),"ttmlonger-mtp");f.mkdirs();return f;}
    private static SharedPreferences prefs(){return app.getSharedPreferences("ttmlonger_mtp",0);}
    static List<Slot> all(){ArrayList<Slot> a=new ArrayList<>(slots.values());a.sort(Comparator.comparing(s->s.id));return a;}
    static boolean running(){return active.length!=0&&!CrashGuard.tripped;}
    private static void publish(){active=slots.values().stream().filter(s->s.enabled&&s.plugin!=null).sorted(Comparator.comparing(s->s.id)).toArray(Slot[]::new);}
    static boolean ready(){return app!=null&&Build.VERSION.SDK_INT>=26;}
    static void add(MtpArchive p)throws Exception{
        if(!ready()||slots.size()>=16&&!slots.containsKey(p.id))throw new IOException("Лимит плагинов");
        Slot old=slots.get(p.id);if(old!=null)stop(old);
        p.save(new File(dir(),p.id+".mtp"));
        prefs().edit().putBoolean("on:"+p.id,false).remove("approved:"+p.id).apply();
        slots.put(p.id,new Slot(p));publish();
    }
    static void remove(Slot s){stop(s);if(!new File(dir(),s.id+".mtp").delete()){s.status="Не удалось удалить файл";return;}slots.remove(s.id);prefs().edit().remove("approved:"+s.id).remove("on:"+s.id).apply();}
    static void approve(Slot s){prefs().edit().putString("approved:"+s.id,s.pkg.digest).putBoolean("on:"+s.id,true).commit();start(s);}
    private static void start(Slot s){
        if(!ready()||CrashGuard.tripped||s.enabled)return;
        try{
            s.rows.clear();
            ClassLoader sdk=MargyPlugin.class.getClassLoader();
            // Module classes must retain their identity, with TikTok classes as a fallback.
            ClassLoader parent=new ClassLoader(sdk){
                @Override protected Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException{
                    try{return super.loadClass(n,resolve);}catch(ClassNotFoundException e){return host.loadClass(n);}
                }
            };
            dalvik.system.InMemoryDexClassLoader loader=new dalvik.system.InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(s.pkg.dex).asReadOnlyBuffer(),parent);
            Class<?> entry=Class.forName(s.pkg.manifest.getString("entry"),false,loader);
            if(entry.getClassLoader()!=loader||!MargyPlugin.class.isAssignableFrom(entry))throw new IllegalArgumentException("entry");
            File folder=new File(dir(),s.id+"-"+s.pkg.digest.substring(0,16));if(!folder.exists()&&!folder.mkdirs())throw new IOException("folder");
            // Keep the validated manifest in the plugin folder; dex is loaded directly from memory.
            FlagStore.writeAtomic(new File(folder,"manifest.json"),s.pkg.manifest.toString());
            if(s.pkg.icon!=null)try(FileOutputStream out=new FileOutputStream(new File(folder,"icon.png"))){out.write(s.pkg.icon);}
            MargyPlugin plugin=(MargyPlugin)entry.getConstructor().newInstance();
            plugin.attach(new PluginContext(app,s.id,folder,line->android.util.Log.i("TTMPlugin",s.id+": "+String.valueOf(line))));
            s.plugin=plugin;s.enabled=true;publish();
            plugin.onStart(app);s.status="Запущен";
        }catch(Throwable e){fault(s,e);}
    }
    static void stop(Slot s){
        MargyPlugin p=s.plugin;s.enabled=false;s.plugin=null;publish();
        prefs().edit().putBoolean("on:"+s.id,false).apply();
        if(p!=null)try{p.onStop();}catch(Throwable ignored){}
        clearViews(s);s.rows.clear();s.status="Выключен";
    }
    private static void fault(Slot s,Throwable e){s.enabled=false;s.plugin=null;publish();s.status="Ошибка: "+e.getClass().getSimpleName();prefs().edit().putBoolean("on:"+s.id,false).apply();MAIN.post(()->clearViews(s));}
    private interface Call{void run(MargyPlugin p);}
    private static void events(Call cb){if(!running())return;for(Slot s:active){MargyPlugin p=s.plugin;if(p!=null)try{cb.run(p);}catch(Throwable e){fault(s,e);}}}
    static void created(Activity a){events(p->p.onActivityCreated(a));}
    static void resumed(Activity a){top=new WeakReference<>(a);String uid=account();if(!Objects.equals(uid,lastAccount)){lastAccount=uid;events(p->p.onAccountChanged(uid));}events(p->{p.onActivityResumed(a);p.onScreen(a,a.getClass().getName());});}
    static void paused(Activity a){events(p->p.onActivityPaused(a));for(Slot s:active)clearViews(s);if(top.get()==a)top.clear();}
    private interface Transform<T>{T run(MargyPlugin p,T value);}
    private static <T>T map(T value,Transform<T> f){
        if(!running()||Boolean.TRUE.equals(nested.get()))return value;
        nested.set(true);try{for(Slot s:active){MargyPlugin p=s.plugin;if(p==null)continue;try{T out=f.run(p,value);if(out!=null)value=out;}catch(Throwable e){fault(s,e);}}return value;}finally{nested.remove();}
    }
    static int colour(int c){return map(c,(p,v)->p.onColour(v));}
    static String region(String key,String value){return map(value,(p,v)->p.onRegion(key,v));}
    static String name(String uid,String value){return map(value,(p,v)->p.onName(uid,v));}
    static CharSequence text(CharSequence value){return map(value,(p,v)->p.onText(v));}
    static List feed(List value){return map(value,(p,v)->p.onFeed(new ArrayList(v)));}
    public static Activity activity(){Activity a=top.get();return a!=null&&!a.isFinishing()&&!a.isDestroyed()?a:null;}
    public static String account(){return StreakHooks.liveOwner();}
    public static int accent(){return TtStyle.primary();}
    public static String videoId(){VideoTracker.Entry e=VideoInfo.currentEntry(activity());return e==null?null:e.aid();}
    public static long videoTime(){VideoTracker.Entry e=VideoInfo.currentEntry(activity());return e==null?0:VideoInfo.metaOf(e).created;}
    private static Slot enabled(String id){Slot s=slots.get(id);return s!=null&&s.enabled&&!CrashGuard.tripped?s:null;}
    public static void ui(String id,Runnable r){MAIN.post(()->{Slot s=enabled(id);if(s!=null)try{r.run();}catch(Throwable e){fault(s,e);}});}
    public static void away(String id,Runnable r){Slot s=enabled(id);if(s==null)return;new Thread(()->{if(enabled(id)!=s)return;try{r.run();}catch(Throwable e){fault(s,e);}},"ttm-mtp-"+id).start();}
    public static void row(String id,int k,String title,String detail,String key,String[] options,int min,int max,int def,PluginContext.Tapped action){
        Slot s=enabled(id);if(s==null)return;if(s.rows.size()>=64||title==null||title.length()>160||key!=null&&key.length()>80||max<min||(long)max-min>100000)throw new IllegalArgumentException("row");
        s.rows.add(new Row(k,title,detail,key,options==null?null:options.clone(),min,max,Math.max(min,Math.min(max,def)),action));
    }
    static void changed(Slot s,String key){if(enabled(s.id)!=s)return;try{s.plugin.onSettingsChanged(key);}catch(Throwable e){fault(s,e);}}
    static boolean open(Slot s,Activity a){if(enabled(s.id)!=s)return false;try{
        if(s.plugin.onOpenSettings(a))return true;
        for(String name:new String[]{"openSettings","showSettings","showSettingsDialog"})for(Class<?> type:new Class<?>[]{Activity.class,Context.class})try{
            java.lang.reflect.Method m=s.plugin.getClass().getMethod(name,type);
            if(m.getReturnType()==void.class){m.invoke(s.plugin,a);return true;}if(m.getReturnType()==boolean.class&&Boolean.TRUE.equals(m.invoke(s.plugin,a)))return true;
        }catch(NoSuchMethodException ignored){}
        return false;
    }catch(Throwable e){fault(s,e);return false;}}
    public static void window(String id,String title,String message){ui(id,()->{Activity a=activity();if(a==null)return;AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle(title).setMessage(message).setPositiveButton("Готово",null).create();Screen.markOwn(d);d.show();Ui.style(d);});}
    public static void choose(String id,String title,String[] options,PluginContext.Selected callback){if(options==null||options.length==0||options.length>32)throw new IllegalArgumentException("options");String[] copy=options.clone();ui(id,()->{Activity a=activity();if(a==null)return;AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle(title).setItems(copy,(x,n)->{if(callback!=null)ui(id,()->callback.selected(n));}).setNegativeButton("Отмена",null).create();Screen.markOwn(d);d.show();Ui.style(d);});}
    private static void detach(TextView v){if(v!=null&&v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    private static void clearViews(Slot s){detach(s.offer);detach(s.progress);s.offer=null;s.progress=null;}
    private static TextView pill(Activity a,String text,int gravity){View root=a.getWindow().getDecorView();if(!(root instanceof FrameLayout))return null;Ui u=new Ui(a);TextView t=u.text(text,14,0xffffffff,true);t.setTag(Ui.OWN_TAG);t.setPadding(u.dp(14),u.dp(10),u.dp(14),u.dp(10));t.setBackground(u.round(0xee25232d,20));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,gravity|Gravity.CENTER_HORIZONTAL);lp.topMargin=u.dp(50);lp.bottomMargin=u.dp(140);((FrameLayout)root).addView(t,lp);return t;}
    public static void offer(String id,String label,PluginContext.Tapped cb){ui(id,()->{Slot s=enabled(id);Activity a=activity();if(s==null||a==null)return;detach(s.offer);s.offer=pill(a,label,Gravity.BOTTOM);if(s.offer!=null)s.offer.setOnClickListener(v->{detach(s.offer);s.offer=null;if(cb!=null)ui(id,cb::tapped);});});}
    public static void progress(String id,String title,int pct){ui(id,()->{Slot s=enabled(id);if(s==null)return;detach(s.progress);s.progress=null;Activity a=activity();if(a!=null&&title!=null){s.progress=pill(a,title+" · "+Math.max(0,Math.min(100,pct))+"%",Gravity.TOP);if(pct>=100&&s.progress!=null){TextView v=s.progress;v.postDelayed(()->detach(v),1500);}}});}
    public static byte[] fetch(String id,String url){if(enabled(id)==null||Looper.myLooper()==Looper.getMainLooper())return null;try{ByteArrayOutputStream out=new ByteArrayOutputStream();transfer(url,out,8*1024*1024);return out.toByteArray();}catch(Exception e){return null;}}
    private static void transfer(String address,OutputStream out,long max)throws Exception{
        URL url=new URL(address);if(!"https".equals(url.getProtocol()))throw new IOException("HTTPS required");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);
        try{if(c.getResponseCode()!=200)throw new IOException("HTTP");try(InputStream in=c.getInputStream()){byte[] b=new byte[16384];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("limit");out.write(b,0,n);}}}finally{c.disconnect();}
    }
    private static final Set<String> downloads=ConcurrentHashMap.newKeySet();
    public static void download(String id,String url,String name,PluginContext.Downloaded cb){
        if(name==null||!name.matches("[A-Za-z0-9._-]{1,80}")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("name");
        String key=id+"/"+name;if(enabled(id)==null||!downloads.add(key))throw new IllegalStateException();
        away(id,()->{File folder=new File(app.getFilesDir(),"ttmlonger-plugin-downloads/"+id);folder.mkdirs();File dest=new File(folder,name),temp=new File(folder,name+".part");boolean ok=false;
            try{try(FileOutputStream out=new FileOutputStream(temp)){transfer(url,out,64L*1024*1024);}ok=temp.renameTo(dest);}catch(Exception ignored){}finally{temp.delete();downloads.remove(key);}
            final boolean success=ok;if(cb!=null)ui(id,()->cb.finished(dest,success));
        });
    }
}
