package dev.ttmlonger;
import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.*;
import android.text.style.TypefaceSpan;
import android.widget.TextView;
import java.io.*;
import de.robv.android.xposed.*;

final class EmojiFonts {
    private static volatile Typeface face;
    private static volatile String selected="";
    private static File file;
    static String label(){return selected.isEmpty()?"Системные":selected;}
    static void install(Context c) {
        file=new File(c.getFilesDir(),"ttmlonger-emoji.ttf");
        selected=c.getSharedPreferences("ttmlonger_emoji",0).getString("name","");
        if(Build.VERSION.SDK_INT<29)return;
        if(!selected.isEmpty()&&file.isFile())try{face=Typeface.createFromFile(file);}catch(Throwable ignored){}
        XposedBridge.hookAllMethods(TextView.class,"setText",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                Typeface f=face;
                if(f==null||CrashGuard.tripped||p.args.length==0||!(p.args[0] instanceof CharSequence))return;
                CharSequence s=(CharSequence)p.args[0];
                if(s.length()>12000)return;
                SpannableString out=null;
                for(int i=0;i<s.length();){
                    int cp=Character.codePointAt(s,i),start=i;i+=Character.charCount(cp);
                    if(!emoji(cp))continue;
                    while(i<s.length()){
                        int next=Character.codePointAt(s,i);
                        if(next==0xfe0f||next==0x20e3||next>=0x1f3fb&&next<=0x1f3ff){i+=Character.charCount(next);continue;}
                        if(next==0x200d&&i+1<s.length()){i++;int z=Character.codePointAt(s,i);i+=Character.charCount(z);continue;}
                        if(cp>=0x1f1e6&&cp<=0x1f1ff&&next>=0x1f1e6&&next<=0x1f1ff){i+=Character.charCount(next);}
                        break;
                    }
                    if(out==null)out=new SpannableString(s);
                    out.setSpan(new TypefaceSpan(f),start,i,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if(out!=null)p.args[0]=out;
            }
        });
    }
    private static boolean emoji(int cp){return cp>=0x1f000&&cp<=0x1faff||cp>=0x2600&&cp<=0x27bf;}
    static void show(Activity a,Runnable changed){
        if(Build.VERSION.SDK_INT<29){Toasts.show(a,"Требуется Android 10+");return;}
        String[] items={"Системные  😀 🎉 ❤️ 👍 🔥","Выбрать файл эмодзи (.ttf, .otf)"};
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Эмодзи").setItems(items,(dlg,i)->{
            if(i==0){selected="";face=null;if(file!=null)file.delete();a.getSharedPreferences("ttmlonger_emoji",0).edit().remove("name").apply();changed.run();return;}
            Intent in=new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            if(!ActivityResults.start(a,in,(code,data)->{
                if(code!=Activity.RESULT_OK||data==null||data.getData()==null)return;
                Uri uri=data.getData();
                new Thread(()->{
                    File tmp=null;
                    try{
                        tmp=File.createTempFile("emoji",".ttf",a.getFilesDir());
                        try(InputStream src=a.getContentResolver().openInputStream(uri);OutputStream dst=new FileOutputStream(tmp)){
                            if(src==null)throw new IOException();byte[] buf=new byte[16384];long total=0;int n;
                            while((n=src.read(buf))>0){total+=n;if(total>32L*1024*1024)throw new IOException();dst.write(buf,0,n);}
                        }
                        FontHooks.checkFontFile(tmp);
                        Typeface t=Typeface.createFromFile(tmp);
                        if(file==null)file=new File(a.getFilesDir(),"ttmlonger-emoji.ttf");
                        if(!tmp.renameTo(file))throw new IOException();
                        face=t;selected="Свой шрифт эмодзи";a.getSharedPreferences("ttmlonger_emoji",0).edit().putString("name",selected).apply();
                        a.runOnUiThread(()->{changed.run();Toasts.show(a,"Эмодзи сохранены");});
                    }catch(Throwable e){a.runOnUiThread(()->Toasts.show(a,"Не удалось открыть шрифт эмодзи"));}
                    finally{if(tmp!=null)tmp.delete();}
                },"ttm-emoji").start();
            }))Toasts.show(a,"Не удалось открыть выбор файла");
        }).setNegativeButton("Закрыть",null).create();
        Screen.markOwn(d);d.show();Ui.style(d);
    }
}
