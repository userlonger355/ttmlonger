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
    private static final WeakHashMap<ViewGroup,java.lang.ref.WeakReference<State>> rows=new WeakHashMap<>();
    private static final int STATE_TAG=0x7ff0ad47;
    private static State state(ViewGroup root){Object old=root.getTag(STATE_TAG);return old instanceof State?(State)old:null;}
    private static volatile int hooks;
    static boolean available(){return hooks>0;}
    private static final class State {
        final TextView label;
        int original,extra;
        boolean shown,left;
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
        if(!(r instanceof ViewGroup))r=DownloadHooks.call(component,"LJJIZ");
        return r instanceof ViewGroup?(ViewGroup)r:null;
    }
    static void install(ClassLoader cl){
        StreakPreviewData.install(cl);
        int count=0;
        for(String name:new String[]{"IMSkeletonLayoutReusedUISlotAssem","SkeletonLayoutReusedUISlotAssem"}){
            Class<?> type=XposedHelpers.findClassIfExists(PREFIX+name,cl);if(type==null)continue;
            for(Method method:type.getDeclaredMethods()){
                Class<?>[] p=method.getParameterTypes();
                if(method.getReturnType()!=void.class||p.length!=1||java.lang.reflect.Modifier.isAbstract(method.getModifiers()))continue;
                if(!(name.startsWith("IM")?method.getName().equals("Bq")&&p[0].getName().equals("X.0Qfl"):method.getName().equals("s4")&&p[0]==Object.class))continue;
                try{XposedBridge.hookMethod(method,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p){try{if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())return;ViewGroup v=root(p.thisObject);if(v!=null)remove(v);}catch(Throwable ignored){}}
                    @Override protected void afterHookedMethod(MethodHookParam p){try{if(!p.hasThrowable()&&!CrashGuard.tripped){StreakPreviewData.observe(field(field(DownloadHooks.call(p.args[0],"LLILZIL"),"LIZ"),"LIZ"));if(InterfaceHooks.on("message_clock"))bind(p.thisObject,p.args[0]);}}catch(Throwable ignored){}}
                });count++;}catch(Throwable ignored){}
            }
        }
        hooks=count;
        android.util.Log.i("TTMLonger","Message clock hooks="+count);
    }
    private static void remove(ViewGroup v){
        State s=state(v);if(s==null||!s.shown)return;
        v.getOverlay().remove(s.label);s.shown=false;
        if(v.getPaddingBottom()==s.original+s.extra)v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),s.original);
    }
    private static void bind(Object component,Object model){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())return;
        ViewGroup v=root(component);if(v==null)return;
        Object message=field(field(DownloadHooks.call(model,"LLILZIL"),"LIZ"),"LIZ");
        long ms=millis(DownloadHooks.number(DownloadHooks.call(message,"getCreatedAt")),System.currentTimeMillis());
        if(ms==0)return;
        State s=state(v);
        if(s==null){s=new State(v);rows.put(v,new java.lang.ref.WeakReference<>(s));v.setTag(STATE_TAG,s);final State state=s;v.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->layout((ViewGroup)view,state));}
        remove(v);
        String owner=StreakHooks.knownOwnUid();long sender=DownloadHooks.number(DownloadHooks.call(message,"getSender"));
        s.left=owner!=null&&!owner.isEmpty()&&sender>0&&!owner.equals(Long.toString(sender));
        s.original=v.getPaddingBottom();s.extra=(int)Math.ceil(20*v.getResources().getDisplayMetrics().density);
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
        int w=s.label.getMeasuredWidth();int left=s.left?Math.max(v.getPaddingLeft(),Math.min(contentLeft(v),right-w)):Math.max(v.getPaddingLeft(),right-w);
        s.label.layout(left,Math.max(0,bottom-s.label.getMeasuredHeight()),left+w,Math.max(0,bottom));
    }
    private static int contentLeft(ViewGroup root){
        int x=0;View node=root;
        for(int depth=0;depth<4&&node instanceof ViewGroup;depth++){
            ViewGroup g=(ViewGroup)node;View best=null;long area=0;
            for(int j=0;j<g.getChildCount();j++){View c=g.getChildAt(j);if(c.getVisibility()!=View.VISIBLE)continue;long a=(long)c.getWidth()*c.getHeight();if(a>area){area=a;best=c;}}
            if(best==null)break;x+=best.getLeft();node=best;
        }return Math.max(root.getPaddingLeft(),x);
    }
    static void refresh(){if(!InterfaceHooks.on("message_clock"))for(ViewGroup v:new ArrayList<>(rows.keySet()))if(v!=null)remove(v);}
}
