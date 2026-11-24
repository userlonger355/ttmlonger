package dev.ttmlonger;
import android.app.*;
import android.view.*;
import android.widget.*;
final class ProfileStyleScreen extends Screen {
    private TextView preview,sortValue;
    private ProfileStyleScreen(Activity a){super(a);}
    static void show(Activity a){new ProfileStyleScreen(a).open("Профиль","Предпросмотр и настройки внешнего вида профиля");}
    @Override void build(LinearLayout body){
        body.addView(ui.header("Предпросмотр"));
        preview=ui.text("",15,Ui.INK,false);preview.setPadding(ui.dp(18),ui.dp(18),ui.dp(18),ui.dp(18));preview.setBackground(ui.round(Ui.FIELD,20));
        LinearLayout.LayoutParams lp=ui.matchWrap();lp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(10));body.addView(preview,lp);
        opt(body,"profile_counts","Точные счётчики","Полные числа вместо 1,2M");
        opt(body,"profile_follow","Показывать статус подписки","Под именем пользователя");
        opt(body,"profile_date","Дата создания аккаунта","Приблизительная дата из ID");
        opt(body,"profile_repost","Время последнего репоста","Только если модель TikTok отдаёт время последнего репоста");
        opt(body,"profile_copy","Копировать данные профиля","Долгое нажатие на @username");
        body.addView(toggleRow(-1,"Значки TikTok You","Список бейджиков мода",Prefs.badgesOn,on->{Prefs.setBadgesOn(on);refresh();}));
        body.addView(ui.menuRow(Icons.SLIDERS,"Управление бейджиками","Персональные значки и общий список",null,v->BadgesScreen.show(act)));
        body.addView(ui.header("Кнопки профиля"));
        for(int i=0;i<ProfileStyle.KEYS.length;i++)opt(body,ProfileStyle.KEYS[i],"Скрыть: "+ProfileStyle.TITLES[i],"При распознавании штатной кнопки");
        if(ProfileGrid.hooks>0){
            body.addView(ui.header("Видео в профиле"));
            LinearLayout row=ui.menuRow(Icons.SLIDERS,"Порядок видео","Локальная сортировка загруженной страницы; закреплённые первыми",ProfileGrid.MODES[ProfileGrid.mode()],v->sort());
            sortValue=Ui.valueOf(row);body.addView(row);
        }
        body.addView(ui.header("Заблокированные профили"));
        body.addView(toggleRow(Icons.EYE_OFF,"Просмотр заблокированного профиля","Только профили, заблокированные вами; серверные ограничения сохраняются",Prefs.viewBlocked,on->Prefs.setViewBlocked(on)));
        body.addView(toggleRow(-1,"Прятать блокировку везде","Расширить локальную обработку за пределы профиля",Prefs.viewBlockedEverywhere,on->Prefs.setViewBlockedEverywhere(on)));
        body.addView(ui.header("Жидкое стекло"));
        body.addView(toggleRow(-1,"Жидкое стекло",null,GlassCfg.glass(),on->GlassCfg.setStyle(on?GlassCfg.GLASS:GlassCfg.ORIGINAL)));
        body.addView(toggleRow(-1,"Тонированное стекло",null,GlassCfg.glassPreset()==1,on->GlassCfg.applyGlassPreset(on?1:0)));
        body.addView(toggleRow(-1,"Убрать сплошной фон панели","Прозрачная подложка навигации",GlassCfg.tp,on->GlassCfg.setTp(on)));
        body.addView(ui.menuRow(Icons.SLIDERS,"Настроить стекло","Линза, пружина и размытие",null,v->GlassBarScreen.show(act)));
        refresh();
    }
    private void opt(LinearLayout b,String key,String title,String detail){b.addView(toggleRow(-1,title,detail,InterfaceHooks.on(key),on->{InterfaceHooks.set(key,on);refresh();}));}
    private void refresh(){
        String top=(InterfaceHooks.on("profile_add")?"":"＋   ")+ "Пример профиля"+(InterfaceHooks.on("profile_view")?"":"   ◉")+"   ☰";
        String text=top+"\n\n@sample"+(Prefs.badgesOn?"  ◆":"");
        if(InterfaceHooks.on("profile_follow"))text+="\nПодписан(-а) на вас";
        if(InterfaceHooks.on("profile_date"))text+="\nВ TikTok с 14 марта 2021 г.";
        if(InterfaceHooks.on("profile_repost"))text+="\nПоследний репост: пример";
        text+=InterfaceHooks.on("profile_counts")?"\n\n312     1 234 567     8 954 321":"\n\n312       1,2M       9,0M";
        text+="\nПодписки   Подписчики   Лайки\n\n▣    ▣    ▣\n▣    ▣    ▣";
        preview.setText(text);
    }
    private void sort(){
        ChoiceSheet.show(act,"Порядок видео в профиле",ProfileGrid.MODES,ProfileGrid.mode(),i->{ProfileGrid.set(i);sortValue.setText(ProfileGrid.MODES[i]);});
    }
}
