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
    /** Короткое объяснение для экрана «Огонёк»: что мод сейчас делает или чего ему не хватает. */
    private static volatile String hint = "";
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
        StreakHooks.discover(dry);
        StreakHooks.prepareSender();

        if (StreakHooks.service() == null) {
            last = "служба огоньков ещё не получена: откройте в TikTok список чатов";
            hint = "Откройте в TikTok список чатов: так мод увидит огоньки";
            return;
        }
        List<String> convs = StreakStore.conversations();
        if (convs.isEmpty()) {
            last = "ни одного чата ещё не видели: откройте в TikTok список чатов";
            hint = "Чатов с огоньком пока не видно: откройте в TikTok список чатов";
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
        hint = "Проверено чатов: " + convs.size() + " · к отправке сейчас: " + due.size() + " · сегодня уже: "
                + by[StreakLogic.Verdict.SENT_TODAY.ordinal()];

        if (dry) {
            last = (System.currentTimeMillis() - t0) + " мс, проверка без отправки: " + head;
            return;
        }
        if (due.isEmpty()) {
            last = "проход: " + head;
            StreakStore.flush();
            return;
        }
        if (!online()) {
            last = "нет сети, отправка отложена; " + head;
            hint += " · нет сети, отправка отложена";
            return;
        }
        Object sticker = StreakSticker.get();
        if (sticker == null) {
            last = "к отправке " + due.size() + ", но стикер не выбран: отправьте любой стикер в чате; " + head;
            hint += " · стикер не выбран";
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
        hint = "Отправлено: " + ok + (bad > 0 ? " · не удалось: " + bad : "") + " · сегодня уже: "
                + StreakStore.sentToday(System.currentTimeMillis(), tz);
    }

    /** Короткое состояние для экрана: по шагам, без технических подробностей. */
    static String status() {
        if (!Prefs.streakAuto) return "Автопродление выключено";
        boolean seen = StreakHooks.service() != null;
        boolean stk = StreakSticker.has() || !StreakSticker.chosenId().isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append(seen ? "✓ Огоньки видны" : "✗ Огоньки не видны: мод ищет службу сам, можно открыть в TikTok список чатов").append('\n');
        sb.append(stk ? "✓ Стикер выбран" : "✗ Стикер не выбран: без него ничего не отправится").append('\n');
        sb.append("Чатов известно: ").append(StreakStore.count()).append(" · отправлено сегодня: ")
                .append(StreakStore.sentToday(System.currentTimeMillis(), TimeZone.getDefault()));
        if (!hint.isEmpty()) sb.append('\n').append(hint);
        return sb.toString();
    }

    // ---------------------------------------------------------------- отправить всем сразу

    /** Не больше стольких чатов за одно нажатие: чтобы не завалить TikTok и не получить блокировку. */
    static final int ALL_MAX = 60;
    /** Остановка, если столько отправок подряд не удалось. */
    private static final int STOP_AFTER_FAILS = 3;
    private static volatile boolean busy;

    /** Обратный вызов в главном потоке. */
    interface Callback<T> {
        void on(T value);
    }

    /** Кому уйдёт стикер при нажатии «Отправить всем сейчас». */
    static final class Plan {
        /** Чаты, куда пойдёт стикер. */
        final List<String> targets;
        /** Сколько чатов известно всего. */
        final int known;
        /** Сколько из них не удалось прочитать. */
        final int unreadable;
        /** Огоньки прочитать не удалось: цели — все известные чаты. */
        final boolean guess;
        /** Чатов с огоньком было больше предела, часть отброшена. */
        final boolean capped;
        /** Что мешает (тогда targets пуст); иначе null. */
        final String problem;

        Plan(List<String> targets, int known, int unreadable, boolean guess, boolean capped, String problem) {
            this.targets = targets;
            this.known = known;
            this.unreadable = unreadable;
            this.guess = guess;
            this.capped = capped;
            this.problem = problem;
        }

        static Plan blocked(String why) {
            return new Plan(new ArrayList<String>(), 0, 0, false, false, why);
        }
    }

    static boolean busy() {
        return busy;
    }

    private static void toMain(final Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    /** Считает, кому отправлять; результат в главном потоке. */
    static void plan(final Callback<Plan> cb) {
        async(new Runnable() {
            @Override
            public void run() {
                Plan p;
                try {
                    p = makePlan();
                } catch (Throwable t) {
                    FlagStore.log(t);
                    p = Plan.blocked("Не удалось подготовить отправку: " + t.getClass().getSimpleName());
                }
                final Plan result = p;
                toMain(new Runnable() {
                    @Override
                    public void run() {
                        cb.on(result);
                    }
                });
            }
        });
    }

    private static synchronized Plan makePlan() {
        if (!Prefs.streakAuto) return Plan.blocked("Сначала включите автопродление и перезапустите TikTok");
        if (CrashGuard.tripped) return Plan.blocked("Автопродление отключено защитой от сбоев");
        if (StreakHooks.service() == null) {
            return Plan.blocked("Мод ещё не видит огоньков: откройте в TikTok список чатов и повторите");
        }
        if (StreakSticker.chosenId().isEmpty() && !StreakSticker.has()) {
            return Plan.blocked("Сначала выберите стикер");
        }
        if (StreakSticker.get() == null) {
            return Plan.blocked("Стикер выбран, но ещё не загружен: откройте «Стикер для огоньков» и нажмите "
                    + "«Обновить избранные»");
        }
        List<String> convs = StreakStore.conversations();
        if (convs.isEmpty()) {
            return Plan.blocked("Чатов пока не видно: откройте в TikTok список чатов и повторите");
        }
        List<String> hot = new ArrayList<>();
        int unreadable = 0;
        // Сначала по кэшу TikTok; если нигде огонька не видно, перечитываем заново.
        for (int pass = 0; pass < 2 && hot.isEmpty(); pass++) {
            hot.clear();
            unreadable = 0;
            for (String conv : convs) {
                StreakHooks.Info i = StreakHooks.read(conv, pass == 1);
                if (i == null || i.streak < 0) {
                    unreadable++;
                } else if (i.streak >= StreakLogic.FLAME_FROM) {
                    hot.add(conv);
                }
            }
        }
        boolean guess = hot.isEmpty() && unreadable == convs.size();
        List<String> targets = guess ? new ArrayList<>(convs) : hot;
        boolean capped = targets.size() > ALL_MAX;
        if (capped) targets = new ArrayList<>(targets.subList(0, ALL_MAX));
        if (targets.isEmpty()) {
            return new Plan(targets, convs.size(), unreadable, false, false,
                    "Чатов с огоньком не видно (проверено " + convs.size() + ")");
        }
        return new Plan(targets, convs.size(), unreadable, guess, capped, null);
    }

    /** Отправляет стикер по плану, в фоне и по одному; итог (одно предложение) в главном потоке. */
    static void sendAll(final Plan p, final Callback<String> cb) {
        synchronized (StreakRunner.class) {
            if (busy) {
                cb.on("Отправка уже идёт");
                return;
            }
            busy = true;
        }
        async(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    msg = execute(p);
                } catch (Throwable t) {
                    FlagStore.log(t);
                    msg = "Отправка прервалась: " + t.getClass().getSimpleName();
                } finally {
                    busy = false;
                }
                final String text = msg;
                toMain(new Runnable() {
                    @Override
                    public void run() {
                        cb.on(text);
                    }
                });
            }
        });
    }

    private static synchronized String execute(Plan p) {
        if (!Prefs.streakAuto) return "Автопродление выключено: отправка не начата";
        if (!online()) return "Нет сети: отправка не начата";
        Object sticker = StreakSticker.get();
        if (sticker == null) return "Стикер не выбран: отправка не начата";
        TimeZone tz = TimeZone.getDefault();
        int total = p.targets.size();
        int ok = 0;
        int bad = 0;
        int row = 0;
        boolean stopped = false;
        for (int i = 0; i < total; i++) {
            hint = "Отправляю всем: " + (i + 1) + " из " + total + "…";
            String conv = p.targets.get(i);
            long at = System.currentTimeMillis();
            if (StreakHooks.send(app, sticker, conv)) {
                StreakStore.markSent(conv, at);
                ok++;
                sends++;
                row = 0;
            } else {
                StreakStore.markFail(conv, at);
                bad++;
                fails++;
                if (++row >= STOP_AFTER_FAILS) {
                    stopped = true;
                    break;
                }
            }
            StreakStore.flush();
            if (i + 1 < total) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        StreakStore.flush();
        last = "отправка всем: отправлено " + ok + " из " + total + ", ошибок " + bad;
        hint = "Отправлено всем: " + ok + (bad > 0 ? " · не удалось: " + bad : "") + " · сегодня уже: "
                + StreakStore.sentToday(System.currentTimeMillis(), tz);
        String msg = "Отправлено " + ok + " из " + total;
        if (bad > 0) msg += ", не удалось " + bad;
        if (stopped) msg += ". Остановился после " + STOP_AFTER_FAILS + " ошибок подряд: " + StreakHooks.senderNote();
        return msg;
    }

    // ---------------------------------------------------------------- сведения для разработчика

    /** Сводка для копирования и пересылки: что нашлось и чего нет. Идентификаторов чатов в ней нет. */
    static String details() {
        StringBuilder sb = new StringBuilder();
        sb.append("TTMLonger ").append(Targets.VERSION).append(" · Android ").append(android.os.Build.VERSION.SDK_INT);
        try {
            if (app != null) {
                sb.append(" · TikTok ").append(app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName);
            }
        } catch (Throwable ignored) {
            // версия не обязательна
        }
        sb.append('\n');
        sb.append("автопродление: ").append(Prefs.streakAuto ? "вкл" : "выкл")
                .append(", каждый день: ").append(Prefs.streakDaily ? "да" : "нет").append('\n');
        sb.append(StreakHooks.details()).append('\n');
        sb.append(StickerLibrary.details()).append('\n');
        sb.append(StreakSticker.details()).append('\n');
        sb.append("чатов известно ").append(StreakStore.count()).append(", проходов ").append(rounds)
                .append(", отправок ").append(sends).append(", ошибок ").append(fails).append('\n');
        sb.append("последнее: ").append(last);
        return sb.toString();
    }
}
