package dev.ttmlonger;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.os.Build;

/** A local mock conversation using the real selected sticker thumbnail. Never sends a message. */
final class StreakChatPreview extends LinearLayout {
    private final Ui ui;
    private final int ink, muted, incoming, field, surface;
    private final TextView flame, timestamp, hint, status, placeholder;
    private final ImageView sticker;
    private final FrameLayout picture;
    private final LinearLayout outgoing;
    private String selected="";
    private int generation;
    private boolean auto;
    private int minute=-1;
    private final Runnable poll=new Runnable(){
        @Override public void run(){
            if(!isAttachedToWindow())return;
            update(auto,minute);
            postDelayed(this,1000L);
        }
    };

    StreakChatPreview(Activity a){
        super(a);ui=new Ui(a);
        boolean dark=Ui.DARK;
        ink=dark?0xfff6f6f6:0xff161823;muted=dark?0xff99999d:0xff777780;
        incoming=dark?0xff2b2b2d:0xffededf0;field=dark?0xff29292b:0xfff1f1f3;
        surface=dark?0xff121212:0xffffffff;
        setOrientation(VERTICAL);setBackground(ui.round(surface,20));setClipToOutline(true);
        setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));

        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(ui.icon(Icons.CHEVRON_LEFT,ink,20),new LayoutParams(ui.dp(24),ui.dp(30)));
        TextView avatar=txt("А",16,Color.WHITE,true);
        avatar.setGravity(Gravity.CENTER);avatar.setBackground(ui.round(0xff655693,50));
        LayoutParams av=new LayoutParams(ui.dp(35),ui.dp(35));av.setMargins(ui.dp(4),0,ui.dp(8),0);header.addView(avatar,av);
        LinearLayout name=new LinearLayout(a);name.setOrientation(VERTICAL);
        name.addView(txt("Алекс",15,ink,true));name.addView(txt("Пример чата",11,muted,false));
        header.addView(name,new LayoutParams(0,-2,1));
        flame=txt("",13,0xffff9e48,true);header.addView(flame);
        TextView phone=txt("  ⋯",23,ink,true);header.addView(phone);
        addView(header,new LayoutParams(-1,-2));line();

        TextView today=txt("Сегодня",11,muted,false);today.setGravity(Gravity.CENTER);
        LayoutParams day=new LayoutParams(-1,-2);day.setMargins(0,ui.dp(12),0,ui.dp(12));addView(today,day);

        LinearLayout received=new LinearLayout(a);received.setGravity(Gravity.TOP);
        TextView mini=txt("А",11,Color.WHITE,true);mini.setGravity(Gravity.CENTER);mini.setBackground(ui.round(0xff655693,50));
        LayoutParams ma=new LayoutParams(ui.dp(25),ui.dp(25));ma.rightMargin=ui.dp(6);received.addView(mini,ma);
        TextView message=txt("Не забудь про огонёк 🔥",14,ink,false);
        message.setPadding(ui.dp(12),ui.dp(9),ui.dp(12),ui.dp(9));message.setBackground(ui.round(incoming,18));
        received.addView(message,new LayoutParams(-2,-2));addView(received,new LayoutParams(-1,-2));

        outgoing=new LinearLayout(a);outgoing.setOrientation(VERTICAL);outgoing.setGravity(Gravity.END);
        LayoutParams out=new LayoutParams(-1,-2);out.setMargins(ui.dp(20),ui.dp(12),ui.dp(1),ui.dp(10));addView(outgoing,out);
        picture=new FrameLayout(a);
        LayoutParams pic=new LayoutParams(ui.dp(126),ui.dp(126));pic.gravity=Gravity.END;outgoing.addView(picture,pic);
        sticker=new ImageView(a);sticker.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sticker.setContentDescription("Выбранный стикер");
        picture.addView(sticker,new FrameLayout.LayoutParams(-1,-1));
        placeholder=txt("Выберите\nстикер",13,muted,false);placeholder.setGravity(Gravity.CENTER);placeholder.setBackground(ui.round(field,14));
        picture.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        timestamp=txt("",11,muted,false);timestamp.setGravity(Gravity.END);outgoing.addView(timestamp,new LayoutParams(-1,-2));
        status=txt("",11,muted,false);status.setGravity(Gravity.END);outgoing.addView(status,new LayoutParams(-1,-2));

        line();
        LinearLayout composer=new LinearLayout(a);composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(0,ui.dp(9),0,0);
        composer.addView(ui.icon(Icons.IMAGE,ink,22),new LayoutParams(ui.dp(25),ui.dp(28)));
        TextView input=txt("Сообщение…",13,muted,false);input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(ui.dp(12),0,ui.dp(8),0);input.setBackground(ui.round(field,30));
        LayoutParams ip=new LayoutParams(0,ui.dp(34),1);ip.setMargins(ui.dp(6),0,ui.dp(7),0);composer.addView(input,ip);
        composer.addView(ui.icon(Icons.STICKER,ink,22),new LayoutParams(ui.dp(25),ui.dp(28)));
        composer.addView(ui.icon(Icons.MIC,ink,21),new LayoutParams(ui.dp(26),ui.dp(28)));
        addView(composer,new LayoutParams(-1,-2));

        hint=txt("",12,muted,false);
        hint.setPadding(ui.dp(9),ui.dp(10),ui.dp(9),ui.dp(2));addView(hint,new LayoutParams(-1,-2));
    }
    private TextView txt(String text,int size,int color,boolean bold){
        TextView t=new TextView(getContext());t.setText(text);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;
    }
    private void line(){
        View line=new View(getContext());line.setBackgroundColor(Ui.DARK?0xff272727:0xffeaeaea);
        LayoutParams l=new LayoutParams(-1,ui.dp(1));l.topMargin=ui.dp(8);addView(line,l);
    }
    private void set(TextView v,String s){if(!s.contentEquals(v.getText()))v.setText(s);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(poll);post(poll);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(poll);super.onDetachedFromWindow();}
    void update(boolean enabled,int time){
        auto=enabled;minute=time;
        String id=StreakSticker.chosenId();
        boolean chosen=!id.isEmpty();
        set(flame,enabled?"🔥  12":"🔥  11");
        set(timestamp,time>=0?StreakLogic.formatMinute(time):"При продлении");
        set(status,enabled?(InterfaceHooks.on("chat_ticks")?"✔️":"Отправлено · пример"):"Не отправлено");
        set(hint,!enabled?"Автопродление выключено.":!chosen?"Выберите стикер для автопродления.":"Пример: выбранный стикер будет отправлен "+(time>=0?"в "+StreakLogic.formatMinute(time):"когда огонёк потребует продления")+".");
        if(!id.equals(selected)){
            selected=id;generation++;sticker.setImageDrawable(null);placeholder.setVisibility(VISIBLE);
            if(Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled()){
                picture.setAlpha(.55f);picture.animate().alpha(1f).setDuration(180).start();
            }
        }
        if(!chosen){set(placeholder,"Выберите\nстикер");return;}
        StickerLibrary.Entry entry=StickerLibrary.byId(id);
        if(entry==null){
            Object obj=StreakSticker.get();
            if(obj!=null){StickerLibrary.add(obj,false);entry=StickerLibrary.byId(id);}
        }
        if(entry==null){set(placeholder,"Стикер выбран\nНет изображения");return;}
        if(entry.thumb!=null){sticker.setImageBitmap(entry.thumb);placeholder.setVisibility(GONE);return;}
        set(placeholder,entry.failed||entry.url==null?"Нет изображения":"Загрузка…");
        final StickerLibrary.Entry e=entry;final int g=generation;
        StickerLibrary.thumb(e,()->{
            if(g==generation&&e.id.equals(selected)&&e.thumb!=null&&isAttachedToWindow()){
                sticker.setImageBitmap(e.thumb);placeholder.setVisibility(GONE);
            }
        });
    }
}
