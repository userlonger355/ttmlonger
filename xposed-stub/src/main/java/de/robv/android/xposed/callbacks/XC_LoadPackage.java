package de.robv.android.xposed.callbacks;

/** Заглушка для компиляции. */
public abstract class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
