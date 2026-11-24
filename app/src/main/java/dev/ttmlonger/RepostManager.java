package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Own-account repost manager. Uses only exact native service signatures and explicit selection.
 * A missing or ambiguous response stops the operation; no blind retries. */
final class RepostManager {
    private static final int PAGE=50,MAX_PAGES=3,MAX_DELETE=20;
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static final class Item {
        final String aid,id,label; final int carrier; final long reposted;
        Item(String a,String i,String l,int c,long time){aid=a;id=i;label=l;carrier=c;reposted=time;}
    }
    private static final class Batch {
        final List<Item> items=new ArrayList<>();
        boolean partial;
    }
    static void show(Activity act){
        String owner=StreakHooks.liveOwner();
        if(owner==null){Toasts.show(act,"Откройте свой профиль и входящие TikTok, затем повторите.");return;}
        if(!BUSY.compareAndSet(false,true)){Toasts.show(act,"Уже выполняется операция");return;}
        AtomicBoolean stop=new AtomicBoolean();
        AlertDialog progress=progress(act,"Читаем ваши репосты",stop);
        new Thread(()->{
            Batch batch=null;
            try{batch=read(act,owner,stop);}catch(Throwable ignored){}
            final Batch found=batch;
            BUSY.set(false);
            act.runOnUiThread(()->{
                progress.dismiss();
                if(stop.get()||act.isFinishing()||act.isDestroyed())return;
                if(found==null){message(act,"Репосты","Не удалось прочитать список штатным сервисом этой версии TikTok. Ничего не удалено.");return;}
                if(found.items.isEmpty()){message(act,"Репосты",found.partial?"Нет подтверждённых репостов в полученной части списка.":"Репосты не найдены.");return;}
                select(act,owner,found);
            });
        },"ttm-repost-read").start();
    }
    private static boolean current(Activity a,String owner,AtomicBoolean stop){
        return !stop.get()&&!CrashGuard.tripped&&!a.isFinishing()&&!a.isDestroyed()&&owner.equals(StreakHooks.liveOwner());
    }
    private static Object field(Object o,String name){
        if(o==null)return null;
        try{java.lang.reflect.Field f=DownloadHooks.findField(o.getClass(),name);return f==null?null:f.get(o);}catch(Throwable t){return null;}
    }
    private static String id(Object o){
        String s=o instanceof String?(String)o:o instanceof Number?o.toString():"";
        return s.matches("[0-9]{1,24}")&&!s.matches("0+")?s:null;
    }
    private static Batch read(Activity a,String owner,AtomicBoolean stop)throws Exception{
        ClassLoader cl=a.getClassLoader();
        Class<?> service=Class.forName("com.ss.android.ugc.aweme.upvote.service.UpvoteServiceImpl",false,cl);
        Class<?> stream=Class.forName("X.19ZK",false,cl);
        Method fetch=service.getMethod("LJFF",String.class,long.class,int.class,Integer.class);
        Method wait=stream.getMethod("LIZLLL");
        Object client=service.getConstructor().newInstance();
        LinkedHashMap<String,Item> unique=new LinkedHashMap<>();
        Batch batch=new Batch();
        boolean more=true;
        for(int page=0;page<MAX_PAGES&&more;page++){
            if(!current(a,owner,stop))throw new IllegalStateException();
            Object request=fetch.invoke(client,owner,(long)page*PAGE,PAGE,null);
            if(!stream.isInstance(request))throw new IllegalStateException();
            Object answer=wait.invoke(request);
            Object list=field(answer,"repostList"),hasMore=field(answer,"hasMore");
            if(!(list instanceof List)||!(hasMore instanceof Boolean))throw new IllegalStateException();
            Object status=DownloadHooks.call(answer,"getStatusCode");
            if(status==null)status=field(answer,"status_code");
            if(status instanceof Number&&((Number)status).intValue()!=0)throw new IllegalStateException();
            int old=unique.size();
            for(Object post:(List<?>)list){
                Object info=DownloadHooks.call(post,"getUpvoteInfo");
                String aid=id(DownloadHooks.call(post,"getAid")),item=id(field(info,"itemId")),repost=id(field(info,"upvoteId"));
                Object carrier=field(info,"carrierType");
                if(aid==null||!aid.equals(item)||repost==null||!(carrier instanceof Number))continue;
                Object raw=DownloadHooks.call(post,"getDesc");
                String label=raw instanceof String?((String)raw).replace('\n',' ').trim():"";
                if(label.length()>64)label=label.substring(0,64)+"…";
                unique.putIfAbsent(aid+"/"+repost,new Item(aid,repost,label.isEmpty()?"Видео "+aid:label,((Number)carrier).intValue(),VideoInfo.repostTimeOf(post)));
                if(unique.size()>=PAGE*MAX_PAGES)break;
            }
            more=(Boolean)hasMore;
            if(unique.size()==old&&more)break;
        }
        batch.partial=more;
        batch.items.addAll(unique.values());
        return batch;
    }
    private static void select(Activity a,String owner,Batch b){
        String[] labels=new String[b.items.size()];
        for(int i=0;i<labels.length;i++){Item item=b.items.get(i);labels[i]=item.label+"\n"+(item.reposted>0?VideoInfo.fullTime(item.reposted,Zones.current()):"Время репоста не передано");}
        boolean[] picked=new boolean[labels.length];
        AlertDialog dialog=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Репосты · "+labels.length+(b.partial?"+":""))
                .setMultiChoiceItems(labels,picked,(d,which,checked)->picked[which]=checked)
                .setNegativeButton("Закрыть",null).setPositiveButton("Продолжить",null).create();
        Screen.markOwn(dialog);dialog.show();Ui.style(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            List<Item> selected=new ArrayList<>();
            for(int i=0;i<picked.length;i++)if(picked[i])selected.add(b.items.get(i));
            if(selected.isEmpty()||selected.size()>MAX_DELETE){Toasts.show(a,"Выберите от 1 до "+MAX_DELETE+" репостов.");return;}
            dialog.dismiss();
            AlertDialog confirm=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Отменить "+selected.size()+" репостов?")
                    .setMessage("Будут изменены только выбранные репосты вашего текущего аккаунта. Операцию можно остановить между запросами.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Отменить репосты",(d,w)->remove(a,owner,selected)).create();
            Screen.markOwn(confirm);confirm.show();Ui.style(confirm);
        });
    }
    private static void remove(Activity a,String owner,List<Item> items){
        if(items.isEmpty()||items.size()>MAX_DELETE||!BUSY.compareAndSet(false,true))return;
        AtomicBoolean stop=new AtomicBoolean();
        AlertDialog progress=progress(a,"Отменяем выбранные репосты",stop);
        new Thread(()->{
            int done=0;boolean unknown=false;
            try{
                ClassLoader cl=a.getClassLoader();
                Class<?> op=Class.forName("X.0pPL",false,cl),single=Class.forName("X.19X3",false,cl);
                Method remove=op.getMethod("LLLLL",Integer.class,String.class,String.class),wait=single.getMethod("LJ");
                if(!single.isAssignableFrom(remove.getReturnType()))throw new IllegalStateException();
                Object service=op.getConstructor().newInstance();
                for(Item item:items){
                    if(!current(a,owner,stop))break;
                    // Mark ambiguous until a positive native response has been checked.
                    unknown=true;
                    Object call=remove.invoke(service,item.carrier,item.aid,item.id);
                    if(!single.isInstance(call))break;
                    Object response=wait.invoke(call);
                    Object status=DownloadHooks.call(response,"getStatusCode");
                    if(status==null)status=field(response,"status_code");
                    if(status==null)status=field(response,"statusCode");
                    if(!(status instanceof Number)||((Number)status).intValue()!=0)break;
                    unknown=false;done++;
                    if(done<items.size())Thread.sleep(1500L);
                }
            }catch(Throwable ignored){}
            final int removed=done;final boolean uncertain=unknown;
            BUSY.set(false);
            a.runOnUiThread(()->{
                progress.dismiss();
                if(!a.isFinishing()&&!a.isDestroyed())message(a,"Репосты","Подтверждено сервером: "+removed+" из "+items.size()
                    +(uncertain?"\nОтвет одного запроса не подтверждён. Проверьте профиль; повтор автоматически не выполняется.":"")
                    +"\nОбновите вкладку репостов.");
            });
        },"ttm-repost-remove").start();
    }
    private static AlertDialog progress(Activity a,String title,AtomicBoolean stop){
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle(title).setMessage("Операция текущего аккаунта")
                .setNegativeButton("Остановить",(dlg,w)->stop.set(true)).create();
        d.setOnCancelListener(x->stop.set(true));Screen.markOwn(d);d.show();Ui.style(d);return d;
    }
    private static void message(Activity a,String title,String text){
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle(title).setMessage(text).setPositiveButton("Закрыть",null).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
