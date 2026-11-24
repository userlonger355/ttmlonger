package dev.ttmlonger;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.*;

final class StickerPickScreen {
    private final Activity act;private final Runnable onChosen;private final StreakSheet sheet;private final Ui ui;
    private final LinearLayout grid;private final TextView status,save;
    private String pending;private StickerLibrary.Entry choice;
    private final Map<String,LinearLayout> tiles=new LinkedHashMap<>();
    private final Map<String,TextView> labels=new LinkedHashMap<>();
    private boolean loading;private int filter;private int limit=60;private TextView[] tabs=new TextView[3];
    static void show(Activity a,Runnable onChosen){new StickerPickScreen(a,onChosen).open();}
    private StickerPickScreen(Activity a,Runnable cb){
        act=a;onChosen=cb;sheet=new StreakSheet(a,"Чем продлевать");ui=sheet.ui;pending=StreakSticker.chosenId();
        sheet.root.addView(sheet.note("Обычные наборы, реакции и избранное. Откройте панель стикеров в чате, чтобы загрузить новые модели TikTok."));
        LinearLayout nav=new LinearLayout(a);nav.setPadding(ui.dp(14),0,ui.dp(14),ui.dp(6));String[] categories={"Все","Обычные","Избранное"};for(int i=0;i<3;i++){final int n=i;TextView t=ui.medium(categories[i],13,Ui.INK);t.setGravity(Gravity.CENTER);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(38),1);lp.setMargins(ui.dp(3),0,ui.dp(3),0);nav.addView(t,lp);tabs[i]=t;t.setOnClickListener(v->{filter=n;limit=60;fill();});}sheet.root.addView(nav);
        ScrollView scroll=new ScrollView(a);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setFillViewport(false);
        grid=new LinearLayout(a);grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(ui.dp(14),ui.dp(4),ui.dp(14),ui.dp(6));scroll.addView(grid);
        sheet.root.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        status=sheet.note("");sheet.root.addView(status);
        LinearLayout tools=new LinearLayout(a);tools.setGravity(Gravity.CENTER);
        TextView refresh=ui.text("Обновить",13,Ui.MUTED,true);refresh.setGravity(Gravity.CENTER);refresh.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));refresh.setOnClickListener(v->load());
        TextView capture=ui.text("Выбрать из чата",13,Ui.MUTED,true);capture.setGravity(Gravity.CENTER);capture.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));
        capture.setOnClickListener(v->{StreakSticker.arm();sheet.dialog.dismiss();Toasts.show(a,"Отправьте стикер в чате: мод запомнит его");if(onChosen!=null)onChosen.run();});
        tools.addView(refresh);tools.addView(capture);sheet.root.addView(tools);
        TextView more=ui.text("Показать ещё",13,Theme.accent(),true);more.setGravity(Gravity.CENTER);more.setPadding(0,ui.dp(8),0,ui.dp(8));more.setOnClickListener(v->{limit+=60;fill();});sheet.root.addView(more);
        save=sheet.button("Сохранить",true,()->{
            if(choice==null){if(pending.equals(StreakSticker.chosenId())&&!pending.isEmpty())sheet.dialog.dismiss();return;}
            StreakSticker.choose(choice.item,choice.fav?"из избранных":"из списка");
            sheet.dialog.dismiss();if(onChosen!=null)onChosen.run();
        });
        scroll.setTag(this);
        fill();
    }
    private void open(){sheet.show();StreakHooks.prepareSender();load();}
    private void load(){
        if(loading)return;loading=true;status.setText("Загрузка…");status.setVisibility(View.VISIBLE);
        StickerLibrary.refresh(()->{loading=false;if(sheet.dialog.isShowing())fill();});
    }
    private void fill(){
        List<StickerLibrary.Entry> all=StickerLibrary.list();
        if(!pending.isEmpty()&&StickerLibrary.byId(pending)==null&&pending.equals(StreakSticker.chosenId())){
            Object chosen=StreakSticker.get();if(chosen!=null){StickerLibrary.add(chosen,false);all=StickerLibrary.list();}
        }
        for(int i=0;i<tabs.length;i++){tabs[i].setBackground(ui.round(i==filter?Theme.accent():Ui.FIELD,13));tabs[i].setTextColor(i==filter?Theme.onColor(Theme.accent()):Ui.INK);}
        final int mode=filter;all.removeIf(e->mode==1&&e.fav||mode==2&&!e.fav);
        grid.removeAllViews();tiles.clear();labels.clear();
        int available=Math.round(act.getResources().getDisplayMetrics().widthPixels/act.getResources().getDisplayMetrics().density);
        int cols=Math.max(2,Math.min(5,(available-28)/86)),count=Math.min(limit,all.size());
        LinearLayout line=null;
        for(int i=0;i<count;i++){
            if(i%cols==0){line=new LinearLayout(act);line.setGravity(Gravity.START);grid.addView(line,new LinearLayout.LayoutParams(-1,-2));}
            StickerLibrary.Entry e=all.get(i);if(e.id.equals(pending))choice=e;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp((available-28)/cols-8),ui.dp(88));lp.setMargins(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));line.addView(tile(e),lp);
        }
        String message=count==0?"В этой категории ещё нет загруженных стикеров":all.size()>count?"Показано "+count+" из "+all.size():"";
        status.setText(message);status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
        ScrollView scroll=(ScrollView)grid.getParent();
        int rows=(count+cols-1)/cols;
        scroll.getLayoutParams().height=ui.dp(Math.min(Math.max(96,rows*96+10),Math.max(96,Math.min(280,Math.round(act.getResources().getDisplayMetrics().heightPixels/act.getResources().getDisplayMetrics().density*.30f)))));
        scroll.requestLayout();paint();
    }
    private LinearLayout tile(StickerLibrary.Entry e){
        LinearLayout t=new LinearLayout(act);t.setOrientation(LinearLayout.VERTICAL);t.setGravity(Gravity.CENTER);t.setPadding(ui.dp(5),ui.dp(5),ui.dp(5),ui.dp(4));
        ImageView iv=new ImageView(act);iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        t.addView(iv,new LinearLayout.LayoutParams(ui.dp(54),ui.dp(54)));
        if(e.thumb!=null)iv.setImageBitmap(e.thumb);else{iv.setImageDrawable(Icons.of(Icons.STICKER,Ui.MUTED));StickerLibrary.thumb(e,()->{if(e.thumb!=null)iv.setImageBitmap(e.thumb);});}
        // The preview may already have requested this thumbnail; observe completion without a second download.
        iv.postDelayed(new Runnable(){int attempts;@Override public void run(){if(!sheet.dialog.isShowing()&&iv.isAttachedToWindow())return;if(e.thumb!=null){iv.setImageBitmap(e.thumb);return;}if(!e.failed&&attempts++<15&&iv.isAttachedToWindow())iv.postDelayed(this,400);}},400);
        TextView title=ui.text("",12,Ui.MUTED,true);title.setSingleLine(true);title.setGravity(Gravity.CENTER);t.addView(title,new LinearLayout.LayoutParams(-1,-2));
        tiles.put(e.id,t);labels.put(e.id,title);
        t.setContentDescription(e.title==null||e.title.isEmpty()?"Стикер":e.title);
        t.setOnClickListener(v->{pending=e.id;choice=e;paint();});return t;
    }
    private void paint(){
        int accent=Theme.accent();
        for(Map.Entry<String,LinearLayout> e:tiles.entrySet()){
            boolean selected=e.getKey().equals(pending);GradientDrawable bg=ui.round(selected?Ui.alpha(accent,0x28):Ui.FIELD,15);
            if(selected)bg.setStroke(ui.dp(2),accent);
            e.getValue().setBackground(bg);e.getValue().setSelected(selected);
            TextView label=labels.get(e.getKey());label.setText(selected?"Выбран":"");label.setTextColor(accent);
        }
        boolean valid=choice!=null||(!pending.isEmpty()&&pending.equals(StreakSticker.chosenId()));
        save.setEnabled(valid);save.setAlpha(valid?1f:.5f);
    }
}
