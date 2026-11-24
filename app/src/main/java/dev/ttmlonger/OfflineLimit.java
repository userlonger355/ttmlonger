package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Extends the native offline count picker, not the network downloader. */
final class OfflineLimit {
    static final int MAX=9000;
    private static final int[] EXTRA={500,1000,3000,5000,MAX};
    private static final String KEY="offline_extended";
    private static volatile boolean on;
    private static volatile boolean available;
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("ttmlonger_interface",0);}
    static boolean enabled(Context c){return prefs(c).getBoolean(KEY,false);}
    static void set(Context c,boolean value){on=value;prefs(c).edit().putBoolean(KEY,value).apply();}
    static boolean available(){return available;}
    static boolean extra(int n){for(int k:EXTRA)if(k==n)return true;return false;}
    static List<?> choices(List<?> original,boolean enabled){
        if(!enabled||original==null||original.isEmpty())return original;
        boolean numeric=false;
        for(Object o:original){if(!(o instanceof Integer))return original;if((Integer)o>0)numeric=true;}
        if(!numeric)return original;
        ArrayList<Object> copy=new ArrayList<>(original);
        for(int n:EXTRA)if(!copy.contains(n))copy.add(n);
        return copy;
    }
    private static Field field(Class<?> owner,String name)throws Exception{
        Field f=DownloadHooks.findField(owner,name);
        if(f==null)throw new NoSuchFieldException(name);
        return f;
    }
    static void install(Context ctx,ClassLoader cl){
        on=enabled(ctx);
        try{
            Class<?> provider=Class.forName("X.0wj1",false,cl);
            Class<?> row=Class.forName("X.0wiL",false,cl);
            Class<?> model=Class.forName("X.0lhy",false,cl);
            Class<?> result=Class.forName("X.0ldB",false,cl);
            Class<?> icon=Class.forName("X.0GtW",false,cl);
            Class<?> vm=Class.forName("com.ss.android.ugc.aweme.offlinemode.viewmodel.OfflineModeManagerVM",false,cl);
            Method counts=provider.getDeclaredMethod("LJFF"),render=row.getDeclaredMethod("LIZLLL");
            Method select=vm.getMethod("t43",int.class),state=vm.getMethod("getState");
            Field number=field(row,"LLJJJ"),activity=field(row,"LLJJJIL"),manager=field(row,"LLJJJJ");
            Constructor<?> make=model.getConstructor(boolean.class,String.class,icon,String.class,View.OnClickListener.class,int.class);
            if(!Modifier.isStatic(counts.getModifiers())||counts.getReturnType()!=List.class||
                render.getReturnType()!=result||!result.isAssignableFrom(model)||number.getType()!=int.class||
                !Activity.class.isAssignableFrom(activity.getType())||!vm.isAssignableFrom(manager.getType()))return;
            XposedBridge.hookMethod(render,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(!on||!available||CrashGuard.tripped||p.hasThrowable())return;
                    try{
                        int count=number.getInt(p.thisObject);if(!extra(count))return;
                        Activity host=(Activity)activity.get(p.thisObject);
                        Object controller=manager.get(p.thisObject);
                        if(host==null||host.isFinishing()||!vm.isInstance(controller))return;
                        Object current=state.invoke(controller);
                        if(current==null)return;
                        Object selected=field(current.getClass(),"LLJJIJIL").get(current);
                        Object automatic=field(current.getClass(),"LLJJJ").get(current);
                        if(!(selected instanceof Integer)||!(automatic instanceof Boolean))return;
                        boolean checked=!(Boolean)automatic&&(Integer)selected==count;
                        View.OnClickListener click=v->{
                            if(!on||CrashGuard.tripped||host.isFinishing())return;
                            if(count>=1000){
                                String free="";
                                try{long bytes=new android.os.StatFs(host.getFilesDir().getAbsolutePath()).getAvailableBytes();
                                    free="\nСвободно: "+String.format(java.util.Locale.getDefault(),"%.1f ГБ",bytes/1e9);}
                                catch(Throwable ignored){}
                                AlertDialog dlg=new AlertDialog.Builder(host,Ui.dialogTheme()).setTitle(count+" офлайн-видео")
                                    .setMessage("Большая загрузка может занять много места и трафика. TikTok может ограничить количество. Выбор не запускает скачивание автоматически."+free)
                                    .setNegativeButton("Отмена",null).setPositiveButton("Выбрать",(x,w)->choose(host,controller,select,state,count)).create();
                                Screen.markOwn(dlg);dlg.show();Ui.style(dlg);
                            }else choose(host,controller,select,state,count);
                        };
                        p.setResult(make.newInstance(checked,count+" видео",null,"",click,0x1b7a));
                    }catch(Throwable ignored){}
                }
            });
            XposedBridge.hookMethod(counts,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(!available||!on||CrashGuard.tripped||p.hasThrowable()||!(p.getResult() instanceof List))return;
                    p.setResult(choices((List<?>)p.getResult(),true));
                }
            });
            available=true;
            android.util.Log.i("TTMLonger","Offline native count and row hooks installed");
        }catch(Throwable t){FlagStore.log(t);}
    }
    private static void choose(Activity host,Object controller,Method select,Method state,int count){
        if(!extra(count)||!on||CrashGuard.tripped||host.isFinishing())return;
        try{
            select.invoke(controller,count);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
                if(host.isFinishing()||host.isDestroyed())return;
                try{
                    Object current=state.invoke(controller);
                    Object picked=field(current.getClass(),"LLJJIJIL").get(current);
                    if(!(picked instanceof Integer)||(Integer)picked!=count)Toasts.show(host,"Выбор количества не подтверждён TikTok");
                }catch(Throwable ignored){Toasts.show(host,"Проверьте выбранное количество в меню");}
            },350);
        }catch(Throwable t){Toasts.show(host,"Не удалось выбрать количество");}
    }
}
