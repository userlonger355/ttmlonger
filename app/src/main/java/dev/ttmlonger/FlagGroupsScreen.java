package dev.ttmlonger;
import android.app.Activity;
import android.widget.LinearLayout;

/** Advanced flag controls, separate from independent mod features. */
final class FlagGroupsScreen extends Screen {
    private FlagGroupsScreen(Activity a){super(a);}
    static void show(Activity a){new FlagGroupsScreen(a).open("Группы флагов TikTok","Существующие подмены флагов. Доступность функций зависит от клиента и сервера.");}
    @Override void build(LinearLayout body){
        for(Unlocks.Group group:Unlocks.ALL)body.addView(toggleRow(group.icon,group.title,"Группа A/B-флагов",Unlocks.on(group.id),on->{Prefs.setUnlock(group.id,on);Restart.offer(act);}));
        body.addView(toggleRow(Icons.IMAGE,"Баннер профиля","Флаги profile_bg",FlagStore.allOn(Targets.BANNER_FLAGS),on->{FlagStore.setAll(Targets.BANNER_FLAGS,on);Restart.offer(act);}));
        body.addView(toggleRow(Icons.MIC,"Голосовые комментарии","Флаги записи аудио",FlagStore.allOn(Targets.VOICE_COMMENT_FLAGS),on->{FlagStore.setAll(Targets.VOICE_COMMENT_FLAGS,on);Restart.offer(act);}));
        body.addView(toggleRow(Icons.MIC,"Региональные функции чата","Экспериментальная группа параметров клиента",Prefs.dmGate,on->{Prefs.setDmGate(on);Restart.offer(act);}));
    }
}
