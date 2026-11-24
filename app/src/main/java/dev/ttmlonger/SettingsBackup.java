package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Allow-listed appearance/interface backup. Never export account, message, token, or A/B data. */
final class SettingsBackup {
    private static final String[] BOOL={"clean","labels","like","follow","repost","share","profile_copy","profile_counts","profile_date","profile_follow","details","message_clock","comment_select","comment_copy_body","top_hide_community","top_hide_friends","top_hide_recommend","own_brightness","avatar_contour_spin"};
    private static final String[] CORE_BOOL={"vtTime","vtRepost","vtSeconds","vtRegion","amoled","keepLanguage","saveStickers","hideAds","hideLiveButton","hideLiveRooms"};
    private static final String[] CORE_INT={"accent","vtFormat","vtRegionMode","vtStyle","vtPos","vtOpacity"};
    private static final int MAX=32768;
    private static boolean has(String[] keys,String key){for(String x:keys)if(x.equals(key))return true;return false;}
    private static android.content.SharedPreferences prefs(Activity a){return a.getSharedPreferences("ttmlonger_interface",0);}
    private static JSONObject export(Activity a)throws Exception{
        JSONObject o=new JSONObject(),values=new JSONObject();
        Map<String,?> all=prefs(a).getAll();
        for(String k:BOOL)if(all.get(k) instanceof Boolean)values.put(k,all.get(k));
        if(all.get("avatar_contour") instanceof Integer)values.put("avatar_contour",all.get("avatar_contour"));
        for(String k:CORE_BOOL)values.put(k,Prefs.class.getDeclaredField(k).getBoolean(null));
        for(String k:CORE_INT)values.put(k,Prefs.class.getDeclaredField(k).getInt(null));
        return o.put("schema",1).put("settings",values);
    }
    static Map<String,Object> validate(String json)throws Exception{
        if(json.length()>MAX)throw new IOException("Файл слишком большой");
        JSONObject o=new JSONObject(json);if(o.optInt("schema",-1)!=1)throw new IOException("Версия не поддерживается");
        JSONObject v=o.getJSONObject("settings");Map<String,Object> accepted=new LinkedHashMap<>();
        for(java.util.Iterator<String> it=v.keys();it.hasNext();){
            String k=it.next();Object raw=v.get(k);
            if(has(BOOL,k)||has(CORE_BOOL,k)){if(raw instanceof Boolean)accepted.put(k,raw);continue;}
            if(!has(CORE_INT,k)&&!k.equals("avatar_contour"))continue;
            if(!(raw instanceof Integer))continue;int n=(Integer)raw;
            boolean valid=k.equals("accent")||k.equals("vtFormat")&&n>=0&&n<=1||k.equals("vtRegionMode")&&n>=0&&n<=2||k.equals("vtStyle")&&n>=0&&n<=2||k.equals("vtPos")&&n>=0&&n<=3||k.equals("vtOpacity")&&n>=20&&n<=100||k.equals("avatar_contour")&&n>=0&&n<=9;
            if(valid)accepted.put(k,n);
        }
        return accepted;
    }
    private static void apply(Activity a,Map<String,Object> values){
        android.content.SharedPreferences.Editor edit=prefs(a).edit();
        for(Map.Entry<String,Object> e:values.entrySet()){
            String k=e.getKey();Object value=e.getValue();
            if(has(BOOL,k)){edit.putBoolean(k,(Boolean)value);continue;}
            if(k.equals("avatar_contour")){edit.putInt(k,(Integer)value);continue;}
            try{
                String method="set"+Character.toUpperCase(k.charAt(0))+k.substring(1);
                Prefs.class.getDeclaredMethod(method,value instanceof Boolean?boolean.class:int.class).invoke(null,value);
            }catch(Throwable t){FlagStore.log(t);}
        }
        edit.apply();
    }
    static void show(Activity a){
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Резервная копия")
            .setMessage("JSON содержит только настройки внешнего вида и интерфейса. Аккаунты, авторы в чёрном списке, переписки, токены и флаги A/B исключены.")
            .setNegativeButton("Закрыть",null).setNeutralButton("Импорт",(x,w)->pick(a,false)).setPositiveButton("Экспорт",(x,w)->pick(a,true)).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
    private static void pick(Activity a,boolean write){
        Intent i=new Intent(write?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if(write)i.putExtra(Intent.EXTRA_TITLE,"TTMLonger-settings.json");
        if(!ActivityResults.start(a,i,(code,data)->{
            if(code!=Activity.RESULT_OK||data==null||data.getData()==null)return;
            Uri uri=data.getData();
            new Thread(()->{
                try{
                    if(write){byte[] raw=export(a).toString(2).getBytes(StandardCharsets.UTF_8);try(OutputStream out=a.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(raw);}a.runOnUiThread(()->Toasts.show(a,"Копия сохранена"));}
                    else{
                        ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream in=a.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(buffer.size()+n>MAX)throw new IOException("Слишком большой файл");buffer.write(b,0,n);}}
                        Map<String,Object> values=validate(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
                        a.runOnUiThread(()->review(a,values));
                    }
                }catch(Throwable t){a.runOnUiThread(()->Toasts.show(a,"Не удалось обработать копию"));}
            },"ttm-settings-backup").start();
        }))Toasts.show(a,"Не удалось открыть выбор файла");
    }
    private static void review(Activity a,Map<String,Object> values){
        if(a.isFinishing()||a.isDestroyed())return;
        if(values.isEmpty()){Toasts.show(a,"Нет совместимых настроек");return;}
        StringBuilder details=new StringBuilder();for(Map.Entry<String,Object> e:values.entrySet())details.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Импорт: "+values.size()+" настроек").setMessage(details+"\nДля полного применения перезапустите TikTok.")
            .setNegativeButton("Отмена",null).setPositiveButton("Применить",(x,w)->{apply(a,values);Restart.offer(a);}).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
