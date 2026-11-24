package dev.ttmlonger;
import android.app.Activity;
import android.widget.*;
final class CommentToolsScreen extends Screen {
    private TextView example;
    private CommentToolsScreen(Activity a){super(a);}
    static void show(Activity a){new CommentToolsScreen(a).open("Комментарии","Настройки распознанных элементов TikTok");}
    @Override void build(LinearLayout body){
        body.addView(ui.header("Предпросмотр"));
        example=ui.text("",15,Ui.INK,false);example.setPadding(ui.dp(16),ui.dp(18),ui.dp(16),ui.dp(18));example.setBackground(ui.round(Ui.FIELD,18));
        LinearLayout.LayoutParams lp=ui.matchWrap();lp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(10));body.addView(example,lp);
        for(int i=0;i<CommentTools.KEYS.length;i++){
            String key=CommentTools.KEYS[i];
            body.addView(toggleRow(-1,CommentTools.TITLES[i],i==1?"Долгое нажатие на текст":i==3?"Из модели комментария, не из времени видео":null,InterfaceHooks.on(key),on->{InterfaceHooks.set(key,on);refresh();}));
        }
        body.addView(ui.menuRow(Icons.IMAGE,"Фон комментариев","Изображение и размытие",null,v->CommentWallpaperScreen.show(act)));
        body.addView(ui.note("Распознано методов комментария: "+CommentTools.modelHooks+". Ник, время и жесты зависят от разметки версии TikTok."));
        body.addView(ui.note("Результаты опросов, бегущие комментарии, новые встроенные эмодзи, пауза и размер видео: подтверждённых хуков пока нет."));
        refresh();
    }
    private void refresh(){
        example.setText("128 комментариев     "+(InterfaceHooks.on("comment_full")?"⛶    ":"")+"×\n\n"+(InterfaceHooks.on("comment_nick")?"@sample":"Алекс")+"\nКажется, это одно из лучших видео за последнее время."+(InterfaceHooks.on("comment_expand")?" Очень атмосферно, особенно финальная сцена.":" … Ещё")+"\n\n"+(InterfaceHooks.on("comment_exact")?"24 нояб., 18:04:23":"2 дн.")+"   Ответить     ♡ 24");
    }
}
