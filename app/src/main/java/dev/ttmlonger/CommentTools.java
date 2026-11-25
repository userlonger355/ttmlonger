package dev.ttmlonger;

import android.content.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.text.SimpleDateFormat;
import de.robv.android.xposed.*;

final class CommentTools {
    static final String[] KEYS={"comment_expand","comment_copy_body","comment_nick","comment_exact","comment_double","comment_full"};
    static final String[] TITLES={"Разворачивать длинные комментарии","Копировать текст без имени автора","Ник вместо имени","Точное время комментариев","Двойное нажатие для лайка","Кнопка полноэкранных комментариев"};
    static int modelHooks;
    private static final java.util.concurrent.atomic.AtomicLong matchedRows=new java.util.concurrent.atomic.AtomicLong();
    static String diagnostics(){synchronized(facts){return "Хуки: "+modelHooks+" · модели: "+facts.size()+" · совпадения строк: "+matchedRows.get();}}
    private static final LinkedHashMap<String,List<Fact>> facts=new LinkedHashMap<>();
    private static final ThreadLocal<Boolean> reading=new ThreadLocal<>();
    private static final WeakHashMap<TextView,Edit> edits=new WeakHashMap<>();
    private static final WeakHashMap<TextView,Limits> limits=new WeakHashMap<>();
    private static final WeakHashMap<View,View> buttons=new WeakHashMap<>();
    private static final WeakHashMap<View,Integer> originalHeight=new WeakHashMap<>();
    private static java.lang.ref.WeakReference<TextView> lastTap=new java.lang.ref.WeakReference<>(null);
    private static long lastAt,downAt;private static float downX,downY;
    private static final class Fact {
        final String id,nick,handle,content;final long time;
        Fact(String i,String n,String h,long t,String c){id=i;nick=n;handle=h;time=t;content=c;}
    }
    private static final class Edit {
        final CharSequence before;final String after,key;
        Edit(CharSequence b,String a,String k){before=b;after=a;key=k;}
    }
    private static final class Limits {
        final int max;final android.text.TextUtils.TruncateAt ellipsize;
        Limits(TextView t){max=t.getMaxLines();ellipsize=t.getEllipsize();}
    }
    private static String text(Object o,String getter){Object v=DownloadHooks.call(o,getter);return v instanceof String?((String)v).trim():"";}
    private static String name(View v){
        String n=v.getClass().getName().toLowerCase(Locale.ROOT);
        try{if(v.getId()!=View.NO_ID)n+=" "+v.getResources().getResourceEntryName(v.getId()).toLowerCase(Locale.ROOT);}catch(Throwable ignored){}
        return n;
    }
    private static boolean body(View v){
        String n=name(v);return v instanceof TextView&&!(v instanceof EditText)&&(n.contains("comment_content")||n.contains("comment_text")||n.contains("comment_body")||n.contains("commentcontent")||n.contains("commenttext"));
    }
    private static boolean author(View v){String n=name(v);return n.contains("comment_nickname")||n.contains("comment_username")||n.contains("comment_user_name")||n.contains("comment_author");}
    private static boolean time(View v){String n=name(v);return n.contains("comment_time")||n.contains("comment_date")||n.contains("comment_create_time");}
    private static boolean any(){for(String k:KEYS)if(InterfaceHooks.on(k))return true;return false;}
    private static final WeakHashMap<TextView,Fact> bound=new WeakHashMap<>();
    private static Fact recent;private static long recentAt;private static java.lang.ref.WeakReference<android.app.Activity> owner=new java.lang.ref.WeakReference<>(null);
    private static long lastEvent=-1;private static int lastAction=-1;
    private static String norm(CharSequence s){return s==null?"":s.toString().trim().replaceAll("\\s+"," ");}
    private static String value(Object o,String key){Object x=RepostTimes.readField(o,key);return x==null?"":x.toString().trim();}
    private static void remember(Object model){
        if(model==null||CrashGuard.tripped||reading.get()!=null)return;
        reading.set(true);try{
            String content=CommentSelect.body(model);if(content==null||content.trim().isEmpty()||content.length()>20000)return;
            Object u=RepostTimes.readField(model,"user");if(u==null)u=RepostTimes.readField(model,"author");
            String id=value(model,"cid");if(id.isEmpty())id=value(model,"comment_id");
            String nick=value(u,"nickname"),handle=value(u,"unique_id");
            long sec=VideoInfo.parseEventTime(RepostTimes.readField(model,"create_time"));
            if(sec<1262304000L||sec>System.currentTimeMillis()/1000L+300)sec=0;
            if(id.isEmpty()||nick.isEmpty()&&handle.isEmpty())return;
            Fact fact=new Fact(id,nick,handle,sec,content);
            synchronized(facts){String key=norm(content);List<Fact> list=facts.get(key);if(list==null){list=new ArrayList<>();facts.put(key,list);}list.removeIf(x->x.id.equals(fact.id));list.add(fact);if(list.size()>8)list.remove(0);while(facts.size()>600)facts.remove(facts.keySet().iterator().next());}
        }catch(Throwable ignored){}finally{reading.remove();}
    }
    static void install(ClassLoader cl){
        for(String n:new String[]{"com.ss.android.ugc.aweme.comment.model.Comment","com.ss.android.ugc.aweme.comment.model.CommentItem"}){
            Class<?> type=XposedHelpers.findClassIfExists(n,cl);if(type==null)continue;
            XC_MethodHook observe=new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())remember(p.thisObject);}};
            for(String getter:new String[]{"getText","getUser","getCid","getCreateTime","setText","setUser","setCid","setCreateTime"})try{modelHooks+=XposedBridge.hookAllMethods(type,getter,observe).size();}catch(Throwable ignored){}
        }
        XC_MethodHook events=new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
            if(!any()&&!InterfaceHooks.on("comment_select")||CrashGuard.tripped||p.args.length!=1||!(p.args[0] instanceof MotionEvent))return;
            View root=null;if(p.thisObject instanceof android.app.Activity)root=((android.app.Activity)p.thisObject).getWindow().getDecorView();
            else if(p.thisObject instanceof android.app.Dialog){android.app.Dialog d=(android.app.Dialog)p.thisObject;if(!Screen.isOwn(d)&&d.getWindow()!=null)root=d.getWindow().getDecorView();}
            if(root!=null)touch(root,(MotionEvent)p.args[0]);
        }};
        try{XposedBridge.hookAllMethods(android.app.Activity.class,"dispatchTouchEvent",events);}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(android.app.Dialog.class,"dispatchTouchEvent",events);}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(ClipboardManager.class,"setPrimaryClip",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
            if(!InterfaceHooks.on("comment_copy_body")||CrashGuard.tripped||p.args.length!=1||!(p.args[0] instanceof ClipData))return;
            String body=recentBody();Fact fact=recent;if(body==null||fact==null)return;
            ClipData clip=(ClipData)p.args[0];if(clip.getItemCount()!=1||clip.getItemAt(0).getText()==null)return;
            String raw=clip.getItemAt(0).getText().toString();
            for(String author:new String[]{fact.nick,fact.handle,"@"+fact.handle})if(!author.isEmpty())for(String separator:new String[]{": ",":\\n","：","： ","\\n"," · "," "})if(raw.equals(author+separator+body)){p.args[0]=new ClipData("Комментарий",new String[]{"text/plain"},new ClipData.Item(body));return;}
        }});}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(View.class,"performLongClick",new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
            if(Boolean.TRUE.equals(p.getResult())||CrashGuard.tripped||!(p.thisObject instanceof TextView)||!InterfaceHooks.on("comment_select"))return;
            TextView t=(TextView)p.thisObject;View panel=CommentWallpaper.panel(t.getRootView());if(panel==null||!inside(t,panel))return;Fact hit=bound.get(t);android.app.Activity a=ScreenWatcher.top();
            if(hit!=null&&a!=null&&!a.isFinishing()){CommentSelect.show(a,hit.content);p.setResult(true);}
        }});}catch(Throwable ignored){}
    }
    static String recentBody(){android.app.Activity a=ScreenWatcher.top();return a!=null&&owner.get()==a&&SystemClock.uptimeMillis()-recentAt<12000&&recent!=null?recent.content:null;}
    private static boolean inside(View child,View parent){for(View v=child;v!=null;){if(v==parent)return true;v=v.getParent() instanceof View?(View)v.getParent():null;}return false;}
    static void apply(View root){
        if(root==null||Screen.isOwnDecor(root))return;
        try{
            if(!any()&&!InterfaceHooks.on("comment_select")&&edits.isEmpty()&&limits.isEmpty()&&buttons.isEmpty())return;
            View panel=CrashGuard.tripped||!any()&&!InterfaceHooks.on("comment_select")?null:CommentWallpaper.panel(root);
            if(panel==null && InterfaceHooks.on("comment_full"))for(View old:new ArrayList<>(originalHeight.keySet()))if(old!=null&&old.getRootView()==root&&old.isShown()){panel=old;break;}
            for(TextView t:new ArrayList<>(edits.keySet())){
                if(t==null||t.getRootView()!=root)continue;
                Edit e=edits.get(t);if(e==null)continue;
                if(panel==null||!inside(t,panel)||!InterfaceHooks.on(e.key)||!e.after.contentEquals(t.getText())){
                    edits.remove(t);if(e.after.contentEquals(t.getText()))t.setText(e.before);
                }
            }
            for(TextView t:new ArrayList<>(limits.keySet())){
                if(t==null||t.getRootView()!=root)continue;
                if(panel==null||!inside(t,panel)||!InterfaceHooks.on("comment_expand")){
                    Limits l=limits.remove(t);if(l!=null){t.setMaxLines(l.max);t.setEllipsize(l.ellipsize);}
                }
            }
            View button=buttons.get(root);
            if(button!=null&&(panel==null||!InterfaceHooks.on("comment_full")||!inside(button,panel))){
                buttons.remove(root);if(button.getParent() instanceof ViewGroup)((ViewGroup)button.getParent()).removeView(button);
            }
            for(View expanded:new ArrayList<>(originalHeight.keySet()))if(expanded!=null&&expanded.getRootView()==root&&(panel==null||panel!=expanded||!InterfaceHooks.on("comment_full"))){
                Integer height=originalHeight.remove(expanded);if(height!=null){ViewGroup.LayoutParams lp=expanded.getLayoutParams();lp.height=height;expanded.setLayoutParams(lp);}
            }
            if(panel==null)return;
            List<TextView> list=new ArrayList<>();collect(panel,list,0,new int[]{1800});
            for(TextView t:list){
                Fact hit=format(t,panel);if(hit!=null){Fact previous=bound.put(t,hit);if(previous==null||!previous.id.equals(hit.id))matchedRows.incrementAndGet();}else bound.remove(t);
                if((hit!=null||body(t))&&InterfaceHooks.on("comment_expand")&&!limits.containsKey(t)){limits.put(t,new Limits(t));t.setMaxLines(Integer.MAX_VALUE);t.setEllipsize(null);}
                if(hit!=null&&InterfaceHooks.on("comment_expand")&&!norm(t.getText()).equals(norm(hit.content))&&truncated(t.getText(),hit.content))edit(t,hit.content,"comment_expand");
            }
            if(InterfaceHooks.on("comment_full")&&buttons.get(root)==null)addFull(root,panel,list);
        }catch(Throwable ignored){}
    }
    private static void collect(View v,List<TextView> out,int depth,int[] b){
        if(--b[0]<0||depth>32||v.getVisibility()!=View.VISIBLE||Ui.OWN_TAG.equals(v.getTag()))return;
        if(v instanceof TextView)out.add((TextView)v);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),out,depth+1,b);}
    }
    private static boolean truncated(CharSequence shown,String body){
        String s=norm(shown).replaceFirst("(?i)(?:…|\\.\\.\\.)?\\s*(?:ещё|еще|see more|more)$","").trim().replaceFirst("[…]+$","").trim();
        return s.length()>=12&&s.length()<norm(body).length()&&norm(body).startsWith(s);
    }
    private static boolean authorMatches(String s,Fact f){return !s.isEmpty()&&(s.equals(f.nick)||!f.handle.isEmpty()&&(s.equals(f.handle)||s.equals("@"+f.handle)));}
    private static String original(TextView t){Edit e=edits.get(t);return norm(e!=null&&e.after.contentEquals(t.getText())?e.before:t.getText());}
    private static boolean relativeTime(String s){return s.toLowerCase(Locale.ROOT).matches("(?:только что|сегодня|вчера|just now|today|yesterday|[0-9]+\\s*(?:сек\\.?|мин\\.?|ч\\.?|дн\\.?|д\\.?|нед\\.?|мес\\.?|г\\.?|s|m|h|d|w|mo|y)(?:\\s*назад|\\s*ago)?|[0-9]{1,2}[-./][0-9]{1,2}(?:[-./][0-9]{2,4})?|[0-9]{1,2}\\s+[а-яa-z]{3,12}\\.?(?:\\s+[0-9]{4})?)");}
    private static Fact format(TextView body,View panel){
        if(body instanceof EditText||!body.isShown())return null;
        String shown=original(body);if(shown.isEmpty())return null;
        List<Fact> matches=new ArrayList<>();
        synchronized(facts){List<Fact> exact=facts.get(shown);if(exact!=null)matches.addAll(exact);else if(shown.length()>=12)for(Map.Entry<String,List<Fact>> e:facts.entrySet())if(truncated(shown,e.getKey()))matches.addAll(e.getValue());}
        if(matches.isEmpty())return null;
        for(View row=body.getParent() instanceof View?(View)body.getParent():null;row!=null&&row!=panel;row=row.getParent() instanceof View?(View)row.getParent():null){
            if(row.getHeight()>panel.getHeight()*.65f&&row.getHeight()>body.getHeight()+row.getResources().getDisplayMetrics().density*120)break;
            List<TextView> cells=new ArrayList<>();collect(row,cells,0,new int[]{100});
            Fact hit=null;TextView who=null,when=null;
            for(TextView t:cells){if(t==body)continue;String value=original(t);for(Fact f:matches)if(authorMatches(value,f)){if(hit!=null&&!hit.id.equals(f.id))return null;hit=f;who=t;}}
            if(hit==null)continue;
            for(TextView t:cells)if(t!=body&&t!=who&&(time(t)||relativeTime(original(t)))){if(when!=null){when=null;break;}when=t;}
            if(InterfaceHooks.on("comment_nick")&&!hit.handle.isEmpty())edit(who,"@"+hit.handle,"comment_nick");
            if(InterfaceHooks.on("comment_exact")&&when!=null&&hit.time>0){SimpleDateFormat fmt=new SimpleDateFormat("d MMM, HH:mm:ss",new Locale("ru"));fmt.setTimeZone(Zones.current());edit(when,fmt.format(new Date(hit.time*1000L)),"comment_exact");}
            return hit;
        }return null;
    }
    private static void edit(TextView t,String value,String key){
        if(value.contentEquals(t.getText()))return;Edit old=edits.get(t);
        CharSequence orig=old!=null&&old.after.contentEquals(t.getText())?old.before:t.getText();
        edits.put(t,new Edit(orig,value,key));t.setText(value);
    }
    private static void addFull(View root,View panel,List<TextView> list){
        if(panel.getParent()==null)return;
        for(TextView t:list){
            String s=t.getText().toString().trim().toLowerCase(Locale.ROOT);
            if(!s.matches("(?:[0-9.,\\s]+[kmкм]?\\s+)?(?:comments?|комментарии|комментариев|комментарий)"))continue;
            ViewGroup row=null;
            for(ViewParent q=t.getParent();q instanceof ViewGroup;q=q.getParent()){ViewGroup g=(ViewGroup)q;if(g==panel)break;if(g.getWidth()<panel.getWidth()*.7f||g.getHeight()>panel.getHeight()*.22f)continue;if(g instanceof FrameLayout||g instanceof LinearLayout&&((LinearLayout)g).getOrientation()==LinearLayout.HORIZONTAL){row=g;break;}}
            if(row==null)continue;
            TextView b=new TextView(root.getContext());b.setTag(Ui.OWN_TAG);b.setText("⛶");b.setTextSize(23);b.setTextColor(t.getCurrentTextColor());b.setGravity(Gravity.CENTER);b.setContentDescription("Развернуть комментарии");
            int size=Math.round(root.getResources().getDisplayMetrics().density*40);
            if(row instanceof FrameLayout){FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(size,size,Gravity.START|Gravity.CENTER_VERTICAL);fp.leftMargin=Math.round(size*.12f);row.addView(b,fp);}else row.addView(b,0,new LinearLayout.LayoutParams(size,size));buttons.put(root,b);
            b.setOnClickListener(v->{ViewGroup.LayoutParams lp=panel.getLayoutParams();Integer h=originalHeight.remove(panel);if(h==null){originalHeight.put(panel,lp.height);lp.height=-1;}else lp.height=h;panel.setLayoutParams(lp);});
            return;
        }
    }
    static void touch(View root,MotionEvent event){
        if(CrashGuard.tripped||root==null||Screen.isOwnDecor(root))return;
        int action=event.getActionMasked();long stamp=event.getEventTime();if(lastEvent==stamp&&lastAction==action)return;lastEvent=stamp;lastAction=action;
        if(action==MotionEvent.ACTION_DOWN){View currentPanel=CommentWallpaper.panel(root);if(currentPanel!=null){recent=null;recentAt=0;}downAt=stamp;downX=event.getRawX();downY=event.getRawY();View panel=CommentWallpaper.panel(root);if(panel!=null){Rect r=new Rect();List<TextView> cells=new ArrayList<>();collect(panel,cells,0,new int[]{1800});for(TextView t:cells)if(bound.containsKey(t)&&t.getGlobalVisibleRect(r)&&r.contains((int)downX,(int)downY)){recent=bound.get(t);recentAt=SystemClock.uptimeMillis();owner=new java.lang.ref.WeakReference<>(ScreenWatcher.top());break;}}return;}
        if(!InterfaceHooks.on("comment_double"))return;
        if(action!=MotionEvent.ACTION_UP||event.getEventTime()-downAt>300||Math.abs(event.getRawX()-downX)>20||Math.abs(event.getRawY()-downY)>20)return;
        View panel=CommentWallpaper.panel(root);if(panel==null)return;
        List<TextView> cells=new ArrayList<>();collect(panel,cells,0,new int[]{1800});
        Rect r=new Rect();TextView hit=null;
        for(TextView t:cells)if((body(t)||bound.containsKey(t))&&t.getGlobalVisibleRect(r)&&r.contains((int)event.getRawX(),(int)event.getRawY())){hit=t;break;}
        if(hit==null)return;long now=SystemClock.uptimeMillis();
        if(lastTap.get()==hit&&now-lastAt<320){lastTap.clear();lastAt=0;
            for(View row=hit.getParent() instanceof View?(View)hit.getParent():null;row!=null&&row!=panel;row=row.getParent() instanceof View?(View)row.getParent():null){
                if(row.getHeight()>panel.getHeight()*.65f&&row.getHeight()>hit.getHeight()+row.getResources().getDisplayMetrics().density*120)break;
                View like=like(row,0,new int[]{65});if(like!=null){like.performClick();break;}
            }
        }else{lastTap=new java.lang.ref.WeakReference<>(hit);lastAt=now;}
    }
    private static View like(View v,int depth,int[] b){
        if(--b[0]<0||depth>7||v.getVisibility()!=View.VISIBLE)return null;
        String s=String.valueOf(v.getContentDescription()).toLowerCase(Locale.ROOT).trim(),n=name(v);
        if((s.equals("like")||s.equals("нравится")||s.equals("поставить лайк")||s.startsWith("like comment"))&&!v.isSelected()){if(v.isClickable())return v;if(v.getParent() instanceof View&&((View)v.getParent()).isClickable()&&!((View)v.getParent()).isSelected())return (View)v.getParent();}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=like(g.getChildAt(i),depth+1,b);if(hit!=null)return hit;}}
        return null;
    }
}
