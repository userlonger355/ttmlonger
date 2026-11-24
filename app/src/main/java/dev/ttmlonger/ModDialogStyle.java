package dev.ttmlonger;
import android.app.Dialog;
import android.view.*;
import de.robv.android.xposed.*;
final class ModDialogStyle {
    static void install(){try{XposedBridge.hookAllMethods(Dialog.class,"show",new XC_MethodHook(){
        protected void afterHookedMethod(MethodHookParam p){
            try{Dialog d=(Dialog)p.thisObject;if(!Screen.isOwn(d))return;Window w=d.getWindow();if(w==null)return;
                View decor=w.getDecorView();decor.post(()->{if(!d.isShowing())return;int h=decor.getHeight(),screen=decor.getResources().getDisplayMetrics().heightPixels;
                    if(h>0&&h<screen*.94f)Ui.blurBehind(w,Math.round(32*decor.getResources().getDisplayMetrics().density));
                });
            }catch(Throwable ignored){}
        }
    });}catch(Throwable ignored){}}
}
