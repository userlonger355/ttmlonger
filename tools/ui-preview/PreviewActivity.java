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
            if ("streaks".equals(getIntent().getStringExtra("screen"))) {
                StreakChecks.verifyAccountSettings(this);
                StreakScreen.show(this);
            } else {
                ModMenu.show(this);
            }
        });
    }
}
