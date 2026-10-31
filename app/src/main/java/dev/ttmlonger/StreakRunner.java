package dev.ttmlonger;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Проходы автопродления огонька. Работает в фоновом потоке, пока жив процесс TikTok: на старте
 * (когда TikTok сам спросил про огоньки), при появлении нового чата и каждые десять минут. Пока
 * TikTok выгружен из памяти, ничего не происходит: мод не ставит будильников и не будит приложение.
 */
final class StreakRunner {
    private static final long PERIOD_MS = 10L * 60L * 1000L;
    private static final long FIRST_MS = 90L * 1000L;
    private static final int MAX_LOG = 40;

    private static Context app;
    private static Handler handler;
    private static boolean pending;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final Runnable DAILY = new Runnable() {
        @Override
        public void run() {
            try {
                if (Prefs.streakAuto) round(false);
            } catch (Throwable e) {
                FlagStore.log(e);
            }
            reschedule();
        }
    };
    private static volatile String last = "проходов ещё не было";
    private static volatile int rounds;
    private static volatile int sends;
    private static volatile int fails;

    private StreakRunner() {
    }

    static synchronized void init(Context ctx) {
        if (handler != null || ctx == null) return;
        app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        HandlerThread t = new HandlerThread("ttmlonger-streak", Process.THREAD_PRIORITY_BACKGROUND);
        t.setDaemon(true);
        t.start();
        handler = new Handler(t.getLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Prefs.streakAuto) round(false);
                } catch (Throwable e) {
                    FlagStore.log(e);
                }
                handler.postDelayed(this, PERIOD_MS);
            }
        }, FIRST_MS);
        reschedule();
    }

    /**
     * Ставит проход точно на выбранную минуту (режим «каждый день»). Вызывается при запуске, после
     * каждого такого прохода и когда меняется время или режим. Если TikTok в этот момент спит,
     * проход случится при пробуждении: время к тому моменту уже наступило.
     */
    static synchronized void reschedule() {
        Handler h = handler;
        if (h == null) return;
        h.removeCallbacks(DAILY);
        if (Prefs.streakAuto && Prefs.streakDaily) {
            long wait = StreakLogic.millisUntil(System.currentTimeMillis(), Prefs.streakMinute, TimeZone.getDefault());
            h.postDelayed(DAILY, wait + 2000L);
        }
    }

    /** Выполнить в фоне (запись файлов и тому подобное). */
    static void async(Runnable r) {
        Handler h = handler;
        if (h != null) {
            h.post(r);
        } else {
            Thread t = new Thread(r, "ttmlonger-streak-io");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Выполнить в фоне через delayMs. */
    static void later(final Runnable r, long delayMs) {
        Handler h = handler;
        if (h != null) {
            h.postDelayed(r, delayMs);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    async(r);
                }
            }, delayMs);
        }
    }

    /** Запросить проход через delayMs; повторные запросы, пока один ждёт, склеиваются. */
    static synchronized void request(long delayMs) {
        Handler h = handler;
        if (h == null || pending || !Prefs.streakAuto) return;
        pending = true;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (StreakRunner.class) {
                    pending = false;
                }
                try {
                    round(false);
                } catch (Throwable e) {
                    FlagStore.log(e);
                    note("проход: " + e);
                }
            }
        }, delayMs);
    }

    /** Проверка без отправки: для кнопки на экране. done вызывается на главном потоке. */
    static void check(final Runnable done) {
        Runnable job = new Runnable() {
            @Override
            public void run() {
                try {
                    round(true);
                } catch (Throwable e) {
                    FlagStore.log(e);
                    last = "проверка: " + e;
                }
                new Handler(Looper.getMainLooper()).post(done);
            }
        };
        async(job);
    }

    static void note(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        synchronized (LOG) {
            if (LOG.size() >= MAX_LOG) LOG.removeFirst();
            LOG.addLast(line);
        }
        Diag.note("огонёк: " + s);
    }

    static String summary() {
        return last;
    }

    // ---------------------------------------------------------------- проход

    private static boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Один проход по известным чатам.
     *
     * @param dry true — только прочитать и посчитать, ничего не отправлять
     */
    static synchronized void round(boolean dry) {
        if (!dry && (!Prefs.streakAuto || CrashGuard.tripped)) return;
        long t0 = System.currentTimeMillis();
        StreakHooks.prepareSender();

        if (StreakHooks.service() == null) {
            last = "служба огоньков ещё не получена: откройте в TikTok список чатов";
            return;
        }
        List<String> convs = StreakStore.conversations();
        if (convs.isEmpty()) {
            last = "ни одного чата ещё не видели: откройте в TikTok список чатов";
            return;
        }

        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        int[] by = new int[StreakLogic.Verdict.values().length];
        int unreadable = 0;
        Map<String, Integer> statuses = new TreeMap<>();
        List<String> due = new ArrayList<>();
        for (String conv : convs) {
            StreakHooks.Info i = StreakHooks.read(conv, false);
            if (i == null || i.streak < 0) {
                unreadable++;
                continue;
            }
            if (i.streak >= StreakLogic.FLAME_FROM) {
                String key = i.status == null ? "?" : i.status;
                Integer c = statuses.get(key);
                statuses.put(key, c == null ? 1 : c + 1);
            }
            StreakLogic.Verdict v = StreakLogic.decide(Prefs.streakDaily, Prefs.streakMinute, i.status, i.streak,
                    StreakStore.lastSent(conv), StreakStore.lastFail(conv), now, tz);
            by[v.ordinal()]++;
            if (v == StreakLogic.Verdict.SEND) due.add(conv);
        }

        String head = "чатов " + convs.size() + ": к отправке " + due.size()
                + ", сегодня уже " + by[StreakLogic.Verdict.SENT_TODAY.ordinal()]
                + ", живых " + by[StreakLogic.Verdict.LIT.ordinal()]
                + ", без огонька " + by[StreakLogic.Verdict.NO_FLAME.ordinal()]
                + ", ждут после ошибки " + by[StreakLogic.Verdict.BACKOFF.ordinal()]
                + ", статус неизвестен " + by[StreakLogic.Verdict.UNKNOWN.ordinal()]
                + ", другой статус " + by[StreakLogic.Verdict.GONE.ordinal()]
                + ", не прочитано " + unreadable
                + (statuses.isEmpty() ? "" : "; статусы " + statuses);
        rounds++;

        if (dry) {
            last = (System.currentTimeMillis() - t0) + " мс, проверка без отправки: " + head;
            note(last);
            return;
        }
        if (due.isEmpty()) {
            last = "проход: " + head;
            StreakStore.flush();
            return;
        }
        if (!online()) {
            last = "нет сети, отправка отложена; " + head;
            note(last);
            return;
        }
        Object sticker = StreakSticker.get();
        if (sticker == null) {
            last = "к отправке " + due.size() + ", но стикер не выбран: отправьте любой стикер в чате; " + head;
            note(last);
            return;
        }

        int ok = 0;
        int bad = 0;
        int done = 0;
        for (String conv : due) {
            if (done++ >= StreakLogic.MAX_PER_ROUND) break;
            if (!Prefs.streakAuto) break;
            // Перед отправкой читаем огонёк заново: серый из кэша стикер вызывать не должен.
            StreakHooks.Info fresh = StreakHooks.read(conv, true);
            long at = System.currentTimeMillis();
            if (fresh == null || fresh.streak < 0) {
                bad++;
                continue;
            }
            StreakLogic.Verdict again = StreakLogic.decide(Prefs.streakDaily, Prefs.streakMinute, fresh.status,
                    fresh.streak, StreakStore.lastSent(conv), StreakStore.lastFail(conv), at, tz);
            if (again != StreakLogic.Verdict.SEND) continue;
            if (StreakHooks.send(app, sticker, conv)) {
                StreakStore.markSent(conv, at);
                ok++;
                sends++;
            } else {
                StreakStore.markFail(conv, at);
                bad++;
                fails++;
            }
            StreakStore.flush();
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                break;
            }
        }
        StreakStore.flush();
        last = "отправлено " + ok + ", ошибок " + bad + "; " + head;
        note(last);
    }

    // ---------------------------------------------------------------- отчёт

    static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Автопродление огонька: ").append(Prefs.streakAuto ? "включено" : "выключено")
                .append(Prefs.streakDaily ? ", ежедневно с " + StreakLogic.formatMinute(Prefs.streakMinute)
                        : ", только гаснущие").append('\n');
        sb.append(StreakHooks.report());
        sb.append("  стикер: ").append(StreakSticker.status()).append('\n');
        sb.append(StickerLibrary.report());
        sb.append("  чатов известно: ").append(StreakStore.count()).append(", сегодня отправлено записей: ")
                .append(StreakStore.sentToday(System.currentTimeMillis(), TimeZone.getDefault()))
                .append(", проходов ").append(rounds).append(", отправок ").append(sends)
                .append(", ошибок ").append(fails).append('\n');
        sb.append("  последний проход: ").append(last).append('\n');
        synchronized (LOG) {
            for (String l : LOG) sb.append("  ").append(l).append('\n');
        }
        return sb.toString();
    }
}
