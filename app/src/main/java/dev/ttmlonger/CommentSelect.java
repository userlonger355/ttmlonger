package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Append a local selection action only to the known native comment long-press panel. */
final class CommentSelect {
    static void install(ClassLoader cl){
        Class<?> fragment=XposedHelpers.findClassIfExists("com.ss.android.ugc.aweme.comment.longpresspanel.ui.CommentLongPressFragment",cl);
        Class<?> item=XposedHelpers.findClassIfExists("X.0oi8",cl);
        if(fragment==null||item==null)return;
        for(Method m:fragment.getDeclaredMethods()){
            if(!m.getName().equals("oX")||m.getReturnType()!=void.class||m.getParameterTypes().length!=1||m.getParameterTypes()[0]!=List.class)continue;
            try{XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if((!InterfaceHooks.on("comment_select")&&!InterfaceHooks.on("comment_copy_body"))||CrashGuard.tripped||!(p.args[0] instanceof List))return;
                    try{
                        Field cf=DownloadHooks.findField(fragment,"LLJJL");
                        Object model=cf==null?null:cf.get(p.thisObject);
                        String text=body(model);
                        if(text==null||text.isEmpty())return;
                        Activity a=ScreenWatcher.top();if(a==null||a.isFinishing())return;
                        List<?> old=(List<?>)p.args[0];if(old.isEmpty())return;
                        Field key=item.getField("LIZIZ"),title=item.getField("LIZLLL"),icon=item.getField("LIZJ"),action=item.getField("LJ");
                        int pos=-1;Object art=null;
                        for(int i=0;i<old.size();i++){
                            Object row=old.get(i);if(!item.isInstance(row))return;
                            String k=String.valueOf(key.get(row));if("ttm_select_text".equals(k)||"ttm_copy_body".equals(k))return;
                            if(k.toLowerCase(java.util.Locale.ROOT).contains("copy")){pos=i+1;art=icon.get(row);}
                        }
                        if(pos<0)return;
                        Class<?> fn=action.getType();if(!fn.isInterface()||!fn.getName().equals("kotlin.jvm.functions.Function0"))return;
                        Object unit=Class.forName("kotlin.Unit",false,cl).getField("INSTANCE").get(null);
                        ArrayList<Object> result=new ArrayList<>(old);
                        if(InterfaceHooks.on("comment_copy_body")){
                            Object row=make(item,key,title,icon,action,fn,cl,unit,art,"ttm_copy_body","Копировать без юзернейма",()->{
                                if(!a.isFinishing()&&InterfaceHooks.on("comment_copy_body")&&!CrashGuard.tripped)copy(a,(String)text);
                            },a);
                            result.add(pos++,row);
                        }
                        if(InterfaceHooks.on("comment_select")){
                            Object row=make(item,key,title,icon,action,fn,cl,unit,art,"ttm_select_text","Выделить текст",()->{
                                if(!a.isFinishing()&&InterfaceHooks.on("comment_select")&&!CrashGuard.tripped)show(a,(String)text);
                            },a);
                            result.add(pos,row);
                        }
                        p.args[0]=result;
                    }catch(Throwable ignored){}
                }
            });android.util.Log.i("TTMLonger","Comment selection hook installed");}catch(Throwable ignored){}
        }
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
        }));
        return row;
    }
    static String body(Object model){
        Object text=DownloadHooks.call(model,"getText");
        return text instanceof String&&( (String)text).length()<=20000?(String)text:null;
    }
    private static void copy(Activity a,String text){
        android.content.ClipboardManager cm=(android.content.ClipboardManager)a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if(cm==null)return;
        // No username prefix, no stripping legitimate @mentions or modifying URLs.
        cm.setPrimaryClip(new android.content.ClipData("Комментарий",new String[]{"text/plain"},new android.content.ClipData.Item(text)));
        Toasts.show(a,"Скопировано без юзернейма");
    }
    static void show(Activity a,String text){
        Ui ui=new Ui(a);TextView t=ui.text(text,17,Ui.INK,false);
        t.setTextIsSelectable(true);t.setPadding(ui.dp(20),ui.dp(14),ui.dp(20),ui.dp(16));
        android.widget.ScrollView scroll=new android.widget.ScrollView(a);scroll.addView(t);
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Выделите нужный текст").setView(scroll).setPositiveButton("Закрыть",null).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
