package dev.ttmlonger;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;

/**
 * Нижняя панель TikTok в стиле Liquid Glass: включение, поиск панели и подключение {@link GlassSession}.
 *
 * <p>Режим «Оригинал» ничего не трогает. В режиме «Liquid Glass» мод раз в секунду, пока открыт экран TikTok,
 * ищет панель ({@link BarFinder}); найдя, надевает на неё стеклянную капсулу и линзу. Выключили режим или
 * ушли на другой экран — всё снимается, TikTok остаётся как был.
 */
final class GlassBar {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile GlassSession live;
    private static WeakReference<Activity> liveAct = new WeakReference<>(null);
    private static boolean hooked;
    private static boolean looping;

    private static volatile String diag = "Не включено";
    private static volatile int scans;
    private static volatile String found = "";
    private static int misses;
    private static WeakReference<Activity> missedOn = new WeakReference<>(null);

    private GlassBar() {
    }

    /** Хук на касания окна: линза должна видеть палец, даже когда касание забирает TikTok. */
    static void install() {
        if (hooked) return;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            GlassSession s = live;
                            if (s == null) return;
                            try {
                                if (param.thisObject == liveAct.get() && param.args[0] instanceof MotionEvent) {
                                    s.onTouch((MotionEvent) param.args[0]);
                                }
                            } catch (Throwable t) {
                                FlagStore.log(t);
                            }
                        }
                    });
            hooked = true;
        } catch (Throwable t) {
            diag = "Хук касаний не встал: " + t.getClass().getSimpleName();
            FlagStore.log(t);
        }
    }

    /** Запускает или останавливает слежение по текущей настройке. Главный поток. */
    static void kick() {
        if (GlassCfg.glass()) {
            if (!looping) {
                looping = true;
                MAIN.postDelayed(TICK, 250L);
            }
        } else {
            release("Не включено");
        }
    }

    /** Настройка изменилась (из экрана настроек): применить сразу. */
    static void refresh() {
        kick();
        GlassSession s = live;
        if (s != null) s.refresh();
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            long delay = 1000L;
            try {
                if (!GlassCfg.glass()) {
                    looping = false;
                    release(CrashGuard.tripped && GlassCfg.style == GlassCfg.GLASS
                            ? "Отключено защитой от вылетов" : "Не включено");
                    return;
                }
                delay = tick();
            } catch (Throwable t) {
                FlagStore.log(t);
                diag = "Сбой: " + t.getClass().getSimpleName();
                delay = 3000L;
            }
            MAIN.postDelayed(this, delay);
        }
    };

    private static long tick() {
        Activity a = ScreenWatcher.top();
        if (a == null) return 1500L;
        GlassSession s = live;
        if (s != null) {
            boolean same = a == liveAct.get();
            if (same && s.isAttached() && s.bar.isAttachedToWindow() && s.bar.getRootView() == a.getWindow().peekDecorView()) {
                diag = "Панель найдена: " + found;
                return 2500L;
            }
            release("Панель ушла с экрана, ищу снова");
        }
        View decor = a.getWindow().peekDecorView();
        if (decor == null || decor.getWindowVisibility() != View.VISIBLE) return 2000L;
        scans++;
        BarFinder.Found f = BarFinder.find(decor);
        if (f == null) {
            // на экранах без панели ищем всё реже: обход видов не бесплатный
            if (missedOn.get() != a) {
                missedOn = new WeakReference<>(a);
                misses = 0;
            }
            misses++;
            diag = "Панель вкладок на этом экране не нашёл (проверено рядов: " + BarFinder.lastConsidered
                    + ", видов: " + BarFinder.lastNodes + ")";
            return Math.min(6000L, 1500L + misses * 500L);
        }
        misses = 0;
        GlassSession ns = new GlassSession(f.bar, f.tabs, f.create, false);
        ns.attach();
        live = ns;
        liveAct = new WeakReference<>(a);
        found = f.how;
        diag = "Панель найдена: " + found;
        return 2500L;
    }

    private static void release(String why) {
        GlassSession s = live;
        live = null;
        liveAct = new WeakReference<>(null);
        if (s != null) {
            try {
                s.detach();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
        }
        diag = why;
    }

    // ------------------------------------------------------------ сведения

    static String status() {
        GlassSession s = live;
        StringBuilder sb = new StringBuilder(diag);
        if (s != null) sb.append("\n").append(s.details());
        return sb.toString();
    }

    static String details() {
        return "Нижняя панель: " + GlassCfg.summary() + ", Android " + android.os.Build.VERSION.SDK_INT
                + ", поисков " + scans + "\n" + status() + "\nнастройки: " + GlassCfg.toJson();
    }
}
