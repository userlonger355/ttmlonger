package dev.ttmlonger;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Real cached conversation preview. The planned sticker is visually separate and never sent here. */
final class StreakChatPreview extends LinearLayout {
    private final Activity act;private final Ui ui;
    private final int ink,muted,incoming,surface;
    private final LinearLayout header,avatarSlot,messages,planned;
    private final TextView name,flame,empty,caption,stickerState,next;
    private final StickerMotionView sticker;
    private final StreakSchedule schedule;
    private final List<StreakRunner.Person> people=new ArrayList<>();
    private int index,generation,minute=-1;private boolean auto,loading,attached;
    private String owner,selected="",signature="";
    private final Runnable poll=new Runnable(){public void run(){if(!attached)return;renderMessages();renderSticker();postDelayed(this,1200);}};
    StreakChatPreview(Activity a){
        super(a);act=a;ui=new Ui(a);schedule=new StreakSchedule(a);ink=Ui.INK;muted=Ui.MUTED;incoming=Ui.DARK?0xff28282a:0xffeeeeef;surface=Ui.DARK?0xff191919:0xffffffff;
        setOrientation(VERTICAL);setBackground(ui.round(surface,18));setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));
        header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
        avatarSlot=new LinearLayout(a);LayoutParams av=new LayoutParams(ui.dp(38),ui.dp(38));av.rightMargin=ui.dp(9);header.addView(avatarSlot,av);
        LinearLayout title=new LinearLayout(a);title.setOrientation(VERTICAL);
        name=ui.medium("Реальный чат",15,ink);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);title.addView(name);
        flame=ui.text("",12,muted,false);title.addView(flame);header.addView(title,new LayoutParams(0,-2,1));
        next=ui.text("Другой чат ›",12,Theme.accent(),true);next.setPadding(ui.dp(8),ui.dp(10),0,ui.dp(10));header.addView(next);next.setOnClickListener(v->next());
        addView(header);line();
        messages=new LinearLayout(a);messages.setOrientation(VERTICAL);addView(messages,new LayoutParams(-1,-2));
        empty=ui.text("Загружаю огоньки…",13,muted,false);empty.setPadding(0,ui.dp(12),0,ui.dp(12));addView(empty);
        planned=new LinearLayout(a);planned.setOrientation(VERTICAL);planned.setGravity(Gravity.END);
        LayoutParams pp=new LayoutParams(-1,-2);pp.topMargin=ui.dp(5);addView(planned,pp);
        sticker=new StickerMotionView(a);planned.addView(sticker,new LayoutParams(ui.dp(74),ui.dp(74)));
        stickerState=ui.text("",11,muted,false);stickerState.setGravity(Gravity.END);planned.addView(stickerState,new LayoutParams(-1,-2));
        line();caption=ui.text("",12,muted,false);caption.setPadding(0,ui.dp(8),0,0);addView(caption,new LayoutParams(-1,-2));
    }
    private void line(){View v=new View(act);v.setBackgroundColor(Ui.DARK?0xff303030:0xffe7e7e7);LayoutParams lp=new LayoutParams(-1,ui.dp(1));lp.topMargin=ui.dp(8);addView(v,lp);}
    private void set(TextView v,String s){if(!s.contentEquals(v.getText()))v.setText(s);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;load();post(poll);}
    @Override protected void onDetachedFromWindow(){attached=false;generation++;loading=false;people.clear();owner=null;messages.removeAllViews();signature="";removeCallbacks(poll);super.onDetachedFromWindow();}
    private void load(){
        if(loading)return;loading=true;final int gen=++generation;owner=StreakHooks.liveOwner();
        people.clear();signature="";empty.setVisibility(VISIBLE);set(empty,"Загружаю огоньки…");messages.removeAllViews();avatarSlot.removeAllViews();set(name,"Реальный чат");set(flame,"");planned.setVisibility(GONE);
        StreakRunner.people(p->{
            if(!attached||gen!=generation)return;loading=false;
            String current=StreakHooks.liveOwner();if(owner==null||!owner.equals(current)){set(empty,"Откройте входящие, затем обновите превью.");return;}
            for(StreakRunner.Person person:p.list)if(StreakLogic.worthSending(person.status)&&(!Prefs.streakOnlyPicked||StreakStore.isPicked(person.conv)))people.add(person);
            Collections.shuffle(people);index=0;
            if(people.isEmpty()){set(empty,p.problem!=null?p.problem:"Нет доступных активных огоньков");return;}showPerson();
        });
    }
    private void next(){if(loading)return;if(people.isEmpty()||index+1>=people.size()){load();return;}index++;showPerson();}
    private void showPerson(){
        StreakRunner.Person person=people.get(index);signature="";
        set(name,person.name);set(flame,"🔥 "+person.streak+" · "+StreakLogic.days(person.streak));
        flame.setTextColor(StreakLogic.isGrey(person.status)?muted:0xffff9e48);
        avatarSlot.removeAllViews();avatarSlot.addView(StreakPreviewData.avatarView(act,StreakLogic.friendUid(person.conv,owner),person.name,38),new LayoutParams(-1,-1));
        renderMessages();renderSticker();
    }
    private void renderMessages(){
        if(owner!=null&&!owner.equals(StreakHooks.knownOwnUid())){people.clear();messages.removeAllViews();avatarSlot.removeAllViews();set(name,"Реальный чат");set(flame,"");empty.setVisibility(VISIBLE);set(empty,"Аккаунт изменился. Обновите чат.");planned.setVisibility(GONE);return;}
        if(people.isEmpty())return;
        StreakRunner.Person person=people.get(index);List<StreakPreviewData.Entry> entries=StreakPreviewData.messages(owner,person.conv);
        StringBuilder k=new StringBuilder(person.conv);for(StreakPreviewData.Entry e:entries)k.append('|').append(e.key).append(':').append(e.text.hashCode());
        if(k.toString().equals(signature))return;signature=k.toString();messages.removeAllViews();
        empty.setVisibility(entries.size()<2?VISIBLE:GONE);
        if(entries.size()<2)set(empty,entries.isEmpty()?"История ещё не загружена. Откройте этот чат в TikTok.":"Загружено одно сообщение. Откройте чат для остальных.");
        for(StreakPreviewData.Entry e:entries){
            LinearLayout row=new LinearLayout(act);row.setOrientation(VERTICAL);row.setGravity(e.own?Gravity.END:Gravity.START);LayoutParams rp=new LayoutParams(-1,-2);rp.topMargin=ui.dp(7);
            TextView bubble=ui.text(e.text,14,e.own?0xffffffff:ink,false);bubble.setMaxWidth((int)(getResources().getDisplayMetrics().widthPixels*.62f));bubble.setMaxLines(4);bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);bubble.setPadding(ui.dp(11),ui.dp(8),ui.dp(11),ui.dp(8));bubble.setBackground(ui.round(e.own?0xff385b88:incoming,15));
            row.addView(bubble,new LayoutParams(-2,-2));java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("dd.MM · HH:mm",Locale.getDefault());date.setTimeZone(Zones.current());
            TextView time=ui.text(date.format(new Date(e.time)),10,muted,false);row.addView(time,new LayoutParams(-2,-2));messages.addView(row,rp);
        }
    }
    void update(boolean enabled,int time){auto=enabled;minute=time;renderSticker();}
    private void renderSticker(){
        set(caption,"Локальный предпросмотр · ничего не отправляется");
        if(people.isEmpty()){planned.setVisibility(GONE);return;}
        planned.setVisibility(VISIBLE);
        String id=StreakSticker.chosenId();boolean chosen=!id.isEmpty();
        if(!id.equals(selected)){selected=id;sticker.clear();}
        int effective=schedule.effective(owner,people.get(index).conv);
        set(stickerState,!auto?"Автопродление выключено":!chosen?"Выберите стикер":("Будущая отправка · "+StreakSchedule.label(effective)));
        sticker.setVisibility(chosen?VISIBLE:GONE);if(!chosen)return;
        StickerLibrary.Entry e=StickerLibrary.byId(id);
        if(e==null){Object model=StreakSticker.get();if(model!=null){StickerLibrary.add(model,false);e=StickerLibrary.byId(id);}}
        if(e==null){sticker.clear();return;}
        sticker.bind(e);
    }
}
