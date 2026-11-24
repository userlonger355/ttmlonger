package dev.ttmlonger;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.ArrayList;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Exact received message timestamp, no guessed time and no message content storage. */
final class MessageClock {
    private static final String PREFIX="com.ss.android.ugc.aweme.im.sdk.chat.messagelist.skeleton.cell.";
    private static final WeakHashMap<ViewGroup,State> rows=new WeakHashMap<>();
    private static final class State {
        final TextView label;
        int original,extra;
        boolean shown;
        State(ViewGroup root){label=new TextView(root.getContext());label.setTextSize(11);label.setAlpha(.75f);label.setIncludeFontPadding(false);}
    }
    static long millis(long raw,long now){
        if(raw<=0)return 0;
        long ms=raw>100000000000000L?raw/1000:raw<100000000000L?raw*1000:raw;
        return ms>=946684800000L&&ms<=now+86400000L?ms:0;
    }
    private static Object field(Object o,String name){
        if(o==null)return null;
        try{java.lang.reflect.Field f=DownloadHooks.findField(o.getClass(),name);return f==null?null:f.get(o);}catch(Throwable t){return null;}
    }
    private static ViewGroup root(Object component){
        Object r=field(component,"LLLILZ");
        if(!(r instanceof ViewGroup))r=DownloadHooks.call(component,"getContentView");
        return r instanceof ViewGroup?(ViewGroup)r:null;
    }
    static void install(ClassLoader cl){
        int count=0;
        for(String name:new String[]{"IMSkeletonLayoutReusedUISlotAssem","SkeletonLayoutReusedUISlotAssem"}){
            Class<?> type=XposedHelpers.findClassIfExists(PREFIX+name,cl);if(type==null)continue;
            for(Method method:type.getDeclaredMethods()){
                Class<?>[] p=method.getParameterTypes();
                if(method.getReturnType()!=void.class||p.length!=1||java.lang.reflect.Modifier.isAbstract(method.getModifiers()))continue;
                if(!(name.startsWith("IM")?method.getName().equals("Bq")&&p[0].getName().equals("X.0Qfl"):method.getName().equals("s4")&&p[0]==Object.class))continue;
                try{XposedBridge.hookMethod(method,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p){try{ViewGroup v=root(p.thisObject);if(v!=null)remove(v);}catch(Throwable ignored){}}
                    @Override protected void afterHookedMethod(MethodHookParam p){try{if(!p.hasThrowable()&&InterfaceHooks.on("message_clock")&&!CrashGuard.tripped)bind(p.thisObject,p.args[0]);}catch(Throwable ignored){}}
                });count++;}catch(Throwable ignored){}
            }
        }
        android.util.Log.i("TTMLonger","Message clock hooks="+count);
    }
    private static void remove(ViewGroup v){
        State s=rows.get(v);if(s==null||!s.shown)return;
        v.getOverlay().remove(s.label);s.shown=false;
        if(v.getPaddingBottom()==s.original+s.extra)v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),s.original);
    }
    private static void bind(Object component,Object model){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())return;
        ViewGroup v=root(component);if(v==null)return;
        Object message=field(field(DownloadHooks.call(model,"LLILZIL"),"LIZ"),"LIZ");
        long ms=millis(DownloadHooks.number(DownloadHooks.call(message,"getCreatedAt")),System.currentTimeMillis());
        if(ms==0)return;
        State s=rows.get(v);
        if(s==null){s=new State(v);rows.put(v,s);final State state=s;v.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->layout((ViewGroup)view,state));}
        remove(v);
        s.original=v.getPaddingBottom();s.extra=(int)Math.ceil(18*v.getResources().getDisplayMetrics().density);
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault());f.setTimeZone(Zones.current());
        s.label.setText(f.format(new java.util.Date(ms)));
        s.label.setTextColor(VideoInfo.isNight(v.getContext())?0xffdddddd:0xff444444);
        s.shown=true;v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),s.original+s.extra);
        v.getOverlay().add(s.label);layout(v,s);
    }
    private static void layout(ViewGroup v,State s){
        if(!s.shown)return;
        int width=Math.max(0,v.getWidth()-v.getPaddingLeft()-v.getPaddingRight());
        s.label.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int right=v.getWidth()-v.getPaddingRight(),bottom=v.getHeight()-s.original;
        s.label.layout(Math.max(v.getPaddingLeft(),right-s.label.getMeasuredWidth()),Math.max(0,bottom-s.label.getMeasuredHeight()),right,Math.max(0,bottom));
    }
    static void refresh(){if(!InterfaceHooks.on("message_clock"))for(ViewGroup v:new ArrayList<>(rows.keySet()))if(v!=null)remove(v);}
}
