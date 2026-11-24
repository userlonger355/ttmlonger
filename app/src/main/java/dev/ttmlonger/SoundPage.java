package dev.ttmlonger;
import android.app.Activity;
import android.view.*;
import android.widget.TextView;
import java.util.*;
import de.robv.android.xposed.*;

final class SoundPage {
    private static final Map<String,Object> music=new LinkedHashMap<String,Object>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Object> e){return size()>48;}};
    static void install(ClassLoader cl){
        Class<?> type=XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.music.model.Music",cl);if(type==null)return;
        for(String getter:new String[]{"getTitle","getPlayUrl"})try{XposedBridge.hookAllMethods(type,getter,new XC_MethodHook(){
            protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())remember(p.thisObject);}
        });}catch(Throwable ignored){}
    }
    private static final ThreadLocal<Boolean> guard=new ThreadLocal<>();
    private static String id(Object m){Object id=DownloadHooks.call(m,"getMid");if(id==null)id=DownloadHooks.call(m,"getId");return id==null?"":id.toString();}
    private static void remember(Object m){
        if(m==null||guard.get()!=null)return;guard.set(true);try{String id=id(m);if(id.matches("[0-9]{5,24}"))synchronized(music){music.put(id,m);}}catch(Throwable ignored){}finally{guard.remove();}
    }
    private static String norm(String s){return s==null?"":s.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    static Object find(Activity a){
        if(a==null)return null;String name=a.getClass().getName().toLowerCase(Locale.ROOT);
        List<String> labels=new ArrayList<>();collect(a.getWindow().getDecorView(),labels,0,new int[]{1200});
        boolean sound=name.contains("musicdetail")||name.contains("music_detail")||name.contains("sounddetail");
        for(String l:labels)if(l.startsWith("звук пользователя ")||l.startsWith("original sound")||l.equals("использовать этот звук")||l.equals("use this sound"))sound=true;
        if(!sound)return null;
        List<Object> models; synchronized(music){models=new ArrayList<>(music.values());}
        for(VideoTracker.Entry e:VideoTracker.candidates()){Object m=DownloadHooks.call(e.aweme,"getMusic");if(m!=null)models.add(m);}
        String requested=null;try{android.content.Intent in=a.getIntent();if(in!=null)for(String key:new String[]{"music_id","musicId","mid"}){Object v=in.getExtras()==null?null:in.getExtras().get(key);if(v!=null&&v.toString().matches("[0-9]{5,24}")){requested=v.toString();break;}}}catch(Throwable ignored){}
        if(requested!=null)for(Object m:models)if(requested.equals(id(m)))return m;
        Object match=null;String matchId="";
        for(Object m:models){
            Object title=DownloadHooks.call(m,"getTitle");String text=norm(title==null?"":title.toString());String mid=id(m);
            if(mid.isEmpty()||text.length()<4||!labels.contains(text))continue;
            if(match!=null&&!matchId.equals(mid))return null;match=m;matchId=mid;
        }return match;
    }
    private static void collect(View v,List<String> out,int depth,int[] budget){
        if(v==null||v.getVisibility()!=View.VISIBLE||depth>30||--budget[0]<0)return;
        if(v instanceof TextView){String s=norm(((TextView)v).getText().toString());if(!s.isEmpty())out.add(s);}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),out,depth+1,budget);}
    }
}
