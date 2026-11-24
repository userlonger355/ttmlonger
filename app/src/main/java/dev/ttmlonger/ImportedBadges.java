package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import org.json.*;
import java.util.*;
import java.io.*;

/** Local MargyT badge catalogue, stable indices for the life of a process.
 * Colours and UID assignments only. Neutral community glyph, not an official verification mark. */
final class ImportedBadges {
    private static final int FIRST=32,MAX=48,BYTES=256*1024;
    private static final String FILE="imported_margyt_badges.json";
    private static volatile Map<String,String> assignments=Collections.emptyMap();
    private static final Map<Integer,BadgeArt> arts=new HashMap<>();
    private static final Map<String,Integer> slots=new HashMap<>();
    private static boolean loaded;
    private static final String GLYPH="M 12 2 L 21 7 L 21 17 L 12 22 L 3 17 L 3 7 Z F";
    private static final class Catalog {
        final Map<String,BadgeSet.Def> defs=new LinkedHashMap<>();
        final Map<String,List<String>> people=new LinkedHashMap<>();
    }
    static int count(String raw)throws Exception{return parse(raw).people.size();}
    private static Catalog parse(String raw)throws Exception{
        if(raw.length()>BYTES)throw new IOException("Слишком большой каталог");
        JSONArray array=new JSONObject(raw).getJSONArray("badges");
        if(array.length()>MAX)throw new IOException("Максимум 48 типов");
        Catalog c=new Catalog();
        for(int i=0;i<array.length();i++){
            JSONObject item=array.getJSONObject(i);String id=item.optString("id"),rgb=item.optString("colour","8D9AE5");
            if(!id.matches("[a-zA-Z0-9_.-]{1,64}")||c.defs.containsKey(id)||!rgb.matches("[a-fA-F0-9]{6}"))continue;
            String title=item.optString("title_ru",item.optString("title","Бейдж сообщества"));
            c.defs.put(id,new BadgeSet.Def(id,title,0xff000000|Integer.parseInt(rgb,16),GLYPH));
            JSONArray uids=item.optJSONArray("users");if(uids==null)continue;
            for(int j=0;j<Math.min(uids.length(),5000);j++){
                String uid=uids.optString(j,"");if(!StreakLogic.isUid(uid))continue;
                if(c.people.size()>=5000&&!c.people.containsKey(uid))continue;
                List<String> list=c.people.get(uid);if(list==null){list=new ArrayList<>();c.people.put(uid,list);}
                if(list.size()<4&&!list.contains(id))list.add(id);
            }
        }
        return c;
    }
    private static synchronized void apply(Catalog c){
        Map<String,String> marks=new HashMap<>();
        for(BadgeSet.Def def:c.defs.values()){
            // New colour or ID gets a new slot; never reuse existing rendered indices.
            String key=def.id+":"+def.color;
            Integer index=slots.get(key);
            if(index==null&&slots.size()<BadgeLogic.MAX-FIRST){index=FIRST+slots.size();slots.put(key,index);arts.put(index,new BadgeArt(def));}
        }
        for(Map.Entry<String,List<String>> e:c.people.entrySet()){
            StringBuilder text=new StringBuilder();for(String id:e.getValue()){
                BadgeSet.Def def=c.defs.get(id);Integer i=slots.get(def.id+":"+def.color);if(i!=null)text.append(BadgeLogic.mark(i));
            }
            if(text.length()>0)marks.put(e.getKey(),text.toString());
        }
        assignments=marks;
    }
    static Map<String,String> marks(){return assignments;}
    static synchronized BadgeArt art(int index){return arts.get(index);}
    private static File file(){File d=FlagStore.dir();return d==null?null:new File(d,FILE);}
    static void load(){
        if(loaded)return;loaded=true;
        try{File f=file();if(f!=null&&f.isFile()&&f.length()<=BYTES)apply(parse(FlagStore.readFile(f)));}catch(Throwable ignored){}
    }
    static void clear(){
        File f=file();if(f!=null&&f.exists()&&!f.delete())return;
        assignments=Collections.emptyMap();BadgeStore.importedChanged();
    }
    static void pick(Activity a,Runnable done){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        if(!ActivityResults.start(a,i,(code,data)->{
            if(code!=Activity.RESULT_OK||data==null||data.getData()==null)return;
            new Thread(()->{
                try{
                    String raw=CommandPlugins.read(a,data.getData(),BYTES);Catalog c=parse(raw);
                    if(c.people.isEmpty())throw new IOException("Нет совместимых записей");
                    a.runOnUiThread(()->{
                        if(a.isFinishing()||a.isDestroyed())return;
                        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Импорт бейджиков?")
                            .setMessage("Аккаунтов: "+c.people.size()+"\nТипов: "+c.defs.size()+"\nЛокальные цветные значки сообщества; изображения и ссылки игнорируются. Это не официальная верификация.")
                            .setNegativeButton("Отмена",null).setPositiveButton("Импорт",(x,w)->{
                                try{File dest=file();if(dest==null)throw new IOException();FlagStore.writeAtomic(dest,raw);apply(c);BadgeStore.importedChanged();done.run();Toasts.show(a,"Импортировано. Обновите экран или перезапустите TikTok.");}
                                catch(Throwable t){Toasts.show(a,"Не удалось сохранить каталог");}
                            }).create();Screen.markOwn(d);d.show();Ui.style(d);
                    });
                }catch(Throwable t){a.runOnUiThread(()->Toasts.show(a,"Несовместимый каталог"));}
            },"ttm-badge-import").start();
        }))Toasts.show(a,"Не удалось открыть файл");
    }
}
