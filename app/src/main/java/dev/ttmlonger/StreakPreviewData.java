package dev.ttmlonger;

import android.app.Activity;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import de.robv.android.xposed.*;

/** In-process preview of messages already loaded by TikTok. No storage, logs, read or send requests. */
final class StreakPreviewData {
    static final class Entry {
        final String key,text;final long time;final boolean own;
        Entry(String k,String t,long m,boolean o){key=k;text=t;time=m;own=o;}
    }
    private static final LinkedHashMap<String,List<Entry>> cache=new LinkedHashMap<>(32,.75f,true);
    private static String account;
    private static final ThreadLocal<Boolean> inside=new ThreadLocal<>();
    private static String value(Object model,String... getters){
        for(String getter:getters){Object v=DownloadHooks.call(model,getter);if(v!=null&&!v.toString().isEmpty())return v.toString();}
        return "";
    }
    static void install(ClassLoader cl){
        Class<?> type=XposedHelpers.findClassIfExists("com.bytedance.im.core.model.Message",cl);
        if(type!=null)try{XposedBridge.hookAllMethods(type,"getContent",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(p.args.length==0&&!p.hasThrowable()&&p.getResult() instanceof String)record(p.thisObject,(String)p.getResult());
            }
        });}catch(Throwable ignored){}
        Class<?> conv=XposedHelpers.findClassIfExists("com.bytedance.im.core.model.Conversation",cl);
        if(conv!=null)try{XposedBridge.hookAllMethods(conv,"getLastMessage",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())observe(p.getResult());}
        });}catch(Throwable ignored){}
    }
    static void observe(Object model){
        if(model==null||inside.get()!=null)return;
        Object content=DownloadHooks.call(model,"getContent");
        if(content instanceof String)record(model,(String)content);
    }
    private static void record(Object model,String content){
        if(model==null||CrashGuard.tripped||inside.get()!=null)return;
        inside.set(true);
        try{
            String own=StreakHooks.knownOwnUid();if(own==null)return;
            String conv=value(model,"getConversationId","getConversationID");
            String friend=StreakLogic.friendUid(conv,own);if(friend==null)return;
            String sender=value(model,"getSender","getSenderUid","getSenderId");if(!StreakLogic.isUid(sender))return;
            sender=StreakLogic.stripZeros(sender);if(!sender.equals(own)&&!sender.equals(friend))return;
            long time=MessageClock.millis(DownloadHooks.number(DownloadHooks.call(model,"getCreatedAt")),System.currentTimeMillis());if(time<=0)return;
            String key=value(model,"getMsgId","getMessageId","getServerMsgId","getUuid");
            String text=null;int type=(int)DownloadHooks.number(DownloadHooks.call(model,"getMsgType"));
            if(type==0)type=(int)DownloadHooks.number(DownloadHooks.call(model,"getMessageType"));
            if(content!=null&&!content.isEmpty()){
                try{JSONObject j=new JSONObject(content);Object t=j.opt("text");if(t instanceof String)text=(String)t;}
                catch(Throwable ignored){if(type==7&&!content.startsWith("{")&&!content.startsWith("["))text=content;}
            }
            if(text==null||text.isEmpty())text="Сообщение без текстового предпросмотра";
            if(text.length()>500)text=text.substring(0,500)+"…";
            if(key.isEmpty())key=sender+":"+time+":"+text.hashCode();
            synchronized(cache){
                if(!own.equals(account)){cache.clear();account=own;}
                List<Entry> list=cache.get(conv);if(list==null){list=new ArrayList<>();cache.put(conv,list);}
                final String id=key;list.removeIf(e->e.key.equals(id));
                list.add(new Entry(key,text,time,sender.equals(own)));list.sort((a,b)->Long.compare(a.time,b.time));
                while(list.size()>2)list.remove(0);while(cache.size()>120)cache.remove(cache.keySet().iterator().next());
            }
        }catch(Throwable ignored){}finally{inside.remove();}
    }
    static List<Entry> messages(String own,String conv){
        synchronized(cache){
            if(own==null||!own.equals(account)){cache.clear();account=own;return new ArrayList<>();}
            List<Entry> list=cache.get(conv);return list==null?new ArrayList<>():new ArrayList<>(list);
        }
    }
    private static final Map<String,String> urls=new LinkedHashMap<>();
    private static final android.util.LruCache<String,Bitmap> pictures=new android.util.LruCache<>(48);
    private static final Set<String> loading=new HashSet<>();
    private static final Set<String> failed=new HashSet<>();
    private static final ExecutorService IO=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"ttm-avatar-preview");t.setDaemon(true);return t;});
    static void friend(Object user){
        try{for(String g:new String[]{"getAvatarThumb","getAvatarMedium","getAvatar300","getAvatarLarger"}){
            Object m=DownloadHooks.call(user,g);if(m!=null){avatar(user,m);break;}
        }}catch(Throwable ignored){}
    }
    static void avatar(Object user,Object model){
        try{String uid=StreakHooks.uidOf(user),url=AvatarHooks.secure(AvatarHooks.firstUrl(model));if(uid==null||url==null)return;
            synchronized(urls){urls.put(StreakLogic.stripZeros(uid),url);while(urls.size()>800)urls.remove(urls.keySet().iterator().next());}
        }catch(Throwable ignored){}
    }
    static View avatarView(Activity a,String uid,String name,int dp){
        Ui ui=new Ui(a);FrameLayout frame=new FrameLayout(a);
        frame.setBackground(ui.oval(Ui.FIELD));frame.setClipToOutline(true);
        TextView initials=ui.text(name==null||name.isEmpty()?"?":name.substring(0,name.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT),16,Ui.MUTED,true);
        initials.setGravity(Gravity.CENTER);frame.addView(initials,new FrameLayout.LayoutParams(-1,-1));
        ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        frame.setContentDescription("Аватар "+(name==null?"":name));frame.setTag(Ui.OWN_TAG);
        if(uid==null)return frame;
        // Load only on attachment, and refresh from the bounded cache while visible.
        Runnable tick=new Runnable(){int tries;public void run(){
            if(!frame.isAttachedToWindow())return;
            String url; synchronized(urls){url=urls.get(uid);}
            if(url!=null){Bitmap bitmap=pictures.get(url);if(bitmap!=null){image.setImageBitmap(bitmap);initials.setVisibility(View.GONE);return;}load(url);}
            if(tries++<24)frame.postDelayed(this,500);
        }};
        frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){frame.post(tick);}
            public void onViewDetachedFromWindow(View v){frame.removeCallbacks(tick);}
        });return frame;
    }
    private static void load(String url){
        synchronized(loading){if(loading.contains(url)||failed.contains(url)||pictures.get(url)!=null)return;loading.add(url);}
        IO.execute(()->{boolean ok=false;try{
            byte[] data=AvatarSaver.download(url);BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);
            int sample=1;while(Math.max(o.outWidth,o.outHeight)/sample>192)sample*=2;
            o=new BitmapFactory.Options();o.inSampleSize=sample;Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,o);
            if(b!=null){pictures.put(url,b);ok=true;}
        }catch(Throwable ignored){}finally{synchronized(loading){loading.remove(url);if(!ok){if(failed.size()>200)failed.clear();failed.add(url);}}}});
    }
}
