package dev.ttmlonger;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Native repost event reader. Only explicit event timestamps, never an Aweme creation date or guessed ID. */
final class RepostTimes {
    private static final String[] DIRECT={"repost_time","repost_timestamp","repost_create_time","reposted_at","upvote_time","upvote_timestamp","upvote_create_time","upvoted_at","repost_at","upvote_at","repost_date","upvote_date","repost_time_ms","upvote_time_ms","repost_timestamp_ms","upvote_timestamp_ms","repost_created_at","upvote_created_at","repost_create_timestamp","upvote_create_timestamp"};
    private static final String[] RECORD={"create_time","created_at","timestamp","create_timestamp","event_time","event_timestamp","create_time_ms","timestamp_ms","time"};
    private static final String[] NESTED={"upvote_info","repost_info","upvote_infos","repost_infos","upvote_info_list","repost_info_list","repost_users","upvote_users","repost_user_info","upvote_user_info","upvote_user_list","repost_user_list"};
    private static String norm(String s){return s.replace("_","").toLowerCase(Locale.ROOT);}
    private static String camel(String s){StringBuilder b=new StringBuilder();boolean up=true;for(char ch:s.toCharArray()){if(ch=='_'){up=true;continue;}b.append(up?Character.toUpperCase(ch):ch);up=false;}return b.toString();}
    private static final Map<Class<?>,Map<String,Field>> FIELDS=new HashMap<>();
    private static Map<String,Field> fields(Class<?> c){synchronized(FIELDS){Map<String,Field> old=FIELDS.get(c);if(old!=null)return old;Map<String,Field> map=new HashMap<>();for(Class<?> k=c;k!=null&&k!=Object.class;k=k.getSuperclass())for(Field f:k.getDeclaredFields())try{if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);map.putIfAbsent(norm(f.getName()),f);for(java.lang.annotation.Annotation a:f.getDeclaredAnnotations()){String n=a.annotationType().getName();if(!n.equals("com.google.gson.annotations.SerializedName")&&!n.endsWith(".JsonProperty"))continue;Object v=a.annotationType().getMethod("value").invoke(a);if(v instanceof String)map.putIfAbsent(norm((String)v),f);}}catch(Throwable ignored){}if(FIELDS.size()>200)FIELDS.clear();FIELDS.put(c,map);return map;}}
    static Object readField(Object o,String key){return read(o,key);}
    private static Object read(Object o,String key){if(o==null)return null;if(o instanceof org.json.JSONObject){Object raw=((org.json.JSONObject)o).opt(key);return raw==org.json.JSONObject.NULL?null:raw;}Object v=DownloadHooks.call(o,"get"+camel(key));if(v!=null)return v;try{Field f=fields(o.getClass()).get(norm(key));return f==null?null:f.get(o);}catch(Throwable ignored){return null;}}
    private static long valid(Object raw,long published,long now){long t=VideoInfo.parseEventTime(raw);return t>=1262304000L&&t<=now+300&&(published<=0||t>=published)?t:0;}
    private static long merge(long a,long b){if(a<0||b<0||a>0&&b>0&&a!=b)return -1;return Math.max(a,b);}
    private static long fieldsTime(Object o,String[] keys,long published,long now){long t=0;for(String key:keys){long v=valid(read(o,key),published,now);t=merge(t,v);if(t<0)return t;}return t;}
    private static long event(Object o,String aid,long published,long now,int depth,Set<Object> seen,boolean dedicated){
        if(o==null||depth>4||seen.size()>80||!seen.add(o))return 0;
        if(o instanceof org.json.JSONArray){long t=0;org.json.JSONArray a=(org.json.JSONArray)o;for(int i=0;i<Math.min(80,a.length());i++)t=merge(t,event(a.opt(i),aid,published,now,depth+1,seen,dedicated));return t;}
        if(o instanceof List){long t=0;for(Object x:(List<?>)o){t=merge(t,event(x,aid,published,now,depth+1,seen,dedicated));if(seen.size()>80)break;}return t;}
        if(o.getClass().isArray()&&!o.getClass().getComponentType().isPrimitive()){long t=0;for(int i=0;i<Math.min(80,Array.getLength(o));i++)t=merge(t,event(Array.get(o,i),aid,published,now,depth+1,seen,dedicated));return t;}
        String cls=o.getClass().getName().toLowerCase(Locale.ROOT);if(o instanceof Number||o instanceof CharSequence||o instanceof Map||o instanceof Boolean||o.getClass().getName().equals(DownloadHooks.AWEME)||cls.endsWith(".user"))return 0;
        Object item=read(o,"item_id");if(item==null)item=read(o,"aweme_id");if(item!=null&&!item.toString().isEmpty()&&!item.toString().equals("0")&&(aid==null||!aid.equals(item.toString())))return 0;
        long t=fieldsTime(o,DIRECT,published,now);synchronized(wireTimes){Long wire=wireTimes.get(o);if(wire!=null)t=merge(t,wire>0&&valid(wire,published,now)==0?0:wire);}
        // Generic timestamp only for a dedicated repost/upvote record, never a nested User.
        if(t==0&&(dedicated||cls.contains("repost")||cls.contains("upvote")))t=fieldsTime(o,RECORD,published,now);
        for(String key:NESTED)t=merge(t,event(read(o,key),aid,published,now,depth+1,seen,key.contains("info")));
        return t;
    }
    private static final WeakHashMap<Object,Long> wireTimes=new WeakHashMap<>();
    static long read(Object aweme){
        if(aweme==null)return 0;Object id=DownloadHooks.call(aweme,"getAid");String aid=id==null?null:id.toString();
        long now=System.currentTimeMillis()/1000L,published=VideoInfo.parseEventTime(DownloadHooks.call(aweme,"getCreateTime"));
        long t=fieldsTime(aweme,DIRECT,published,now);Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(String key:NESTED)t=merge(t,event(read(aweme,key),aid,published,now,0,seen,key.contains("info")));synchronized(wireTimes){Long wire=wireTimes.get(aweme);if(wire!=null)t=merge(t,wire>0&&valid(wire,published,now)==0?0:wire);}return Math.max(0,t);
    }
    private static void captureWire(Object model,Object raw){
        if(model==null||raw==null||!Prefs.vtRepost||CrashGuard.tripped)return;
        String name=model.getClass().getName(),lower=name.toLowerCase(Locale.ROOT);
        boolean post=name.equals(DownloadHooks.AWEME),record=lower.endsWith("upvoteinfo")||lower.endsWith("repostinfo")||lower.endsWith("upvoteuser");
        if(!post&&!record)return;
        try{
            String json=raw instanceof String?(String)raw:raw.getClass().getName().startsWith("com.google.gson.Json")?raw.toString():null;
            if(json==null||json.length()>2000000||!json.trim().startsWith("{"))return;
            org.json.JSONObject obj=new org.json.JSONObject(json);long now=System.currentTimeMillis()/1000L;
            Object id=DownloadHooks.call(model,"getAid");if(id==null)id=read(model,"item_id");
            String aid=obj.optString("aweme_id",obj.optString("item_id",""));
            if(id!=null&&!aid.isEmpty()&&!aid.equals(id.toString()))return;
            if(aid.isEmpty()&&id!=null)aid=id.toString();
            long published=post?VideoInfo.parseEventTime(DownloadHooks.call(model,"getCreateTime")):0;
            long t=fieldsTime(obj,DIRECT,published,now);if(record&&t==0)t=fieldsTime(obj,RECORD,published,now);
            Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(String key:NESTED)t=merge(t,event(read(obj,key),aid,published,now,0,seen,key.contains("info")));
            if(t!=0){synchronized(wireTimes){if(wireTimes.size()>600)wireTimes.clear();wireTimes.put(model,t);}VideoInfo.nudge();}
        }catch(Throwable ignored){}
    }
    static void install(ClassLoader cl){
        Class<?> gson=XposedHelpers.findClassIfExists("com.google.gson.Gson",cl);
        if(gson!=null)try{XposedBridge.hookAllMethods(gson,"fromJson",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable()&&p.args.length>=2)captureWire(p.getResult(),p.args[0]);}});}catch(Throwable ignored){}
        Class<?> aweme=XposedHelpers.findClassIfExists(DownloadHooks.AWEME,cl);if(aweme==null)return;
        for(String key:new String[]{"setUpvoteInfo","setRepostInfo"})try{XposedBridge.hookAllMethods(aweme,key,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable()&&Prefs.vtRepost)VideoInfo.nudge();}});}catch(Throwable ignored){}
    }
}
