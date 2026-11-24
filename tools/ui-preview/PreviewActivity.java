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
            if ("streaks".equals(getIntent().getStringExtra("screen"))) {
                StreakChecks.verifyAccountSettings(this);
                StreakScreen.show(this);
            } else {
                ModMenu.show(this);
            }
        });
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
