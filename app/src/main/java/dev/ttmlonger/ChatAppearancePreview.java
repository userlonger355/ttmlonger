package dev.ttmlonger;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A local illustrative preview. Does not send messages, change chat settings or require an account. */
final class ChatAppearancePreview extends Screen {
    private LinearLayout canvas;
    private TextView selection;
    private int bubble=0, wallpaper=0;
    private boolean dark=Ui.DARK;
    private static final String[] BUBBLES={"Стандарт","Мягкие","Контрастные"};
    private static final String[] WALLPAPERS={"Однотонный","Градиент","Сумерки"};
    private ChatAppearancePreview(Activity a) { super(a); }
    static void show(Activity a) {
        new ChatAppearancePreview(a).open("Предпросмотр чата",
                "Демонстрация оформления. Реальные стили и обои выбираются в TikTok, если доступны для аккаунта.");
    }
    @Override void build(LinearLayout body) {
        canvas=new LinearLayout(act);
        canvas.setOrientation(LinearLayout.VERTICAL);
        canvas.setPadding(ui.dp(12),ui.dp(16),ui.dp(12),ui.dp(16));
        LinearLayout.LayoutParams lp=ui.matchWrap();
        lp.setMargins(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(12));
        body.addView(canvas,lp);
        selection=ui.note("");
        body.addView(selection);
        body.addView(ui.header("Пузыри — пример"));
        for(int i=0;i<BUBBLES.length;i++) {
            final int id=i;
            body.addView(ui.row(-1,BUBBLES[i],null,null,false,v->{bubble=id;render();}));
        }
        body.addView(ui.header("Обои — пример"));
        for(int i=0;i<WALLPAPERS.length;i++) {
            final int id=i;
            body.addView(ui.row(-1,WALLPAPERS[i],null,null,false,v->{wallpaper=id;render();}));
        }
        body.addView(toggleRow("Тёмная тема предпросмотра",null,dark,on->{dark=on;render();}));
        body.addView(ui.row(Icons.REFRESH,"Сбросить предпросмотр",null,null,false,v->{bubble=0;wallpaper=0;render();}));
        body.addView(ui.note("Это только образец. Сообщения вымышленные; выбранные здесь цвета не меняют переписки."));
        render();
    }
    private void render() {
        canvas.removeAllViews();
        int[] bg=wallpaper==1?new int[]{0xff365d90,0xff8d4c80}:wallpaper==2?new int[]{0xff171b38,0xff3e5275}:
            dark?new int[]{0xff18191d,0xff18191d}:new int[]{0xffeef0f4,0xffeef0f4};
        GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,bg);
        background.setCornerRadius(ui.dp(20));
        canvas.setBackground(background);
        int left=bubble==2?0xff28334b:dark?0xff353741:0xffffffff;
        int right=bubble==1?0xff6955bc:bubble==2?0xff153d87:0xff2865a9;
        int leftText=(dark||bubble==2)?0xffffffff:0xff202127;
        message("Привет! Как тебе оформление?",false,left,leftText);
        message("Отлично! ✨",true,right,0xffffffff);
        message("Длинное сообщение: проверяем перенос текста и читаемость на фоне обоев.",false,left,leftText);
        message("♪  0:08   ━━━",true,right,0xffffffff);
        selection.setText("Пример: "+BUBBLES[bubble]+" · "+WALLPAPERS[wallpaper]+" · "+(dark?"тёмная":"светлая"));
    }
    private void message(String text,boolean outgoing,int color,int ink) {
        TextView v=ui.text(text,15,ink,false);
        v.setPadding(ui.dp(12),ui.dp(9),ui.dp(12),ui.dp(9));
        v.setMaxWidth(ui.dp(230));
        v.setBackground(ui.round(color,bubble==1?22:12));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.gravity=outgoing?Gravity.END:Gravity.START;
        lp.bottomMargin=ui.dp(8);
        canvas.addView(v,lp);
    }
}
