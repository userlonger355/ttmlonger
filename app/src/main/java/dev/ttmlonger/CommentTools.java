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
    private static volatile int visibleTextCount,visiblePanelCount;
    private static final java.util.concurrent.atomic.AtomicLong matchedRows=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong cleanCopies=new java.util.concurrent.atomic.AtomicLong();
    static String diagnostics(){synchronized(facts){return "Хуки: "+modelHooks+" · модели: "+facts.size()+" · совпадения строк: "+matchedRows.get()+" · текстовых элементов: "+visibleTextCount+" · панели: "+visiblePanelCount+" · очищено копирований: "+cleanCopies.get()+" · "+CommentSelect.diagnostics();}}
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
    static boolean enabled(){return any();}
    private static boolean any(){for(String k:KEYS)if(InterfaceHooks.on(k))return true;return false;}
    private static final WeakHashMap<TextView,Fact> bound=new WeakHashMap<>();
    private static Fact recent;
    private static String tappedBody="";private static final Set<String> tappedAuthors=new HashSet<>();private static long recentAt;private static java.lang.ref.WeakReference<android.app.Activity> owner=new java.lang.ref.WeakReference<>(null);
    private static long copyPressedAt;
    private static long lastEvent=-1;private static int lastAction=-1;private static java.lang.ref.WeakReference<View> eventRoot=new java.lang.ref.WeakReference<>(null);
    private static String norm(CharSequence s){return Targets.norm(s);}
    private static String value(Object o,String key){Object x=RepostTimes.readField(o,key);return x==null?"":x.toString().trim();}
    static void observe(Object model){
        if(CommentSelect.isComment(model))remember(model);
    }
    private static void remember(Object model){
        if(model==null||CrashGuard.tripped||reading.get()!=null)return;
        reading.set(true);try{
            String content=CommentSelect.body(model);if(content==null||content.trim().isEmpty()||content.length()>20000)return;
            Object u=RepostTimes.readField(model,"user");if(u==null)u=RepostTimes.readField(model,"author");
            String id=value(model,"cid");if(id.isEmpty())id=value(model,"comment_id");
            String nick=value(u,"nickname"),handle=value(u,"unique_id");if(nick.isEmpty())nick=value(model,"nickname");if(handle.isEmpty())handle=value(model,"unique_id");
            long sec=VideoInfo.parseEventTime(RepostTimes.readField(model,"create_time"));
            if(sec<1262304000L||sec>System.currentTimeMillis()/1000L+300)sec=0;
            if(id.isEmpty())return;
            Fact fact=new Fact(id,nick,handle,sec,content);
            synchronized(facts){String key=norm(content);List<Fact> list=facts.get(key);if(list==null){list=new ArrayList<>();facts.put(key,list);}list.removeIf(x->x.id.equals(fact.id));list.add(fact);if(list.size()>8)list.remove(0);while(facts.size()>600)facts.remove(facts.keySet().iterator().next());}
        }catch(Throwable ignored){}finally{reading.remove();}
    }
    static void install(ClassLoader cl){
        for(String n:new String[]{"com.ss.android.ugc.aweme.comment.model.Comment","com.ss.android.ugc.aweme.comment.model.CommentItem"}){
            Class<?> type=XposedHelpers.findClassIfExists(n,cl);if(type==null)continue;
            XC_MethodHook observe=new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())remember(p.thisObject);}};
            for(String getter:new String[]{"getText","getUser","getCid","getCreateTime","getDiggCount","getReplyId","getAwemeId","setText","setUser","setCid","setCreateTime"})try{modelHooks+=XposedBridge.hookAllMethods(type,getter,observe).size();}catch(Throwable ignored){}
        }
        XC_MethodHook events=new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
            if(!any()||CrashGuard.tripped||p.args.length!=1||!(p.args[0] instanceof MotionEvent))return;
            View root=null;if(p.thisObject instanceof android.app.Activity)root=((android.app.Activity)p.thisObject).getWindow().getDecorView();
            else if(p.thisObject instanceof android.app.Dialog){android.app.Dialog d=(android.app.Dialog)p.thisObject;if(!Screen.isOwn(d)&&d.getWindow()!=null)root=d.getWindow().getDecorView();}
            if(root!=null)touch(root,(MotionEvent)p.args[0]);
        }};
        try{XposedBridge.hookAllMethods(android.app.Activity.class,"dispatchTouchEvent",events);}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(android.app.Dialog.class,"dispatchTouchEvent",events);}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(View.class,"performLongClick",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
            if(!InterfaceHooks.on("comment_copy_body")||CrashGuard.tripped||!(p.thisObject instanceof View))return;View v=(View)p.thisObject;View panel=CommentWallpaper.panel(v.getRootView());if(panel==null||!inside(v,panel))return;
            List<TextView> cells=new ArrayList<>();collect(v,cells,0,new int[]{100});Fact hit=null;for(TextView t:cells){Fact f=bound.get(t);if(f==null)continue;if(!norm(t.getText()).equals(norm(f.content))&&!truncated(t.getText(),f.content))continue;if(hit!=null&&!hit.id.equals(f.id))return;hit=f;}
            if(hit!=null){recent=hit;recentAt=SystemClock.uptimeMillis();owner=new java.lang.ref.WeakReference<>(ScreenWatcher.top());}
        }});}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(View.class,"performClick",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
            if(!InterfaceHooks.on("comment_copy_body")||CrashGuard.tripped||!(p.thisObject instanceof View)||!fresh())return;View v=(View)p.thisObject;if(Screen.isOwnDecor(v.getRootView())||!copyLabel(v,0,new int[]{20}))return;
            // Keep the remembered target while the native menu closes and writes to the clipboard.
            recentAt=SystemClock.uptimeMillis();copyPressedAt=recentAt;
        }});}catch(Throwable ignored){}
        try{XposedBridge.hookAllMethods(ClipboardManager.class,"setPrimaryClip",new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
            if(!InterfaceHooks.on("comment_copy_body")||CrashGuard.tripped||p.args.length!=1||!(p.args[0] instanceof ClipData))return;
            String body=recentBody();Fact fact=recent;
            ClipData clip=(ClipData)p.args[0];if(clip.getItemCount()!=1||clip.getItemAt(0).getText()==null)return;
            String raw=clip.getItemAt(0).getText().toString();
            String clean=null;if(body!=null&&fact!=null){Set<String> names=new HashSet<>(Arrays.asList(fact.nick,fact.handle,"@"+fact.handle));if(prefixed(raw,body,names))clean=body;}
            if(clean==null&&fresh()&&!tappedBody.isEmpty()&&prefixed(raw,tappedBody,tappedAuthors))clean=tappedBody;
            if(clean==null&&fresh()&&fact!=null&&copyPressedAt>0&&SystemClock.uptimeMillis()-copyPressedAt<3000&&CommentCopyText.verifiedSuffix(raw,fact.content))clean=fact.content;
            boolean confirmed=nativeCommentCopy()||fresh()&&copyPressedAt>0&&SystemClock.uptimeMillis()-copyPressedAt<3000;
            if(clean==null&&confirmed)clean=knownBody(raw);
            if(clean==null&&confirmed&&fresh()&&!tappedBody.isEmpty()&&CommentCopyText.verifiedSuffix(raw,tappedBody))clean=tappedBody;
            if(clean!=null&&!raw.equals(clean)){p.args[0]=new ClipData("Комментарий",new String[]{"text/plain"},new ClipData.Item(clean));cleanCopies.incrementAndGet();}
        }});}catch(Throwable ignored){}
    }
    private static boolean copyLabel(View v,int depth,int[] budget){if(v==null||depth>4||--budget[0]<0)return false;String s=norm(v.getContentDescription()).toLowerCase(Locale.ROOT);if(s.equals("копировать")||s.equals("copy")||s.equals("копировать текст")||s.equals("копировать комментарий")||s.equals("copy comment"))return true;if(v instanceof TextView){s=norm(((TextView)v).getText()).toLowerCase(Locale.ROOT);if(s.equals("копировать")||s.equals("copy")||s.equals("копировать текст")||s.equals("копировать комментарий")||s.equals("copy comment"))return true;}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(copyLabel(g.getChildAt(i),depth+1,budget))return true;}return false;}
    private static boolean fresh(){android.app.Activity a=ScreenWatcher.top();return a!=null&&owner.get()==a&&SystemClock.uptimeMillis()-recentAt<20000;}
    private static boolean prefixed(String raw,String body,Set<String> authors){return CommentCopyText.matches(raw,body,authors);}
    private static boolean nativeCommentCopy(){
        if(CommentSelect.inNativeCopy())return true;
        for(StackTraceElement frame:Thread.currentThread().getStackTrace()){String name=frame.getClassName().toLowerCase(Locale.ROOT),method=frame.getMethodName().toLowerCase(Locale.ROOT);if(name.startsWith("com.ss.android.ugc.aweme.comment.")&&(name.contains("longpress")||name.contains("copy")||method.contains("copy")))return true;}
        return false;
    }
    private static String knownBody(String raw){
        if(raw==null||raw.length()>20500)return null;String hit=null;
        synchronized(facts){for(List<Fact> list:facts.values())for(Fact fact:list){if(!prefixed(raw,fact.content,new HashSet<>(Arrays.asList(fact.nick,fact.handle,"@"+fact.handle)))&&!(fresh()&&recent!=null&&recent.id.equals(fact.id)&&CommentCopyText.verifiedSuffix(raw,fact.content)))continue;if(hit!=null&&!hit.equals(fact.content))return null;hit=fact.content;}}
        return hit;
    }
    static String recentBody(){return fresh()&&recent!=null?recent.content:null;}
    private static void rememberTap(View panel,float x,float y){
        recent=null;recentAt=0;copyPressedAt=0;tappedBody="";tappedAuthors.clear();Rect rect=new Rect();List<TextView> cells=new ArrayList<>();collect(panel,cells,0,new int[]{1800});
        for(TextView t:cells){if(t instanceof EditText||!t.getGlobalVisibleRect(rect)||!rect.contains((int)x,(int)y))continue;Fact hit=bound.get(t);if(hit==null)hit=format(t,panel);if(hit!=null){recent=hit;tappedBody=hit.content;for(View row=t.getParent() instanceof View?(View)t.getParent():null;row!=null&&row!=panel;row=row.getParent() instanceof View?(View)row.getParent():null){if(row.getHeight()>panel.getHeight()*.45f)break;List<TextView> neighbors=new ArrayList<>();collect(row,neighbors,0,new int[]{70});for(TextView n:neighbors)if(n!=t&&authorMatches(original(n),hit)){tappedAuthors.add(norm(n.getText()));tappedAuthors.add(original(n));}}recentAt=SystemClock.uptimeMillis();owner=new java.lang.ref.WeakReference<>(ScreenWatcher.top());return;}
            String shown=t.getText().toString();if(shown.trim().isEmpty()||relativeTime(norm(shown))||author(t)||shown.length()>20000)continue;
            for(View row=t.getParent() instanceof View?(View)t.getParent():null;row!=null&&row!=panel;row=row.getParent() instanceof View?(View)row.getParent():null){if(row.getHeight()>panel.getHeight()*.6f)break;List<TextView> neighbors=new ArrayList<>();collect(row,neighbors,0,new int[]{65});boolean timestamp=false;Set<String> names=new HashSet<>();int[] tp=new int[2];t.getLocationOnScreen(tp);for(TextView n:neighbors){if(n==t||n instanceof EditText)continue;String s=n.getText().toString().trim();int[] np=new int[2];n.getLocationOnScreen(np);if(relativeTime(norm(s))||time(n)||norm(s).matches("[0-9]{1,2} .{2,12}, [0-9]{2}:[0-9]{2}:[0-9]{2}"))timestamp=true;else if(!s.isEmpty()&&s.length()<=100&&(author(n)||np[1]<tp[1]))names.add(s);}if(timestamp&&!names.isEmpty()){tappedBody=shown;tappedAuthors.addAll(names);recentAt=SystemClock.uptimeMillis();owner=new java.lang.ref.WeakReference<>(ScreenWatcher.top());return;}}
        }
    }
    private static boolean inside(View child,View parent){for(View v=child;v!=null;){if(v==parent)return true;v=v.getParent() instanceof View?(View)v.getParent():null;}return false;}
    static void apply(View root){
        if(root==null||Screen.isOwnDecor(root))return;
        if(transitioning(root)){positionFull(root);return;}
        try{
            if(!any()&&edits.isEmpty()&&limits.isEmpty()&&buttons.isEmpty())return;
            View panel=CrashGuard.tripped||!any()?null:CommentWallpaper.panel(root);
            if(InterfaceHooks.on("comment_full"))for(View old:new ArrayList<>(expandedHosts.keySet()))if(old!=null&&old.getRootView()==root&&old.isShown()&&header(root,old)!=null){View outer=expandedHosts.get(old);if(panel==null||inside(panel,outer)||inside(outer,panel)){panel=old;break;}}
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
            if(button!=null&&(panel==null||!InterfaceHooks.on("comment_full")||!button.isAttachedToWindow())){
                buttons.remove(root);fullTitles.remove(root);if(button.getParent() instanceof ViewGroup)((ViewGroup)button.getParent()).removeView(button);
            }
            for(View expanded:new ArrayList<>(originalHeight.keySet()))if(expanded!=null&&expanded.getRootView()==root&&(panel==null||panel!=expanded&&!inside(panel,expanded)||!InterfaceHooks.on("comment_full"))){
                expandedHosts.values().removeIf(v->v==expanded);restoreFull(expanded);
            }
            if(panel==null)return;
            visiblePanelCount++;List<TextView> list=new ArrayList<>();collect(panel,list,0,new int[]{1800});visibleTextCount=list.size();
            for(TextView t:list){
                if(t.getTag()!=null)observe(t.getTag());
                Fact hit=format(t,panel);if(hit!=null){Fact previous=bound.put(t,hit);if(previous==null||!previous.id.equals(hit.id))matchedRows.incrementAndGet();}else bound.remove(t);
                if((hit!=null||body(t))&&InterfaceHooks.on("comment_expand")&&!limits.containsKey(t)){limits.put(t,new Limits(t));t.setMaxLines(Integer.MAX_VALUE);t.setEllipsize(null);}
                if(hit!=null&&InterfaceHooks.on("comment_expand")&&!norm(t.getText()).equals(norm(hit.content))&&truncated(t.getText(),hit.content))edit(t,hit.content,"comment_expand");
            }
            if(InterfaceHooks.on("comment_full"))addFull(root,panel,list);
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
    private static final WeakHashMap<View,View> buttonPanels=new WeakHashMap<>();
    private static final WeakHashMap<View,View> expandedHosts=new WeakHashMap<>();
    private static boolean headerTitle(TextView t){return norm(t.getText()).toLowerCase(Locale.ROOT).matches("(?:[0-9., kкmм]+ )?(?:comments?|комментарии|комментариев|комментария|комментарий)(?: *\\(?[0-9., kкmм]+\\)?)?");}
    private static final WeakHashMap<View,CommentFullLayout> fullLayouts=new WeakHashMap<>();
    private static TextView header(View root,View panel){
        Rect area=new Rect();if(!panel.getGlobalVisibleRect(area))return null;float dp=root.getResources().getDisplayMetrics().density;
        List<TextView> all=new ArrayList<>();collect(root,all,0,new int[]{3000});TextView best=null;int distance=Integer.MAX_VALUE;
        for(TextView t:all){if(!headerTitle(t))continue;Rect b=new Rect();if(!t.getGlobalVisibleRect(b)||b.centerY()<area.top-dp*100||b.centerY()>area.top+dp*90)continue;int d=Math.abs(b.centerY()-area.top);if(d<distance){distance=d;best=t;}}
        return best;
    }
    private static View sheet(View root,View panel,TextView title){
        float dp=root.getResources().getDisplayMetrics().density;View target=panel;
        for(ViewParent q=panel.getParent();q instanceof ViewGroup&&q!=root;q=q.getParent()){
            ViewGroup g=(ViewGroup)q;if(g.getWidth()<root.getWidth()*.82f)break;
            Object b=DownloadHooks.call(g.getLayoutParams(),"getBehavior");
            if(b!=null&&DownloadHooks.call(b,"getState") instanceof Number&&title!=null&&inside(title,g))return g;
            if(g.getHeight()>root.getHeight()*.96f)break;
            if(title!=null&&inside(title,g)&&g.getHeight()>=panel.getHeight()&&g.getHeight()-panel.getHeight()<dp*180){target=g;View input=CommentWallpaper.composer(g,0,new int[]{600});if(input!=null&&inside(input,g))break;}
        }
        return target;
    }
    private static final WeakHashMap<View,TextView> fullTitles=new WeakHashMap<>();
    static boolean transitioning(View root){for(CommentFullLayout l:fullLayouts.values())if(l!=null&&l.transitioning(root))return true;return false;}
    static void positionFull(View root){
        View button=buttons.get(root);TextView title=fullTitles.get(root);
        if(button==null||title==null||!title.isAttachedToWindow())return;
        int[] a=new int[2],o=new int[2];title.getLocationOnScreen(a);root.getLocationOnScreen(o);
        float dp=root.getResources().getDisplayMetrics().density;int w=button.getWidth()>0?button.getWidth():Math.round(dp*36);
        float x=root.getWidth()*.73f-w/2f,y=a[1]-o[1]+title.getHeight()/2f-w/2f;
        if(button.getTranslationX()!=x)button.setTranslationX(x);if(button.getTranslationY()!=y)button.setTranslationY(y);
    }
    private static void addFull(View root,View panel,List<TextView> list){
        if(!(root instanceof FrameLayout)||panel.getParent()==null)return;
        FrameLayout host=(FrameLayout)root;float dp=root.getResources().getDisplayMetrics().density;int size=Math.round(dp*36);
        TextView title=header(root,panel);if(title==null){View old=buttons.remove(root);fullTitles.remove(root);if(old!=null&&old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);return;}
        View old=buttons.get(root);ImageView button=old instanceof ImageView?(ImageView)old:null;
        if(old!=null&&(button==null||buttonPanels.get(root)!=panel)){if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);buttons.remove(root);button=null;}
        if(button==null){
            button=new ImageView(root.getContext());button.setTag(Ui.OWN_TAG);button.setScaleType(ImageView.ScaleType.FIT_CENTER);int pad=Math.round(dp*6);button.setPadding(pad,pad,pad,pad);button.setBackground(null);button.setFocusable(true);
            final View target=panel;final ImageView control=button;
            button.setOnClickListener(v->{toggleFull(root,target,fullTitles.get(root));control.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);updateIcon(root,target);});
            host.addView(button,new FrameLayout.LayoutParams(size,size,Gravity.TOP|Gravity.LEFT));buttons.put(root,button);buttonPanels.put(root,panel);
        }
        fullTitles.put(root,title);updateIcon(root,panel);positionFull(root);
        if(host.indexOfChild(button)!=host.getChildCount()-1)button.bringToFront();
    }
    private static void updateIcon(View root,View panel){
        View v=buttons.get(root);TextView title=fullTitles.get(root);if(!(v instanceof ImageView)||title==null)return;
        View target=expandedHosts.get(panel);CommentFullLayout l=fullLayouts.get(target);
        boolean expanded=l!=null&&l.expanded();String desc=expanded?"Свернуть комментарии":"Развернуть комментарии";
        int tint=title.getCurrentTextColor();int key=expanded?Icons.COLLAPSE:Icons.EXPAND;Integer last=(Integer)v.getTag(0x54544649),color=(Integer)v.getTag(0x54544643);
        if(last==null||last!=key||color==null||color!=tint){((ImageView)v).setImageDrawable(Icons.of(key,tint));v.setTag(0x54544649,key);v.setTag(0x54544643,tint);v.setContentDescription(desc);}
    }
    private static void toggleFull(View root,View panel,TextView title){
        if(!panel.isAttachedToWindow())return;
        View previous=expandedHosts.get(panel);
        if(previous!=null){
            CommentFullLayout layout=fullLayouts.get(previous);
            if(layout!=null){layout.toggle();return;}
            expandedHosts.remove(panel);restoreFull(previous);return;
        }
        View target=sheet(root,panel,title);if(target==panel&&(title==null||!inside(title,target))){Toasts.show(root.getContext(),"Не найден внешний контейнер комментариев");return;}
        CommentFullLayout layout=CommentFullLayout.create(root,target,panel,title,()->{
            expandedHosts.remove(panel);originalHeight.remove(target);fullLayouts.remove(target);CommentWallpaper.invalidatePanel(root);root.post(()->{CommentTools.apply(root);CommentWallpaper.refreshRoot(root);});
        });
        if(layout==null){Toasts.show(root.getContext(),"Не удалось определить размеры комментариев");return;}
        originalHeight.put(target,target.getLayoutParams().height);expandedHosts.put(panel,target);fullLayouts.put(target,layout);layout.toggle();
    }
    private static void restoreFull(View target){
        CommentFullLayout layout=fullLayouts.remove(target);if(layout!=null){layout.restore();originalHeight.remove(target);return;}
        Integer h=originalHeight.remove(target);if(h!=null&&target.getLayoutParams()!=null){ViewGroup.LayoutParams lp=target.getLayoutParams();lp.height=h;target.setLayoutParams(lp);}
    }
    static void touch(View root,MotionEvent event){
        if(CrashGuard.tripped||root==null||Screen.isOwnDecor(root))return;
        int action=event.getActionMasked();long stamp=event.getEventTime();if(lastEvent==stamp&&lastAction==action&&eventRoot.get()==root)return;lastEvent=stamp;lastAction=action;eventRoot=new java.lang.ref.WeakReference<>(root);
        if(action==MotionEvent.ACTION_DOWN){downAt=stamp;downX=event.getRawX();downY=event.getRawY();View panel=CommentWallpaper.panel(root);if(touchCopy(root,downX,downY,0,new int[]{1600})&&fresh()){copyPressedAt=SystemClock.uptimeMillis();}else if(panel!=null)rememberTap(panel,downX,downY);return;}
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
    private static boolean touchCopy(View v,float x,float y,int depth,int[] budget){if(v==null||depth>30||--budget[0]<0||!v.isShown())return false;Rect rect=new Rect();if(!v.getGlobalVisibleRect(rect)||!rect.contains((int)x,(int)y))return false;if(v instanceof TextView&&copyLabel(v,0,new int[]{1}))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(touchCopy(g.getChildAt(i),x,y,depth+1,budget))return true;}return false;}
    private static View like(View v,int depth,int[] b){
        if(--b[0]<0||depth>7||v.getVisibility()!=View.VISIBLE)return null;
        String s=String.valueOf(v.getContentDescription()).toLowerCase(Locale.ROOT).trim(),n=name(v);
        if((s.equals("like")||s.equals("нравится")||s.equals("поставить лайк")||s.startsWith("like comment"))&&!v.isSelected()){if(v.isClickable())return v;if(v.getParent() instanceof View&&((View)v.getParent()).isClickable()&&!((View)v.getParent()).isSelected())return (View)v.getParent();}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=like(g.getChildAt(i),depth+1,b);if(hit!=null)return hit;}}
        return null;
    }
}
