package fixture.mtp;
import android.content.Context;
import cat.narezany.margyt.plugin.MargyPlugin;
public final class Probe extends MargyPlugin {
    @Override public void onStart(Context context) {
        if(margyt()==null||context==null)throw new IllegalStateException("context");
        margyt().addSettingsSection("CI fixture");
        margyt().addSettingsToggle("CI toggle",null,"toggle",false);
        margyt().addSettingsChoice("CI choice",null,"choice",new String[]{"A","B"},0);
        margyt().addSettingsSlider("CI slider",null,"strength",0,100,50);
        margyt().addSettingsRow("CI action",null,()->margyt().putString("action","done"));
    }
    @Override public void onSettingsChanged(String key){margyt().putString("changed",key);}
    @Override public boolean onOpenSettings(android.app.Activity activity){throw new IllegalStateException("fixture settings failure");}
}