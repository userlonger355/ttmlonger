package dev.ttmlonger;
import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** Only the single selected preview animates. Native stickers remain untouched. */
final class StickerMotionView extends FrameLayout{
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ttm-sticker-motion");t.setDaemon(true);return t;});
    private final ImageView image;private VideoView video;private android.media.MediaPlayer player;private File file;
    private StickerLibrary.Entry entry;private int token;private boolean attempted;private Drawable motion;
    StickerMotionView(Activity a){super(a);image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);addView(image,new LayoutParams(-1,-1));setTag(Ui.OWN_TAG);}
    void bind(StickerLibrary.Entry e){
        if(entry!=e){clear();entry=e;}
        if(motion==null&&video==null){if(e.thumb!=null){if(!(image.getDrawable() instanceof BitmapDrawable)||((BitmapDrawable)image.getDrawable()).getBitmap()!=e.thumb)image.setImageBitmap(e.thumb);}else{if(image.getDrawable()==null)image.setImageDrawable(Icons.of(Icons.STICKER,Ui.MUTED));StickerLibrary.thumb(e,()->{if(entry==e&&motion==null&&video==null&&e.thumb!=null)image.setImageBitmap(e.thumb);});}}
        if(!attempted&&isAttachedToWindow()&&isShown()&&hasWindowFocus())load(e);
    }
    private void load(StickerLibrary.Entry e){
        attempted=true;final int gen=++token;
        IO.execute(()->{
            File tmp=null;Drawable animated=null;
            try{
                String url=StickerLibrary.motionUrl(e.item);if(url==null)url=AvatarHooks.secure(e.url);if(url==null)return;
                byte[] bytes=AvatarSaver.download(url);boolean mp4=bytes.length>12&&bytes[4]=='f'&&bytes[5]=='t'&&bytes[6]=='y'&&bytes[7]=='p';
                if(mp4){tmp=File.createTempFile("ttm-motion-",".mp4",getContext().getCacheDir());try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);}}
                else if(android.os.Build.VERSION.SDK_INT>=28){
                    Drawable d=ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),(decoder,info,src)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,256f/Math.max(w,h));decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));});
                    if(d instanceof AnimatedImageDrawable)animated=d;
                }
            }catch(Throwable ignored){}
            final File ready=tmp;final Drawable d=animated;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{if(gen!=token||entry!=e||!isAttachedToWindow()||!isShown()){if(ready!=null)ready.delete();attempted=gen==token?false:attempted;return;}if(ready!=null)play(ready);else if(d!=null){motion=d;image.setImageDrawable(d);sync();}});
        });
    }
    private void play(File path){
        file=path;VideoView v=new VideoView(getContext());video=v;v.setZOrderMediaOverlay(true);addView(v,new LayoutParams(-1,-1,Gravity.CENTER));
        v.setOnPreparedListener(mp->{if(video!=v)return;player=mp;mp.setVolume(0,0);mp.setLooping(true);image.setVisibility(INVISIBLE);sync();});
        v.setOnErrorListener((mp,what,extra)->{if(video==v)releaseVideo();return true;});
        try{v.setVideoPath(path.getAbsolutePath());}catch(Throwable t){releaseVideo();}
    }
    private void sync(){boolean run=isAttachedToWindow()&&isShown()&&hasWindowFocus();if(motion instanceof Animatable){if(run)((Animatable)motion).start();else ((Animatable)motion).stop();}if(video!=null&&player!=null){try{if(run)video.start();else video.pause();}catch(Throwable ignored){}}}
    private void releaseVideo(){if(video!=null){try{video.stopPlayback();}catch(Throwable ignored){}removeView(video);video=null;}player=null;if(file!=null){file.delete();file=null;}image.setVisibility(VISIBLE);}
    void clear(){token++;attempted=false;entry=null;if(motion instanceof Animatable)((Animatable)motion).stop();motion=null;releaseVideo();image.setImageDrawable(null);}
    @Override protected void onDetachedFromWindow(){clear();super.onDetachedFromWindow();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);sync();}
    @Override protected void onVisibilityChanged(View v,int visibility){super.onVisibilityChanged(v,visibility);if(image!=null)sync();}
}
