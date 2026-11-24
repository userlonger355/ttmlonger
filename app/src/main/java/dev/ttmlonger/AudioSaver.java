package dev.ttmlonger;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Save the original sound when available, otherwise extract the AAC track from the selected video. */
final class AudioSaver {
    private static final Set<Object> running=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
    private static final AtomicInteger counter=new AtomicInteger();
    private AudioSaver() {}

    static void start(Context context, VideoTracker.Entry entry) {
        if(entry==null){Toasts.show(context,"Звук не найден");return;}
        Object key=entry.aweme!=null?entry.aweme:entry.video;
        if(key==null){Toasts.show(context,"Звук не найден");return;}
        synchronized(running) {
            if(!running.add(key)){Toasts.show(context,"Звук уже скачивается");return;}
        }
        Context app=context.getApplicationContext()==null?context:context.getApplicationContext();
        Toasts.show(app,"Скачиваю звук…");
        new Thread(() -> {
            File input=null,output=null;
            try {
                input=File.createTempFile("ttm_audio_",".part",app.getCacheDir());
                output=File.createTempFile("ttm_audio_",".m4a",app.getCacheDir());
                String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+"_"+counter.incrementAndGet();
                String base="ttm_sound_"+stamp;
                long deadline=System.currentTimeMillis()+240000L;
                LinkedHashSet<String> urls=new LinkedHashSet<>();
                Object music=DownloadHooks.call(entry.aweme,"getMusic");
                for(String getter:new String[]{"getPlayUrl","getPlayUrlModel","getAudioUrl","getMusicUrl"}) {
                    Object value=DownloadHooks.call(music,getter);
                    urls.addAll(VideoSaver.urlsOf(value));
                    if(value instanceof String) {
                        String u=AvatarHooks.secure((String)value);if(u!=null)urls.add(u);
                    }
                }
                Object video=entry.video!=null?entry.video:DownloadHooks.call(entry.aweme,"getVideo");
                urls.addAll(VideoSaver.videoCandidates(video));
                int tries=0;
                Exception last=null;
                for(String url:urls) {
                    if(++tries>10||System.currentTimeMillis()>deadline)break;
                    try {
                        VideoSaver.fetch(url,input,null,deadline);
                        Result result=audio(input,output,deadline);
                        String where=Media.saveFile(app,result.file,base+"."+result.ext,result.mime);
                        Toasts.show(app,"Звук сохранён: "+where);
                        return;
                    }catch(Exception e){last=e;}
                }
                if(last!=null)FlagStore.log(last);
                Toasts.show(app,"Не удалось скачать звук: нет доступной аудиодорожки");
            }catch(Throwable t) {
                FlagStore.log(t);Toasts.show(app,"Не удалось скачать звук");
            }finally{
                if(input!=null)input.delete();if(output!=null)output.delete();
                synchronized(running){running.remove(key);}
            }
        },"ttmlonger-audio").start();
    }

    private static final class Result {
        final File file;final String ext,mime;
        Result(File f,String e,String m){file=f;ext=e;mime=m;}
    }

    private static Result audio(File input, File output,long deadline) throws Exception {
        MediaExtractor ex=new MediaExtractor();
        MediaMuxer mux=null;
        boolean started=false;
        try{
            ex.setDataSource(input.getAbsolutePath());
            int track=-1;boolean hasVideo=false;MediaFormat format=null;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/"))hasVideo=true;
                if(mime!=null&&mime.startsWith("audio/")&&track<0){track=i;format=f;}
            }
            if(track<0||format==null)throw new IOException("No audio");
            String mime=format.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(track);
            if(ex.getSampleTime()<0)throw new IOException("Empty audio");
            // Raw MP3 only: require an MP3 header, not a video/container with an MP3 track.
            if(!hasVideo && "audio/mpeg".equals(mime) && isMp3(input))
                return new Result(input,"mp3","audio/mpeg");
            if(!"audio/mp4a-latm".equals(mime))throw new IOException("Unsupported audio codec");
            mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int out=mux.addTrack(format);mux.start();started=true;
            ByteBuffer buf=ByteBuffer.allocateDirect(1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            long first=-1,last=-1;int samples=0;
            while(true) {
                if(System.currentTimeMillis()>deadline)throw new IOException("Timeout");
                buf.clear();int n=ex.readSampleData(buf,0);if(n<0)break;
                long pts=ex.getSampleTime();if(first<0)first=pts;
                long normalized=Math.max(0,pts-first);
                if(normalized<last)throw new IOException("Invalid audio timestamps");
                if((ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("Encrypted audio");
                info.set(0,n,normalized,0);
                if(n>0){buf.position(0);buf.limit(n);mux.writeSampleData(out,buf,info);samples++;last=normalized;}
                if(!ex.advance())break;
            }
            if(samples==0)throw new IOException("Empty audio");
            mux.stop();started=false;
            return new Result(output,"m4a","audio/mp4");
        }finally{
            ex.release();
            if(mux!=null){if(started)try{mux.stop();}catch(Throwable ignored){}try{mux.release();}catch(Throwable ignored){}}
        }
    }

    private static boolean isMp3(File file) throws IOException {
        try(java.io.InputStream in=new java.io.FileInputStream(file)){
            int a=in.read(),b=in.read(),c=in.read();
            return (a=='I'&&b=='D'&&c=='3') || (a==255&&b>=0&&(b&0xe0)==0xe0&&(b&6)!=0);
        }
    }
}
