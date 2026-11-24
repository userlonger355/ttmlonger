package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Locale;

/** Session-local unread badge dismissal; never sends read receipts to TikTok. */
final class InboxBadge {
    private static int baseline;
    private static String owner;
    private static WeakReference<View> tab=new WeakReference<>(null);
    private static WeakReference<TextView> hidden=new WeakReference<>(null);
    private static WeakReference<ViewTreeObserver> observer=new WeakReference<>(null);
    private static float originalAlpha=1;
    private static long missingAt,lastFind;
    static int count(CharSequence text){
        if(text==null)return 0;
        String s=text.toString().trim();
        if(s.equals("99+"))return 100;
        if(!s.matches("[0-9]{1,2}"))return 0;
        return Integer.parseInt(s);
    }
    static int next(int dismissed,int observed){return dismissed>0&&observed>0&&observed<=dismissed?observed:0;}
    static boolean label(CharSequence raw){
        if(raw==null)return false;
        String s=raw.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("inbox")||s.equals("входящие")||s.equals("вхідні")||s.equals("сообщения")||s.equals("messages");
    }
    static boolean isInbox(View v){return named(v,0,new int[]{64});}
    private static boolean named(View v,int depth,int[] budget){
        if(v==null||depth>5||--budget[0]<0||v.getVisibility()!=View.VISIBLE)return false;
        if(label(v.getContentDescription())||v instanceof TextView&&label(((TextView)v).getText()))return true;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(named(g.getChildAt(i),depth+1,budget))return true;}
        return false;
    }
    static TextView number(View v){return findNumber(v,0,new int[]{64});}
    private static TextView findNumber(View v,int depth,int[] budget){
        if(v==null||depth>4||--budget[0]<0||v.getVisibility()!=View.VISIBLE)return null;
        if(v instanceof TextView&&count(((TextView)v).getText())>0)return (TextView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView hit=findNumber(g.getChildAt(i),depth+1,budget);if(hit!=null)return hit;}}
        return null;
    }
    private static String account(){String id=StreakHooks.knownOwnUid();return id==null?"":id;}
    private static void restore(){TextView old=hidden.get();if(old!=null&&old.getAlpha()==0f)old.setAlpha(originalAlpha);hidden.clear();}
    private static final ViewTreeObserver.OnPreDrawListener TICK=()->{
        try{
            if(CrashGuard.tripped||!java.util.Objects.equals(owner,account())){baseline=0;restore();return true;}
            View v=tab.get();
            long now=android.os.SystemClock.uptimeMillis();
            if(v==null||!v.isAttachedToWindow()||!v.isShown()){
                if(now-lastFind<400)return true;
                lastFind=now;
                Activity act=ScreenWatcher.top();
                if(act!=null)attach(act);
                v=tab.get();
                if(v==null||!v.isAttachedToWindow())return true;
            }
            TextView badge=number(v);
            if(badge==null){
                restore();
                if(missingAt==0)missingAt=now;
                else if(now-missingAt>20000)baseline=0;
                return true;
            }
            missingAt=0;baseline=next(baseline,count(badge.getText()));
            if(baseline==0)restore();
            else{
                if(hidden.get()!=badge){restore();originalAlpha=badge.getAlpha();hidden=new WeakReference<>(badge);}
                if(badge.getAlpha()!=0f)badge.setAlpha(0f);
            }
        }catch(Throwable ignored){}
        return true;
    };
    static void attach(Activity a){
        if(baseline==0&&hidden.get()==null)return;
        try{
            View root=a.getWindow().getDecorView();
            BarFinder.Found f=BarFinder.find(root);if(f==null)return;
            for(View v:f.tabs)if(isInbox(v)){watch(root,v);return;}
        }catch(Throwable ignored){}
    }
    private static void watch(View root,View v){
        if(tab.get()!=v){restore();tab=new WeakReference<>(v);}
        ViewTreeObserver now=root.getViewTreeObserver(),old=observer.get();
        if(old!=now){if(old!=null&&old.isAlive())old.removeOnPreDrawListener(TICK);if(now.isAlive()){now.addOnPreDrawListener(TICK);observer=new WeakReference<>(now);}}
        root.invalidate();
    }
    static void ask(Activity a,View v){
        TextView badge=number(v);if(badge==null)return;
        final boolean back=hidden.get()==badge;
        final String identity=account();
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle(back?"Вернуть число?":"Прочитать все?")
            .setMessage(back?"Показать счётчик снова?":"Число на вкладке исчезнет до появления новых уведомлений. Сообщения на сервере останутся непрочитанными.")
            .setNegativeButton("Отмена",null).setPositiveButton("Готово",(dialog,which)->{
                if(!identity.equals(account())||!v.isAttachedToWindow())return;
                TextView current=number(v);if(current==null)return;
                owner=identity;baseline=back?0:count(current.getText());missingAt=0;
                if(back)restore();
                watch(a.getWindow().getDecorView(),v);v.invalidate();
            }).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
