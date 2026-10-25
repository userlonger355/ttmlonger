package dev.ttmlonger;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.concurrent.atomic.AtomicBoolean;

/** Точка входа модуля (assets/xposed_init). */
public final class MainHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean booted = new AtomicBoolean();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Targets.isTarget(lp.packageName)) return;
        // Только главный процесс: экраны мода рисуются поверх его активностей.
        if (lp.processName != null && !lp.processName.equals(lp.packageName)) return;

        final ClassLoader cl = lp.classLoader;

        // Каждая часть ставится отдельно: если одна не взлетела, остальные продолжают работать.
        try {
            FlagHooks.install(cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            RegionHooks.installSim();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            ColorHooks.install();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            ScreenWatcher.install();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            ActivityResults.install();
        } catch (Throwable t) {
            FlagStore.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boot((Context) param.thisObject, cl);
                }
            });
        } catch (Throwable t) {
            FlagStore.log(t);
        }

        // Если Application уже создан к моменту загрузки модуля.
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) boot((Context) app, cl);
        } catch (Throwable ignored) {
            // Подхватим через attach.
        }
    }

    private static void boot(Context ctx, ClassLoader cl) {
        if (ctx == null || !booted.compareAndSet(false, true)) return;
        try {
            FlagStore.init(ctx);      // внутри читаются и Prefs
            Trigger.install();
            RegionHooks.installLocale();
            if (FlagHooks.count() == 0) FlagHooks.retryLater(cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
