package dev.ttmlonger;

import java.io.File;
import org.json.JSONObject;

/** Three local user profiles; storing a look never changes the active style. */
final class GlassProfiles {
    static final int COUNT=3;
    private GlassProfiles() {}
    private static File file(int slot) {
        File dir=FlagStore.dir();
        if(dir==null || slot<0 || slot>=COUNT) return null;
        return new File(dir,"glass-profile-"+(slot+1)+".json");
    }
    static boolean exists(int slot) {
        File f=file(slot);
        return f!=null && f.isFile();
    }
    static synchronized boolean store(int slot) {
        File f=file(slot);
        if(f==null) return false;
        try {
            JSONObject o=GlassCfg.toJson();
            o.put("profileVersion",1);
            FlagStore.writeAtomic(f,o.toString());
            return f.isFile();
        } catch(Throwable e) { FlagStore.log(e); return false; }
    }
    static synchronized boolean apply(int slot) {
        File f=file(slot);
        if(f==null || !f.isFile()) return false;
        try {
            JSONObject o=new JSONObject(FlagStore.readFile(f));
            if(o.optInt("profileVersion",0)!=1) return false;
            o.put("style",GlassCfg.style);
            GlassCfg.load(o);
            GlassCfg.rev++;
            GlassCfg.save();
            return true;
        } catch(Throwable e) { FlagStore.log(e); return false; }
    }
}
