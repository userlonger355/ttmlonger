package dev.ttmlonger;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Native repost event reader. Only explicit event timestamps, never an Aweme creation date or guessed ID. */
final class RepostTimes {
    private static final String[] DIRECT={"repost_time","repost_timestamp","repost_create_time","reposted_at","upvote_time","upvote_timestamp","upvote_create_time","upvoted_at","repost_at","upvote_at","repost_date","upvote_date","repost_time_ms","upvote_time_ms","repost_timestamp_ms","upvote_timestamp_ms","repost_created_at","upvote_created_at","repost_create_timestamp","upvote_create_timestamp","repost_publish_time","upvote_publish_time","repost_event_time","upvote_event_time"};
    private static final String[] RECORD={"create_time","created_at","timestamp","create_timestamp","event_time","event_timestamp","create_time_ms","timestamp_ms","time"};
    private static final String[] NESTED={"upvote_info","repost_info","upvote_infos","repost_infos","upvote_info_list","repost_info_list","repost_users","upvote_users","repost_user_info","upvote_user_info","upvote_user_list","repost_user_list","repost_info_v2","upvote_info_v2","repost_records","upvote_records","repost_list","upvote_list"};
    private static String norm(String s){return s.replace("_","").toLowerCase(Locale.ROOT);}
    private static String camel(String s){StringBuilder b=new StringBuilder();boolean up=true;for(char ch:s.toCharArray()){if(ch=='_'){up=true;continue;}b.append(up?Character.toUpperCase(ch):ch);up=false;}return b.toString();}
    private static final Map<Class<?>,Map<String,Field>> FIELDS=new HashMap<>();
    private static Map<String,Field> fields(Class<?> c){synchronized(FIELDS){Map<String,Field> old=FIELDS.get(c);if(old!=null)return old;Map<String,Field> map=new HashMap<>();for(Class<?> k=c;k!=null&&k!=Object.class;k=k.getSuperclass())for(Field f:k.getDeclaredFields())try{if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);map.putIfAbsent(norm(f.getName()),f);for(java.lang.annotation.Annotation a:f.getDeclaredAnnotations()){String n=a.annotationType().getName();if(!n.equals("com.google.gson.annotations.SerializedName")&&!n.endsWith(".JsonProperty"))continue;Object v=a.annotationType().getMethod("value").invoke(a);if(v instanceof String)map.putIfAbsent(norm((String)v),f);try{Object alt=a.annotationType().getMethod("alternate").invoke(a);if(alt instanceof String[])for(String alias:(String[])alt)map.putIfAbsent(norm(alias),f);}catch(Throwable ignored){}}}catch(Throwable ignored){}if(FIELDS.size()>200)FIELDS.clear();FIELDS.put(c,map);return map;}}
    static Object readField(Object o,String key){return read(o,key);}
    private static Object read(Object o,String key){if(o==null)return null;if(o instanceof org.json.JSONObject){org.json.JSONObject json=(org.json.JSONObject)o;Object raw=json.opt(key);if(raw==null){Iterator<String> it=json.keys();while(it.hasNext()){String k=it.next();if(norm(k).equals(norm(key))){raw=json.opt(k);break;}}}return raw==org.json.JSONObject.NULL?null:raw;}Object v=DownloadHooks.call(o,"get"+camel(key));if(v!=null)return v;try{Field f=fields(o.getClass()).get(norm(key));return f==null?null:f.get(o);}catch(Throwable ignored){return null;}}
    private static long valid(Object raw,long published,long now){long t=VideoInfo.parseEventTime(raw);return t>=1262304000L&&t<=now+300&&(published<=0||t>=published)?t:0;}
    private static long merge(long a,long b){if(a<0||b<0||a>0&&b>0&&a!=b)return -1;return Math.max(a,b);}
    private static long fieldsTime(Object o,String[] keys,long published,long now){long t=0;for(String key:keys){long v=valid(read(o,key),published,now);t=merge(t,v);if(t<0)return t;}return t;}
    private static long latest(long a,long b){return Math.max(Math.max(0,a),Math.max(0,b));}
    private static boolean recordType(String n){n=n.toLowerCase(Locale.ROOT);return n.contains("upvote")||n.contains("repost");}
    private static long event(Object o,String aid,long published,long now,int depth,Set<Object> seen,boolean dedicated){
        if(o==null||depth>4||seen.size()>80||!seen.add(o))return 0;
        if(o instanceof org.json.JSONArray){long t=0;org.json.JSONArray a=(org.json.JSONArray)o;for(int i=0;i<Math.min(80,a.length());i++)t=latest(t,event(a.opt(i),aid,published,now,depth+1,seen,dedicated));return t;}
        if(o instanceof List){long t=0;for(Object x:(List<?>)o){t=latest(t,event(x,aid,published,now,depth+1,seen,dedicated));if(seen.size()>80)break;}return t;}
        if(o.getClass().isArray()&&!o.getClass().getComponentType().isPrimitive()){long t=0;for(int i=0;i<Math.min(80,Array.getLength(o));i++)t=latest(t,event(Array.get(o,i),aid,published,now,depth+1,seen,dedicated));return t;}
        String cls=o.getClass().getName().toLowerCase(Locale.ROOT);if(o instanceof Number||o instanceof CharSequence||o instanceof Map||o instanceof Boolean||o.getClass().getName().equals(DownloadHooks.AWEME)||cls.endsWith(".user"))return 0;
        Object item=read(o,"item_id");if(item==null)item=read(o,"aweme_id");if(item!=null&&!item.toString().isEmpty()&&!item.toString().equals("0")&&(aid==null||!aid.equals(item.toString())))return 0;
        long t=fieldsTime(o,DIRECT,published,now);synchronized(wireTimes){Long wire=wireTimes.get(o);if(wire!=null)t=merge(t,wire>0&&valid(wire,published,now)==0?0:wire);}
        // Generic timestamp only for a dedicated repost/upvote record, never a nested User.
        if(t==0&&(dedicated||cls.contains("repost")||cls.contains("upvote")))t=fieldsTime(o,RECORD,published,now);
        if(t!=0)return t;
        for(String key:NESTED)t=latest(t,event(read(o,key),aid,published,now,depth+1,seen,key.contains("info")||key.contains("record")));
        return t;
    }
    private static final WeakHashMap<Object,Long> wireTimes=new WeakHashMap<>();
    private static final LinkedHashMap<String,Long> byVideo=new LinkedHashMap<String,Long>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>400;}};
    private static final java.util.concurrent.atomic.AtomicLong cachedHits=new java.util.concurrent.atomic.AtomicLong(),records=new java.util.concurrent.atomic.AtomicLong();
    private static void save(String aid,long t){if(aid==null||!aid.matches("[0-9]{5,24}")||t<=0)return;synchronized(byVideo){Long old=byVideo.get(aid);byVideo.put(aid,old==null?t:Math.max(old,t));}}
    private static long cached(String aid,long published,long now){if(aid==null)return 0;synchronized(byVideo){Long t=byVideo.get(aid);return t==null?0:valid(t,published,now);}}
    private static final java.util.concurrent.atomic.AtomicLong reads=new java.util.concurrent.atomic.AtomicLong(),found=new java.util.concurrent.atomic.AtomicLong(),conflicts=new java.util.concurrent.atomic.AtomicLong(),wireFound=new java.util.concurrent.atomic.AtomicLong();
    static String diagnostics(){return "Проверки: "+reads.get()+" · точное время: "+found.get()+" · конфликт: "+conflicts.get()+" · из JSON: "+wireFound.get()+" · отдельные записи: "+records.get()+" · совпадения ID: "+cachedHits.get();}
    static long read(Object aweme){
        if(aweme==null)return 0;reads.incrementAndGet();Object id=read(aweme,"aid");if(id==null)id=read(aweme,"aweme_id");String aid=id==null?null:id.toString();
        long now=System.currentTimeMillis()/1000L,published=VideoInfo.parseEventTime(DownloadHooks.call(aweme,"getCreateTime"));
        long t=fieldsTime(aweme,DIRECT,published,now);Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        if(t==0){for(String key:NESTED)t=latest(t,event(read(aweme,key),aid,published,now,0,seen,key.contains("info")||key.contains("record")));}synchronized(wireTimes){Long wire=wireTimes.get(aweme);if(t==0&&wire!=null&&valid(wire,published,now)>0)t=wire;}if(t>0){save(aid,t);found.incrementAndGet();}else if(t<0)conflicts.incrementAndGet();else if((t=cached(aid,published,now))>0)cachedHits.incrementAndGet();return Math.max(0,t);
    }
    private static void captureWire(Object model,Object raw){
        if(model==null||raw==null||!Prefs.vtRepost||CrashGuard.tripped)return;
        String name=model.getClass().getName(),lower=name.toLowerCase(Locale.ROOT);
        boolean post=name.equals(DownloadHooks.AWEME),record=lower.endsWith("upvoteinfo")||lower.endsWith("repostinfo")||lower.endsWith("upvoteuser")||lower.endsWith("upvoterecord")||lower.endsWith("repostrecord");
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
            if(t==0)for(String key:NESTED)t=latest(t,event(read(obj,key),aid,published,now,0,seen,key.contains("info")||key.contains("record")));
            if(t!=0){if(t>0){wireFound.incrementAndGet();save(aid,t);}synchronized(wireTimes){if(wireTimes.size()>600)wireTimes.clear();wireTimes.put(model,t);}VideoInfo.nudge();}
        }catch(Throwable ignored){}
    }
    private static final ThreadLocal<Boolean> observing=new ThreadLocal<>();
    static void observe(Object model){if(observing.get()!=null)return;observing.set(true);try{observeInner(model);}finally{observing.remove();}}
    private static void observeInner(Object model){if(model==null||!Prefs.vtRepost||CrashGuard.tripped)return;String name=model.getClass().getName();if(name.equals(DownloadHooks.AWEME)){long time=read(model);if(time>0)VideoInfo.nudge();}else if(recordType(name)){Object item=read(model,"item_id");if(item==null)item=read(model,"aweme_id");if(item==null)return;long time=event(model,item.toString(),0,System.currentTimeMillis()/1000L,0,Collections.newSetFromMap(new IdentityHashMap<>()),false);if(time>0){records.incrementAndGet();save(item.toString(),time);synchronized(wireTimes){if(wireTimes.size()>600)wireTimes.clear();wireTimes.put(model,time);}VideoInfo.nudge();}}}
    static void install(ClassLoader cl){
        Class<?> gson=XposedHelpers.findClassIfExists("com.google.gson.Gson",cl);
        if(gson!=null)try{XposedBridge.hookAllMethods(gson,"fromJson",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable()&&p.args.length>=2)captureWire(p.getResult(),p.args[0]);}});}catch(Throwable ignored){}
        Class<?> aweme=XposedHelpers.findClassIfExists(DownloadHooks.AWEME,cl);if(aweme==null)return;
        for(String key:new String[]{"setUpvoteInfo","setRepostInfo","getUpvoteInfo","getRepostInfo"})try{XposedBridge.hookAllMethods(aweme,key,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable()&&Prefs.vtRepost){if(p.args.length>0)observe(p.args[0]);else observe(p.getResult());VideoInfo.nudge();}}});}catch(Throwable ignored){}
    }
}
