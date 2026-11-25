package dev.ttmlonger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/** Serialises checks and sticker submissions on a worker in the TikTok process. */
final class StreakRunner {
    private static final long PERIOD_MS = 15L * 60L * 1000L;
    private static final long FIRST_MS = 90L * 1000L;
    private static final int MAX_LOG = 40;
    /** Свой сигнал будильника: проход в выбранное время, даже если телефон спал. */
    private static final String ALARM_ACTION = "dev.ttmlonger.STREAK_ALARM";
    private static final int ALARM_CODE = 7301;
    /** Не чаще раза в две минуты запускать проход от включения экрана. */
    private static final long WAKE_GAP_MS = 120_000L;
    private static long lastWake = -WAKE_GAP_MS;

    private static Context app;
    private static volatile Handler handler;
    private static StreakSchedule schedule;
    private static long requestedAt=Long.MAX_VALUE;
    private static final Runnable REQUESTED=()->{synchronized(StreakRunner.class){requestedAt=Long.MAX_VALUE;}try{round(false);}catch(Throwable e){FlagStore.log(e);}};
    private static volatile long nextScheduledAt;
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
    private static volatile long autoHoldUntil;
    static final class Overview {
        final String owner;final int active,grey,sent,unknown,due;final long at;
        Overview(String o,int a,int g,int s,int u,int d,long t){owner=o;active=a;grey=g;sent=s;unknown=u;due=d;at=t;}
    }
    private static volatile Overview overview;
    static Overview overview(String owner){Overview o=overview;return owner!=null&&o!=null&&owner.equals(o.owner)?o:null;}
    static long nextScheduledAt(){return nextScheduledAt;}
    static long holdRemaining(){return Math.max(0,autoHoldUntil-SystemClock.elapsedRealtime());}

    private StreakRunner() {
    }

    static synchronized void init(Context ctx) {
        if (handler != null || ctx == null) return;
        app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        schedule = new StreakSchedule(app);
        HandlerThread t = new HandlerThread("ttmlonger-streak", Process.THREAD_PRIORITY_BACKGROUND);
        t.setDaemon(true);
        t.start();
        handler = new Handler(t.getLooper());
        registerWake(app);
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
        long now=System.currentTimeMillis();
        long wait=Prefs.streakAuto?schedule.nextWait(StreakHooks.liveOwner(),now,Zones.current()):Long.MAX_VALUE;
        nextScheduledAt=wait==Long.MAX_VALUE?0:now+wait+2000L;
        if(wait!=Long.MAX_VALUE){
            // Задержка Handler считается по времени работы процессора и во сне не идёт: сам по себе он
            // сработает позже срока. Поэтому рядом ставится будильник по настоящему времени, а на включение
            // экрана и смену времени или пояса проход запрашивается заново (registerWake).
            h.postDelayed(DAILY, wait + 2000L);
            setAlarm(now + wait + 2000L);
        } else {
            cancelAlarm();
        }
    }

    // ---------------------------------------------------------------- пробуждение по настоящему времени

    private static PendingIntent alarmIntent() {
        Intent i = new Intent(ALARM_ACTION).setPackage(app.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(app, ALARM_CODE, i, flags);
    }

    /** Неточный будильник без особых разрешений: сработает в пределах нескольких минут даже во сне. */
    private static void setAlarm(long atMs) {
        try {
            if (app == null) return;
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static void cancelAlarm() {
        try {
            if (app == null) return;
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(alarmIntent());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static void registerWake(Context c) {
        try {
            BroadcastReceiver system = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onWake(intent == null ? null : intent.getAction());
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_DATE_CHANGED);
            c.registerReceiver(system, f);

            BroadcastReceiver alarm = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onWake(ALARM_ACTION);
                }
            };
            IntentFilter af = new IntentFilter(ALARM_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(alarm, af, Context.RECEIVER_NOT_EXPORTED);
            } else {
                c.registerReceiver(alarm, af);
            }
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    /**
     * Телефон проснулся или поменялось время: старые задержки Handler устарели, а выбранное время уже могло
     * наступить. Расписание ставится заново, проход проверяет настоящее время.
     */
    private static void onWake(String action) {
        try {
            Handler h = handler;
            if (h == null || CrashGuard.tripped || !Prefs.streakAuto) return;
            if (ALARM_ACTION.equals(action)) {
                h.post(DAILY);
                return;
            }
            boolean clock = Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                    || Intent.ACTION_DATE_CHANGED.equals(action);
            long t = SystemClock.elapsedRealtime();
            if (!clock && t - lastWake < WAKE_GAP_MS) return;
            lastWake = t;
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) java.util.TimeZone.setDefault(null);
            reschedule();
            request(clock ? 2000L : 6000L);
        } catch (Throwable e) {
            FlagStore.log(e);
        }
    }

    private static String timeStamp() {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(Zones.current());
        return f.format(new Date());
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
        Handler h=handler;if(h==null||!Prefs.streakAuto)return;
        long delay=Math.max(0,delayMs);long at=SystemClock.uptimeMillis()+delay;
        if(at>=requestedAt)return;
        h.removeCallbacks(REQUESTED);requestedAt=at;
        if(!h.postAtTime(REQUESTED,at))requestedAt=Long.MAX_VALUE;
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
                    hint = "Не удалось прочитать огоньки: " + e.getClass().getSimpleName();
                    last = hint;
                }
                new Handler(Looper.getMainLooper()).post(done);
            }
        };
        async(job);
    }

