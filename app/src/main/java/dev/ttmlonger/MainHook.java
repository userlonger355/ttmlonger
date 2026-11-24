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

        android.util.Log.i("TTMLonger", "TTMLONGER_HOOK_ENTRY " + Targets.VERSION);
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
            DownloadHooks.install(cl);
            NativeDates.install(cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            AdHooks.install(cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            BlockedProfile.install(cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            Invisible.install(cl);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        try {
            StoryShare.install();
            StickerSaver.install();
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
            FlagStore.init(ctx);
            try{CommentWallpaper.install(ctx);}catch(Throwable t){FlagStore.log(t);}
            try { MessageClock.install(cl); } catch(Throwable t){FlagStore.log(t);}
            try { CommentSelect.install(cl); } catch(Throwable t){FlagStore.log(t);}
            try { FeedMute.install(ctx,cl); } catch(Throwable t){FlagStore.log(t);}      // внутри читаются и Prefs
            try { AvatarContours.install(cl,ctx); } catch(Throwable t) { FlagStore.log(t); }
            try { BrightnessLock.install(ctx); } catch(Throwable t) { FlagStore.log(t); }
            try { OfflineLimit.install(ctx,cl); } catch(Throwable t) { FlagStore.log(t); }
            try { SponsorSegments.install(ctx,cl); } catch(Throwable t) { FlagStore.log(t); }
            try {
                InterfaceHooks.install(ctx);
                CommentTools.install(cl);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            try {
                BrowserLinks.install();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            try {
                FeedButtons.install(cl);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            try {
                ProfileFacts.install(cl);
                ProfileGrid.install(cl);
                EmojiFonts.install(ctx);
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            AccentHooks.installIfNeeded(); // глубокая подмена акцента: только если акцент выбран
            try {
                BadgeHooks.install(cl); // бейджики после имён: только если включены
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            try {
                ApiTap.install(); // перехват запросов лайков/избранного/репостов: только если включён
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            try {
                UnsupportedTap.install(); // ловит надпись «не поддерживается в вашем регионе» для разведки
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            Trigger.install();
            try {
                GlassBar.install(); // линза нижней панели видит касания; включается настройкой «Нижняя панель»
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            RegionHooks.installLocale();
            try { MtpRuntime.install(ctx,cl); } catch(Throwable t){FlagStore.log(t);}
            if (FlagHooks.count() == 0) FlagHooks.retryLater(cl);
            DexHooks.start(ctx, cl);  // второй читатель флагов и нажатия на стикеры: поиск в dex, в фоне
            android.util.Log.i("TTMLonger", "TTMLONGER_BOOT_READY " + Targets.VERSION + " flagHooks=" + FlagHooks.count());
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
                        MtpRuntime.resumed(a);
                    }

                    @Override
                    public void onActivityCreated(Activity a, Bundle b) {
                        MtpRuntime.created(a);
                    }

                    @Override
                    public void onActivityStarted(Activity a) {
                    }

                    @Override
                    public void onActivityPaused(Activity a) {
                        MtpRuntime.paused(a);
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
