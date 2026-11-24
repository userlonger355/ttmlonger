package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;
import org.json.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Opt-in third-party segment lookup. No audio capture, identity, credentials or uploads. */
final class SponsorSegments {
    private static final String KEY="sponsor_segments";
    private static volatile boolean on;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService NET=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ttm-ad-segments");t.setDaemon(true);return t;});
    private static final Map<String,List<long[]>> cache=new LinkedHashMap<String,List<long[]>>(32,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,List<long[]>> e){return size()>100;}
    };
    private static final Set<String> allowed=new HashSet<>();
    private static String current="",pending="";
    private static long lastTry,lastTick;
    private static TextView undo;
    static boolean enabled(Context c){return c.getSharedPreferences("ttmlonger_interface",0).getBoolean(KEY,false);}
    static void set(Context c,boolean value){on=value;c.getSharedPreferences("ttmlonger_interface",0).edit().putBoolean(KEY,value).apply();if(!value){cache.clear();allowed.clear();removeUndo();}}
    static boolean valid(long start,long end,long duration){return start>=0&&end>start+400&&end<=duration&&duration>0;}
    static void install(Context c,ClassLoader cl){
        on=enabled(c);
        Class<?> type=XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.controller.PlayerController",cl);
        if(type==null)return;
        for(Method method:type.getDeclaredMethods()){
            if(!method.getName().equals("onPlayProgressChange")||!Arrays.equals(method.getParameterTypes(),new Class<?>[]{String.class,long.class,long.class})||method.getReturnType()!=void.class)continue;
            XposedBridge.hookMethod(method,new XC_MethodHook(){
                protected void afterHookedMethod(MethodHookParam p){
                    if(!on||CrashGuard.tripped||p.hasThrowable()||Looper.myLooper()!=Looper.getMainLooper())return;
                    try{progress(p.thisObject,(String)p.args[0]);}catch(Throwable ignored){}
                }
            });
            android.util.Log.i("TTMLonger","Sponsor segment progress hook installed");
        }
    }
    private static void progress(Object controller,String aid)throws Exception{
        if(aid==null||!aid.matches("[0-9]{5,24}"))return;
        Activity a=ScreenWatcher.top();
        if(a==null||!a.hasWindowFocus()||!FeedButtons.inFeed(a)||Screen.isOwnDecor(a.getWindow().getDecorView()))return;
        Object post=DownloadHooks.call(controller,"LLJJJIL");
        if(!aid.equals(DownloadHooks.call(post,"getAid")))return;
        if(!aid.equals(current)){current=aid;allowed.clear();removeUndo();lastTick=0;}
        long now=android.os.SystemClock.uptimeMillis();
        if(now-lastTick<150)return;lastTick=now;
        List<long[]> parts=cache.get(aid);
        if(parts==null){lookup(aid,now);return;}
        if(parts.isEmpty())return;
        Object manager=DownloadHooks.call(controller,"getPlayerManager");if(manager==null)return;
        Object pos=DownloadHooks.call(manager,"getCurrentPosition"),len=DownloadHooks.call(manager,"getDuration");
        if(!(pos instanceof Number)||!(len instanceof Number))return;
        long position=((Number)pos).longValue(),duration=((Number)len).longValue();
        for(long[] segment:parts){
            String key=segment[0]+":"+segment[1];
            if(!valid(segment[0],segment[1],duration)||allowed.contains(key)||position<segment[0]||position>=segment[1]-400)continue;
            Method seek=manager.getClass().getMethod("seek",float.class);
            if(seek.getReturnType()!=void.class)return;
            allowed.add(key);
            seek.invoke(manager,100f*Math.min(duration-1,segment[1])/duration);
            showUndo(a,controller,aid,manager,seek,segment[0],duration);return;
        }
    }
    private static void lookup(String aid,long now){
        if(!pending.isEmpty()||now-lastTry<2000)return;
        pending=aid;lastTry=now;
        NET.execute(()->{
            List<long[]> out=null;
            try{
                HttpsOnly h=new HttpsOnly(aid);
                out=h.get();
            }catch(Throwable ignored){}
            final List<long[]> result=out;
            MAIN.post(()->{pending="";if(on&&result!=null)cache.put(aid,result);});
        });
    }
    private static final class HttpsOnly{
        final String aid;
        HttpsOnly(String s){aid=s;}
        List<long[]> get()throws Exception{
            javax.net.ssl.HttpsURLConnection c=(javax.net.ssl.HttpsURLConnection)new URL("https://margy.narez.xyz/api/sb?ids="+aid).openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(5000);
            c.setRequestProperty("Accept","application/json");
            try{
                if(c.getResponseCode()!=200)return null;
                ByteArrayOutputStream b=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(b.size()+n>65536)throw new IOException("oversized");b.write(buf,0,n);}}
                JSONObject response=new JSONObject(new String(b.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                JSONObject segments=response.optJSONObject("segments");if(segments==null)return null;
                JSONArray array=segments.optJSONArray(aid);List<long[]> list=new ArrayList<>();
                if(array==null)return list;
                for(int i=0;i<Math.min(50,array.length());i++){JSONArray row=array.optJSONArray(i);if(row==null||row.length()<2)continue;long s=row.optLong(0,-1),e=row.optLong(1,-1);if(s>=0&&e>s+400&&e<=86400000)list.add(new long[]{s,e});}
                return list;
            }finally{c.disconnect();}
        }
    }
    private static void removeUndo(){if(undo!=null){if(undo.getParent() instanceof ViewGroup)((ViewGroup)undo.getParent()).removeView(undo);undo=null;}}
    private static void showUndo(Activity a,Object controller,String aid,Object manager,Method seek,long start,long duration){
        removeUndo();View root=a.getWindow().getDecorView();if(!(root instanceof ViewGroup))return;
        Ui ui=new Ui(a);TextView pill=ui.text("Реклама пропущена · Вернуть",14,0xffffffff,true);pill.setTag(Ui.OWN_TAG);
        pill.setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(10));pill.setBackground(ui.round(0xe6202020,24));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.bottomMargin=ui.dp(150);
        ((ViewGroup)root).addView(pill,lp);undo=pill;
        pill.setOnClickListener(v->{try{
            if(on&&aid.equals(current)&&aid.equals(DownloadHooks.call(DownloadHooks.call(controller,"LLJJJIL"),"getAid"))&&manager==DownloadHooks.call(controller,"getPlayerManager"))
                seek.invoke(manager,100f*start/duration);
        }catch(Throwable ignored){}removeUndo();});
        pill.postDelayed(()->{if(undo==pill)removeUndo();},4000);
    }
}
