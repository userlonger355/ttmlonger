package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Own-account repost manager. Uses only exact native service signatures and explicit selection.
 * A missing or ambiguous response stops the operation; no blind retries. */
final class RepostManager {
    private static final int PAGE=50,MAX_PAGES=40,MAX_PER_LOAD=3;
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static final class Item {
        final String aid,id,label; final int carrier; final long reposted;
        Item(String a,String i,String l,int c,long time){aid=a;id=i;label=l;carrier=c;reposted=time;}
        String key(){return aid+"/"+id;}
    }
    private static final class Batch {
        final LinkedHashMap<String,Item> items=new LinkedHashMap<>();
        int page;boolean more=true,uncertain;String problem="";
    }
    static void show(Activity a){
        String owner=StreakHooks.liveOwner();
        if(owner==null){Toasts.show(a,"Откройте свой профиль и входящие TikTok, затем повторите.");return;}
        Manager screen=new Manager(a,owner);screen.open("Очистка отметок",null);screen.load(false);
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
    private static void read(Activity a,String owner,AtomicBoolean stop,Batch b,boolean all)throws Exception{
        ClassLoader cl=a.getClassLoader();
        Class<?> service=Class.forName("com.ss.android.ugc.aweme.upvote.service.UpvoteServiceImpl",false,cl);
        Class<?> stream=Class.forName("X.19ZK",false,cl);
        Method fetch=service.getMethod("LJFF",String.class,long.class,int.class,Integer.class);
        Method wait=stream.getMethod("LIZLLL");
        Object client=service.getConstructor().newInstance();
        int count=0;
        while(b.more&&b.page<MAX_PAGES&&(all||count<MAX_PER_LOAD)){
            if(!current(a,owner,stop))return;
            Object request=fetch.invoke(client,owner,(long)b.page*PAGE,PAGE,null);
            if(!stream.isInstance(request))throw new IllegalStateException();
            Object answer=wait.invoke(request);
            Object list=field(answer,"repostList"),hasMore=field(answer,"hasMore");
            if(!(list instanceof List)||!(hasMore instanceof Boolean))throw new IllegalStateException();
            Object status=DownloadHooks.call(answer,"getStatusCode");
            if(status==null)status=field(answer,"status_code");
            if(status instanceof Number&&((Number)status).intValue()!=0)throw new IllegalStateException();
            int old=b.items.size();int invalid=0;
            for(Object post:(List<?>)list){
                Object info=DownloadHooks.call(post,"getUpvoteInfo");
                String aid=id(DownloadHooks.call(post,"getAid")),item=id(field(info,"itemId")),repost=id(field(info,"upvoteId"));
                Object carrier=field(info,"carrierType");
                if(aid==null||!aid.equals(item)||repost==null||!(carrier instanceof Number)){invalid++;continue;}
                Object raw=DownloadHooks.call(post,"getDesc");
                String label=raw instanceof String?((String)raw).replace('\n',' ').trim():"";
                if(label.length()>110)label=label.substring(0,110)+"…";
                Item entry=new Item(aid,repost,label.isEmpty()?"Видео "+aid:label,((Number)carrier).intValue(),VideoInfo.repostTimeOf(post));
                b.items.putIfAbsent(entry.key(),entry);
            }
            if(invalid>0)b.uncertain=true;
            b.page++;count++;b.more=(Boolean)hasMore;
            if(b.items.size()==old&&b.more){b.problem="Сервер не вернул новых записей";b.uncertain=true;break;}
            if(b.more&&all)Thread.sleep(350);
        }
        if(b.more&&b.page>=MAX_PAGES)b.problem="Достигнут лимит 2000 записей за загрузку";
    }
    private static final class Manager extends Screen {
        final String owner;final Batch batch=new Batch();final java.util.Set<String> chosen=new java.util.HashSet<>();
        LinearLayout rows,tools;TextView status,selected,load,clear,remove,choose,settings;
        TextView[] tabs=new TextView[3];int tab,delay;boolean working;AtomicBoolean cancel;
        Manager(Activity a,String o){super(a);owner=o;delay=Math.max(1500,Math.min(5000,a.getSharedPreferences("ttmlonger_interface",0).getInt("cleanup_delay",1500)));}
        @Override void build(LinearLayout body){
            LinearLayout bar=new LinearLayout(act);bar.setPadding(ui.dp(14),ui.dp(8),ui.dp(14),ui.dp(8));
            String[] labels={"Репосты","Избранное","Лайки"};
            for(int i=0;i<3;i++){final int n=i;TextView t=ui.medium(labels[i],14,Ui.INK);t.setGravity(android.view.Gravity.CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(46),1);lp.setMargins(ui.dp(3),0,ui.dp(3),0);bar.addView(t,lp);tabs[i]=t;t.setOnClickListener(v->{if(!working){tab=n;draw();}});}body.addView(bar);
            status=ui.note("");body.addView(status);
            tools=new LinearLayout(act);tools.setOrientation(1);tools.setPadding(ui.dp(16),0,ui.dp(16),0);body.addView(tools);
            settings=action("Настройки очистки",false,()->settings());tools.addView(settings,ui.matchWrap());
            choose=action("Выбрать все загруженные",false,()->{if(working)return;if(chosen.size()==batch.items.size())chosen.clear();else chosen.addAll(batch.items.keySet());draw();});tools.addView(choose,ui.matchWrap());
            selected=ui.note("");tools.addView(selected);
            remove=action("Очистить выбранные",true,()->confirm(false));tools.addView(remove,ui.matchWrap());
            clear=action("Очистить все репосты",false,()->{if(!working){if(batch.more){load(true);}else confirm(true);}});tools.addView(clear,ui.matchWrap());
            load=action("Загрузить ещё",false,()->load(false));tools.addView(load,ui.matchWrap());
            rows=new LinearLayout(act);rows.setOrientation(1);rows.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),0);body.addView(rows);draw();
        }
        TextView action(String label,boolean primary,Runnable task){TextView b=primary?TtStyle.accent(act,label,48,15):TtStyle.neutral(act,label,44,14);b.setOnClickListener(v->task.run());return b;}
        void draw(){
            if(rows==null)return;
            for(int i=0;i<3;i++){tabs[i].setBackground(ui.round(i==tab?Theme.accent():Ui.FIELD,14));tabs[i].setTextColor(i==tab?Theme.onColor(Theme.accent()):Ui.INK);}
            rows.removeAllViews();tools.setVisibility(tab==0?View.VISIBLE:View.GONE);
            if(tab!=0){status.setText("Очистка "+(tab==1?"избранного":"лайков")+" пока недоступна: штатный сервис этой версии TikTok не подтверждён.");return;}
            status.setText((working?"Выполняется операция · ":"")+"Загружено: "+batch.items.size()+(batch.more?"+":"")+(batch.uncertain?" · список частичный":"")+(batch.problem.isEmpty()?"":"\n"+batch.problem));
            selected.setText("Выбрано: "+chosen.size()+" · задержка "+delay+" мс");
            choose.setEnabled(!working&&!batch.items.isEmpty());remove.setEnabled(!working&&!chosen.isEmpty());clear.setEnabled(!working&&(batch.more||!batch.uncertain&&!batch.items.isEmpty()));load.setEnabled(!working&&batch.more&&batch.page<MAX_PAGES);settings.setEnabled(!working);
            clear.setText(batch.more?"Загрузить все для очистки":"Очистить все репосты");
            choose.setText(!batch.items.isEmpty()&&chosen.size()==batch.items.size()?"Снять выбор":"Выбрать все загруженные");
            if(batch.items.isEmpty()){rows.addView(ui.note(working?"Читаем список…":"Нет загруженных репостов"));return;}
            // Keep the full account snapshot in memory, but cap the visible UI to avoid a huge view tree.
            int shown=0;
            for(Item item:batch.items.values()){
                if(shown++>=300){rows.addView(ui.note("Показаны первые 300 записей. Выбрать все можно выше."));break;}
                LinearLayout row=ui.row(Icons.VIDEO,item.label,item.reposted>0?VideoInfo.fullTime(item.reposted,Zones.current()):"Время репоста не передано",null,false,null);
                row.setBackground(ui.round(Ui.FIELD,14));android.widget.CheckBox cb=new android.widget.CheckBox(act);cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Theme.accent()));cb.setChecked(chosen.contains(item.key()));cb.setEnabled(!working);
                cb.setOnCheckedChangeListener((v,on)->{if(on)chosen.add(item.key());else chosen.remove(item.key());selected.setText("Выбрано: "+chosen.size()+" · задержка "+delay+" мс");remove.setEnabled(!working&&!chosen.isEmpty());});
                row.addView(cb);row.setOnClickListener(v->{if(!working)cb.setChecked(!cb.isChecked());});LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(6);rows.addView(row,lp);
            }
        }
        void settings(){
            StreakSheet sh=new StreakSheet(act,"Настройки очистки");sh.root.addView(sh.note("Репосты · только текущий аккаунт"));
            TextView value=sh.note("Задержка: "+delay+" мс");sh.root.addView(value);android.widget.SeekBar bar=new android.widget.SeekBar(act);bar.setMax(14);bar.setProgress((delay-1500)/250);sh.root.addView(bar,ui.matchWrap());
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){}public void onProgressChanged(android.widget.SeekBar b,int n,boolean user){value.setText("Задержка: "+(1500+n*250)+" мс");}});
            sh.button("Сохранить",true,()->{delay=1500+bar.getProgress()*250;act.getSharedPreferences("ttmlonger_interface",0).edit().putInt("cleanup_delay",delay).apply();sh.dialog.dismiss();draw();});sh.show();
        }
        void load(boolean all){
            if(working||!batch.more||batch.page>=MAX_PAGES)return;
            if(!owner.equals(StreakHooks.liveOwner())){Toasts.show(act,"Аккаунт изменился. Откройте экран заново.");return;}
            if(!BUSY.compareAndSet(false,true)){Toasts.show(act,"Уже выполняется операция");return;}
            working=true;cancel=new AtomicBoolean();AtomicBoolean stop=cancel;draw();
            StreakSheet progress=new StreakSheet(act,"Загрузка репостов");progress.root.addView(progress.note("Только чтение списка. Ничего не удаляется."));
            progress.button("Остановить",false,()->{stop.set(true);progress.dialog.dismiss();});progress.dialog.setOnDismissListener(d->stop.set(true));progress.show();
            new Thread(()->{
                try{read(act,owner,stop,batch,all);}catch(Throwable t){batch.problem="Не удалось загрузить список штатным сервисом";}finally{BUSY.set(false);}
                act.runOnUiThread(()->{boolean cancelled=stop.get();progress.dialog.dismiss();working=false;if(!dialog.isShowing()||act.isFinishing()||act.isDestroyed())return;draw();if(all&&!cancelled&&!batch.more&&!batch.uncertain&&!batch.items.isEmpty())confirm(true);});
            },"ttm-cleanup-read").start();
        }
        void confirm(boolean all){
            if(working||all&&(batch.more||batch.uncertain))return;
            if(!owner.equals(StreakHooks.liveOwner())){Toasts.show(act,"Аккаунт изменился");return;}
            List<Item> items=new ArrayList<>();for(Item item:batch.items.values())if(all||chosen.contains(item.key()))items.add(item);
            if(items.isEmpty())return;
            StreakSheet sh=new StreakSheet(act,all?"Очистить все репосты?":"Очистить выбранные?");
            sh.root.addView(sh.note("Будет отменено "+items.size()+" репостов текущего аккаунта. Лайки, избранное и публикации не изменятся. Операция не имеет кнопки отмены, но её можно остановить между запросами."));
            TextView destructive=sh.button("Отменить "+items.size()+" репостов",true,()->{sh.dialog.dismiss();remove(items);});destructive.setBackground(ui.round(0xfffe2c55,26));destructive.setTextColor(0xffffffff);
            sh.button("Не очищать",false,()->sh.dialog.dismiss());sh.show();
        }
        void remove(List<Item> items){
            if(working||!BUSY.compareAndSet(false,true))return;working=true;AtomicBoolean stop=new AtomicBoolean();cancel=stop;draw();
            StreakSheet progress=new StreakSheet(act,"Очистка репостов");TextView info=progress.note("0 из "+items.size());progress.root.addView(info);
            android.widget.ProgressBar bar=new android.widget.ProgressBar(act,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(items.size());progress.root.addView(bar,ui.matchWrap());
            progress.button("Остановить",false,()->{stop.set(true);info.setText("Остановка после текущего запроса…");});progress.dialog.setOnDismissListener(d->stop.set(true));progress.show();
            final int interval=delay;
            new Thread(()->{
                List<Item> completed=new ArrayList<>();boolean unknown=false;
                try{
                    Activity a=act;
            ClassLoader cl=a.getClassLoader();
                Class<?> op=Class.forName("X.0pPL",false,cl),single=Class.forName("X.19X3",false,cl);
                Method remove=op.getMethod("LLLLL",Integer.class,String.class,String.class),wait=single.getMethod("LJ");
                if(!single.isAssignableFrom(remove.getReturnType()))throw new IllegalStateException();
                Object service=op.getConstructor().newInstance();
                    for(Item item:items){
                        if(!current(a,owner,stop))break;unknown=true;
                        Object call=remove.invoke(service,item.carrier,item.aid,item.id);
                        if(!single.isInstance(call))break;
                        Object response=wait.invoke(call);
                        Object status=DownloadHooks.call(response,"getStatusCode");
                        if(status==null)status=field(response,"status_code");
                        if(status==null)status=field(response,"statusCode");
                        if(!(status instanceof Number)||((Number)status).intValue()!=0)break;
                        unknown=false;completed.add(item);int done=completed.size();a.runOnUiThread(()->{if(progress.dialog.isShowing()){bar.setProgress(done);info.setText(done+" из "+items.size());}});
                        if(done<items.size()&&!stop.get())Thread.sleep(interval);
                    }
                }catch(Throwable ignored){}
                final boolean uncertain=unknown;BUSY.set(false);
                act.runOnUiThread(()->{
                    progress.dialog.dismiss();working=false;
                    for(Item item:completed){batch.items.remove(item.key());chosen.remove(item.key());}
                    // Deletions shift offset-based pages. Reload before another pagination.
                    if(batch.more){batch.page=0;batch.uncertain=true;batch.problem="После очистки обновите экран для корректной загрузки.";}
                    if(!dialog.isShowing()||act.isFinishing()||act.isDestroyed())return;draw();
                    StreakSheet result=new StreakSheet(act,"Результат очистки");result.root.addView(result.note("Подтверждено сервером: "+completed.size()+" из "+items.size()+(uncertain?"\nОдин запрос не подтверждён. Автоматического повтора не будет.":"")+"\nОбновите профиль TikTok."));result.button("Готово",true,()->result.dialog.dismiss());result.show();
                });
            },"ttm-cleanup-remove").start();
        }
    }
}
