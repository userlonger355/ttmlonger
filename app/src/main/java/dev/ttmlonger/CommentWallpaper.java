package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;

/** Local wallpaper behind recognized native comment panels. No account or network access. */
final class CommentWallpaper {
    private static final String FILE="ttm-comment-wallpaper.jpg";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ttm-comment-wallpaper");t.setDaemon(true);return t;});
    private static Context app;
    private static volatile Bitmap image;
    private static volatile boolean enabled;
    private static volatile int generation;
    private static int blur=20,dim=45;
    private static boolean hasPhoto;
    private static final Map<View,State> painted=new WeakHashMap<>();
    private static final Map<View,Long> watched=new WeakHashMap<>();
    private static final Set<View> dialogs=Collections.newSetFromMap(new WeakHashMap<View,Boolean>());
    private static final class State {
        final Drawable before,applied;
        State(Drawable before,Drawable applied){this.before=before;this.applied=applied;}
    }
    interface Done { void result(boolean ok); }
    private static SharedPreferences prefs(){return app.getSharedPreferences("ttm_comment_wallpaper",0);}
    static int clamp(int v){return Math.max(0,Math.min(100,v));}
    static boolean enabled(){return enabled;}
    static int blur(){return blur;}
    static int dim(){return dim;}
    static boolean exists(){return app!=null&&hasPhoto;}
    private static File file(){return new File(app.getFilesDir(),FILE);}
    static Bitmap image(){return image;}
    static void install(Context context){
        app=context.getApplicationContext();if(app==null)app=context;
        enabled=prefs().getBoolean("enabled",false);
        blur=clamp(prefs().getInt("blur",20));dim=clamp(prefs().getInt("dim",45));
        hasPhoto=file().isFile();
        if(exists())load();
        // Some versions display the comment sheet inside a Dialog instead of the Activity.
        try{XposedHelpers.findAndHookMethod(Dialog.class,"show",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                Dialog d=(Dialog)p.thisObject;
                if(Screen.isOwn(d)||d.getWindow()==null)return;
                View root=d.getWindow().getDecorView();
                if(root==null||!dialogs.add(root))return;
                watch(root);
                root.getViewTreeObserver().addOnGlobalLayoutListener(()->{if(root.isShown()){invalidatePanel(root);CommentTools.apply(root);if(enabled)scan(root);}});
                root.post(()->{CommentTools.apply(root);if(enabled)scan(root);});
            }
        });}catch(Throwable e){FlagStore.log(e);}
    }
    static void configure(boolean on,int b,int d){
        if(app==null)return;boolean changed=blur!=clamp(b);enabled=on&&exists();blur=clamp(b);dim=clamp(d);
        prefs().edit().putBoolean("enabled",enabled).putInt("blur",blur).putInt("dim",dim).apply();
        restoreAll();
        if(exists()&&(changed||image==null))load();
        if(enabled)refresh();
    }
    static void reset(){
        if(app==null)return;generation++;enabled=false;hasPhoto=false;image=null;restoreAll();
        prefs().edit().clear().apply();blur=20;dim=45;
        WORK.execute(()->file().delete());
    }
    static void importImage(Uri uri,Done cb){
        if(app==null||uri==null){cb.result(false);return;}
        WORK.execute(()->{
            boolean ok=false;File temp=null;
            try{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream in=app.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
                if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>32000||bounds.outHeight>32000)throw new IOException("image dimensions");
                BitmapFactory.Options opts=new BitmapFactory.Options();
                opts.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize>1600)opts.inSampleSize*=2;
                opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
                Bitmap src;try(InputStream in=app.getContentResolver().openInputStream(uri)){src=BitmapFactory.decodeStream(in,null,opts);}
                if(src==null)throw new IOException("decode");
                temp=File.createTempFile("comment-wallpaper-", ".jpg",app.getFilesDir());
                try(FileOutputStream out=new FileOutputStream(temp)){if(!src.compress(Bitmap.CompressFormat.JPEG,92,out))throw new IOException("compress");out.getFD().sync();}
                src.recycle();
                if(!temp.renameTo(file()))throw new IOException("replace");
                ok=true;
            }catch(Throwable e){FlagStore.log(e);}finally{if(temp!=null)temp.delete();}
            final boolean success=ok;
            MAIN.post(()->{if(success){hasPhoto=true;enabled=true;prefs().edit().putBoolean("enabled",true).apply();load();}cb.result(success);});
        });
    }
    private static void load(){
        if(app==null)return;final int token=++generation,b=blur;
        WORK.execute(()->{
            if(token!=generation)return;
            Bitmap out=null;
            try{Bitmap src=BitmapFactory.decodeFile(file().getAbsolutePath());if(src!=null)out=blurred(src,b);}
            catch(Throwable e){FlagStore.log(e);}
            final Bitmap ready=out;
            MAIN.post(()->{if(token!=generation){if(ready!=null)ready.recycle();return;}image=ready;restoreAll();refresh();});
        });
    }
    /** Three separable box passes on a bounded copy. The original photo is retained on disk. */
    static Bitmap blurred(Bitmap src,int amount){
        int level=clamp(amount);if(level==0)return src;
        float scale=Math.min(1f,640f/Math.max(src.getWidth(),src.getHeight()));
        int w=Math.max(1,Math.round(src.getWidth()*scale)),h=Math.max(1,Math.round(src.getHeight()*scale));
        Bitmap small=Bitmap.createScaledBitmap(src,w,h,true);
        int[] pixels=new int[w*h],tmp=new int[w*h];small.getPixels(pixels,0,w,0,0,w,h);
        int r=Math.max(1,Math.round(level*0.22f));
        for(int n=0;n<3;n++){box(pixels,tmp,w,h,r,true);box(tmp,pixels,w,h,r,false);}
        Bitmap result=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        if(small!=src)small.recycle();src.recycle();return result;
    }
    private static void box(int[] src,int[] dst,int w,int h,int r,boolean horizontal){
        int lines=horizontal?h:w,len=horizontal?w:h,stride=horizontal?1:w,span=2*r+1;
        for(int line=0;line<lines;line++){
            int base=horizontal?line*w:line,rr=0,gg=0,bb=0,aa=0;
            for(int k=-r;k<=r;k++){int c=src[base+Math.max(0,Math.min(len-1,k))*stride];aa+=c>>>24;rr+=(c>>16)&255;gg+=(c>>8)&255;bb+=c&255;}
            for(int x=0;x<len;x++){
                dst[base+x*stride]=(aa/span<<24)|(rr/span<<16)|(gg/span<<8)|bb/span;
                int left=src[base+Math.max(0,x-r)*stride],right=src[base+Math.min(len-1,x+r+1)*stride];
                aa+=(right>>>24)-(left>>>24);rr+=((right>>16)&255)-((left>>16)&255);gg+=((right>>8)&255)-((left>>8)&255);bb+=(right&255)-(left&255);
            }
        }
    }
    static Drawable drawable(Bitmap bmp,int shade,boolean night,float radius){
        return new Photo(bmp,clamp(shade),night,radius);
    }
    private static final class Photo extends Drawable {
        final Bitmap bmp;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final int shade;final float radius;
        Photo(Bitmap b,int dim,boolean night,float r){bmp=b;shade=Color.argb(Math.round(dim*2.55f),night?0:255,night?0:255,night?0:255);radius=r;}
        @Override public void draw(Canvas c){
            Rect bounds=getBounds();if(bounds.isEmpty())return;
            int save=c.save();Path clip=new Path();clip.addRoundRect(new RectF(bounds),radius,radius,Path.Direction.CW);c.clipPath(clip);
            if(bmp!=null&&!bmp.isRecycled()){
                float scale=Math.max(bounds.width()/(float)bmp.getWidth(),bounds.height()/(float)bmp.getHeight());
                float w=bmp.getWidth()*scale,h=bmp.getHeight()*scale;
                c.drawBitmap(bmp,null,new RectF(bounds.exactCenterX()-w/2,bounds.exactCenterY()-h/2,bounds.exactCenterX()+w/2,bounds.exactCenterY()+h/2),paint);
            }
            c.drawColor(shade);c.restoreToCount(save);
        }
        @Override public void setAlpha(int a){paint.setAlpha(a);}
        @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    /** Original reference identifies X.0h10 and these named panel classes. Never match a plain text hint. */
    static boolean panelName(String type){
        String n=type.toLowerCase(Locale.ROOT);
        return n.equals("x.0h10")||n.startsWith("x.0h10 ")||n.contains("commentlist")||n.contains("commentpanel")||n.contains("commentsheet")||n.contains("commentbottomsheet")||n.contains("commentdialog")||n.contains("comment_list")||n.contains("comment_panel")||n.contains("comment_fragment")||n.contains("commentfragment")||n.contains("comment_container")||n.contains("commentcontainer");
    }
    private static boolean candidate(View v,View root){
        return v!=root&&v instanceof ViewGroup&&!(v instanceof android.widget.AdapterView)&&
            (panelName(viewName(v))||Boolean.TRUE.equals(v.getTag(0x5454434D)))&&
            v.getWidth()>=root.getWidth()*.65f&&v.getHeight()>=root.getHeight()*.20f&&v.getHeight()<=root.getHeight();
    }
    private static View find(View v,View root,int depth,int[] budget){
        if(v==null||!v.isShown()||depth>30||--budget[0]<0)return null;
        if(candidate(v,root))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),root,depth+1,budget);if(found!=null)return found;}}
        return null;
    }
    private static String viewName(View v){
        String n=v.getClass().getName();try{if(v.getId()!=View.NO_ID)n+=" "+v.getResources().getResourceEntryName(v.getId());}catch(Throwable ignored){}return n;
    }
    private static boolean header(View v){
        if(!(v instanceof android.widget.TextView)||v instanceof android.widget.EditText)return false;
        String t=((android.widget.TextView)v).getText().toString().toLowerCase(Locale.ROOT).trim();
        return t.matches("(?:[0-9.,\\s\\u00a0\\u202f]+[kmкм]?\\s*)?(?:comments?|комментарии|комментариев|комментария|комментарий)(?:\\s*\\(?[0-9.,\\s\\u00a0\\u202f]+[kmкм]?\\)?)?");
    }
    private static View title(View v,int depth,int[] budget){
        if(v==null||!v.isShown()||depth>30||--budget[0]<0)return null;
        if(header(v))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View t=title(g.getChildAt(i),depth+1,budget);if(t!=null)return t;}}return null;
    }
    private static boolean hasList(View v,int depth,int[] budget){
        if(v==null||!v.isShown()||depth>12||--budget[0]<0)return false;
        if(listSurface(v)&&v.getHeight()>0)return true;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(hasList(g.getChildAt(i),depth+1,budget))return true;}return false;
    }
    private static View composer(View v,int depth,int[] budget){
        if(v==null||!v.isShown()||depth>30||--budget[0]<0)return null;
        String name=viewName(v).toLowerCase(Locale.ROOT);
        if(v instanceof android.widget.TextView){
            android.widget.TextView t=(android.widget.TextView)v;
            String text=(t.getHint()==null?"":t.getHint().toString())+" "+t.getText();
            text=text.trim().toLowerCase(Locale.ROOT);
            if(text.startsWith("добавить комментарий")||text.startsWith("напишите комментарий")||text.startsWith("написать комментарий")||text.startsWith("add a comment")||text.startsWith("write a comment"))return v;
        }
        if(name.contains("comment_input")||name.contains("commentinput")||name.contains("comment_edit"))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=composer(g.getChildAt(i),depth+1,budget);if(found!=null)return found;}}return null;
    }
    private static boolean withinPanel(View group,View root){return group.getWidth()>=root.getWidth()*.65f&&group.getHeight()>=root.getHeight()*.20f&&group.getHeight()<=root.getHeight();}
    private static final WeakHashMap<View,PanelCache> panels=new WeakHashMap<>();
    private static final class PanelCache {long at;boolean dirty=true;java.lang.ref.WeakReference<View> hit=new java.lang.ref.WeakReference<>(null);}
    static View panel(View root){
        if(root==null||root.getWidth()==0||root.getHeight()==0||Screen.isOwnDecor(root))return null;
        PanelCache c=panels.get(root);if(c==null){c=new PanelCache();panels.put(root,c);}
        long now=SystemClock.uptimeMillis();View old=c.hit.get();
        if(!c.dirty&&now-c.at<180&&(old==null||old.isShown()&&old.isAttachedToWindow()))return old;
        View next=findPanel(root);c.at=now;c.dirty=false;c.hit=new java.lang.ref.WeakReference<>(next);return next;
    }
    static void invalidatePanel(View root){PanelCache c=panels.get(root);if(c!=null)c.dirty=true;}
    private static View findPanel(View root){
        if(root==null||root.getWidth()==0||root.getHeight()==0||Screen.isOwnDecor(root))return null;
        View named=find(root,root,0,new int[]{2400});
        View label=title(root,0,new int[]{2400}),input=composer(root,0,new int[]{2400});
        if(named!=null&&hasList(named,0,new int[]{700})){
            // Prefer the wrapper enclosing the comment list and its composer/header,
            // rather than a recycler nested below an opaque parent.
            for(ViewParent q=named.getParent();q instanceof ViewGroup;q=q.getParent()){
                ViewGroup g=(ViewGroup)q;if(g==root||!withinPanel(g,root))break;
                if(!panelName(viewName(g))&&!(input!=null&&within(input,g)&&!within(input,named))&&!(label!=null&&within(label,g)&&!within(label,named)))break;
                if(g.getHeight()>root.getHeight()*.98f&&!dialogs.contains(root))break;
                named=g;
            }return named;
        }
        View anchor=label!=null?label:input;if(anchor==null)return null;
        for(ViewParent q=anchor.getParent();q instanceof ViewGroup;q=q.getParent()){
            ViewGroup group=(ViewGroup)q;if(group==root)break;
            if(!withinPanel(group,root)||!hasList(group,0,new int[]{700}))continue;
            if(label==null&&input!=null){if(!within(input,group))continue;int[] a=new int[2],b=new int[2];group.getLocationOnScreen(a);input.getLocationOnScreen(b);if(b[1]-a[1]<group.getHeight()*.45f)continue;}
            if(label!=null){int[] a=new int[2],b=new int[2];group.getLocationOnScreen(a);label.getLocationOnScreen(b);if(b[1]-a[1]>group.getHeight()*.30f)continue;}
            if(group.getHeight()>root.getHeight()*.90f&&!dialogs.contains(root)&&!(label!=null&&input!=null&&within(input,group)))continue;
            return group;
        }
        return null;
    }
    private static void watch(View root){
        if(watched.containsKey(root))return;watched.put(root,0L);
        root.getViewTreeObserver().addOnPreDrawListener(()->{
            if(!enabled||image==null||!root.isShown())return true;
            long now=SystemClock.uptimeMillis(),prev=watched.getOrDefault(root,0L);
            if(now-prev>=120){watched.put(root,now);scan(root);}return true;
        });
    }
    static void apply(Activity a){if(a!=null&&enabled&&image!=null&&!a.isFinishing()&&!a.isDestroyed()){View root=a.getWindow().getDecorView();watch(root);scan(root);}}
    private static void refresh(){
        Activity a=ScreenWatcher.top();if(a!=null)apply(a);
        for(View root:new ArrayList<>(dialogs))if(root!=null&&root.isShown())scan(root);
    }
    private static void scan(View root){
        if(CrashGuard.tripped||!enabled||image==null||!root.isShown()||Screen.isOwnDecor(root))return;
        View target=panel(root);
        for(View old:new ArrayList<>(painted.keySet()))if(old!=null&&(!old.isAttachedToWindow()||old.getRootView()==root&&!within(old,target)))restore(old);
        if(target==null)return;
        State old=painted.get(target);
        if(old==null||target.getBackground()!=old.applied){
            Drawable photo=drawable(image,dim,VideoInfo.isNight(target.getContext()),16*target.getResources().getDisplayMetrics().density);
            applyBackground(target,photo);
            android.util.Log.i("TTMLonger","COMMENT_WALLPAPER_APPLIED "+target.getClass().getName());
        }
        State state=painted.get(target);
        if(state!=null)uncover(target,target,solid(state.before),0,new int[]{700});
    }
    private static boolean within(View child,View parent){
        if(parent==null)return false;
        for(View current=child;current!=null;){if(current==parent)return true;ViewParent p=current.getParent();current=p instanceof View?(View)p:null;}
        return false;
    }
    private static boolean listSurface(View v){
        for(Class<?> c=v.getClass();c!=null;c=c.getSuperclass()){
            String n=c.getName().toLowerCase(Locale.ROOT);
            if(n.endsWith(".recyclerview")||n.endsWith(".listview")||n.endsWith(".lazylist")||panelName(n))return true;
        }return false;
    }
    private static Integer solid(Drawable d){
        if(d instanceof ColorDrawable)return ((ColorDrawable)d).getColor();
        if(d instanceof GradientDrawable&&Build.VERSION.SDK_INT>=24){android.content.res.ColorStateList c=((GradientDrawable)d).getColor();if(c!=null)return c.getDefaultColor();}
        if(d instanceof android.graphics.drawable.InsetDrawable)return solid(((android.graphics.drawable.InsetDrawable)d).getDrawable());
        if(d instanceof android.graphics.drawable.LayerDrawable){
            android.graphics.drawable.LayerDrawable l=(android.graphics.drawable.LayerDrawable)d;
            for(int i=l.getNumberOfLayers()-1;i>=0;i--){if(l.getId(i)==android.R.id.mask)continue;Integer c=solid(l.getDrawable(i));if(c!=null&&Color.alpha(c)>=220)return c;}
        }
        if(d instanceof android.graphics.drawable.StateListDrawable)return solid(d.getCurrent()==d?null:d.getCurrent());
        return null;
    }
    private static boolean neutral(Integer c){if(c==null||Color.alpha(c)<220)return false;return Math.max(Color.red(c),Math.max(Color.green(c),Color.blue(c)))-Math.min(Color.red(c),Math.min(Color.green(c),Color.blue(c)))<28;}
    private static boolean surface(View v,View panel,int depth){
        if(!(v instanceof ViewGroup)||v.getWidth()<panel.getWidth()*.78f)return false;
        String n=viewName(v).toLowerCase(Locale.ROOT);
        if(n.contains("input")||n.contains("edit")||n.contains("emoji")||n.contains("sticker")||n.contains("button")||n.contains("popup")||n.contains("dialog")&&!panelName(n))return false;
        boolean list=isItemList(v);
        return list||v.getHeight()>=panel.getHeight()*.18f||depth<=5&&v.getHeight()>=panel.getResources().getDisplayMetrics().density*34;
    }
    private static void uncover(View v,View panel,Integer color,int depth,int[] budget){
        if(depth>12||--budget[0]<0||!(v instanceof ViewGroup))return;
        ViewGroup group=(ViewGroup)v;
        for(int i=0;i<group.getChildCount()&&budget[0]>0;i++){
            View child=group.getChildAt(i);if(!child.isShown()||child.getWidth()<panel.getWidth()*.78f)continue;
            State previous=painted.get(child);
            Drawable bg=child.getBackground();
            if(previous!=null&&bg==previous.applied){uncover(child,panel,color,depth+1,budget);continue;}
            Integer fill=solid(bg);boolean structural=surface(child,panel,depth);
            // Recycled comment rows and wrapped Ripple/Inset backgrounds can also
            // hide the photo. Only clear neutral opaque surfaces, not input, media or buttons.
            if(structural&&neutral(fill)){
                Drawable clear=new ColorDrawable(Color.TRANSPARENT);
                if(bg instanceof android.graphics.drawable.RippleDrawable)clear=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(VideoInfo.isNight(child.getContext())?0x22ffffff:0x16000000),clear,null);
                applyBackground(child,clear);
            }else if(structural&&bg!=null&&fill==null&&isItemList(child)&&bg.getOpacity()==PixelFormat.OPAQUE){
                applyBackground(child,new ColorDrawable(Color.TRANSPARENT));
            }
            if(child instanceof ViewGroup)uncover(child,panel,color,depth+1,budget);
        }
    }
    private static boolean isItemList(View v){for(Class<?> c=v.getClass();c!=null;c=c.getSuperclass()){String n=c.getName().toLowerCase(Locale.ROOT);if(n.endsWith("recyclerview")||n.endsWith("listview"))return true;}return false;}
    static void applyBackground(View target,Drawable photo){
        State old=painted.get(target);Drawable current=target.getBackground();if(old!=null&&current==old.applied)current=old.before;painted.put(target,new State(current,photo));setBackground(target,photo);
    }
    private static void setBackground(View v,Drawable d){
        int l=v.getPaddingLeft(),t=v.getPaddingTop(),r=v.getPaddingRight(),b=v.getPaddingBottom();
        v.setBackground(d);v.setPadding(l,t,r,b);
    }
    static void restore(View v){
        State s=painted.remove(v);if(s!=null&&v.getBackground()==s.applied)setBackground(v,s.before);
    }
    private static void restoreAll(){for(View v:new ArrayList<>(painted.keySet()))if(v!=null)restore(v);}
}
