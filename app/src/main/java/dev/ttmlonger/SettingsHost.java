package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.WeakHashMap;

/** A real, fixed footer in the settings activity. No Compose drawing, virtual
 * accessibility coordinates, scroll offsets or touch-event remapping. */
final class SettingsHost {
    static final String ACTIVITY="com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity";
    private static final int TAG=0x7ff0ad02;
    static boolean matches(Activity a){
        for(Class<?> c=a.getClass();c!=null;c=c.getSuperclass())if(ACTIVITY.equals(c.getName()))return true;
        return false;
    }
    static boolean handles(Activity a){return matches(a);}
    static String status(Activity a){
        Object x=a.getWindow().getDecorView().getTag(TAG);
        return x instanceof Injector?((Injector)x).state:"не страница настроек";
    }
    static void attach(Activity a){
        View decor=a.getWindow().getDecorView();if(decor.getTag(TAG)!=null)return;
        Injector i=new Injector(a);decor.setTag(TAG,i);
        decor.getViewTreeObserver().addOnGlobalLayoutListener(i);decor.post(i::onGlobalLayout);
    }
    private static int dp(Context c,float n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    private static GradientDrawable rounded(int color,int radius){
        GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(radius);return bg;
    }
    private static final class Injector implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity act;final View decor;
        final WeakHashMap<View,Integer> nativeMargins=new WeakHashMap<>();
        FrameLayout host;LinearLayout footer,action;TextView text;ImageView icon,chevron;
        boolean busy,dark;int reserved=-1;String state="ожидает контейнер настроек";
        Injector(Activity a){act=a;decor=a.getWindow().getDecorView();}
        @Override public void onGlobalLayout(){
            if(busy||act.isFinishing()||act.isDestroyed())return;
            busy=true;
            try{
                View content=act.findViewById(android.R.id.content);
                if(!(content instanceof FrameLayout)||content.getWidth()==0)return;
                FrameLayout current=(FrameLayout)content;
                if(host!=current){detach();host=current;}
                if(footer==null){if(host.getChildCount()==0)return;createFooter();}
                if(footer.getParent()!=host){detach();host=current;createFooter();}
                // The footer is fixed for the whole settings activity (including its subpages).
                // It never depends on recycled rows, Compose semantics, or scroll position.
                int inset=bottomOverlap();
                int height=dp(act,50)+inset;
                if(reserved!=height){
                    reserved=height;
                    FrameLayout.LayoutParams fp=(FrameLayout.LayoutParams)footer.getLayoutParams();
                    if(fp.height!=height){fp.height=height;footer.setLayoutParams(fp);}
                    footer.setPadding(dp(act,8),0,dp(act,8),inset);
                }
                // Reserve space using real layout parameters on the existing content
                // children. Do not reparent a fragment or a Compose view.
                for(int n=0;n<host.getChildCount();n++){
                    View child=host.getChildAt(n);if(child==footer)continue;
                    ViewGroup.LayoutParams raw=child.getLayoutParams();
                    if(!(raw instanceof FrameLayout.LayoutParams))continue;
                    FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)raw;
                    Integer original=nativeMargins.get(child);
                    if(original==null){original=lp.bottomMargin;nativeMargins.put(child,original);}
                    int wanted=Math.max(original+dp(act,50),reserved);
                    if(lp.bottomMargin!=wanted){lp.bottomMargin=wanted;child.setLayoutParams(lp);}
                }
                if(host.indexOfChild(footer)!=host.getChildCount()-1)footer.bringToFront();
                boolean night=isDark();if(night!=dark)style(night);
                state="закреплённая панель настроек мода";
            }catch(Throwable t){state="не удалось добавить панель";FlagStore.log(t);}
            finally{busy=false;}
        }
        private int bottomOverlap(){
            try{
                WindowInsets in=decor.getRootWindowInsets();if(in==null)return 0;
                int bottom;
                if(android.os.Build.VERSION.SDK_INT>=30)bottom=in.getInsets(WindowInsets.Type.navigationBars()).bottom;
                else bottom=in.getStableInsetBottom();
                int[] d=new int[2],h=new int[2];decor.getLocationInWindow(d);host.getLocationInWindow(h);
                int already=Math.max(0,d[1]+decor.getHeight()-h[1]-host.getHeight());
                return Math.max(0,bottom-already);
            }catch(Throwable ignored){return 0;}
        }
        private boolean isDark(){
            boolean night=(act.getResources().getConfiguration().uiMode&0x30)==0x20;
            for(int n=0;n<host.getChildCount();n++){
                View child=host.getChildAt(n);if(child==footer)continue;
                android.graphics.drawable.Drawable bg=child.getBackground();
                if(bg instanceof android.graphics.drawable.ColorDrawable){
                    int color=((android.graphics.drawable.ColorDrawable)bg).getColor();
                    if(android.graphics.Color.alpha(color)==255)return ModBlock.luminance(color)<.5f;
                }
            }return night;
        }
        private void createFooter(){
            reserved=-1;footer=new LinearLayout(act);footer.setTag(Ui.OWN_TAG);
            footer.setOrientation(LinearLayout.VERTICAL);footer.setGravity(Gravity.CENTER_VERTICAL);
            action=new LinearLayout(act);action.setGravity(Gravity.CENTER_VERTICAL);action.setOrientation(LinearLayout.HORIZONTAL);
            action.setPadding(dp(act,12),0,dp(act,12),0);
            icon=new ImageView(act);action.addView(icon,new LinearLayout.LayoutParams(dp(act,18),dp(act,18)));
            text=new TextView(act);text.setText("Настройки мода");text.setTextSize(15);text.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));text.setSingleLine(true);text.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-1,1);tp.setMarginStart(dp(act,12));action.addView(text,tp);
            chevron=new ImageView(act);action.addView(chevron,new LinearLayout.LayoutParams(dp(act,18),dp(act,18)));
            action.setContentDescription("Настройки мода");action.setClickable(true);action.setFocusable(true);
            action.setOnClickListener(v->ModMenu.show(act));footer.addView(action,new LinearLayout.LayoutParams(-1,dp(act,48)));
            style(isDark());
            FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-1,dp(act,50),Gravity.BOTTOM);host.addView(footer,fp);
        }
        private void style(boolean night){
            dark=night;int ink=dark?0xfff5f5f5:0xff161823,muted=dark?0xffa1a1a1:0xff74747c;
            footer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            action.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x16000000),rounded(dark?0xff1d1d1d:0xfff3f3f5,dp(act,14)),null));
            icon.setImageDrawable(Icons.of(Icons.SLIDERS,muted));chevron.setImageDrawable(Icons.of(Icons.CHEVRON_RIGHT,muted));text.setTextColor(ink);
        }
        private void detach(){
            for(View child:nativeMargins.keySet().toArray(new View[0])){
                if(child==null||!(child.getLayoutParams() instanceof FrameLayout.LayoutParams))continue;
                Integer old=nativeMargins.get(child);if(old==null)continue;
                FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)child.getLayoutParams();
                if(lp.bottomMargin==Math.max(old+dp(act,50),reserved)){lp.bottomMargin=old;child.setLayoutParams(lp);}
            }
            nativeMargins.clear();if(footer!=null&&footer.getParent() instanceof ViewGroup)((ViewGroup)footer.getParent()).removeView(footer);
            footer=null;host=null;reserved=-1;
        }
    }
}
