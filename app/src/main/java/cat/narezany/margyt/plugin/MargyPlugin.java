package cat.narezany.margyt.plugin;
import android.app.Activity;
import android.content.Context;
import java.util.List;
/** TTMLonger compatibility ABI for public MargyT plugin API 1–5. Independently implemented. */
public abstract class MargyPlugin {
    public static final int API=5;
    private PluginContext bridge;
    public final void attach(PluginContext c){bridge=c;}
    public final PluginContext margyt(){return bridge;}
    public void onStart(Context c){}
    public void onActivityCreated(Activity a){}
    public void onActivityResumed(Activity a){}
    public void onActivityPaused(Activity a){}
    public boolean onOpenSettings(Activity a){return false;}
    public void onAccountChanged(String uid){}
    public void onSettingsChanged(String key){}
    public int onColour(int c){return c;}
    public String onRegion(String key,String value){return value;}
    public void onScreen(Activity a,String name){}
    public List onFeed(List posts){return posts;}
    public String onName(String uid,String name){return name;}
    public CharSequence onText(CharSequence text){return text;}
    public void onStop(){}
}
