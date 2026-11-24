package dev.ttmlonger;

import android.app.Activity;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Pause only the current feed player, never toggle playback or resume on menu close. */
final class FeedPause {
    private static volatile Sample last;
    private static final class Sample {
        final WeakReference<Object> controller;final String aid;final long pos,at,advanced;
        Sample(Object c,String a,long p,long t,long m){controller=new WeakReference<>(c);aid=a;pos=p;at=t;advanced=m;}
    }
    static void observe(Object controller,String aid,long pos){
        if(controller==null||aid==null||aid.isEmpty())return;
        long now=SystemClock.uptimeMillis();Sample old=last;
        long moved=old!=null&&old.controller.get()==controller&&aid.equals(old.aid)?(pos>old.pos?now:old.advanced):0;
        last=new Sample(controller,aid,pos,now,moved);
    }
    static void pause(Activity a){
        if(CrashGuard.tripped||!FeedButtons.inFeed(a))return;
        Sample s=last;long now=SystemClock.uptimeMillis();
        if(s==null||now-s.at>2500)return;
        try{
            Object controller=s.controller.get();if(controller==null)return;
            Object post=DownloadHooks.call(controller,"LLJJJIL");
            if(!s.aid.equals(DownloadHooks.call(post,"getAid")))return;
            Object manager=DownloadHooks.call(controller,"getPlayerManager");if(manager==null)return;
            Object playing=DownloadHooks.call(manager,"isPlaying");
            if(Boolean.FALSE.equals(playing))return;
            if(!Boolean.TRUE.equals(playing)&&(s.advanced==0||now-s.advanced>1800))return;
            for(Object target:new Object[]{manager,controller}){
                for(String name:new String[]{"pause","pausePlay","pauseVideo"}){
                    Method method=DownloadHooks.findMethodNamed(target.getClass(),name);
                    if(method==null||java.lang.reflect.Modifier.isStatic(method.getModifiers())||method.getReturnType()!=void.class)continue;
                    try{method.invoke(target);return;}catch(Throwable ignored){}
                }
            }
        }catch(Throwable ignored){}
    }
}
