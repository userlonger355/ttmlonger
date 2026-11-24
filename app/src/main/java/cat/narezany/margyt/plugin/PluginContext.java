package cat.narezany.margyt.plugin;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import dev.ttmlonger.MtpRuntime;
/** Public binary compatibility surface. Host-specific behavior is delegated to TTMLonger. */
public final class PluginContext {
    public interface Diarist{void note(String line);}
    public interface Tapped{void tapped();}
    public interface Selected{void selected(int index);}
    public interface Downloaded{void finished(File file,boolean success);}
    private final Context app;private final String id;private final File folder;private final Diarist log;
    public PluginContext(Context c,String id,File folder,Diarist d){app=c;this.id=id;this.folder=folder;log=d;}
    public Context context(){return app;}
    public String id(){return id;}
    public File folder(){return folder;}
    public SharedPreferences prefs(){return app.getSharedPreferences("margyt_plugin_"+id,0);}
    public SharedPreferences accountPrefs(){String uid=accountId();return app.getSharedPreferences("margyt_plugin_"+id+"_"+(uid==null?"guest":uid),0);}
    public String accountId(){return MtpRuntime.account();}
    public String currentVideoId(){return MtpRuntime.videoId();}
    public long currentVideoPublishedTime(){return MtpRuntime.videoTime();}
    public int accentColour(){return MtpRuntime.accent();}
    public Activity activity(){return MtpRuntime.activity();}
    public Activity screen(){return activity();}
    public String getString(String k,String d){return prefs().getString(k,d);}
    public void putString(String k,String v){if(k==null||k.length()>80||v==null||v.length()>8192)throw new IllegalArgumentException();prefs().edit().putString(k,v).apply();}
    public boolean getBoolean(String k,boolean d){return prefs().getBoolean(k,d);}
    public int getInt(String k,int d){return prefs().getInt(k,d);}
    public void onUiThread(Runnable r){MtpRuntime.ui(id,r);}
    public void copyText(String text){onUiThread(()->{android.content.ClipboardManager c=(android.content.ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(android.content.ClipData.newPlainText(id,text==null?"":text));});}
    public void showWindow(String title,String message){MtpRuntime.window(id,title,message);}
    public void choose(String title,String[] options,Selected cb){MtpRuntime.choose(id,title,options,cb);}
    public void offer(String label,Tapped cb){MtpRuntime.offer(id,label,cb);}
    public void progress(String what,int percent){MtpRuntime.progress(id,what,percent);}
    public void progressDone(){MtpRuntime.progress(id,"Готово",100);}
    public void progressGone(){MtpRuntime.progress(id,null,0);}
    public void away(String what,Runnable r){MtpRuntime.away(id,r);}
    public byte[] fetch(String url){return MtpRuntime.fetch(id,url);}
    public void download(String url,String name,Downloaded cb){MtpRuntime.download(id,url,name,cb);}
    public void log(String line){if(log!=null)log.note(line);}
    public void addSettingsRow(String title,String detail,Tapped action){MtpRuntime.row(id,0,title,detail,null,null,0,0,0,action);}
    public void addSettingsSection(String title){MtpRuntime.row(id,4,title,null,null,null,0,0,0,null);}
    public void addSettingsToggle(String title,String detail,String key,boolean def){MtpRuntime.row(id,1,title,detail,key,null,0,1,def?1:0,null);}
    public void addSettingsChoice(String title,String detail,String key,String[] options,int def){MtpRuntime.row(id,2,title,detail,key,options,0,options==null?0:options.length-1,def,null);}
    public void addSettingsSlider(String title,String detail,String key,int min,int max,int def){MtpRuntime.row(id,3,title,detail,key,null,min,max,def,null);}
}
