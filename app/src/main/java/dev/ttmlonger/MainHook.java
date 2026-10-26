package dev.ttmlonger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

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
        // Только главный процесс: экраны мода рисуются поверх его активностей. Побочные процессы
        // называются «пакет:имя»; главный может прийти и под другим именем (зависит от загрузчика).
        if (lp.processName != null && lp.processName.indexOf(':') >= 0) return;

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
            FontHooks.install();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            AvatarHooks.install(cl);
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

    /**
     * Запасной запуск: если хук на Application.attach не сработал, мод стартует с первой же
     * активности, чтобы жест и экраны всё равно заработали.
     */
    static void ensureBoot(Activity a) {
        if (booted.get()) return;
        Application app = a.getApplication();
        boot(app != null ? app : a, a.getClassLoader());
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
        // Следим за активностями и без хука на Activity.onResume: жизненный цикл даёт сам Android.
        try {
            if (ctx instanceof Application) {
                ((Application) ctx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityResumed(Activity a) {
                        ScreenWatcher.onResumed(a);
                    }

                    @Override
                    public void onActivityCreated(Activity a, Bundle b) {
                    }

                    @Override
                    public void onActivityStarted(Activity a) {
                    }

                    @Override
                    public void onActivityPaused(Activity a) {
                    }

                    @Override
                    public void onActivityStopped(Activity a) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity a, Bundle b) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity a) {
                    }
                });
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
