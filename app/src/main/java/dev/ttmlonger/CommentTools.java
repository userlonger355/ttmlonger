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
    static final String[] KEYS={"comment_expand","comment_copy","comment_nick","comment_exact","comment_double","comment_full"};
    static final String[] TITLES={"Разворачивать длинные комментарии","Копировать текст без имени автора","Ник вместо имени","Точное время комментариев","Двойное нажатие для лайка","Кнопка полноэкранных комментариев"};
    static int modelHooks;
    private static final LinkedHashMap<String,List<Fact>> facts=new LinkedHashMap<>();
    private static final ThreadLocal<Boolean> reading=new ThreadLocal<>();
    private static final WeakHashMap<TextView,Edit> edits=new WeakHashMap<>();
    private static final WeakHashMap<TextView,Limits> limits=new WeakHashMap<>();
    private static final WeakHashMap<View,View> buttons=new WeakHashMap<>();
    private static final WeakHashMap<View,Integer> originalHeight=new WeakHashMap<>();
    private static java.lang.ref.WeakReference<TextView> lastTap=new java.lang.ref.WeakReference<>(null);
    private static long lastAt,downAt;private static float downX,downY;
    private static final class Fact {
        final String id,nick,handle;final long time;
        Fact(String i,String n,String h,long t){id=i;nick=n;handle=h;time=t;}
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
    static void install(ClassLoader cl){
        for(String n:new String[]{"com.ss.android.ugc.aweme.comment.model.Comment","com.ss.android.ugc.aweme.comment.model.CommentItem"}){
            try{
                Class<?> c=Class.forName(n,false,cl);
                for(java.lang.reflect.Method m:c.getDeclaredMethods()){
                    if(!m.getName().equals("getText")||m.getParameterTypes().length!=0||m.getReturnType()!=String.class)continue;
                    XposedBridge.hookMethod(m,new XC_MethodHook(){
                        @Override protected void afterHookedMethod(MethodHookParam p){
                            if(CrashGuard.tripped||reading.get()!=null||!(p.getResult() instanceof String))return;
                            String content=((String)p.getResult()).trim();if(content.isEmpty()||content.length()>6000)return;
                            reading.set(true);
                            try{
                                Object model=p.thisObject,u=DownloadHooks.call(model,"getUser");
                                String id=text(model,"getCid"),nick=text(u,"getNickname"),handle=text(u,"getUniqueId");
                                Object t=DownloadHooks.call(model,"getCreateTime");
                                long sec=t instanceof Number?VideoInfo.toSeconds(((Number)t).longValue()):0;
                                if(id.isEmpty()||nick.isEmpty()&&handle.isEmpty())return;
                                synchronized(facts){
                                    List<Fact> list=facts.get(content);if(list==null){list=new ArrayList<>();facts.put(content,list);}
                                    for(int i=0;i<list.size();i++)if(list.get(i).id.equals(id)){list.remove(i);break;}
                                    list.add(new Fact(id,nick,handle,sec));if(list.size()>8)list.remove(0);
                                    while(facts.size()>450)facts.remove(facts.keySet().iterator().next());
                                }
                            }finally{reading.remove();}
                        }
                    });modelHooks++;
                }
            }catch(Throwable ignored){}
        }
        XposedBridge.hookAllMethods(View.class,"performLongClick",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(CrashGuard.tripped||!InterfaceHooks.on("comment_copy"))return;
                View v=(View)p.thisObject;if(!body(v)||Screen.isOwnDecor(v.getRootView()))return;
                View panel=CommentWallpaper.panel(v.getRootView());if(panel==null||!inside(v,panel))return;
                CharSequence s=((TextView)v).getText();if(s==null||s.length()==0)return;
                ClipboardManager cm=(ClipboardManager)v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if(cm!=null){cm.setPrimaryClip(ClipData.newPlainText("Комментарий",s));Toasts.show(v.getContext(),"Текст комментария скопирован");p.setResult(true);}
            }
        });
    }
    private static boolean inside(View child,View parent){for(View v=child;v!=null;){if(v==parent)return true;v=v.getParent() instanceof View?(View)v.getParent():null;}return false;}
    static void apply(View root){
        if(root==null||Screen.isOwnDecor(root))return;
        try{
            if(!any()&&edits.isEmpty()&&limits.isEmpty()&&buttons.isEmpty())return;
            View panel=CrashGuard.tripped||!any()?null:CommentWallpaper.panel(root);
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
            for(TextView t:list)if(body(t)){
                if(InterfaceHooks.on("comment_expand")&&!limits.containsKey(t)){limits.put(t,new Limits(t));t.setMaxLines(Integer.MAX_VALUE);t.setEllipsize(null);}
                if(InterfaceHooks.on("comment_copy"))t.setLongClickable(true);
                if(InterfaceHooks.on("comment_nick")||InterfaceHooks.on("comment_exact"))format(t,panel);
            }
            if(InterfaceHooks.on("comment_full")&&buttons.get(root)==null)addFull(root,panel,list);
        }catch(Throwable ignored){}
    }
    private static void collect(View v,List<TextView> out,int depth,int[] b){
        if(--b[0]<0||depth>32||v.getVisibility()!=View.VISIBLE||Ui.OWN_TAG.equals(v.getTag()))return;
        if(v instanceof TextView)out.add((TextView)v);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),out,depth+1,b);}
    }
    private static void format(TextView body,View panel){
        List<Fact> matches;
        synchronized(facts){List<Fact> f=facts.get(body.getText().toString().trim());if(f==null)return;matches=new ArrayList<>(f);}
        for(View row=body.getParent() instanceof View?(View)body.getParent():null;row!=null&&row!=panel;row=row.getParent() instanceof View?(View)row.getParent():null){
            List<TextView> cells=new ArrayList<>();collect(row,cells,0,new int[]{70});
            TextView who=null,when=null;int count=0;
            for(TextView t:cells){if(body(t))count++;if(author(t))who=t;if(time(t))when=t;}
            if(count>1)return;if(who==null)continue;
            Edit previous=edits.get(who);String shown=previous==null?who.getText().toString().trim():previous.before.toString().trim();
            Fact hit=null;for(Fact f:matches)if(shown.equals(f.nick)||shown.equals("@"+f.handle)||shown.equals(f.handle)){if(hit!=null&&!hit.id.equals(f.id))return;hit=f;}
            if(hit==null)return;
            if(InterfaceHooks.on("comment_nick")&&!hit.handle.isEmpty())edit(who,"@"+hit.handle,"comment_nick");
            if(InterfaceHooks.on("comment_exact")&&when!=null&&hit.time>0){
                SimpleDateFormat fmt=new SimpleDateFormat("d MMM, HH:mm:ss",new Locale("ru"));fmt.setTimeZone(Zones.current());
                edit(when,fmt.format(new Date(hit.time*1000L)),"comment_exact");
            }
            return;
        }
    }
    private static void edit(TextView t,String value,String key){
        if(value.contentEquals(t.getText()))return;Edit old=edits.get(t);
        CharSequence orig=old!=null&&old.after.contentEquals(t.getText())?old.before:t.getText();
        edits.put(t,new Edit(orig,value,key));t.setText(value);
    }
    private static void addFull(View root,View panel,List<TextView> list){
        if(!(panel.getParent() instanceof FrameLayout))return;
        for(TextView t:list){
            String s=t.getText().toString().trim().toLowerCase(Locale.ROOT);
            if(!s.matches("(?:[0-9.,\\s]+[kmкм]?\\s+)?(?:comments?|комментарии|комментариев|комментарий)"))continue;
            if(!(t.getParent() instanceof LinearLayout))continue;
            LinearLayout row=(LinearLayout)t.getParent();if(row.getOrientation()!=LinearLayout.HORIZONTAL)continue;
            TextView b=new TextView(root.getContext());b.setTag(Ui.OWN_TAG);b.setText("⛶");b.setTextSize(23);b.setTextColor(t.getCurrentTextColor());b.setGravity(Gravity.CENTER);b.setContentDescription("Развернуть комментарии");
            int size=Math.round(root.getResources().getDisplayMetrics().density*40);
            row.addView(b,new LinearLayout.LayoutParams(size,size));buttons.put(root,b);
            b.setOnClickListener(v->{ViewGroup.LayoutParams lp=panel.getLayoutParams();Integer h=originalHeight.remove(panel);if(h==null){originalHeight.put(panel,lp.height);lp.height=-1;}else lp.height=h;panel.setLayoutParams(lp);});
            return;
        }
    }
    static void touch(View root,MotionEvent event){
        if(!InterfaceHooks.on("comment_double")||CrashGuard.tripped||root==null||Screen.isOwnDecor(root))return;
        int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN){downAt=event.getEventTime();downX=event.getRawX();downY=event.getRawY();return;}
        if(action!=MotionEvent.ACTION_UP||event.getEventTime()-downAt>300||Math.abs(event.getRawX()-downX)>20||Math.abs(event.getRawY()-downY)>20)return;
        View panel=CommentWallpaper.panel(root);if(panel==null)return;
        List<TextView> cells=new ArrayList<>();collect(panel,cells,0,new int[]{1800});
        Rect r=new Rect();TextView hit=null;
        for(TextView t:cells)if(body(t)&&t.getGlobalVisibleRect(r)&&r.contains((int)event.getRawX(),(int)event.getRawY())){hit=t;break;}
        if(hit==null)return;long now=SystemClock.uptimeMillis();
        if(lastTap.get()==hit&&now-lastAt<320){lastTap.clear();lastAt=0;
            for(View row=hit.getParent() instanceof View?(View)hit.getParent():null;row!=null&&row!=panel;row=row.getParent() instanceof View?(View)row.getParent():null){
                View like=like(row,0,new int[]{65});if(like!=null){like.performClick();break;}
            }
        }else{lastTap=new java.lang.ref.WeakReference<>(hit);lastAt=now;}
    }
    private static View like(View v,int depth,int[] b){
        if(--b[0]<0||depth>7||v.getVisibility()!=View.VISIBLE)return null;
        String s=String.valueOf(v.getContentDescription()).toLowerCase(Locale.ROOT).trim(),n=name(v);
        if(v.isClickable()&&(n.contains("comment_like")||n.contains("comment_digg"))&&(s.equals("like")||s.equals("нравится")||s.equals("поставить лайк")))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=like(g.getChildAt(i),depth+1,b);if(hit!=null)return hit;}}
        return null;
    }
}
