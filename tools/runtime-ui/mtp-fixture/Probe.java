package fixture.mtp;
import android.content.Context;
import cat.narezany.margyt.plugin.MargyPlugin;
public final class Probe extends MargyPlugin {
    @Override public void onStart(Context context) {
        if(margyt()==null||context==null)throw new IllegalStateException("context");
        margyt().addSettingsSection("CI fixture");
    }
}