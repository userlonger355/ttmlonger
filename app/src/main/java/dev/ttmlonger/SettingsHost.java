package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.lang.reflect.Method;
import java.util.*;

/** Inserts a settings row into the Activity group, without a top banner or inset changes. */
final class SettingsHost {
    static final String ACTIVITY="com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity";
    private static final int TAG=0x7ff0ad02;
    private static final String[] FIRST={"управление публикациями","manage posts","manage post","post management"};
    private static final String[] SECOND={"предпочитаемый контент","preferred content","content preferences"};
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
        View root=a.getWindow().getDecorView();if(root.getTag(TAG)!=null)return;
        Injector i=new Injector(a);root.setTag(TAG,i);
        root.getViewTreeObserver().addOnGlobalLayoutListener(i);root.post(i::onGlobalLayout);
    }
    private static boolean is(CharSequence x,String[] words){
        String n=Targets.norm(x);for(String s:words)if(n.equals(s))return true;return false;
    }
    private static int dp(Context c,float n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    private static LinearLayout row(Activity a,boolean dark){
        int ink=dark?0xfff5f5f5:0xff161823,muted=dark?0xff828282:0xff74747c,fill=dark?0xff1d1d1d:0xfff5f5f5;
        Ui.use(ModBlock.Palette.of(dark?0xff000000:0xffffffff));
        LinearLayout r=new LinearLayout(a);r.setTag(Ui.OWN_TAG);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(a,22),0,dp(a,20),0);r.setMinimumHeight(dp(a,48));
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x16000000),new ColorDrawable(fill),null));
        ImageView icon=new ImageView(a);icon.setImageDrawable(Icons.of(Icons.SLIDERS,muted));
        r.addView(icon,new LinearLayout.LayoutParams(dp(a,21),dp(a,21)));
        TextView text=new TextView(a);text.setText("Настройки мода");text.setTextColor(ink);text.setTextSize(16);text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);text.setGravity(Gravity.CENTER_VERTICAL);text.setSingleLine(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.leftMargin=dp(a,10);r.addView(text,lp);
        ImageView chevron=new ImageView(a);chevron.setImageDrawable(Icons.of(Icons.CHEVRON_RIGHT,muted));r.addView(chevron,new LinearLayout.LayoutParams(dp(a,16),dp(a,16)));
        r.setContentDescription("Настройки мода");r.setClickable(true);r.setFocusable(true);r.setOnClickListener(v->ModMenu.show(a));return r;
    }
    private static class Injector implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity a;boolean busy;View nativeRow;InlineFrame frame;String state="ожидает раздел «Активность»";
        Injector(Activity a){this.a=a;}
        @Override public void onGlobalLayout(){
            if(busy||a.isFinishing()||a.isDestroyed())return;
            if(frame!=null){frame.invalidate();return;}
            if(nativeRow!=null&&nativeRow.isAttachedToWindow())return;
            busy=true;
            try{
                ViewGroup c=container();if(c==null||c.getWidth()==0)return;
                TextView first=findText(c,FIRST,0),second=findText(c,SECOND,0);
                if(first!=null&&second!=null){
                    for(ViewParent q=first.getParent();q instanceof ViewGroup;q=q.getParent()){
                        if(!(q instanceof LinearLayout))continue;LinearLayout g=(LinearLayout)q;
                        if(g.getOrientation()!=LinearLayout.VERTICAL)continue;
                        View f=child(first,g),s=child(second,g);
                        if(f==null||s==null||f==s||g.indexOfChild(s)!=g.indexOfChild(f)+1)continue;
                        int h=f.getHeight();if(h<dp(a,36)||h>dp(a,84))continue;
                        boolean dark=ModBlock.luminance(first.getCurrentTextColor())>.5f;
                        nativeRow=row(a,dark);g.addView(nativeRow,g.indexOfChild(f),new LinearLayout.LayoutParams(-1,h));
                        state="первый пункт группы «Активность»";return;
                    }
                }
                // Compose: the content remains intact; a visual slot is inserted at a recognized row boundary.
                if(!(c.getParent() instanceof ViewGroup)||findProvider(c,0)==null)return;
                ViewGroup parent=(ViewGroup)c.getParent();int index=parent.indexOfChild(c);
                ViewGroup.LayoutParams original=c.getLayoutParams();
                InlineFrame wrap=new InlineFrame(a,c,this);
                parent.removeView(c);
                try{wrap.addView(c,new FrameLayout.LayoutParams(-1,-1));parent.addView(wrap,index,original);frame=wrap;state="ожидает семантику Compose";}
                catch(Throwable t){wrap.removeView(c);if(wrap.getParent()==parent)parent.removeView(wrap);if(c.getParent()==null)parent.addView(c,index,original);}
            }catch(Throwable ignored){}finally{busy=false;}
        }
        private ViewGroup container(){
            try{
                for(Class<?> k=a.getClass();k!=null;k=k.getSuperclass())try{
                    Method m=k.getDeclaredMethod("getFragmentContainer");m.setAccessible(true);
                    Object id=m.invoke(a);if(id instanceof Integer){View v=a.findViewById((Integer)id);if(v instanceof ViewGroup)return (ViewGroup)v;}break;
                }catch(NoSuchMethodException ignored){}
            }catch(Throwable ignored){}
            View content=a.findViewById(android.R.id.content);
            if(content instanceof ViewGroup&&((ViewGroup)content).getChildCount()>0){View v=((ViewGroup)content).getChildAt(0);if(v instanceof ViewGroup)return (ViewGroup)v;}
            return null;
        }
    }
    private static View child(View v,ViewGroup parent){
        while(v!=null&&v.getParent()!=parent){if(!(v.getParent() instanceof View))return null;v=(View)v.getParent();}return v;
    }
    private static TextView findText(View v,String[] labels,int depth){
        if(depth>40||v.getVisibility()!=View.VISIBLE)return null;
        if(v instanceof TextView&&is(((TextView)v).getText(),labels))return (TextView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=findText(g.getChildAt(i),labels,depth+1);if(t!=null)return t;}}
        return null;
    }
    private static View findProvider(View v,int depth){
        if(depth>35||v.getVisibility()!=View.VISIBLE)return null;
        String n=v.getClass().getName().toLowerCase(Locale.ROOT);
        if(n.contains("compose")&&v.getAccessibilityNodeProvider()!=null)return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View x=findProvider(g.getChildAt(i),depth+1);if(x!=null)return x;}}
        return null;
    }
    private static class Found {
        Rect first,second;boolean activity;
    }
    private static void nodes(AccessibilityNodeProvider provider,AccessibilityNodeInfo n,Rect clickable,Found out,int[] budget,int depth){
        if(n==null||--budget[0]<0||depth>32)return;
        Rect r=new Rect();n.getBoundsInScreen(r);
        Rect row=n.isClickable()&&!r.isEmpty()?r:clickable;
        CharSequence t=n.getText(),d=n.getContentDescription();
        if(is(t,FIRST)||is(d,FIRST))out.first=new Rect(row==null?r:row);
        if(is(t,SECOND)||is(d,SECOND))out.second=new Rect(row==null?r:row);
        if(is(t,new String[]{"активность","activity"})||is(d,new String[]{"активность","activity"}))out.activity=true;
        for(int i=0;i<n.getChildCount()&&budget[0]>0;i++){
            AccessibilityNodeInfo ch=null;
            try{
                try {
                    Method getId=AccessibilityNodeInfo.class.getDeclaredMethod("getChildId",int.class);
                    getId.setAccessible(true);
                    Object id=getId.invoke(n,i);
                    if(id instanceof Long)ch=provider.createAccessibilityNodeInfo((int)(((Long)id)>>>32));
                } catch(Throwable ignored) { }
                if(ch==null)ch=n.getChild(i);
                nodes(provider,ch,row,out,budget,depth+1);
            }catch(Throwable ignored){}finally{if(ch!=null)ch.recycle();}
        }
    }
    private static Found locate(View compose){
        Found f=new Found();AccessibilityNodeProvider p=compose.getAccessibilityNodeProvider();
        if(p==null)return f;
        AccessibilityNodeInfo root=null;
        try{root=p.createAccessibilityNodeInfo(View.NO_ID);nodes(p,root,null,f,new int[]{180},0);}
        catch(Throwable ignored){}finally{if(root!=null)root.recycle();}
        // Some Compose versions expose direct text lookup but not the virtual tree.
        if(f.first==null||f.second==null)for(String[] group:new String[][]{FIRST,SECOND}){
            boolean first=group==FIRST;if(first?f.first!=null:f.second!=null)continue;
            for(String label:group)try{
                List<AccessibilityNodeInfo> found=p.findAccessibilityNodeInfosByText(label,View.NO_ID);
                if(found==null)continue;
                for(AccessibilityNodeInfo n:found){try{if(is(n.getText(),group)||is(n.getContentDescription(),group)){Rect r=new Rect();n.getBoundsInScreen(r);if(first)f.first=r;else f.second=r;}}finally{n.recycle();}}
            }catch(Throwable ignored){}
        }
        return f;
    }
    private static final class InlineFrame extends FrameLayout {
        final Activity act;final ViewGroup content;final Injector owner;
        LinearLayout action;int cut=-1,delta,left,width;boolean dark;
        long lastScan;boolean gesture,shifted,rowTouch;
        InlineFrame(Activity a,ViewGroup c,Injector owner){
            super(a);this.act=a;content=c;this.owner=owner;setClipChildren(true);
            getViewTreeObserver().addOnPreDrawListener(()->{refresh();return true;});
        }
        private void refresh(){
            long now=SystemClock.uptimeMillis();if(now-lastScan<80||getWidth()==0)return;lastScan=now;
            try{
                AccessibilityManager am=(AccessibilityManager)act.getSystemService(Context.ACCESSIBILITY_SERVICE);
                if(am!=null&&am.isTouchExplorationEnabled()){hideSlot();return;}
                int visible=0;for(int i=0;i<content.getChildCount();i++)if(content.getChildAt(i).getVisibility()==VISIBLE)visible++;
                if(visible>1){hideSlot();return;}
                View v=findProvider(content,0);if(v==null){hideSlot();return;}
                Found f=locate(v);
                if(f.first==null||f.second==null){hideSlot();return;}
                Rect a=f.first,b=f.second;int[] pos=new int[2];getLocationOnScreen(pos);
                int h=b.centerY()-a.centerY();
                if(h<dp(act,36)||h>dp(act,84)||Math.abs(a.centerX()-b.centerX())>dp(act,25)&&a.width()>getWidth()*.6f){hideSlot();return;}
                int y=a.centerY()-h/2-pos[1];
                if(y<dp(act,50)||y+h>getHeight()-dp(act,16)){hideSlot();return;}
                int l=a.width()>getWidth()*.7f?Math.max(0,a.left-pos[0]):dp(act,8);
                int w=a.width()>getWidth()*.7f?Math.min(getWidth()-l,a.width()):getWidth()-l*2;
                boolean night=(act.getResources().getConfiguration().uiMode&0x30)==0x20;
                android.graphics.drawable.Drawable bg=content.getBackground();
                if(bg instanceof ColorDrawable)night=ModBlock.luminance(((ColorDrawable)bg).getColor())<.5f;
                if(action==null||night!=dark){
                    if(action!=null)removeView(action);dark=night;action=row(act,dark);
                    addView(action,new FrameLayout.LayoutParams(w,h));action.setVisibility(INVISIBLE);
                }
                boolean changed=cut!=y||delta!=h||left!=l||width!=w;
                cut=y;delta=h;left=l;width=w;
                if(changed||action.getVisibility()!=VISIBLE){
                    FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)action.getLayoutParams();lp.width=w;lp.height=h;lp.leftMargin=l;lp.topMargin=y;action.setLayoutParams(lp);action.setVisibility(VISIBLE);invalidate();
                }
                owner.state="первый пункт «Активность» (визуальная вставка Compose)";
            }catch(Throwable ignored){hideSlot();}
        }
        private void hideSlot(){if(cut<0)return;cut=-1;delta=0;if(action!=null)action.setVisibility(INVISIBLE);invalidate();owner.state="раздел «Активность» не распознан";}
        @Override protected void dispatchDraw(Canvas canvas){
            if(cut<0||action==null||action.getVisibility()!=VISIBLE){super.dispatchDraw(canvas);return;}
            long time=getDrawingTime();
            int s=canvas.save();canvas.clipRect(0,0,getWidth(),cut);drawChild(canvas,content,time);canvas.restoreToCount(s);
            s=canvas.save();canvas.clipRect(0,cut+delta,getWidth(),getHeight());canvas.translate(0,delta);drawChild(canvas,content,time);canvas.restoreToCount(s);
            s=canvas.save();canvas.clipRect(0,cut,getWidth(),cut+delta);canvas.drawColor(dark?0xff000000:0xffffffff);drawChild(canvas,action,time);canvas.restoreToCount(s);
        }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
            int type=e.getActionMasked();
            if(type==MotionEvent.ACTION_DOWN){
                gesture=true;rowTouch=cut>=0&&e.getY()>=cut&&e.getY()<cut+delta;
                shifted=cut>=0&&e.getY()>=cut+delta;
            }
            if(!gesture||(!rowTouch&&!shifted)){boolean r=super.dispatchTouchEvent(e);if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL)gesture=false;return r;}
            MotionEvent copy=MotionEvent.obtain(e);
            try{
                if(rowTouch&&action!=null){copy.offsetLocation(-action.getLeft(),-action.getTop());action.dispatchTouchEvent(copy);return true;}
                copy.offsetLocation(0,-delta);return content.dispatchTouchEvent(copy);
            }finally{copy.recycle();if(type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL){gesture=false;rowTouch=false;shifted=false;}}
        }
    }
}
