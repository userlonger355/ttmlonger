package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** CI-only UI checks, compiled only into the isolated patched runtime test APK. */
final class RuntimeUiChecks {
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final String TAG = "TTMRuntimeUI";
    private static int fails, passes, skips;
    private static Activity activity;
    private static Dialog menu, glass;
    private static ViewGroup preview;
    private static GlassSession session;
    private static int oldStyle;
    private static final String[][] ROWS = {
        {"region","Регион"}, {"interface","Интерфейс и конфиденциальность"},
        {"appearance","Внешний вид"}, {"metadata","Время и регион видео"},
        {"unlocks","Возможности"}, {"badges","Бейджики"}, {"privacy","Невидимка"},
        {"ads","Реклама и LIVE"}, {"downloads","Скачивание"},
        {"video","Видео и истории"}, {"stickers","Стикеры"}, {"streaks","Огоньки"},
        {"flags","Настройки разработчика TikTok"}, {"recon","Разведка кода"},
        {"requests","Перехват запросов"}
    };
    static void schedule(Activity a) {
        if (!started.compareAndSet(false, true)) return;
        activity = a;
        main.postDelayed(RuntimeUiChecks::start, 3500);
    }
    private static void result(String id, boolean ok) {
        if (ok) passes++; else fails++;
        Log.i(TAG, "RESULT " + id + " " + (ok ? "PASS" : "FAIL"));
    }
    private static void skip(String id) {
        skips++;
        Log.i(TAG, "RESULT " + id + " SKIP");
    }
    private static void stage(String id) { Log.i(TAG, "STAGE " + id); }
    @SuppressWarnings("unchecked")
    private static ArrayList<Dialog> dialogs() throws Exception {
        Field f = Screen.class.getDeclaredField("OWN");
        f.setAccessible(true);
        Set<Dialog> own = (Set<Dialog>) f.get(null);
        ArrayList<Dialog> list = new ArrayList<>();
        synchronized (own) {
            for (Dialog d : own) if (d != null && d.isShowing()) list.add(d);
        }
        return list;
    }
    private static Dialog newest(ArrayList<Dialog> before) throws Exception {
        for (Dialog d : dialogs()) if (!before.contains(d)) return d;
        return null;
    }
    private static View text(View v, String label) {
        if (v instanceof TextView && label.contentEquals(((TextView) v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i=0;i<g.getChildCount();i++) {
                View found = text(g.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static boolean click(Dialog d, String label) {
        if (d == null || d.getWindow() == null) return false;
        View v = text(d.getWindow().getDecorView(), label);
        while (v != null) {
            if (v.hasOnClickListeners() && v.performClick()) return true;
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
    private static ViewGroup preview(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            if (g.getClass().getName().endsWith("GlassBarScreen$PreviewBar")) return g;
            for (int i=0;i<g.getChildCount();i++) {
                ViewGroup b=preview(g.getChildAt(i));
                if (b!=null) return b;
            }
        }
        return null;
    }
    private static void start() {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) { skip("activity_unavailable"); finish(); return; }
            ArrayList<Dialog> before=dialogs();
            ModMenu.show(activity);
            menu=newest(before);
            result("menu_dialog",menu!=null && text(menu.getWindow().getDecorView(),"Настройки мода")!=null);
            stage("menu");
            main.postDelayed(() -> row(0), 1800);
        } catch(Throwable e) { fail("menu_exception",e); finish(); }
    }
    private static void row(int i) {
        if (i>=ROWS.length) { glassStart(); return; }
        String id=ROWS[i][0], label=ROWS[i][1];
        try {
            ArrayList<Dialog> before=dialogs();
            boolean clicked=click(menu,label);
            main.postDelayed(() -> {
                Dialog opened=null;
                try {
                    opened=newest(before);
                    result("screen_"+id,clicked && opened!=null && opened.getWindow()!=null);
                    if(opened!=null) stage(id);
                } catch(Throwable e) { fail("screen_"+id,e); }
                final Dialog close=opened;
                main.postDelayed(() -> {
                    if(close!=null) close.dismiss();
                    main.postDelayed(() -> row(i+1), 250);
                }, 1600);
            }, 400);
        } catch(Throwable e) { fail("screen_"+id,e); main.postDelayed(() -> row(i+1),250); }
    }
    private static void glassStart() {
        try {
            oldStyle=GlassCfg.style;
            ArrayList<Dialog> before=dialogs();
            boolean clicked=click(menu,"Нижняя панель");
            glass=newest(before);
            result("glass_screen",clicked && glass!=null);
            if(glass==null) { finish(); return; }
            click(glass,"Оригинал");
            result("glass_original",GlassCfg.style==GlassCfg.ORIGINAL);
            stage("glass-original");
            main.postDelayed(RuntimeUiChecks::glassEnable,1600);
        } catch(Throwable e) { fail("glass_screen_exception",e); finish(); }
    }
    private static void glassEnable() {
        try {
            result("glass_enable",click(glass,"Liquid Glass") && GlassCfg.style==GlassCfg.GLASS);
            main.postDelayed(() -> {
                try {
                    preview=preview(glass.getWindow().getDecorView());
                    if(preview!=null) {
                        Field f=preview.getClass().getDeclaredField("session");
                        f.setAccessible(true);
                        session=(GlassSession)f.get(preview);
                    }
                    result("glass_preview_attach",session!=null && session.isAttached());
                    if(session==null || preview.getChildCount()!=5) { finish(); return; }
                    Log.i(TAG,"GLASS_RENDER "+session.shaderNote()+" / "+session.bkStatus());
                    stage("glass-enabled");
                    main.postDelayed(() -> tab(0),1800);
                } catch(Throwable e) { fail("glass_preview_exception",e); finish(); }
            },1600);
        } catch(Throwable e) { fail("glass_enable_exception",e); finish(); }
    }
    private static final int[] TABS={0,1,3,4,0};
    private static void tab(int i) {
        if(i==TABS.length) { drag(); return; }
        try {
            int index=TABS[i];
            View v=preview.getChildAt(index);
            boolean click=v.performClick();
            main.postDelayed(() -> {
                result("glass_tab_"+i,click && v.isSelected());
                stage("glass-tab-"+index+"-"+i);
                main.postDelayed(() -> tab(i+1),1300);
            },450);
        } catch(Throwable e) { fail("glass_tab_"+i,e); finish(); }
    }
    private static void touch(int action,long down,float x,float y) {
        MotionEvent ev=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        try { session.onTouch(ev); } finally { ev.recycle(); }
    }
    private static void drag() {
        try {
            View from=preview.getChildAt(0), to=preview.getChildAt(4);
            int[] a=new int[2], b=new int[2];
            from.getLocationOnScreen(a); to.getLocationOnScreen(b);
            float x=a[0]+from.getWidth()/2f, y=a[1]+from.getHeight()/2f;
            float end=b[0]+to.getWidth()/2f;
            long down=SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN,down,x,y);
            for(int i=1;i<=8;i++) touch(MotionEvent.ACTION_MOVE,down,x+(end-x)*i/8f,y);
            touch(MotionEvent.ACTION_UP,down,end,y);
            main.postDelayed(() -> {
                result("glass_drag_settles",to.isSelected());
                Log.i(TAG,"GLASS_RENDER "+session.shaderNote()+" / "+session.bkStatus());
                stage("glass-drag");
                main.postDelayed(RuntimeUiChecks::cancelDrag,1800);
            },2500);
        } catch(Throwable e) { fail("glass_drag_exception",e); finish(); }
    }
    private static void cancelDrag() {
        try {
            View from=preview.getChildAt(4), to=preview.getChildAt(0);
            int[] a=new int[2], b=new int[2];
            from.getLocationOnScreen(a); to.getLocationOnScreen(b);
            float x=a[0]+from.getWidth()/2f, y=a[1]+from.getHeight()/2f;
            float end=b[0]+to.getWidth()/2f;
            long down=SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN,down,x,y);
            touch(MotionEvent.ACTION_MOVE,down,end,y);
            result("glass_cancel_tracks",session.ph.drag);
            touch(MotionEvent.ACTION_CANCEL,down,end,y);
            result("glass_cancel_target",!session.ph.drag && session.ph.tgt==3);
            down=SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN,down,x,y);
            touch(MotionEvent.ACTION_MOVE,down,end,y);
            touch(MotionEvent.ACTION_UP,down,end,y-2*preview.getHeight());
            result("glass_outside_target",!session.ph.drag && session.ph.tgt==3);
            main.postDelayed(() -> {
                result("glass_cancel_keeps_tab",from.isSelected() && session.selectedTab()==4);
                stage("glass-cancel");
                main.postDelayed(RuntimeUiChecks::configChecks,1500);
            },2200);
        } catch(Throwable e) { fail("glass_cancel_exception",e); finish(); }
    }
    private static void configChecks() {
        org.json.JSONObject original=GlassCfg.toJson();
        try {
            for(int i=0;i<GlassCfg.GLASS_NAMES.length;i++) {
                GlassCfg.applyGlassPreset(i);
                result("glass_preset_"+i,GlassCfg.glassPreset()==i);
            }
            for(int i=0;i<GlassCfg.PHYS_NAMES.length;i++) {
                GlassCfg.applyPhysPreset(i);
                result("physics_preset_"+i,GlassCfg.physPreset()==i);
            }
            GlassCfg.setTr(-999);
            result("glass_min_bound",GlassCfg.tr==GlassCfg.TR_MIN);
            GlassCfg.setTr(999);
            result("glass_max_bound",GlassCfg.tr==GlassCfg.TR_MAX);
            GlassCfg.setTr(47);
            GlassCfg.setBk(false);
            GlassCfg.save();
            GlassCfg.setTr(65);
            GlassCfg.setBk(true);
            GlassCfg.init(FlagStore.dir());
            result("glass_disk_roundtrip",GlassCfg.tr==47 && !GlassCfg.bk);
            GlassCfg.resetLook();
            result("glass_reset_keeps_style",GlassCfg.style==GlassCfg.GLASS && GlassCfg.tr==55);
        } catch(Throwable e) { fail("glass_config_exception",e); }
        finally { GlassCfg.load(original); GlassCfg.save(); GlassBar.refresh(); }
        try {
            session.demoCompact();
            result("glass_compact_target",session.compacted());
            stage("glass-compact");
            main.postDelayed(() -> {
                result("glass_expand_target",!session.compacted());
                stage("glass-expand");
                main.postDelayed(RuntimeUiChecks::reopenGlass,1500);
            },1800);
        } catch(Throwable e) { fail("glass_compact_exception",e); finish(); }
    }
    private static void reopenGlass() {
        try {
            GlassSession previous=session;
            glass.dismiss();
            result("glass_close_detaches",!previous.isAttached());
            ArrayList<Dialog> before=dialogs();
            boolean clicked=click(menu,"Нижняя панель");
            glass=newest(before);
            result("glass_reopen",clicked && glass!=null);
            if(glass==null) { finish(); return; }
            main.postDelayed(() -> {
                try {
                    preview=preview(glass.getWindow().getDecorView());
                    Field f=preview.getClass().getDeclaredField("session");
                    f.setAccessible(true);
                    session=(GlassSession)f.get(preview);
                    result("glass_new_session",session!=null && session!=previous && session.isAttached());
                    stage("glass-reopen");
                } catch(Throwable e) { fail("glass_reopen_exception",e); }
                main.postDelayed(RuntimeUiChecks::finish,1800);
            },1600);
        } catch(Throwable e) { fail("glass_reopen_exception",e); finish(); }
    }
    private static void fail(String id,Throwable e) {
        fails++; Log.e(TAG,"RESULT "+id+" FAIL "+e.getClass().getSimpleName());
    }
    private static void finish() {
        try {
            if(glass!=null) {
                click(glass,"Оригинал");
                result("glass_detach",session==null || !session.isAttached());
                glass.dismiss();
                GlassCfg.setStyle(oldStyle); GlassBar.refresh();
            }
            if(menu!=null) menu.dismiss();
            // A signed-out onboarding screen does not establish a real feed navigation test.
            BarFinder.Found found=BarFinder.find(activity.getWindow().getDecorView());
            Log.i(TAG,"NATIVE_BAR "+(found==null?"not_found":"candidate_found")+" "+GlassBar.status().replace('\n',' '));
            skip("native_feed_tabs_and_home_hold");
            skip("account_chat_streak_server_features");
            skip("live_feed_ads_media_repost_content");
        } catch(Throwable e) { fail("cleanup",e); }
        Log.i(TAG,"DONE pass="+passes+" fail="+fails+" skip="+skips);
    }
}
