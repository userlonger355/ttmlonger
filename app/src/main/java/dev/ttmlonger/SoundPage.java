package dev.ttmlonger;
import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import java.util.*;
import de.robv.android.xposed.*;

final class SoundPage {
    private static final Map<String,Object> music=new LinkedHashMap<String,Object>(32,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Object> e){return size()>128;}};
    private static final ThreadLocal<Boolean> guard=new ThreadLocal<>();
    private static volatile int hooks;
    private static volatile String status="Нет попыток";
    static String diagnostics(){synchronized(music){return "Хуки: "+hooks+" · модели: "+music.size()+" · "+status;}}
    static void install(ClassLoader cl){
        for(String n:new String[]{"com.ss.android.ugc.aweme.music.model.Music","com.ss.android.ugc.aweme.music.model.MusicDetail"}) {
            Class<?> type=XposedHelpers.findClassIfExists(n,cl);if(type==null)continue;
            XC_MethodHook hook=new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable()){observe(p.thisObject);observe(p.getResult());}}};
            for(String getter:new String[]{"getTitle","getPlayUrl","getMid","getId","getAuthor","getMusic","setTitle","setPlayUrl","setMid"})try{hooks+=XposedBridge.hookAllMethods(type,getter,hook).size();}catch(Throwable ignored){}
            try{hooks+=XposedBridge.hookAllConstructors(type,hook).size();}catch(Throwable ignored){}
        }
        Class<?> aweme=XposedHelpers.findClassIfExists(DownloadHooks.AWEME,cl);
        if(aweme!=null)try{hooks+=XposedBridge.hookAllMethods(aweme,"getMusic",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())observe(p.getResult());}}).size();}catch(Throwable ignored){}
    }
    static String id(Object m){if(m==null)return "";for(String key:new String[]{"mid","id","music_id"}){Object v=RepostTimes.readField(m,key);if(v!=null&&v.toString().matches("[0-9]{5,24}"))return v.toString();}return "";}
    static void observe(Object m){
        if(m==null||guard.get()!=null||CrashGuard.tripped||!m.getClass().getName().equals("com.ss.android.ugc.aweme.music.model.Music"))return;
        guard.set(true);try{String id=id(m);if(!id.isEmpty())synchronized(music){Object old=music.get(id);if(old==null||hasAudio(m)||!hasAudio(old))music.put(id,m);}}catch(Throwable ignored){}finally{guard.remove();}
    }
    private static boolean hasAudio(Object m){for(String k:new String[]{"play_url","play_url_model","audio_url","music_url"}){Object v=RepostTimes.readField(m,k);if(v instanceof String&&!((String)v).isEmpty()||!VideoSaver.urlsOf(v).isEmpty())return true;}return false;}
    private static String norm(String s){return Targets.norm(s).toLowerCase(Locale.ROOT);}
    private static String titleKey(String s){String v=norm(s);for(String prefix:new String[]{"звук пользователя ","оригинальный звук – ","оригинальный звук - ","original sound - ","original sound – ","original sound by "})if(v.startsWith(prefix))return v.substring(prefix.length()).trim();return v;}
    private static String value(Object m,String key){Object v=RepostTimes.readField(m,key);return v==null?"":norm(v.toString());}
    private static void ids(Bundle bundle,Set<String> out,int depth){if(bundle==null||depth>2)return;try{for(String key:bundle.keySet()){Object v=bundle.get(key);String k=key.toLowerCase(Locale.ROOT).replace("_","");if(v instanceof Bundle)ids((Bundle)v,out,depth+1);else if((k.equals("musicid")||k.equals("mid")||k.equals("soundid")||k.equals("musicdetailid"))&&v!=null&&v.toString().matches("[0-9]{5,24}"))out.add(v.toString());}}catch(Throwable ignored){}}
    private static boolean musicType(Object o){return o!=null&&o.getClass().getName().equals("com.ss.android.ugc.aweme.music.model.Music");}
    private static boolean detailType(String n){n=n.toLowerCase(Locale.ROOT);return n.contains("musicdetail")||n.contains("music_detail")||n.contains("sounddetail")||n.contains("music.detail")||n.contains("music.model.musicdetail")||n.contains("musicheader");}
    private static void readDirect(Object o,Map<String,Object> out,Set<Object> seen,int depth){
        if(o==null||depth>3||seen.size()>100||!seen.add(o))return;
        if(musicType(o)){String mid=id(o);if(!mid.isEmpty())prefer(out,mid,o);return;}
        if(o instanceof Bundle){Bundle args=(Bundle)o;try{for(String key:args.keySet()){Object v=args.get(key);if(musicType(v)||v instanceof Bundle||v!=null&&detailType(v.getClass().getName()))readDirect(v,out,seen,depth+1);}}catch(Throwable ignored){}return;}
        boolean allowed=detailType(o.getClass().getName());if(!allowed)return;
        for(Class<?> type=o.getClass();type!=null&&type.getName().startsWith("com.ss.");type=type.getSuperclass())for(java.lang.reflect.Field field:type.getDeclaredFields())try{
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive())continue;
            field.setAccessible(true);Object v=field.get(o);if(musicType(v)||v instanceof Bundle||v!=null&&detailType(v.getClass().getName()))readDirect(v,out,seen,depth+1);
        }catch(Throwable ignored){}
        for(String key:new String[]{"music","music_detail","music_model"})readDirect(RepostTimes.readField(o,key),out,seen,depth+1);
    }
    private static void fragments(Object manager,Map<String,Object> out,Set<Object> seen,int depth){
        if(manager==null||depth>4)return;Object all=DownloadHooks.call(manager,"getFragments");if(!(all instanceof List))return;
        for(Object fragment:(List<?>)all){if(fragment==null)continue;Object view=DownloadHooks.call(fragment,"getView");if(!(view instanceof View)||!((View)view).isShown())continue;
            if(detailType(fragment.getClass().getName())){readDirect(fragment,out,seen,0);readDirect(DownloadHooks.call(fragment,"getArguments"),out,seen,0);}
            fragments(DownloadHooks.call(fragment,"getChildFragmentManager"),out,seen,depth+1);
        }
    }
    private static void headerModels(View v,Map<String,Object> out,Set<Object> seen,int height,int[] budget,int depth){
        if(v==null||depth>24||--budget[0]<0||!v.isShown())return;android.graphics.Rect r=new android.graphics.Rect();if(!v.getGlobalVisibleRect(r)||r.top>height*.40f)return;
        readDirect(v.getTag(),out,seen,0);if(detailType(v.getClass().getName()))readDirect(v,out,seen,0);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)headerModels(g.getChildAt(i),out,seen,height,budget,depth+1);}
    }
    static Object find(Activity a){return find(a,false);}
    static Object find(Activity a,boolean confirmedSoundSheet){
        if(a==null||a.isFinishing()||a.isDestroyed()){status="Нет страницы";return null;}
        View root=a.getWindow().getDecorView();List<String> labels=new ArrayList<>();collect(root,labels,0,new int[]{1600},root.getHeight());
        String name=a.getClass().getName().toLowerCase(Locale.ROOT);
        boolean sound=confirmedSoundSheet||name.contains("musicdetail")||name.contains("music_detail")||name.contains("sounddetail");
        for(String l:labels)if(l.startsWith("звук пользователя ")||l.startsWith("original sound")||l.equals("использовать этот звук")||l.equals("use this sound"))sound=true;
        if(!sound){status="Не распознана страница";return null;}
        Map<String,Object> direct=new LinkedHashMap<>();Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        try{readDirect(a,direct,seen,0);if(a.getIntent()!=null)readDirect(a.getIntent().getExtras(),direct,seen,0);fragments(DownloadHooks.call(a,"getSupportFragmentManager"),direct,seen,0);headerModels(root,direct,seen,root.getHeight(),new int[]{900},0);}catch(Throwable ignored){}
        List<Object> models; synchronized(music){models=new ArrayList<>(music.values());}models.addAll(direct.values());
        for(VideoTracker.Entry e:VideoTracker.candidates()){Object m=RepostTimes.readField(e.aweme,"music");if(m!=null)models.add(m);}
        Set<String> requested=new HashSet<>();try{android.content.Intent in=a.getIntent();if(in!=null){ids(in.getExtras(),requested,0);android.net.Uri uri=in.getData();if(uri!=null){for(String k:new String[]{"music_id","musicId","mid","sound_id"}){String v=uri.getQueryParameter(k);if(v!=null&&v.matches("[0-9]{5,24}"))requested.add(v);}}}}catch(Throwable ignored){}
        Map<String,Object> exact=new LinkedHashMap<>(),titleMatches=new LinkedHashMap<>(),artistMatches=new LinkedHashMap<>();
        for(Object m:models){
            String mid=id(m);if(mid.isEmpty())continue;
            if(requested.contains(mid))prefer(exact,mid,m);
            String title=value(m,"title");if(title.isEmpty())title=value(m,"music_name");if(title.length()<2)continue;
            boolean match=false;for(String l:labels)if(l.equals(title)||titleKey(l).equals(titleKey(title))){match=true;break;}
            if(!match)continue;prefer(titleMatches,mid,m);
            String artist=value(m,"author");if(artist.isEmpty())artist=value(m,"author_name");
            if(!artist.isEmpty())for(String l:labels)if(l.equals(artist)||l.startsWith(artist+" |")||l.startsWith(artist+" ·")){prefer(artistMatches,mid,m);break;}
        }
        if(exact.size()==1){status="Совпадение ID";return exact.values().iterator().next();}
        if(direct.size()==1){String mid=direct.keySet().iterator().next();if(titleMatches.isEmpty()||titleMatches.containsKey(mid)){Object nativeModel=direct.get(mid),better=titleMatches.get(mid);status="Модель текущей страницы";return better!=null&&!hasAudio(nativeModel)&&hasAudio(better)?better:nativeModel;}}
        if(artistMatches.size()==1){status="Совпадение названия и автора";return artistMatches.values().iterator().next();}
        if(titleMatches.size()==1){status="Совпадение названия";return titleMatches.values().iterator().next();}
        status=titleMatches.size()>1?"Несколько совпадений":"Нет модели с таким названием";return null;
    }
    private static void prefer(Map<String,Object> m,String id,Object v){Object old=m.get(id);if(old==null||!hasAudio(old)&&hasAudio(v))m.put(id,v);}
    static Object matchingVideo(Object m){String mid=id(m);if(mid.isEmpty())return null;for(VideoTracker.Entry e:VideoTracker.candidates())if(mid.equals(id(RepostTimes.readField(e.aweme,"music")))){Object v=e.video!=null?e.video:RepostTimes.readField(e.aweme,"video");if(v!=null)return v;}return null;}
    private static void collect(View v,List<String> out,int depth,int[] budget,int height){
        if(v==null||v.getVisibility()!=View.VISIBLE||depth>30||--budget[0]<0||Ui.OWN_TAG.equals(v.getTag()))return;
        if(v instanceof TextView){android.graphics.Rect box=new android.graphics.Rect();if(v.getGlobalVisibleRect(box)&&box.centerY()<height*.55f){String s=norm(((TextView)v).getText().toString());if(!s.isEmpty())out.add(s);CharSequence d=v.getContentDescription();if(d!=null&&!norm(d.toString()).isEmpty())out.add(norm(d.toString()));}}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),out,depth+1,budget,height);}
    }
}