    static void note(String s) {
        String line = timeStamp() + "  " + s;
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
    static void round(boolean dry) {
        if (CrashGuard.tripped || (!dry && !Prefs.streakAuto)) return;
        if(!dry&&holdRemaining()>0){hint="Автоотправка временно приостановлена после ошибок";request(holdRemaining());return;}
        if (schedule == null) {
            hint = "Мод ещё подключается к TikTok. Повторите проверку.";
            return;
        }
        String owner = StreakHooks.liveOwner();
        if (owner == null) {
            hint = "Откройте входящие TikTok, чтобы определить текущий аккаунт.";
            return;
        }
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
        convs.removeIf(conv -> StreakLogic.friendUid(conv, owner) == null);
        int known = convs.size();
        convs = StreakLogic.filterPicked(Prefs.streakOnlyPicked, StreakStore.picked(), convs);
        if (convs.isEmpty()) {
            last = "включено «только выбранным», но никто не отмечен (известно чатов " + known + ")";
            hint = known == 0 ? "Нет известных чатов текущего аккаунта" : "Отметьте людей в разделе «Кому продлевать»";
            return;
        }

        TimeZone tz = Zones.current();
        long now = System.currentTimeMillis();
        int[] by = new int[StreakLogic.Verdict.values().length];
        int unreadable = 0;
        Map<String, Integer> statuses = new TreeMap<>();
        List<String> due = new ArrayList<>();
        Map<String,StreakHooks.Info> priorities=new java.util.HashMap<>();
        int activeCount=0,greyCount=0;
        for (String conv : convs) {
            int minute=schedule.effective(owner,conv);
            StreakHooks.Info i = StreakHooks.read(conv, true);
            if (i == null || i.streak < 0) {
                unreadable++;
                continue;
            }
            if (i.streak >= StreakLogic.FLAME_FROM) {
                if(StreakLogic.ACTIVE.equals(i.status))activeCount++;else if(StreakLogic.isGrey(i.status))greyCount++;
                String key = i.status == null ? "?" : i.status;
                Integer c = statuses.get(key);
                statuses.put(key, c == null ? 1 : c + 1);
            }
            StreakLogic.Verdict v = StreakLogic.decide(minute >= 0, minute, i.status, i.streak,
                    schedule.last(owner, minute, conv), StreakStore.lastFail(conv), now, tz);
            by[v.ordinal()]++;
            if (v == StreakLogic.Verdict.SEND){due.add(conv);priorities.put(conv,i);}
        }

        due.sort((a,b)->{StreakHooks.Info x=priorities.get(a),y=priorities.get(b);return StreakLogic.order(x.status,x.streak,a,y.status,y.streak,b);});
        overview=new Overview(owner,activeCount,greyCount,by[StreakLogic.Verdict.SENT_TODAY.ordinal()],unreadable+by[StreakLogic.Verdict.UNKNOWN.ordinal()],due.size(),System.currentTimeMillis());
        String head = "чатов " + convs.size() + (convs.size() != known ? " из " + known + " (выбранные)" : "")
                + ": к отправке " + due.size()
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
            request(120_000L);return;
        }
        Object sticker = StreakSticker.get();
        if (sticker == null) {
            last = "к отправке " + due.size() + ", но стикер не выбран: отправьте любой стикер в чате; " + head;
            hint += " · стикер не выбран";
            return;
        }

        int ok = 0;
        int bad = 0;
        java.util.List<String> greySubmitted=new java.util.ArrayList<>();
        int done = 0,consecutive=0;boolean networkLost=false;
        for (String conv : due) {
            if (done++ >= StreakLogic.MAX_PER_ROUND) break;
            if(!online()){networkLost=true;break;}
            hint="Автопродление: "+done+" из "+Math.min(due.size(),StreakLogic.MAX_PER_ROUND);
            if (!Prefs.streakAuto || !owner.equals(StreakHooks.liveOwner())) break;
            int minute=schedule.effective(owner,conv);
            if (Prefs.streakOnlyPicked && !StreakStore.isPicked(conv)) continue;
            // Перед отправкой читаем огонёк заново: серый из кэша стикер вызывать не должен.
            StreakHooks.Info fresh = StreakHooks.read(conv, true);
            long at = System.currentTimeMillis();
            if (fresh == null || fresh.streak < 0) {
                bad++;
                continue;
            }
            StreakLogic.Verdict again = StreakLogic.decide(minute >= 0, minute, fresh.status,
                    fresh.streak, schedule.last(owner, minute, conv), StreakStore.lastFail(conv), at, tz);
            if (again != StreakLogic.Verdict.SEND||schedule.effective(owner,conv)!=minute) continue;
            if (StreakHooks.send(app, sticker, conv, owner, true)) {
                schedule.submitted(owner, minute, conv, at);
                if(StreakLogic.isGrey(fresh.status))greySubmitted.add(conv);
                ok++;
                sends++;
                consecutive=0;
            } else {
                StreakStore.markFail(conv, at);
                bad++;
                fails++;
                if(++consecutive>=STOP_AFTER_FAILS){autoHoldUntil=SystemClock.elapsedRealtime()+30*60_000L;break;}
            }
            StreakStore.flush();
            try{Thread.sleep(650L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        StreakStore.flush();
        int confirmed=verifyGrey(owner,greySubmitted);
        last = "передано " + ok + ", подтверждено " + confirmed + ", ошибок " + bad + "; " + head;
        hint = "Передано TikTok: " + ok + " · серые огоньки стали активными: "+confirmed
                + " из "+greySubmitted.size()+(bad > 0 ? " · ошибок: " + bad : "");
        if(holdRemaining()>0){hint+=" · пауза 30 минут после трёх ошибок подряд";request(holdRemaining());}
        else if(networkLost){hint+=" · сеть потеряна, повторная проверка через 2 минуты";request(120_000L);}
        else if (bad > 0 || due.size() > StreakLogic.MAX_PER_ROUND) request(StreakLogic.FAIL_BACKOFF_MS);
    }

    /** Read-only verification, at most two passes; never resubmit on unconfirmed result. */
    private static int verifyGrey(String owner,java.util.List<String> sent) {
        java.util.List<String> pending=new java.util.ArrayList<>(sent);
        for(int pass=0;pass<2 && !pending.isEmpty();pass++) {
            if(CrashGuard.tripped || !owner.equals(StreakHooks.liveOwner()))break;
            try{Thread.sleep(3000L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            for(java.util.Iterator<String> it=pending.iterator();it.hasNext();) {
                if(CrashGuard.tripped || !owner.equals(StreakHooks.liveOwner()))return sent.size()-pending.size();
                StreakHooks.Info info=StreakHooks.read(it.next(),true);
                if(info!=null && StreakLogic.ACTIVE.equals(info.status) && info.streak>=StreakLogic.FLAME_FROM)it.remove();
            }
        }
        return sent.size()-pending.size();
    }

    static String checkSummary() {
        return hint.isEmpty() ? "Проверка завершена" : hint;
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
        final String owner;
        /** Чатов с огоньком было больше предела, часть отброшена. */
        final boolean capped;
        /** Что мешает (тогда targets пуст); иначе null. */
        final String problem;

        Plan(List<String> targets, int known, int unreadable, String owner, boolean capped, String problem) {
            this.targets = targets;
            this.known = known;
            this.unreadable = unreadable;
            this.owner = owner;
            this.capped = capped;
            this.problem = problem;
        }

        static Plan blocked(String why) {
            return new Plan(new ArrayList<String>(), 0, 0, null, false, why);
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

    private static Plan makePlan() {
        String owner = StreakHooks.liveOwner();
        if (owner == null || schedule == null) return Plan.blocked("Откройте входящие TikTok и повторите проверку");
        StreakHooks.discover(true);
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
        convs = StreakLogic.filterPicked(Prefs.streakOnlyPicked, StreakStore.picked(), convs);
        if (convs.isEmpty()) {
            return Plan.blocked("Включено «только выбранным», но никто не отмечен: откройте «Люди» и поставьте галочки");
        }
        List<String> hot = new ArrayList<>();
        int unreadable = 0;
        for (String conv : convs) {
            if (StreakLogic.friendUid(conv, owner) == null) continue;
            StreakHooks.Info info = StreakHooks.read(conv, true);
            if (info == null || info.streak < 0 || info.status == null) {
                unreadable++;
            } else if (StreakLogic.manualVerdict(info.status,info.streak,schedule.lastAny(owner,conv),
                    StreakStore.lastFail(conv),System.currentTimeMillis(),Zones.current())==StreakLogic.Verdict.SEND) {
                hot.add(conv);
            }
        }
        List<String> targets = hot;
        boolean capped = targets.size() > ALL_MAX;
        if (capped) targets = new ArrayList<>(targets.subList(0, ALL_MAX));
        if (targets.isEmpty()) {
            return new Plan(targets, convs.size(), unreadable, owner, false,
                    "Нет серий для отправки сейчас: возможно, сегодня уже отправляли или действует пауза после ошибки (проверено " + convs.size() + ")");
        }
        return new Plan(targets, convs.size(), unreadable, owner, capped, null);
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

    private static String execute(Plan p) {
        String owner = p.owner;
        if (owner == null || !owner.equals(StreakHooks.liveOwner()) || CrashGuard.tripped)
            return "Аккаунт изменился или сработала защита. Откройте список заново.";
        if (!online()) return "Нет сети: отправка не начата";
        Object sticker = StreakSticker.get();
        if (sticker == null) return "Стикер не выбран: отправка не начата";
        int total = p.targets.size();
        int ok = 0;
        int bad = 0;
        int row = 0;
        java.util.List<String> greySubmitted=new java.util.ArrayList<>();
        boolean stopped = false;
        for (int i = 0; i < total; i++) {
            hint = "Отправляю всем: " + (i + 1) + " из " + total + "…";
            String conv = p.targets.get(i);
            long at = System.currentTimeMillis();
            if (!owner.equals(StreakHooks.liveOwner()) || CrashGuard.tripped) break;
            if (Prefs.streakOnlyPicked && !StreakStore.isPicked(conv)) continue;
            StreakHooks.Info fresh = StreakHooks.read(conv, true);
            if (fresh == null || StreakLogic.manualVerdict(fresh.status,fresh.streak,
                    schedule.lastAny(owner,conv),StreakStore.lastFail(conv),at,Zones.current())!=StreakLogic.Verdict.SEND) continue;
            if (StreakHooks.send(app, sticker, conv, owner, false)) {
                schedule.submitted(owner, StreakSchedule.WHEN_GREY, conv, at);
                if(StreakLogic.isGrey(fresh.status))greySubmitted.add(conv);
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
                    Thread.sleep(600L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        StreakStore.flush();
        int confirmed=verifyGrey(owner,greySubmitted);
        int skipped = Math.max(0,total-ok-bad);
        last = "Передано TikTok " + ok + " из " + total + ", ошибок " + bad + ", пропущено " + skipped;
        hint = "Передано TikTok: " + ok + " из " + total + (bad > 0 ? " · ошибок: " + bad : "")
                + (skipped>0 ? " · не отправлено: "+skipped : "") + ". Серые огоньки стали активными: "+confirmed+" из "+greySubmitted.size()+". Остальные проверьте в чатах.";
        String msg = hint;

        if (stopped) msg += ". Остановился после " + STOP_AFTER_FAILS + " ошибок подряд: " + StreakHooks.senderNote();
        return msg;
    }

    // ---------------------------------------------------------------- список людей с серией

    /** Один человек в списке: чат, имя, серия и статус. */
    static final class Person {
        final String conv;
        final String name;
        final int streak;
        final String status;
        final boolean sentToday;

        Person(String conv, String name, int streak, String status, boolean sentToday) {
            this.conv = conv;
            this.name = name;
            this.streak = streak;
            this.status = status;
            this.sentToday = sentToday;
        }
    }

    /** Итог чтения для экрана «Люди». */
    static final class People {
        final List<Person> list;
        final int known;
        final int unreadable;
        /** Что мешает прочитать (тогда список пуст), иначе null. */
        final String problem;

        People(List<Person> list, int known, int unreadable, String problem) {
            this.list = list;
            this.known = known;
            this.unreadable = unreadable;
            this.problem = problem;
        }
    }

    /** Сколько имён друзей запомнено (для сведений, самих имён там нет). */
    static int namesKnown() {
        int n = 0;
        for (String conv : StreakStore.conversations()) {
            String uid = StreakLogic.friendUid(conv, ownForNames());
            if (uid != null && StreakStore.name(uid) != null) n++;
        }
        return n;
    }

    private static String ownForNames() {
        String own = StreakHooks.knownOwnUid();
        return own != null ? own : StreakLogic.commonUid(StreakStore.conversations());
    }

    /** Читает огоньки известных чатов в фоне; результат в главном потоке. */
    static void people(final Callback<People> cb) {
        async(new Runnable() {
            @Override
            public void run() {
                People p;
                try {
                    p = makePeople();
                } catch (Throwable t) {
                    FlagStore.log(t);
                    p = new People(new ArrayList<Person>(), 0, 0, "Не удалось прочитать огоньки: " + t.getClass().getSimpleName());
                }
                final People result = p;
                toMain(new Runnable() {
                    @Override
                    public void run() {
                        cb.on(result);
                    }
                });
            }
        });
    }

    private static People makePeople() {
        String own = StreakHooks.liveOwner();
        if (own == null || schedule == null || CrashGuard.tripped) {
            return new People(new ArrayList<Person>(), 0, 0, "Откройте входящие TikTok и обновите список.");
        }
        StreakHooks.discover(false);
        if (StreakHooks.service() == null) {
            return new People(new ArrayList<Person>(), 0, 0,
                    "Мод ещё не видит огоньков: откройте в TikTok список чатов и повторите");
        }
        List<String> convs = StreakStore.conversations();
        if (convs.isEmpty()) {
            return new People(new ArrayList<Person>(), 0, 0, "Чатов пока не видно: откройте в TikTok список чатов");
        }
        long now = System.currentTimeMillis();
        TimeZone tz = Zones.current();
        List<Person> out = new ArrayList<>();
        int unreadable = 0;
        for (String conv : convs) {
            if (StreakLogic.friendUid(conv, own) == null) continue;
            StreakHooks.Info i = StreakHooks.read(conv, true);
            if (i == null || i.streak < 0) {
                unreadable++;
                continue;
            }
            if (i.streak < StreakLogic.FLAME_FROM) continue;
            String uid = StreakLogic.friendUid(conv, own);
            String name = StreakLogic.displayName(StreakStore.name(uid), uid);
            long sentAt = schedule.lastAny(own, conv);
            out.add(new Person(conv, name, i.streak, i.status, sentAt > 0 && StreakLogic.sameDay(sentAt, now, tz)));
        }
        java.util.Collections.sort(out, new java.util.Comparator<Person>() {
            @Override
            public int compare(Person a, Person b) {
                return StreakLogic.order(a.status, a.streak, a.name, b.status, b.streak, b.name);
            }
        });
        return new People(out, convs.size(), unreadable, null);
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
                .append(", расписание: ").append(schedule == null ? -1 : schedule.minute(StreakHooks.knownOwnUid())).append('\n');
        sb.append("часовой пояс расписания: ").append(Zones.note()).append(" · по умолчанию в процессе: ")
                .append(TimeZone.getDefault().getID()).append('\n');
        sb.append(StreakHooks.details()).append('\n');
        sb.append(StickerLibrary.details()).append('\n');
        sb.append(StreakSticker.details()).append('\n');
        sb.append("только выбранным: ").append(Prefs.streakOnlyPicked ? "да" : "нет").append(", отмечено ")
                .append(StreakStore.pickedCount()).append(", имён друзей запомнено ").append(namesKnown()).append('\n');
        sb.append("чатов известно ").append(StreakStore.count()).append(", проходов ").append(rounds)
                .append(", отправок ").append(sends).append(", ошибок ").append(fails).append('\n');
        sb.append("последнее: ").append(last);
        return sb.toString();
    }
}
