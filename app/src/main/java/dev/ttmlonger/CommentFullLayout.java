package dev.ttmlonger;

import android.animation.*;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Resizes the sheet AND its native list wrappers. No reparenting, no synthetic touches. */
final class CommentFullLayout {
    private final View root,target,panel,composer,header;
    private final Runnable finished;
    private final IdentityHashMap<View,Slot> slots=new IdentityHashMap<>();
    private final Object behavior;
    private final int nativeState,nativeOffset;
    private final boolean nativeFit;
    private final int initialHeight;
    private final int initialBottom;
    private final int initialTop;
    private int safeHeight,safeBottom,safeTop;
    private float fraction;
    private boolean transitioning,laidOut;
    private int generation;
    private final float baseTranslation;
    private final int initialContentHeight;
    boolean expanded(){return expanded;}
    boolean transitioning(View v){return root==v&&transitioning;}
    private boolean expanded,restored;
    private ValueAnimator animator;
    private final View.OnAttachStateChangeListener attach=new View.OnAttachStateChangeListener(){
        public void onViewAttachedToWindow(View v){}
        public void onViewDetachedFromWindow(View v){restore();}
    };
    private static final class Slot{
        final View view;final int height,measured,top,bottom,gravity,padTop,padBottom;
        boolean grow,move;
        Slot(View v){view=v;ViewGroup.LayoutParams p=v.getLayoutParams();height=p.height;measured=v.getHeight();
            top=p instanceof ViewGroup.MarginLayoutParams?((ViewGroup.MarginLayoutParams)p).topMargin:0;
            bottom=p instanceof ViewGroup.MarginLayoutParams?((ViewGroup.MarginLayoutParams)p).bottomMargin:0;
            gravity=p instanceof FrameLayout.LayoutParams?((FrameLayout.LayoutParams)p).gravity:-1;
            padTop=v.getPaddingTop();padBottom=v.getPaddingBottom();}
        void restore(){ViewGroup.LayoutParams p=view.getLayoutParams();if(p==null)return;
            p.height=height;if(p instanceof ViewGroup.MarginLayoutParams){((ViewGroup.MarginLayoutParams)p).topMargin=top;((ViewGroup.MarginLayoutParams)p).bottomMargin=bottom;}
            if(p instanceof FrameLayout.LayoutParams)((FrameLayout.LayoutParams)p).gravity=gravity;
            view.setLayoutParams(p);if(view.getPaddingTop()!=padTop||view.getPaddingBottom()!=padBottom)view.setPadding(view.getPaddingLeft(),padTop,view.getPaddingRight(),padBottom);}
    }
    static CommentFullLayout create(View root,View target,View panel,View title,Runnable done){
        if(target==null||title==null||target.getHeight()<1||target.getLayoutParams()==null||!(target.getParent() instanceof View))return null;
        View input=CommentWallpaper.composer(target,0,new int[]{1600});
        return new CommentFullLayout(root,target,panel,title,input,done);
    }
    private CommentFullLayout(View r,View t,View p,View h,View input,Runnable done){
        root=r;target=t;panel=p;header=h;composer=input;finished=done;
        initialHeight=t.getHeight();baseTranslation=t.getTranslationY();int[] xy=new int[2];t.getLocationOnScreen(xy);initialTop=xy[1];initialBottom=xy[1]+initialHeight;
        int content=initialHeight;if(input!=null){int[] hp=new int[2],ip=new int[2];h.getLocationOnScreen(hp);input.getLocationOnScreen(ip);content=Math.max(1,ip[1]+input.getHeight()-hp[1]+Math.round(t.getResources().getDisplayMetrics().density*28));}initialContentHeight=Math.min(initialHeight,content);
        behavior=DownloadHooks.call(t.getLayoutParams(),"getBehavior");nativeState=number(DownloadHooks.call(behavior,"getState"),-1);
        nativeOffset=number(DownloadHooks.call(behavior,"getExpandedOffset"),-1);
        nativeFit=Boolean.TRUE.equals(DownloadHooks.call(behavior,"isFitToContents"));
        slot(target).grow=true;
        // Grow only the chain containing the actual scrolling comment list; sibling rows and
        // input controls keep their intrinsic height.
        View list=findList(panel,0,new int[]{1600});if(list==null)list=findList(target,0,new int[]{1600});
        markChain(list!=null?list:panel);
        if(list!=null&&!contains(panel,list))markChain(panel);
        // A fixed intermediate content wrapper may enclose both the list and the composer.
        // FrameLayout top-positioned composer rows need their original margin moved with the list.
        if(composer!=null)for(View v=composer;v!=null&&v!=target;v=parent(v)){
            View parent=parent(v);if(parent==null)break;
            Slot existing=slots.get(v);
            if(existing!=null&&existing.grow)continue;
            if(parent instanceof FrameLayout&&slots.containsKey(parent)&&v.getLayoutParams() instanceof FrameLayout.LayoutParams){
                FrameLayout.LayoutParams fp=(FrameLayout.LayoutParams)v.getLayoutParams();
                if((fp.gravity&Gravity.VERTICAL_GRAVITY_MASK)!=Gravity.BOTTOM&&v.getHeight()<parent.getHeight()*.5f&&v.getBottom()>parent.getHeight()*.65f){slot(v).move=true;break;}
            }
        }
        computeBounds();target.addOnAttachStateChangeListener(attach);
    }
    private static View parent(View v){return v.getParent() instanceof View?(View)v.getParent():null;}
    private static boolean contains(View parent,View child){for(View v=child;v!=null;v=parent(v))if(v==parent)return true;return false;}
    private static int number(Object o,int fallback){return o instanceof Number?((Number)o).intValue():fallback;}
    private Slot slot(View v){Slot s=slots.get(v);if(s==null&&v.getLayoutParams()!=null){s=new Slot(v);slots.put(v,s);}return s;}
    private void markChain(View leaf){for(View v=leaf;v!=null&&v!=target;v=parent(v)){if(!contains(target,v)||v.getLayoutParams()==null)break;Slot s=slot(v);if(s!=null)s.grow=true;}}
    private static View findList(View v,int depth,int[] budget){
        if(v==null||!v.isShown()||depth>28||--budget[0]<0)return null;
        for(Class<?> k=v.getClass();k!=null;k=k.getSuperclass()){
            String n=k.getName().toLowerCase(Locale.ROOT);
            if((n.endsWith(".recyclerview")||n.endsWith(".listview")||n.endsWith(".lazylist"))&&v.getHeight()>v.getResources().getDisplayMetrics().density*80)return v;
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;View best=null;for(int i=0;i<g.getChildCount();i++){View hit=findList(g.getChildAt(i),depth+1,budget);if(hit!=null&&(best==null||hit.getHeight()>best.getHeight()))best=hit;}return best;}
        return null;
    }
    private void computeBounds(){
        Rect visible=new Rect();root.getWindowVisibleDisplayFrame(visible);
        int[] rp=new int[2],pp=new int[2];root.getLocationOnScreen(rp);View parent=parent(target);parent.getLocationOnScreen(pp);
        int top=rp[1],bottom=rp[1]+root.getHeight();
        WindowInsets in=root.getRootWindowInsets();
        if(in!=null){
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets sys=in.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());top+=sys.top;bottom-=sys.bottom;}
            else{top+=in.getStableInsetTop();bottom-=in.getStableInsetBottom();}
        }
        if(visible.height()>0){top=Math.max(top,visible.top);bottom=Math.min(bottom,visible.bottom);}
        top=Math.max(top,pp[1]+parent.getPaddingTop());bottom=Math.min(bottom,pp[1]+parent.getHeight()-parent.getPaddingBottom());
        safeTop=top;safeBottom=bottom;
        safeHeight=Math.max(initialHeight,bottom-top);
    }
    void toggle(){
        if(restored)return;expanded=!expanded;final int token=++generation;transitioning=true;
        if(animator!=null){animator.removeAllListeners();animator.cancel();animator=null;}
        if(expanded&&!laidOut){
            computeBounds();apply(1f);laidOut=true;prepareBehavior();
            // Do the expensive measure/layout once, before the visual transition starts.
            root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
                public boolean onPreDraw(){
                    if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(this);
                    if(restored||token!=generation)return true;
                    fraction=0f;translate(0f);animateTo(1f,token);return true;
                }
            });target.requestLayout();return;
        }
        animateTo(expanded?1f:0f,token);
    }
    private void translate(float f){
        fraction=f;int[] xy=new int[2];target.getLocationOnScreen(xy);
        float natural=xy[1]-target.getTranslationY()+baseTranslation;
        // Move between actual initial and final top; never resize the RecyclerView per frame.
        float desired=initialTop+(safeTop-initialTop)*f;
        target.setTranslationY(baseTranslation+desired-natural);
        CommentTools.positionFull(root);
    }
    private void animateTo(float end,int token){
        if(!Ui.motionEnabled()){translate(end);complete(end);return;}
        animator=ValueAnimator.ofFloat(fraction,end);animator.setDuration(Math.max(100,Math.round((end>fraction?300:260)*Math.abs(end-fraction))));
        animator.setInterpolator(Ui.motionCurve());animator.addUpdateListener(a->{if(!restored&&token==generation&&target.isAttachedToWindow())translate((Float)a.getAnimatedValue());});
        animator.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(!restored&&token==generation)complete(end);}});animator.start();
    }
    private void prepareBehavior(){
        if(behavior==null)return;
        if(!nativeFit&&nativeOffset>=0){int[] pp=new int[2];parent(target).getLocationOnScreen(pp);invoke("setExpandedOffset",Math.max(0,safeTop-pp[1]));}
        if(nativeState>=0)invoke("setState",3);
    }
    private void invoke(String method,int value){if(behavior!=null)try{behavior.getClass().getMethod(method,int.class).invoke(behavior,value);}catch(Throwable ignored){}}
    private void apply(float f){
        fraction=f;int delta=Math.round(Math.max(0,safeHeight-initialContentHeight)*f);
        for(Slot s:slots.values()){
            View v=s.view;ViewGroup.LayoutParams lp=v.getLayoutParams();if(lp==null)continue;boolean change=false;
            if(s.grow){
                // MATCH_PARENT and weighted LinearLayout children grow naturally. Explicit pixel
                // heights are the source of the blank bottom on 47.9.3.
                boolean weighted=lp instanceof LinearLayout.LayoutParams&&((LinearLayout.LayoutParams)lp).weight>0;
                if(v==target||!weighted&&v!=header&&v!=composer&&s.measured>target.getResources().getDisplayMetrics().density*100){
                    int h=v==target?safeHeight:Math.max(1,s.measured+delta);if(lp.height!=h){lp.height=h;change=true;}
                }
            }
            if(s.move&&lp instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams m=(ViewGroup.MarginLayoutParams)lp;int top=s.top+delta;if(m.topMargin!=top){m.topMargin=top;change=true;}}
            if(v==target&&lp instanceof FrameLayout.LayoutParams&&behavior==null){
                FrameLayout.LayoutParams fp=(FrameLayout.LayoutParams)lp;View par=parent(v);int[] xy=new int[2];par.getLocationOnScreen(xy);
                int bottomMargin=Math.max(0,xy[1]+par.getHeight()-par.getPaddingBottom()-Math.round(initialBottom+(safeBottom-initialBottom)*f));
                int gravity=(s.gravity<0?Gravity.LEFT:s.gravity&~Gravity.VERTICAL_GRAVITY_MASK)|Gravity.BOTTOM;
                if(fp.gravity!=gravity){fp.gravity=gravity;change=true;}
                if(fp.bottomMargin!=bottomMargin){fp.bottomMargin=bottomMargin;change=true;}
            }
            if(change)v.setLayoutParams(lp);
        }
        // Non-FrameLayout hosts can pin the sheet to y=0. Keep header below status
        // bar with real padding; its list still occupies the remaining height.
        if(!(target.getLayoutParams() instanceof FrameLayout.LayoutParams)&&behavior==null){
            int[] pos=new int[2];target.getLocationOnScreen(pos);Slot s=slots.get(target);int overlap=Math.max(0,safeTop-pos[1]);
            int padding=s.padTop+Math.round(overlap*f);if(target.getPaddingTop()!=padding)target.setPadding(target.getPaddingLeft(),padding,target.getPaddingRight(),s.padBottom);
        }
        root.invalidate();
    }
    private void complete(float end){animator=null;if(end==0f){restore();return;}transitioning=false;CommentWallpaper.invalidatePanel(root);CommentTools.positionFull(root);root.post(()->{CommentTools.apply(root);CommentWallpaper.refreshRoot(root);});}
    void restore(){
        if(restored)return;restored=true;transitioning=false;generation++;target.setTranslationY(baseTranslation);if(animator!=null){animator.removeAllListeners();animator.cancel();animator=null;}
        target.removeOnAttachStateChangeListener(attach);
        for(Slot s:slots.values())s.restore();
        if(!nativeFit&&nativeOffset>=0)invoke("setExpandedOffset",nativeOffset);
        if(nativeState>=0)invoke("setState",nativeState);
        target.requestLayout();finished.run();
    }
}
