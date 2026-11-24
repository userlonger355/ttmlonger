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
                root.getViewTreeObserver().addOnGlobalLayoutListener(()->{if(root.isShown()){CommentTools.apply(root);if(enabled)scan(root);}});
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
        return n.equals("x.0h10")||n.contains("commentlist")||n.contains("commentpanel")||n.contains("commentsheet");
    }
    private static boolean candidate(View v,View root){
        return v!=root&&v instanceof ViewGroup&&!(v instanceof android.widget.AdapterView)&&
            (panelName(v.getClass().getName())||Boolean.TRUE.equals(v.getTag(0x5454434D)))&&
            v.getWidth()>=root.getWidth()*.65f&&v.getHeight()>=root.getHeight()*.20f&&v.getHeight()<root.getHeight()*.98f;
    }
    private static View find(View v,View root,int depth,int[] budget){
        if(v==null||!v.isShown()||depth>30||--budget[0]<0)return null;
        if(candidate(v,root))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),root,depth+1,budget);if(found!=null)return found;}}
        return null;
    }
    static View panel(View root){return find(root,root,0,new int[]{1800});}
    static void apply(Activity a){if(a!=null&&enabled&&image!=null&&!a.isFinishing()&&!a.isDestroyed())scan(a.getWindow().getDecorView());}
    private static void refresh(){
        Activity a=ScreenWatcher.top();if(a!=null)apply(a);
        for(View root:new ArrayList<>(dialogs))if(root!=null&&root.isShown())scan(root);
    }
    private static void scan(View root){
        if(CrashGuard.tripped||!enabled||image==null||!root.isShown()||Screen.isOwnDecor(root))return;
        View target=find(root,root,0,new int[]{1800});
        for(View old:new ArrayList<>(painted.keySet()))if(old!=null&&(!old.isAttachedToWindow()||old.getRootView()==root&&!within(old,target)))restore(old);
        if(target==null)return;
        State old=painted.get(target);
        if(old==null||target.getBackground()!=old.applied){
            Drawable photo=drawable(image,dim,VideoInfo.isNight(target.getContext()),16*target.getResources().getDisplayMetrics().density);
            applyBackground(target,photo);
            android.util.Log.i("TTMLonger","COMMENT_WALLPAPER_APPLIED "+target.getClass().getName());
        }
        State state=painted.get(target);
        if(state!=null&&state.before instanceof ColorDrawable)uncover(target,target,((ColorDrawable)state.before).getColor(),0);
    }
    private static boolean within(View child,View parent){
        if(parent==null)return false;
        for(View current=child;current!=null;){if(current==parent)return true;ViewParent p=current.getParent();current=p instanceof View?(View)p:null;}
        return false;
    }
    private static boolean listSurface(View v){
        for(Class<?> c=v.getClass();c!=null;c=c.getSuperclass()){
            String n=c.getName().toLowerCase(Locale.ROOT);
            if(n.endsWith(".recyclerview")||n.endsWith(".listview")||panelName(n))return true;
        }return false;
    }
    private static void uncover(View v,View panel,int color,int depth){
        if(depth>4||!(v instanceof ViewGroup))return;
        ViewGroup group=(ViewGroup)v;
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            if(child.getWidth()<panel.getWidth()*.8f||child.getHeight()<panel.getHeight()*.35f)continue;
            if(listSurface(child)&&child.getBackground() instanceof ColorDrawable&&((ColorDrawable)child.getBackground()).getColor()==color&&Color.alpha(color)==255){
                applyBackground(child,new ColorDrawable(Color.TRANSPARENT));
            }
            uncover(child,panel,color,depth+1);
        }
    }
    static void applyBackground(View target,Drawable photo){
        painted.put(target,new State(target.getBackground(),photo));setBackground(target,photo);
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
