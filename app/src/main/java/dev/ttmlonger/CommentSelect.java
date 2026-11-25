package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native comment action list. Copy uses the exact model body, never a formatted username prefix. */
final class CommentSelect {
    private static volatile int hooks;
    private static final java.util.WeakHashMap<Object,Object> originals=new java.util.WeakHashMap<>();
    private static final String FRAGMENT="com.ss.android.ugc.aweme.comment.longpresspanel.ui.CommentLongPressFragment";
    static void install(ClassLoader cl){
        Class<?> fragment=XposedHelpers.findClassIfExists(FRAGMENT,cl);
        if(fragment==null)return;
        // The action list must consist of the known action model (or an equivalent typed row).
        for(Method m:fragment.getDeclaredMethods()){
            if(m.getReturnType()!=void.class||m.getParameterTypes().length!=1||!List.class.isAssignableFrom(m.getParameterTypes()[0])||Modifier.isAbstract(m.getModifiers())||Modifier.isStatic(m.getModifiers()))continue;
            try{XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(!InterfaceHooks.on("comment_copy_body")||CrashGuard.tripped||!(p.args[0] instanceof List))return;
                    try{
                        String resolved=body(resolve(p.thisObject));final String text=resolved==null||resolved.isEmpty()?CommentTools.recentBody():resolved;if(text==null||text.isEmpty())return;
                        Activity a=ScreenWatcher.top();if(a==null||a.isFinishing()||a.isDestroyed())return;
                        List<?> old=(List<?>)p.args[0];if(old.isEmpty()||old.size()>80)return;
                        Object first=old.get(0);if(first==null)return;
                        Class<?> item=first.getClass();
                        Field key=DownloadHooks.findField(item,"LIZIZ"),title=DownloadHooks.findField(item,"LIZLLL"),icon=DownloadHooks.findField(item,"LIZJ"),action=DownloadHooks.findField(item,"LJ");
                        if(key==null||title==null||action==null)return;
                        Class<?> fn=action.getType();if(!fn.isInterface()||!fn.getName().equals("kotlin.jvm.functions.Function0"))return;
                        Object unit=Class.forName("kotlin.Unit",false,cl).getField("INSTANCE").get(null);
                        int pos=-1;Object nativeCopy=null;
                        for(int i=0;i<old.size();i++){
                            Object row=old.get(i);if(!item.isInstance(row))return;
                            String k=String.valueOf(key.get(row)),label=String.valueOf(title.get(row));
                            if("ttm_select_text".equals(k))return;
                            if("ttm_copy_body".equals(k))continue;
                            if(isCopy(k,label)){pos=i;nativeCopy=row;break;}
                        }
                        if(pos<0||nativeCopy==null)return;
                        ArrayList<Object> result=new ArrayList<>(old);
                        if(InterfaceHooks.on("comment_copy_body")){
                            // Reuse the native row: no constructor/extra action required to repair Copy.
                            Object current=action.get(nativeCopy);
                            Object saved=originals.get(nativeCopy);
                            Object original=saved==null?current:saved;
                            if(original==null)return;
                            originals.put(nativeCopy,original);
                            Object replacement=Proxy.newProxyInstance(cl,new Class<?>[]{fn},(proxy,method,args)->{
                                switch(method.getName()){
                                    case "invoke":
                                        if(a.isFinishing()||a.isDestroyed())return unit;
                                        if(!InterfaceHooks.on("comment_copy_body")||CrashGuard.tripped)return method.invoke(original,args);
                                        // Preserve native menu dismissal and analytics, but intercept its clipboard write.
                                        return runCopy(a,text,original,method,args,unit);
                                    case "toString":return "ttm_native_copy";
                                    case "hashCode":return System.identityHashCode(proxy);
                                    case "equals":return args!=null&&args.length==1&&proxy==args[0];
                                    default:return null;
                                }
                            });
                            action.set(nativeCopy,replacement);
                        }
                        p.args[0]=result;
                    }catch(Throwable ignored){}
                }
            });hooks++;}catch(Throwable ignored){}
        }
        try{XposedBridge.hookAllMethods(android.content.ClipboardManager.class,"setPrimaryClip",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                CopyScope scope=activeCopy.get();
                if(scope==null||p.args.length!=1||!(p.args[0] instanceof android.content.ClipData))return;
                p.args[0]=plain(scope.text);scope.written=true;
            }
        });}catch(Throwable ignored){}
        android.util.Log.i("TTMLonger","Comment action-list hooks="+hooks);
    }
    private static final class CopyScope {
        final String text;boolean written;
        CopyScope(String t){text=t;}
    }
    private static final ThreadLocal<CopyScope> activeCopy=new ThreadLocal<>();
    private static Object runCopy(Activity a,String text,Object original,Method method,Object[] args,Object unit){
        CopyScope prior=activeCopy.get(),scope=new CopyScope(text);activeCopy.set(scope);
        try{
            // Copy actions are invoked from the native menu on the main thread.
            // Use the original action synchronously so its normal closing behaviour survives.
            method.invoke(original,args);
        }catch(Throwable ignored){}
        finally{if(prior==null)activeCopy.remove();else activeCopy.set(prior);}
        if(!scope.written)copy(a,text);else Toasts.show(a,"Скопировано без юзернейма");
        return unit;
    }
    private static boolean isCopy(String key,String label){
        String k=key.toLowerCase(Locale.ROOT).trim(),t=label.toLowerCase(Locale.ROOT).trim();
        if(k.equals("ttm_copy_body"))return false;
        return k.equals("copy")||k.equals("copy_comment")||k.equals("copy_text")||k.equals("copy_comment_text")||(k.contains("copy")&&!k.contains("link")&&!k.contains("url"))||t.equals("копировать")||t.equals("копировать текст")||t.equals("копировать комментарий")||t.equals("copy")||t.equals("copy text")||t.equals("copy comment");
    }
    private static Object resolve(Object fragment){
        try{
            Field known=DownloadHooks.findField(fragment.getClass(),"LLJJL");
            Object value=known==null?null:known.get(fragment);if(isComment(value)&&body(value)!=null)return value;
        }catch(Throwable ignored){}
        // Avoid reading arbitrary strings or other comments from the list; inspect only direct model fields.
        Object found=null;
        for(Class<?> k=fragment.getClass();k!=null&&k!=Object.class;k=k.getSuperclass()){
            for(Field field:k.getDeclaredFields())try{
                if(Modifier.isStatic(field.getModifiers())||field.getType().isPrimitive())continue;
                String n=field.getType().getName();
                if(!n.contains(".comment.model.")&&!n.equals("java.lang.Object"))continue;
                field.setAccessible(true);Object value=field.get(fragment);
                if(!isComment(value)||body(value)==null)continue;
                if(found!=null&&found!=value)return null;found=value;
            }catch(Throwable ignored){}
        }
        return found;
    }
    private static boolean isComment(Object model){
        if(model==null)return false;for(Class<?> k=model.getClass();k!=null;k=k.getSuperclass()){
            String n=k.getName();if(n.equals("com.ss.android.ugc.aweme.comment.model.Comment")||n.equals("com.ss.android.ugc.aweme.comment.model.CommentItem"))return true;
        }return false;
    }
    private static Object make(Class<?> item,Field key,Field title,Field icon,Field action,Class<?> fn,ClassLoader cl,Object unit,Object art,String id,String label,Runnable task,Activity a)throws Exception{
        Object row=item.getConstructor().newInstance();key.set(row,id);title.set(row,label);icon.set(row,art);
        action.set(row,Proxy.newProxyInstance(cl,new Class<?>[]{fn},(proxy,method,args)->{
            switch(method.getName()){
                case "invoke":a.runOnUiThread(task);return unit;
                case "toString":return id;
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return args!=null&&args.length==1&&proxy==args[0];
                default:return null;
            }
        }));return row;
    }
    static String body(Object model){
        Object text=RepostTimes.readField(model,"text");
        if(!(text instanceof CharSequence)&&isComment(model))try{Field f=DownloadHooks.findField(model.getClass(),"text");if(f!=null)text=f.get(model);}catch(Throwable ignored){}
        return text instanceof CharSequence&&((CharSequence)text).length()<=20000?text.toString():null;
    }
    private static android.content.ClipData plain(String text){
        // Do not use newPlainText(): the unrelated clean-links hook can rewrite a URL in the comment.
        return new android.content.ClipData("Комментарий",new String[]{"text/plain"},new android.content.ClipData.Item(text));
    }
    private static void copy(Activity a,String text){
        android.content.ClipboardManager cm=(android.content.ClipboardManager)a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if(cm==null)return;cm.setPrimaryClip(plain(text));Toasts.show(a,"Скопировано без юзернейма");
    }
    static void show(Activity a,String text){
        Ui ui=new Ui(a);TextView t=ui.text(text,17,Ui.INK,false);
        t.setTextIsSelectable(true);t.setPadding(ui.dp(20),ui.dp(14),ui.dp(20),ui.dp(16));
        android.widget.ScrollView scroll=new android.widget.ScrollView(a);scroll.addView(t);
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Выделите нужный текст").setView(scroll).setPositiveButton("Закрыть",null).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
