package dev.ttmlonger;

import android.content.Context;
import android.content.SharedPreferences;

/** The minute and submission receipts belong to the signed-in account. -1 is grey-only mode. */
final class StreakSchedule {
    static final int WHEN_GREY = -1;
    private final SharedPreferences data;

    StreakSchedule(Context context) {
        data = context.getSharedPreferences("ttmlonger_streak_schedule", Context.MODE_PRIVATE);
    }

    int minute(String owner) {
        if (owner == null) return WHEN_GREY;
        synchronized (StreakSchedule.class) {
            if (!data.contains("migrated")) {
                int old = Prefs.streakDaily ? Prefs.streakMinute : WHEN_GREY;
                data.edit().putInt("minute." + owner, old).putString("migrated", owner).apply();
            }
        }
        return data.getInt("minute." + owner, WHEN_GREY);
    }

    void setMinute(String owner, int minute) {
        if (owner == null || minute < WHEN_GREY || minute >= 1440) return;
        minute(owner);
        data.edit().putInt("minute." + owner, minute).apply();
    }

    long last(String owner, int minute, String conversation) {
        return data.getLong(receipt(owner, minute, conversation), legacy(owner, conversation));
    }

    long lastAny(String owner, String conversation) {
        return data.getLong("last." + owner + "." + conversation, legacy(owner, conversation));
    }

    void submitted(String owner, int minute, String conversation, long at) {
        data.edit().putLong(receipt(owner, minute, conversation), at)
                .putLong("last." + owner + "." + conversation, at).commit();
    }

    private long legacy(String owner, String conversation) {
        return owner.equals(data.getString("migrated", "")) ? StreakStore.lastSent(conversation) : 0L;
    }

    private static String receipt(String owner, int minute, String conversation) {
        return "sent." + owner + "." + minute + "." + conversation;
    }
}
