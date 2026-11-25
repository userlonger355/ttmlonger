package dev.ttmlonger;
import java.util.*;
import de.robv.android.xposed.*;
/** Capture only explicitly typed models and known response fields. No raw payload logging. */
final class NativeModelObserver {
    private static final ThreadLocal<Boolean> active=new ThreadLocal<>();
    private static final Set<Class<?>> adapters=new HashSet<>();
    private static boolean wanted(String n){return n.equals(DownloadHooks.AWEME)||n.equals(AvatarHooks.USER)||n.equals(BadgeHooks.PROFILE)||n.equals("com.ss.android.ugc.aweme.music.model.Music")||n.startsWith("com.ss.android.ugc.aweme.comment.model.")||n.contains(".music.model.MusicDetail")||n.contains(".profile.model.UserResponse")||n.startsWith("com.ss.android.ugc.aweme.upvote.")&&n.contains(".model.")||n.startsWith("com.ss.android.ugc.aweme.repost.")&&n.contains(".model.");}
    static void install(ClassLoader cl){
        Class<?> gson=XposedHelpers.findClassIfExists("com.google.gson.Gson",cl);if(gson==null)return;
        XC_MethodHook after=new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())observe(p.getResult());}};
        try{XposedBridge.hookAllMethods(gson,"fromJson",after);}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(gson,"getAdapter",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
            if(p.hasThrowable()||p.getResult()==null||p.args.length!=1)return;
            Object raw=p.args[0];Object c=raw instanceof Class?raw:DownloadHooks.call(raw,"getRawType");
            if(!(c instanceof Class)||!wanted(((Class<?>)c).getName()))return;
            Class<?> adapter=p.getResult().getClass();synchronized(adapters){if(adapters.size()>=48||!adapters.add(adapter))return;}
            try{XposedBridge.hookAllMethods(adapter,"read",after);}catch(Throwable ignored){}
        }});}catch(Throwable ignored){}
    }
    private static void observe(Object value){
        if(value==null||CrashGuard.tripped||active.get()!=null)return;
        if(!wanted(value.getClass().getName()))return;
        active.set(true);try{visit(value,0,Collections.newSetFromMap(new IdentityHashMap<>()));}catch(Throwable ignored){}finally{active.remove();}
    }
    private static void visit(Object o,int depth,Set<Object> seen){
        if(o==null||depth>3||seen.size()>250||!seen.add(o))return;
        if(o instanceof List){for(Object v:(List<?>)o){if(seen.size()>250)break;visit(v,depth+1,seen);}return;}
        if(!wanted(o.getClass().getName()))return;
        SoundPage.observe(o);ProfileFacts.observe(o);CommentTools.observe(o);RepostTimes.observe(o);
        if(o.getClass().getName().equals(DownloadHooks.AWEME)){SoundPage.observe(RepostTimes.readField(o,"music"));ProfileFacts.observe(RepostTimes.readField(o,"author"));return;}
        for(String key:new String[]{"user","author","music","comments","comment_list","items","aweme_list"})visit(RepostTimes.readField(o,key),depth+1,seen);
    }
}
