package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.LinearLayout;
import org.json.*;
import java.util.*;
import java.io.*;

/** Bounded declarative subset of MargyT commands-plugin.json; no interpreter or DEX loader. */
final class CommandPlugins extends Screen {
    private static final int MAX=16,MAX_BYTES=65536;
    private final static String PREF="ttmlonger_commands";
    private CommandPlugins(Activity a){super(a);}
    static void show(Activity a){new CommandPlugins(a).open("Плагины","Локальные JSON-команды. Исполняемые .mtp и скрипты не поддерживаются.");}
    private android.content.SharedPreferences prefs(){return act.getSharedPreferences(PREF,0);}
    static JSONObject parse(String text)throws Exception{
        if(text.length()>MAX_BYTES)throw new IOException("Файл слишком большой");
        JSONObject o=new JSONObject(text);
        if(o.optInt("schema",-1)!=1||!o.optString("id","").matches("[a-zA-Z0-9_.-]{1,64}"))throw new IOException("Несовместимый манифест");
        JSONObject clean=new JSONObject().put("schema",1).put("id",o.getString("id"));
        for(String key:new String[]{"name","author","version"}){
            String value=o.optString(key,"").trim();
            if(value.isEmpty()||value.length()>100)throw new IOException("Нет поля "+key);
            clean.put(key,value);
        }
        JSONArray rows=o.getJSONArray("rows"),out=new JSONArray();
        if(rows.length()==0||rows.length()>32)throw new IOException("От 1 до 32 команд");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            String action=row.optString("action"),title=row.optString("title"),value=row.optString("value");
            if(!action.equals("window")||title.trim().isEmpty()||title.length()>100||value.length()>4000)throw new IOException("Поддерживается только action: window");
            out.put(new JSONObject().put("action",action).put("title",title).put("value",value));
        }
        return clean.put("rows",out);
    }
    @Override void build(LinearLayout body){
        body.addView(ui.menuRow(Icons.DOWNLOAD,"Установить JSON-плагин","Выбрать локальный файл",null,v->install()));
        body.addView(ui.note("Поддержка формата commands-plugin.json из MargyT: команды открывают информационные окна. Нет доступа к сети, хукам, аккаунту и произвольному коду."));
        for(Map.Entry<String,?> e:new TreeMap<>(prefs().getAll()).entrySet()){
            if(!e.getKey().startsWith("plugin:")||!(e.getValue() instanceof String))continue;
            try{
                JSONObject o=parse((String)e.getValue());
                String id=o.getString("id"),name=o.getString("name");
                body.addView(ui.header(name+" · "+o.getString("version")));
                body.addView(ui.note("Автор: "+o.getString("author")));
                boolean on=prefs().getBoolean("on:"+id,false);
                body.addView(toggleRow(-1,"Включить",null,on,value->{prefs().edit().putBoolean("on:"+id,value).apply();reload();}));
                if(on){JSONArray rows=o.getJSONArray("rows");for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);
                    body.addView(ui.menuRow(Icons.SLIDERS,row.getString("title"),"Команда плагина",null,v->{
                        if(!prefs().getBoolean("on:"+id,false)||CrashGuard.tripped)return;
                        AlertDialog d=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle(row.optString("title")).setMessage(row.optString("value")).setPositiveButton("Готово",null).create();Screen.markOwn(d);d.show();Ui.style(d);
                    }));
                }}
                body.addView(ui.menuRow(Icons.TRASH,"Удалить "+name,null,null,v->{
                    AlertDialog d=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle("Удалить плагин?").setMessage(name).setNegativeButton("Отмена",null).setPositiveButton("Удалить",(x,w)->{prefs().edit().remove("plugin:"+id).remove("on:"+id).apply();reload();}).create();Screen.markOwn(d);d.show();Ui.style(d);
                }));
            }catch(Exception ignored){}
        }
    }
    private void reload(){if(dialog!=null)dialog.dismiss();show(act);}
    static String read(Activity a,android.net.Uri uri,int max)throws IOException{
        try(InputStream in=a.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Нет файла");
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Слишком большой файл");out.write(buf,0,n);}
            return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private void install(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        if(!ActivityResults.start(act,i,(code,data)->{
            if(code!=Activity.RESULT_OK||data==null||data.getData()==null)return;
            new Thread(()->{
                try{
                    JSONObject o=parse(read(act,data.getData(),MAX_BYTES));
                    act.runOnUiThread(()->review(o));
                }catch(Throwable t){act.runOnUiThread(()->Toasts.show(act,"Плагин не поддерживается: "+t.getMessage()));}
            },"ttm-plugin-import").start();
        }))Toasts.show(act,"Не удалось открыть файл");
    }
    private void review(JSONObject o){
        if(act.isFinishing()||act.isDestroyed())return;
        String id=o.optString("id"),name=o.optString("name");
        int n=0;for(String key:prefs().getAll().keySet())if(key.startsWith("plugin:"))n++;
        if(n>=MAX&&!prefs().contains("plugin:"+id)){Toasts.show(act,"Максимум 16 плагинов");return;}
        AlertDialog d=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle("Установить "+name+"?")
            .setMessage("Автор: "+o.optString("author")+"\nВерсия: "+o.optString("version")+"\nКоманд: "+o.optJSONArray("rows").length()+"\nПлагин будет выключен.")
            .setNegativeButton("Отмена",null).setPositiveButton("Установить",(x,w)->{prefs().edit().putString("plugin:"+id,o.toString()).putBoolean("on:"+id,false).apply();reload();}).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
