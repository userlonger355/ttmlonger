package dev.ttmlonger;

import android.content.Context;
import java.time.Instant;
import java.util.TimeZone;

public final class StreakChecks {
    private static void expect(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        long now = Instant.parse("2026-11-24T13:00:00Z").toEpochMilli();
        TimeZone zone = TimeZone.getTimeZone("Asia/Krasnoyarsk");
        int slot = 20 * 60;
        for (String state : new String[]{StreakLogic.ACTIVE, StreakLogic.GREY}) {
            expect(StreakLogic.decide(true, slot, state, 3, 0, 0, now - 1, zone) == StreakLogic.Verdict.LIT, "before time");
            expect(StreakLogic.decide(true, slot, state, 3, 0, 0, now, zone) == StreakLogic.Verdict.SEND, "at time");
            expect(StreakLogic.decide(true, slot, state, 3, now, 0, now + 60000, zone) == StreakLogic.Verdict.SENT_TODAY, "duplicate");
        }
        expect(StreakLogic.decide(false, -1, StreakLogic.GREY, 3, 0, 0, now, zone) == StreakLogic.Verdict.SEND, "grey");
        expect(StreakLogic.decide(false, -1, StreakLogic.ACTIVE, 3, 0, 0, now, zone) == StreakLogic.Verdict.LIT, "already active");
        expect(StreakLogic.decide(false, -1, StreakLogic.GREY, 3, now - 86400000, 0, now, zone) == StreakLogic.Verdict.SEND, "new day");
        expect(StreakLogic.decide(false, -1, StreakLogic.GREY, 2, 0, 0, now, zone) == StreakLogic.Verdict.NO_FLAME, "young");
        expect(StreakLogic.decide(false, -1, StreakLogic.GREY, 3, 0, now - 1, now, zone) == StreakLogic.Verdict.BACKOFF, "failed dispatch");
        for (String state : new String[]{null, "EXPIRED", "UNKNOWN"}) {
            expect(!StreakLogic.worthSending(state), "manual skips missing or expired");
            expect(StreakLogic.decide(true, slot, state, 7, 0, 0, now, zone) != StreakLogic.Verdict.SEND, "automatic skips missing or expired");
        }
        expect(StreakLogic.friendUid("0:1:21:35", "21").equals("35"), "owner");
        expect(StreakLogic.friendUid("0:1:21:35", "88") == null, "foreign conversation");
        System.out.println("Streak decisions passed");
    }

    static void verifyAccountSettings(Context context) {
        main(new String[0]);
        context.getSharedPreferences("ttmlonger_streak_schedule", 0).edit().clear().commit();
        boolean old = Prefs.streakDaily;
        Prefs.streakDaily = true;
        StreakSchedule schedule = new StreakSchedule(context);
        expect(schedule.minute(null) == -1, "signed out");
        expect(schedule.minute("21") == Prefs.streakMinute, "migrate old time");
        expect(schedule.minute("35") == -1, "new account");
        schedule.setMinute("35", 440);
        String conversation = "0:1:21:35";
        schedule.submitted("35", 440, conversation, 10000);
        StreakSchedule reopened = new StreakSchedule(context);
        expect(reopened.minute("35") == 440, "restored time");
        expect(reopened.last("35", 440, conversation) == 10000, "persistent receipt");
        expect(reopened.last("21", 440, conversation) == 0, "other account receipt");
        expect(reopened.last("35", 441, conversation) == 10000, "changing time preserves daily receipt");
        expect(reopened.last("35", -1, conversation) == 10000, "manual and grey share daily receipt");
        expect(reopened.lastAny("35", conversation) == 10000, "summary receipt");
        reopened.submitted("35", -1, conversation, 20000);
        expect(reopened.last("35", 440, conversation) == 20000, "manual receipt prevents scheduled duplicate");
        expect(reopened.last("21", -1, conversation) == 0, "manual receipt remains account scoped");
        Prefs.streakDaily = old;
        context.getSharedPreferences("ttmlonger_streak_schedule", 0).edit().clear().commit();
    }
}
