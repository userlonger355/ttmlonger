package dev.ttmlonger;

import android.content.Context;
import android.content.SharedPreferences;

/** The minute and submission receipts belong to the signed-in account. -1 is grey-only mode. */
final class StreakSchedule {
    static final int WHEN_GREY = -1, INHERIT = -2;
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

    private static String personalKey(String owner,String conv){return "personal."+owner+"."+conv;}
    int override(String owner,String conv){
        if(StreakLogic.friendUid(conv,owner)==null)return INHERIT;
        int value=data.getInt(personalKey(owner,conv),INHERIT);
        return value>=WHEN_GREY&&value<1440?value:INHERIT;
    }
    int effective(String owner,String conv){int v=override(owner,conv);return v==INHERIT?minute(owner):v;}
    boolean setOverride(String owner,String conv,int value){
        if(StreakLogic.friendUid(conv,owner)==null||value<INHERIT||value>=1440)return false;
        SharedPreferences.Editor e=data.edit();if(value==INHERIT)e.remove(personalKey(owner,conv));else e.putInt(personalKey(owner,conv),value);e.apply();return true;
    }
    static String label(int value){return value==INHERIT?"Общее время":value==WHEN_GREY?"Когда огонёк серый":StreakLogic.formatMinute(value);}
    int count(String owner){if(owner==null)return 0;int n=0;String prefix="personal."+owner+".";for(String k:data.getAll().keySet())if(k.startsWith(prefix)&&StreakLogic.friendUid(k.substring(prefix.length()),owner)!=null)n++;return n;}
    long nextWait(String owner,long now,java.util.TimeZone tz){
        if(owner==null)return Long.MAX_VALUE;
        long best=Long.MAX_VALUE;int global=minute(owner);
        if(global>=0)best=StreakLogic.millisUntil(now,global,tz);
        String prefix="personal."+owner+".";
        for(java.util.Map.Entry<String,?> e:data.getAll().entrySet()){
            if(!e.getKey().startsWith(prefix)||!(e.getValue() instanceof Integer))continue;
            String conv=e.getKey().substring(prefix.length());
            if(StreakLogic.friendUid(conv,owner)==null||Prefs.streakOnlyPicked&&!StreakStore.isPicked(conv))continue;
            int m=(Integer)e.getValue();if(m>=0&&m<1440)best=Math.min(best,StreakLogic.millisUntil(now,m,tz));
        }return best;
    }
    long last(String owner, int minute, String conversation) {
        return Math.max(lastAny(owner, conversation),
                data.getLong(receipt(owner, minute, conversation), legacy(owner, conversation)));
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
