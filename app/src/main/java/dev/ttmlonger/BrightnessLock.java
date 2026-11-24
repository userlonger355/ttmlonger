package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Optional window brightness lock, independent of the player's HDR rendition selection. */
final class BrightnessLock {
    private static final String KEY="own_brightness";
    private static volatile boolean on;
    private static boolean installed;
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("ttmlonger_interface",0);}
    static boolean enabled(Context c){return prefs(c).getBoolean(KEY,false);}
    static void set(Activity a,boolean value){
        on=value;
        prefs(a).edit().putBoolean(KEY,value).apply();
        if(on)apply(a.getWindow());
    }
    static float brightness(float requested,boolean enabled){
        return enabled && requested>=0f ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE:requested;
    }
    static void install(Context c){
        on=enabled(c);
        if(installed)return;
        XposedBridge.hookAllMethods(Window.class,"setAttributes",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(!on||CrashGuard.tripped||p.args.length!=1||!(p.args[0] instanceof WindowManager.LayoutParams))return;
                WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)p.args[0];
                attrs.screenBrightness=brightness(attrs.screenBrightness,true);
            }
        });
        installed=true;
    }
    static void apply(Window window){
        if(!on||CrashGuard.tripped||window==null)return;
        try{
            WindowManager.LayoutParams attrs=window.getAttributes();
            if(attrs.screenBrightness>=0f){
                attrs.screenBrightness=brightness(attrs.screenBrightness,true);
                window.setAttributes(attrs);
            }
        }catch(Throwable t){FlagStore.log(t);}
    }
}
