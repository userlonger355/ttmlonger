package dev.ttmlonger;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native author-holder binding, inspired by MargyT's scoped date renderer. */
final class NativeDates {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View,VideoTracker.Entry> CELLS=new WeakHashMap<>();
    private static Class<?> aweme;
    static void install(ClassLoader cl) {
        try {
            aweme=XposedHelpers.findClassIfExists(DownloadHooks.AWEME,cl);
            Class<?> owner=XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.feed.assem.videoauthorinfo.VideoAuthorInfoRelationAssem",cl);
            if(aweme==null || owner==null)return;
            int count=0;
            for(Method method:owner.getDeclaredMethods()) {
                Class<?>[] p=method.getParameterTypes();
                if(method.getReturnType()!=void.class || p.length!=1 ||
                    !(method.getName().equals("s4") || (method.getName().equals("onViewCreated") && View.class.isAssignableFrom(p[0]))))continue;
                XposedBridge.hookMethod(method,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if(param.hasThrowable())return;
                        try { bound(param.thisObject,param.args[0]); }catch(Throwable ignored){}
                    }
                });
                count++;
            }
            android.util.Log.i("TTMLonger","Native date author hooks="+count);
        }catch(Throwable t){FlagStore.log(t);}
    }
    private static Object field(Object o,String n) {
        if(o==null)return null;
        try{java.lang.reflect.Field f=DownloadHooks.findField(o.getClass(),n);return f==null?null:f.get(o);}catch(Throwable ignored){return null;}
    }
    private static Object post(Object o) {
        if(o==null)return null;
        if(aweme.isInstance(o))return o;
        Object p=DownloadHooks.call(o,"getAweme");
        return aweme.isInstance(p)?p:null;
    }
    private static void bound(Object holder,Object arg) {
        Object content=DownloadHooks.call(holder,"getContentView");
        if(!(content instanceof View))return;
        View cell=(View)content;
        Object found=post(arg);
        if(found==null)found=post(field(field(holder,"LLJJJJLIIL"),"LL"));
        final Object model=found;
        Runnable apply=()->{
            CELLS.remove(cell);
            if(model!=null) {
                if(CELLS.size()>=32)CELLS.remove(CELLS.keySet().iterator().next());
                VideoTracker.Entry entry=new VideoTracker.Entry(model);
                if(entry.aid()!=null && !entry.aid().isEmpty()) {
                    VideoInfo.metaOf(entry);
                    CELLS.put(cell,entry);
                }
            }
            VideoInfo.nudge();
        };
        if(Looper.myLooper()==Looper.getMainLooper())apply.run();else MAIN.post(apply);
    }
    static final class Binding {
        final VideoTracker.Entry entry; final TextView anchor;
        Binding(VideoTracker.Entry e,TextView a){entry=e;anchor=a;}
    }
    static Binding visible(View root) {
        Binding best=null;
        long bestArea=0;
        Rect r=new Rect();
        for(java.util.Map.Entry<View,VideoTracker.Entry> one:CELLS.entrySet()) {
            View cell=one.getKey();
            if(cell==null||cell.getRootView()!=root||!cell.isShown()||!cell.isAttachedToWindow()||!cell.getGlobalVisibleRect(r))continue;
            if(r.height()*10<cell.getHeight()*8)continue;
            long area=(long)r.width()*r.height();
            TextView anchor=find(cell,VideoInfo.keysOf(one.getValue()),new int[]{96},0);
            if(anchor==null)continue;
            if(area>bestArea){best=new Binding(one.getValue(),anchor);bestArea=area;}
            else if(area==bestArea && best!=null && !VideoInfo.samePost(best.entry,one.getValue()))return null;
        }
        return best;
    }
    private static TextView find(View v,VideoInfo.Keys keys,int[] budget,int depth) {
        if(v==null||--budget[0]<0||depth>10||!v.isShown()||Ui.OWN_TAG.equals(v.getTag()))return null;
        if(v instanceof TextView) {
            TextView t=(TextView)v;
            if(VideoInfo.match(VideoInfo.norm(t.getText().toString()),keys.names,"")==1)return t;
        }
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int x=0;x<g.getChildCount();x++){TextView t=find(g.getChildAt(x),keys,budget,depth+1);if(t!=null)return t;}
        }
        return null;
    }
}
