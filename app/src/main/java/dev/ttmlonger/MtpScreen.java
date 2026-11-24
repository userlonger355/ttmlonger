package dev.ttmlonger;
import android.app.*;
import android.content.*;
import android.widget.*;
import android.view.*;
import java.util.*;
import java.io.*;
final class MtpScreen extends Screen{
    private MtpScreen(Activity a){super(a);}
    static void show(Activity a){new MtpScreen(a).open("MTP-плагины","Совместимость публичного API MargyT 1–5. Незнакомый DEX может читать данные TikTok и выполнять действия от вашего имени. Устанавливайте только проверенный код.");}
    void build(LinearLayout body){
        if(!MtpRuntime.ready()){body.addView(ui.note("Нужен Android 8+ и запущенный мод."));return;}
        body.addView(ui.menuRow(Icons.DOWNLOAD,"Установить .mtp","Выбор локального ZIP-пакета",null,v->pick()));
        for(MtpRuntime.Slot s:MtpRuntime.all()){
            body.addView(ui.header(s.pkg.manifest.optString("name_ru",s.pkg.manifest.optString("name"))+" · "+s.pkg.manifest.optString("version")));
            body.addView(ui.note("Автор: "+s.pkg.manifest.optString("author")+" · "+s.status));
            body.addView(ui.menuRow(Icons.SLIDERS,s.enabled?"Выключить":"Включить","DEX · без песочницы",null,v->{if(s.enabled){MtpRuntime.stop(s);reload();}else approve(s);}));
            if(s.enabled)body.addView(ui.menuRow(Icons.SLIDERS,"Настройки плагина",null,null,v->settings(act,s)));
            body.addView(ui.menuRow(Icons.TRASH,"Удалить",null,null,v->{AlertDialog d=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle("Удалить "+s.id+"?").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(x,w)->{MtpRuntime.remove(s);reload();}).create();markOwn(d);d.show();Ui.style(d);}));
        }
    }
    private void reload(){dialog.dismiss();show(act);}
    private void approve(MtpRuntime.Slot s){
        AlertDialog d=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle("Запустить доверенный DEX?")
            .setMessage(s.id+"\nSHA-256: "+s.pkg.digest+"\nПлагин получит возможности процесса TikTok, включая доступ к его файлам и аккаунту. Это не песочница. Выключение не отменяет уже выполненные действия.")
            .setNegativeButton("Отмена",null).setPositiveButton("Доверяю и запускаю",(x,w)->{MtpRuntime.approve(s);reload();}).create();markOwn(d);d.show();Ui.style(d);
    }
    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        if(!ActivityResults.start(act,i,(code,data)->{
            if(code!=Activity.RESULT_OK||data==null||data.getData()==null)return;
            new Thread(()->{try{MtpArchive p=MtpArchive.read(act.getContentResolver().openInputStream(data.getData()));act.runOnUiThread(()->review(p));}catch(Throwable t){act.runOnUiThread(()->Toasts.show(act,"MTP отклонён: "+t.getMessage()));}},"ttm-mtp-import").start();
        }))Toasts.show(act,"Не удалось открыть файл");
    }
    private void review(MtpArchive p){
        if(act.isFinishing()||act.isDestroyed())return;
        AlertDialog d=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle("Установить "+p.manifest.optString("name")+"?")
            .setMessage("Автор: "+p.manifest.optString("author")+"\nAPI: "+p.manifest.optInt("min_api",1)+"\nSHA-256: "+p.digest+"\nУстанавливается выключенным. Автор не проверен; исполняемый код не анализировался.")
            .setNegativeButton("Отмена",null).setPositiveButton("Установить",(x,w)->{try{MtpRuntime.add(p);reload();}catch(Exception e){Toasts.show(act,"Не удалось установить");}}).create();markOwn(d);d.show();Ui.style(d);
    }
    static void settings(Activity a,MtpRuntime.Slot s){new Screen(a){
        void build(LinearLayout body){
            if(!s.settingsError.isEmpty())body.addView(ui.note("Ошибка настроек: "+s.settingsError));
            if(MtpRuntime.customSettings(s))body.addView(ui.menuRow(Icons.SLIDERS,"Окно настроек плагина","Открыть собственный интерфейс MTP",null,v->{
                dialog.dismiss();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                    if(!MtpRuntime.open(s,a))settings(a,s);
                });
            }));
            if(s.rows.isEmpty())body.addView(ui.note("Стандартных настроек нет. Если собственное окно не работает, плагин может зависеть от внутренних классов MargyT."));
            for(MtpRuntime.Row r:s.rows){
                if(r.kind==4){body.addView(ui.header(r.title));continue;}
                if(r.kind==0){body.addView(ui.menuRow(Icons.SLIDERS,r.title,r.detail,null,v->{MtpRuntime.settingAction(s,r);}));continue;}
                if(r.kind==1){body.addView(toggleRow(-1,r.title,r.detail,MtpRuntime.boolValue(s,r),on->MtpRuntime.saveSetting(s,r,on?1:0)));continue;}
                if(r.kind==2&&r.options!=null&&r.options.length>0){int current=Math.max(0,Math.min(r.options.length-1,MtpRuntime.intValue(s,r)));LinearLayout row=ui.menuRow(Icons.SLIDERS,r.title,r.detail,r.options[current],v->{AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle(r.title).setSingleChoiceItems(r.options,MtpRuntime.intValue(s,r),(x,n)->{MtpRuntime.saveSetting(s,r,n);x.dismiss();TextView val=Ui.valueOf((LinearLayout)v);if(val!=null)val.setText(r.options[n]);}).setNegativeButton("Отмена",null).create();markOwn(d);d.show();Ui.style(d);});body.addView(row);continue;}
                if(r.kind==3){body.addView(ui.header(r.title));SeekBar slider=new SeekBar(a);slider.setMax(r.max-r.min);slider.setProgress(Math.max(0,Math.min(r.max-r.min,p.getInt(r.key,r.def)-r.min)));TextView label=ui.note(String.valueOf(r.min+slider.getProgress()));body.addView(label);body.addView(slider);slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){label.setText(String.valueOf(r.min+n));}public void onStopTrackingTouch(SeekBar b){MtpRuntime.saveSetting(s,r,r.min+b.getProgress());}});}
            }
        }
    }.open(s.id,"Настройки MTP");}
}
