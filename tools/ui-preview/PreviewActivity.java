package dev.ttmlonger;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Emulator-only host for the real module dialogs. Compiled only in the screenshot job. */
public final class PreviewActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout frame = new FrameLayout(this);
        setContentView(frame);
        frame.post(() -> {
            verifyFixes();
            verifyPreferences();
            Ui.ensure(this);
            boolean night=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            expect(Ui.DARK==night,"preview theme follows system");
            String screen=getIntent().getStringExtra("screen");
            if ("streaks".equals(screen)) {
                StreakChecks.verifyAccountSettings(this);
                StreakScreen.show(this);
            } else if ("chat".equals(screen)) {
                ChatAppearancePreview.show(this);
            } else if ("glass".equals(screen)) {
                GlassBarScreen.show(this);
            } else {
                ModMenu.show(this);
            }
        });
    }

    private void verifyPreferences() {
        java.io.File dir=new java.io.File(getCacheDir(),"preview-prefs");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new AssertionError("preview prefs directory");
        Prefs.init(dir);
        Prefs.setRegion("");
        Prefs.setVtOpacity(63);
        Prefs.setHideAds(false);
        Prefs.setHideLiveRooms(true);
        Prefs.setUnlock("chat_bubble",false);
        Prefs.setUnlock("chat_wallpaper",true);
        Prefs.setPageBg(0);
        // Change memory without writing, then re-read from disk.
        Prefs.region="US"; Prefs.vtOpacity=20; Prefs.hideAds=true;
        Prefs.hideLiveRooms=false; Prefs.unlockOff="";
        Prefs.init(dir);
        expect(Prefs.region.isEmpty(),"persist no region override");
        expect(Prefs.vtOpacity==63,"persist metadata opacity");
        expect(!Prefs.hideAds && Prefs.hideLiveRooms,"persist independent ad/live switches");
        expect(!Unlocks.on("chat_bubble") && Unlocks.on("chat_wallpaper"),"persist independent chat settings");
        Prefs.setVtOpacity(-1);
        expect(Prefs.vtOpacity==20,"opacity lower bound");
        Prefs.setVtOpacity(101);
        expect(Prefs.vtOpacity==100,"opacity upper bound");
        Prefs.setVtOpacity(90);
        Prefs.setHideAds(true);
        Prefs.setHideLiveRooms(false);
        Prefs.setUnlock("chat_bubble",true);
        android.util.Log.i("TTMPreview","PREFS_ROUNDTRIP PASS");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Reposted {
        public long getRepostTime() { return 1_700_000_000_000L; }
        public long getCreateTime() { return 1_600_000_000L; }
    }

    public static final class PublishedOnly {
        public long getCreateTime() { return 1_600_000_000L; }
    }

    private static void verifyFixes() {
        expect(VideoInfo.parseEventTime("1700000000000") == 1_700_000_000L, "milliseconds");
        expect(VideoInfo.parseEventTime(1_700_000_000L) == 1_700_000_000L, "seconds");
        expect(VideoInfo.parseEventTime("invalid") == 0, "invalid event time");
        expect(VideoInfo.repostTimeOf(new Reposted()) == 1_700_000_000L, "actual repost time");
        expect(VideoInfo.repostTimeOf(new PublishedOnly()) == 0, "publication is not repost");
        expect(RefSet.isAdSuppression("ad_banner_enable"), "advertising flag");
        expect(!RefSet.isAdSuppression("im_voice_message"), "unrelated flag");
        expect(BrowserLinks.nativeAdPage("com.example.adlanding.Client", null), "native ad client");
        expect(BrowserLinks.nativeAdPage("Client", "https://inapp.tiktokv.com/ad/landing"), "native ad URL");
        expect(!BrowserLinks.nativeAdPage("Client", "https://example.org/ad/landing"), "external host");
        expect(!BrowserLinks.nativeAdPage("Client", "https://www.tiktok.com/@user"), "normal TikTok page");
        expect(AdHooks.process(null, true, true) == null, "null feed list");
    }
}
