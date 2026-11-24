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
        {"video","Видео и истории"}, {"stickers","Стикеры"}, {"streaks","Огоньки"}, {"flags","Настройки разработчика TikTok"}
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
            result("release_no_developer_rows",text(menu.getWindow().getDecorView(),"Для опытных")==null
                && text(menu.getWindow().getDecorView(),"Разведка кода")==null
                && text(menu.getWindow().getDecorView(),"Перехват запросов")==null
                && text(menu.getWindow().getDecorView(),"Настройки разработчика TikTok")!=null);
            stage("menu");
            metadataChecks();
            result("inbox_label_with_count",InboxBadge.label("Входящие, 12")&&InboxBadge.label("Inbox, 3 unread"));
            try{
                android.graphics.Path mask=new android.graphics.Path();
                AvatarContours.addMask(mask,2,50,50,20);
                android.graphics.RectF bounds=new android.graphics.RectF();mask.computeBounds(bounds,true);
                android.graphics.Region region=new android.graphics.Region();
                region.setPath(mask,new android.graphics.Region(0,0,100,100));
                result("avatar_mask_square_corner",region.contains(34,34)&&region.contains(50,50)&&!region.contains(10,10));
                result("avatar_mask_not_empty",!mask.isEmpty()&&bounds.width()>30);
            }catch(Throwable e){fail("avatar_mask",e);}
            result("top_tabs_independent", "top_hide_community".equals(TopTabs.setting("community"))&&"top_hide_friends".equals(TopTabs.setting("friends"))&&"top_hide_recommend".equals(TopTabs.setting("recommend"))&&TopTabs.setting("following")==null);
            result("top_tabs_scoped_row",TopTabs.navigation(new java.util.HashSet<>(java.util.Arrays.asList("community","friends","recommend")),false)&&!TopTabs.navigation(new java.util.HashSet<>(java.util.Arrays.asList("friends","community","following")),true));
            result("comment_copy_preserves_mentions","Спасибо @friend! https://example.org".equals(CommentSelect.body(new CopyCommentFixture()))&&CommentSelect.body(new Object())==null);
            mtpDexChecks();
            result("mtp_api_version",cat.narezany.margyt.plugin.MargyPlugin.API==5);
            result("mtp_safe_id",MtpArchive.validId("margyt.hello")&&!MtpArchive.validId("../bad")&&!MtpArchive.validId("a/b"));
            try{
                result("plugin_window_manifest",CommandPlugins.parse("{\"schema\":1,\"id\":\"hello\",\"name\":\"Hello\",\"author\":\"test\",\"version\":\"1\",\"rows\":[{\"title\":\"Hi\",\"action\":\"window\",\"value\":\"Hello\"}]}").getJSONArray("rows").length()==1);
                boolean rejected=false;try{CommandPlugins.parse("{\"schema\":1,\"id\":\"hello\",\"name\":\"Hello\",\"author\":\"test\",\"version\":\"1\",\"rows\":[{\"title\":\"Hi\",\"action\":\"exec\",\"value\":\"x\"}]}");}catch(Exception expected){rejected=true;}
                result("plugin_exec_rejected",rejected);
                result("margyt_badge_catalog",ImportedBadges.count("{\"badges\":[{\"id\":\"helper\",\"colour\":\"64B5F6\",\"users\":[\"1234567890123456789\"]}]}")==1);
            }catch(Exception e){fail("plugin_badge_import",e);}
            result("inbox_badge_count",InboxBadge.count("99+")==100&&InboxBadge.count("Входящие")==0&&InboxBadge.count("12")==12);
            result("inbox_badge_new_notification",InboxBadge.next(12,10)==10&&InboxBadge.next(10,11)==0);
            result("inbox_badge_labels",InboxBadge.label("Сообщения")&&InboxBadge.label("Inbox")&&!InboxBadge.label("Profile"));
            result("sponsor_segment_bounds",SponsorSegments.valid(1000,2000,5000)&&!SponsorSegments.valid(1000,6000,5000)&&!SponsorSegments.valid(-1,2000,5000));
            extraFeatureChecks();
            adChecks();
            glassGestureLogic();
            streakChecks();
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
                    if(opened!=null) {
                        View root=opened.getWindow().getDecorView();
                        result("release_clean_"+id,text(root,"Скопировать сведения")==null && text(root,"Диагностика чатов")==null
                            && text(root,"Скопировать файл для GitHub")==null && text(root,"Открыть badges.json на GitHub")==null);
                        if("unlocks".equals(id)) chatPreview(opened);
                        stage(id);
                    }
                } catch(Throwable e) { fail("screen_"+id,e); }
                final Dialog close=opened;
                main.postDelayed(() -> {
                    if(close!=null) close.dismiss();
                    main.postDelayed(() -> row(i+1), 250);
                }, 1600);
            }, 400);
        } catch(Throwable e) { fail("screen_"+id,e); main.postDelayed(() -> row(i+1),250); }
    }
    private static void glassGestureLogic() {
        result("glass_horizontal_claim",GlassSession.horizontalDrag(40,5,2));
        result("glass_vertical_not_claimed",!GlassSession.horizontalDrag(8,50,2));
        result("glass_tap_not_claimed",!GlassSession.horizontalDrag(5,2,2));
        int[] pl={10,800,390,900,95,25,4};
        int[] crop=GlassBackdrop.videoStrip(pl,0,100,400,760);
        result("glass_video_above_footer",crop!=null && crop[1]==660 && crop[3]==760);
        result("glass_video_partial",GlassBackdrop.videoStrip(pl,20,100,380,850)!=null);
        result("glass_video_unrelated",GlassBackdrop.videoStrip(pl,0,100,400,300)==null);
        result("glass_video_narrow",GlassBackdrop.videoStrip(pl,100,100,150,850)==null);
    }
    private static void streakChecks() {
        try {
            long now=1700000000000L;
            java.util.TimeZone tz=java.util.TimeZone.getTimeZone("UTC");
            result("streak_manual_eligible",StreakLogic.manualVerdict("GREY",3,0,0,now,tz)==StreakLogic.Verdict.SEND);
            result("streak_manual_duplicate",StreakLogic.manualVerdict("GREY",3,now-1000,0,now,tz)==StreakLogic.Verdict.SENT_TODAY);
            result("streak_manual_backoff",StreakLogic.manualVerdict("GREY",3,0,now-1000,now,tz)==StreakLogic.Verdict.BACKOFF);
            result("streak_manual_expired",StreakLogic.manualVerdict("EXPIRED",5,0,0,now,tz)==StreakLogic.Verdict.GONE);
            result("streak_future_receipt",StreakLogic.manualVerdict("GREY",3,now+1000,0,now,tz)==StreakLogic.Verdict.SENT_TODAY);
            result("streak_future_failure",StreakLogic.manualVerdict("GREY",3,0,now+1000,now,tz)==StreakLogic.Verdict.BACKOFF);
            result("streak_next_day",StreakLogic.manualVerdict("GREY",3,now-86400000L,0,now,tz)==StreakLogic.Verdict.SEND);
        } catch(Throwable e){fail("streak_checks",e);}
    }
    public static final class CopyCommentFixture { public String getText(){return "Спасибо @friend! https://example.org";} public String getNickname(){return "Author";} }
    public static final class FakeFeedPost {
        final boolean ad;
        final int type;
        FakeFeedPost(boolean ad,int type){this.ad=ad;this.type=type;}
        public boolean isAd(){return ad;}
        public int getAwemeType(){return type;}
    }
    public static final class MutableAd {
        public boolean _isAd;
        public boolean isAd(){return false;}
    }
    public static final class WrappedAd {
        public boolean isAd(){return false;}
        public Object getAweme(){return new FakeFeedPost(true,0);}
    }
    public static final class SearchAdCard {
        public boolean isAd(){return false;}
        public Object getAweme(){return null;}
        public Object getPreciseAd(){return new Object();}
    }
    private static void adChecks() {
        try {
            FakeFeedPost regular=new FakeFeedPost(false,0), ad=new FakeFeedPost(true,0), live=new FakeFeedPost(false,101);
            java.util.List<Object> feed=java.util.Arrays.asList(regular,ad,live);
            result("ad_filter_disabled",AdHooks.process(feed,false,false)==feed);
            result("ad_filter_ads_only",AdHooks.process(feed,true,false).equals(java.util.Arrays.asList(regular,live)));
            result("ad_filter_live_only",AdHooks.process(feed,false,true).equals(java.util.Arrays.asList(regular,ad)));
            result("ad_filter_both",AdHooks.process(feed,true,true).equals(java.util.Arrays.asList(regular)));
            result("ad_filter_toggle_back",AdHooks.process(feed,false,false)==feed && feed.size()==3);
            MutableAd marker=new MutableAd();
            java.util.List<Object> page=java.util.Arrays.asList(marker,regular);
            result("ad_flag_initial",AdHooks.process(page,true,false)==page);
            marker._isAd=true;
            result("ad_late_flag",AdHooks.process(page,true,false).equals(java.util.Arrays.asList(regular)));
            result("ad_wrapped_post",AdHooks.judge(new WrappedAd(),true,false)==AdHooks.AD);
            result("ad_search_card",AdHooks.judge(new SearchAdCard(),true,false)==AdHooks.AD);
            java.util.List<Integer> nativeCounts=java.util.Arrays.asList(50,100,200);
            java.util.List<?> expanded=OfflineLimit.choices(nativeCounts,true);
            result("offline_limit_9000",expanded.contains(9000)&&!expanded.contains(9001)&&nativeCounts.size()==3);
            result("offline_limit_disabled",OfflineLimit.choices(nativeCounts,false)==nativeCounts);
            result("offline_limit_no_duplicates",java.util.Collections.frequency(OfflineLimit.choices(java.util.Arrays.asList(500,9000),true),9000)==1);
            result("ad_native_client",BrowserLinks.nativeAdPage("com.tiktok.commercialize.AdWebView","https://example.org"));
            result("ad_native_destination",BrowserLinks.nativeAdPage("Client","https://ads.tiktok.com/landing_page"));
            result("ad_native_domain_boundary",!BrowserLinks.nativeAdPage("Client","https://tiktok.com.example.org/ad/"));
            result("ad_normal_external_link",!BrowserLinks.nativeAdPage("Client","https://example.org/article"));
            result("ad_internal_host",BrowserLinks.internalHost("WWW.TIKTOK.COM.") && !BrowserLinks.internalHost("tiktok.com.example.org"));
        } catch(Throwable e){fail("ad_checks",e);}
    }
    public static final class FakePost {
        public String aid="one";
        public long createTime=1700000000L, repostTime=4102444800L, repostTimestamp=1700000123000L;
        public String region="KZ";
        public FakeUpvote upvoteInfo;
        public FakeUpvote getUpvoteInfo(){return upvoteInfo;}
        public String getAid(){return aid;}
        public long getCreateTime(){return createTime;}
        public long getRepostTime(){return repostTime;}
        public long getRepostTimestamp(){return repostTimestamp;}
        public String getRegion(){return region;}
    }
    public static final class FakeUpvote {
        public String itemId="one",upvoteId="123";
        public long createTime=1700000222L;
    }
    private static void extraFeatureChecks() {
        long now=System.currentTimeMillis(), sec=now/1000L;
        result("message_clock_seconds",MessageClock.millis(sec,now)==sec*1000L);
        result("message_clock_micros",MessageClock.millis(sec*1000000L,now)==sec*1000L);
        result("message_clock_invalid",MessageClock.millis(12,now)==0);
        result("muted_uid_valid",FeedMute.valid("123456789")&&!FeedMute.valid("0")&&!FeedMute.valid("abc"));
        result("download_percent",VideoSaver.percent(50,200)==25&&VideoSaver.percent(1,0)==-1&&VideoSaver.percent(300,200)==100);
        try{
            java.util.Map<String,Object> backup=SettingsBackup.validate("{\"schema\":1,\"settings\":{\"vtTime\":true,\"vtOpacity\":101,\"token\":\"secret\",\"avatar_contour\":3}}");
            result("backup_allowlist",backup.size()==2&&backup.containsKey("vtTime")&&backup.containsKey("avatar_contour"));
        }catch(Throwable e){result("backup_allowlist",false);}
    }
    private static void mtpDexChecks(){
        MtpRuntime.Slot slot=null;
        try{
            byte[] dex=android.util.Base64.decode(RuntimeMtpFixtureData.DEX,android.util.Base64.DEFAULT);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(out)){
                z.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));
                z.write("{\"id\":\"ci.loader.fixture\",\"name\":\"Loader fixture\",\"author\":\"CI\",\"version\":\"1\",\"entry\":\"fixture.mtp.Probe\",\"min_api\":5}".getBytes(java.nio.charset.StandardCharsets.UTF_8));z.closeEntry();
                z.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));z.write(dex);z.closeEntry();
            }
            MtpArchive p=MtpArchive.read(new java.io.ByteArrayInputStream(out.toByteArray()));
            MtpRuntime.add(p);
            for(MtpRuntime.Slot s:MtpRuntime.all())if(s.id.equals(p.id))slot=s;
            if(slot==null)throw new IllegalStateException("missing fixture slot");
            MtpRuntime.approve(slot);
            result("mtp_real_dex_start",slot.enabled&&slot.plugin!=null&&"fixture.mtp.Probe".equals(slot.plugin.getClass().getName()));
            result("mtp_real_dex_context",slot.enabled&&slot.rows.size()==5&&"CI fixture".equals(slot.rows.get(0).title));
            result("mtp_real_dex_readonly",slot.enabled&&slot.plugin.margyt().folder().isDirectory()
                &&(android.system.Os.stat(new java.io.File(slot.plugin.margyt().folder(),"classes.dex").getAbsolutePath()).st_mode&0222)==0);
            result("mtp_setting_toggle",MtpRuntime.saveSetting(slot,slot.rows.get(1),1)&&slot.plugin.margyt().getBoolean("toggle",false));
            result("mtp_setting_choice",MtpRuntime.saveSetting(slot,slot.rows.get(2),1)&&slot.plugin.margyt().getInt("choice",-1)==1);
            result("mtp_setting_slider",MtpRuntime.saveSetting(slot,slot.rows.get(3),75)&&slot.plugin.margyt().getInt("strength",-1)==75);
            result("mtp_setting_callback","strength".equals(slot.plugin.margyt().getString("changed","")));
            MtpRuntime.settingAction(slot,slot.rows.get(4));
            result("mtp_setting_action","done".equals(slot.plugin.margyt().getString("action","")));
            result("mtp_custom_settings_failure_isolated",MtpRuntime.customSettings(slot)&&!MtpRuntime.open(slot,activity)&&slot.enabled&&slot.settingsError.contains("fixture settings failure"));
            ArrayList<Dialog> before=dialogs();MtpScreen.settings(activity,slot);
            Dialog settings=newest(before);
            result("mtp_settings_render",settings!=null&&text(settings.getWindow().getDecorView(),"CI toggle")!=null&&text(settings.getWindow().getDecorView(),"CI choice")!=null);
            if(settings!=null)settings.dismiss();
            MtpRuntime.stop(slot);
            result("mtp_real_dex_stop",!slot.enabled&&slot.plugin==null);
        }catch(Throwable e){fail("mtp_real_dex",e);}finally{if(slot!=null)MtpRuntime.remove(slot);}
    }
    private static void metadataChecks() {
        try {
            FakePost f=new FakePost();
            VideoTracker.Entry e=new VideoTracker.Entry(f);
            result("metadata_repost_ms_fallback",VideoInfo.repostTimeOf(f)==1700000123L);
            f.repostTimestamp=0;
            result("metadata_no_fake_repost",VideoInfo.repostTimeOf(f)==0);
            f.upvoteInfo=new FakeUpvote();
            result("repost_nested_event",VideoInfo.repostTimeOf(f)==1700000222L);
            f.upvoteInfo.itemId="other";
            result("repost_wrong_item",VideoInfo.repostTimeOf(f)==0);
            f.upvoteInfo.itemId="one"; f.upvoteInfo.createTime=1699999999L;
            result("repost_before_publication",VideoInfo.repostTimeOf(f)==0);
            f.upvoteInfo.createTime=1700000222L; f.repostTimestamp=1700000123000L;
            result("repost_conflicting_times",VideoInfo.repostTimeOf(f)==0);
            f.repostTimestamp=0; f.upvoteInfo=null;
            result("metadata_first_bind",VideoInfo.metaOf(e).created==1700000000L);
            FakePost late=new FakePost();late.region="";VideoTracker.Entry lateEntry=new VideoTracker.Entry(late);
            result("metadata_missing_region_not_cached",VideoInfo.metaOf(lateEntry).region==null);
            late.region="RU";result("metadata_late_region_refresh","RU".equals(VideoInfo.metaOf(lateEntry).region));
            f.aid="two"; f.createTime=1700000300L; f.region="US";
            result("metadata_reused_model",VideoInfo.metaOf(e).created==1700000300L && "US".equals(VideoInfo.metaOf(e).region));
            FakePost other=new FakePost();
            result("metadata_distinct_id",!VideoInfo.samePost(e,new VideoTracker.Entry(other)));
            other.aid="two";
            result("metadata_same_id",VideoInfo.samePost(e,new VideoTracker.Entry(other)));
            long ms=1700000123000L;
            result("metadata_timezone",!VideoInfo.formatTime(1700000123L,true,java.util.TimeZone.getTimeZone("UTC"),ms)
                .equals(VideoInfo.formatTime(1700000123L,true,java.util.TimeZone.getTimeZone("GMT+07:00"),ms)));
        } catch(Throwable e) { fail("metadata_checks",e); }
    }
    private static void chatPreview(Dialog parent) throws Exception {
        ArrayList<Dialog> before=dialogs();
        boolean clicked=click(parent,"Предпросмотр чата");
        Dialog d=newest(before);
        result("chat_preview_open",clicked && d!=null);
        if(d==null) return;
        try {
            result("chat_preview_styles",click(d,"Мягкие") && click(d,"Градиент")
                && text(d.getWindow().getDecorView(),"Пример: Мягкие · Градиент · "+(Ui.DARK?"тёмная":"светлая"))!=null);
            result("chat_preview_reset",click(d,"Сбросить предпросмотр")
                && text(d.getWindow().getDecorView(),"Пример: Стандарт · Однотонный · "+(Ui.DARK?"тёмная":"светлая"))!=null);
        } finally { d.dismiss(); }
    }
    private static void glassStart() {
        try {
            oldStyle=GlassCfg.style;
            ArrayList<Dialog> before=dialogs();
            boolean clicked=click(menu,"Нижняя панель");
            glass=newest(before);
            result("glass_screen",clicked && glass!=null);
            if(glass!=null) result("release_clean_glass",text(glass.getWindow().getDecorView(),"Скопировать сведения")==null);
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
    private static void multiTouch(long down,float x,float y,float x2,float y2) {
        MotionEvent.PointerProperties[] pp={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] pc={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++) { pp[i].id=i; pp[i].toolType=MotionEvent.TOOL_TYPE_FINGER; pc[i].pressure=1; pc[i].size=1; }
        pc[0].x=x; pc[0].y=y; pc[1].x=x2; pc[1].y=y2;
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,pp,pc,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
        try { session.onTouch(e); } finally { e.recycle(); }
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
            down=SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN,down,x,y);
            touch(MotionEvent.ACTION_MOVE,down,end,y);
            touch(MotionEvent.ACTION_UP,down,end-2*preview.getWidth(),y);
            result("glass_horizontal_outside_target",!session.ph.drag && session.ph.tgt==3);
            down=SystemClock.uptimeMillis();
            touch(MotionEvent.ACTION_DOWN,down,x,y);
            touch(MotionEvent.ACTION_MOVE,down,end,y);
            multiTouch(down,x,y,end,y);
            result("glass_two_finger_cancel",!session.ph.drag && session.ph.tgt==3);
            touch(MotionEvent.ACTION_UP,down,end,y);
            result("glass_two_finger_no_retarget",session.ph.tgt==3);
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
            org.json.JSONObject transientCfg = GlassCfg.toJson();
            transientCfg.put("tr",65);
            transientCfg.put("bk",true);
            GlassCfg.load(transientCfg); // Change memory only, without overwriting the file.
            GlassCfg.init(FlagStore.dir());
            result("glass_disk_roundtrip",GlassCfg.tr==47 && !GlassCfg.bk);
            GlassCfg.resetLook();
            result("glass_reset_keeps_style",GlassCfg.style==GlassCfg.GLASS && GlassCfg.tr==55);
            result("profile_empty_slot",!GlassProfiles.exists(2) && !GlassProfiles.apply(2));
            GlassCfg.setTr(34);
            GlassCfg.setPk(420);
            GlassCfg.setPlus(3);
            result("profile_store",click(glass,"Сохранить профиль 1") && GlassProfiles.exists(0));
            GlassCfg.setTr(70);
            GlassCfg.setPk(900);
            GlassCfg.setPlus(0);
            result("profile_apply",click(glass,"Применить профиль 1")
                && GlassCfg.tr==34 && GlassCfg.pk==420 && GlassCfg.plus==3);
            result("profile_style_preserved",GlassCfg.style==GlassCfg.GLASS);
            GlassCfg.setTr(77);
            result("profile_second_slot",GlassProfiles.store(1));
            result("profile_independent",GlassProfiles.apply(0) && GlassCfg.tr==34
                && GlassProfiles.apply(1) && GlassCfg.tr==77);
            result("profile_invalid_slot",!GlassProfiles.store(-1) && !GlassProfiles.apply(3));
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
            main.postDelayed(() -> reopenAfterDetach(previous), 500);
        } catch(Throwable e) { fail("glass_close_exception",e); finish(); }
    }
    private static void reopenAfterDetach(GlassSession previous) {
        try {
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
    // CI-only: after menu regression checks, observe the actual TikTok window while ADB navigates guest UI.
    private static void guestBarProbe(int attempt) {
        if(attempt>=180) return;
        try {
            Activity a=ScreenWatcher.top();
            if(a!=null && !a.isFinishing()) {
                BarFinder.Found f=BarFinder.find(a.getWindow().getDecorView());
                if(f!=null) {
                    boolean home=false, profile=false;
                    for(View tab:f.tabs) {
                        home |= text(tab,"Home")!=null || text(tab,"Главная")!=null
                            || "Home".contentEquals(tab.getContentDescription()==null?"":tab.getContentDescription());
                        profile |= text(tab,"Profile")!=null || text(tab,"Профиль")!=null
                            || "Profile".contentEquals(tab.getContentDescription()==null?"":tab.getContentDescription());
                    }
                    if(home && profile) {
                        if(!GlassCfg.glass()) { GlassCfg.setStyle(GlassCfg.GLASS); GlassBar.refresh(); }
                        Field lf=GlassBar.class.getDeclaredField("live"); lf.setAccessible(true);
                        GlassSession live=(GlassSession)lf.get(null);
                        if(live!=null && live.bar==f.bar && live.isAttached()) {
                            Log.i("TTMGuest","NATIVE_GLASS_ATTACHED seq="+attempt+" tabs="+f.tabs.size()+" selected="+live.selectedTab());
                        }
                    }
                }
            }
        } catch(Throwable e) { Log.w("TTMGuest","PROBE_ERROR "+e.getClass().getSimpleName()); }
        main.postDelayed(() -> guestBarProbe(attempt+1),1200);
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
        main.postDelayed(() -> guestBarProbe(0),1500);
    }
}
