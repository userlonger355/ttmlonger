package dev.ttmlonger;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.*;

/** A native themed sheet; captures the selected post before the share sheet closes. */
final class DownloadChoice {
    static void show(Activity a,VideoTracker.Entry e){
        if(e==null){Toasts.show(a,"Видео ещё не распознано");return;}
        StreakSheet sheet=new StreakSheet(a,"Скачать через мод");Ui ui=sheet.ui;
        sheet.root.addView(sheet.note("Выберите, что сохранить на устройство"));
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(1);panel.setPadding(ui.dp(16),0,ui.dp(16),0);
        action(a,sheet,panel,Icons.VIDEO,"Видео","Сохранить видео или фото публикации",()->VideoSaver.start(a.getApplicationContext(),e));
        action(a,sheet,panel,Icons.MUSIC,"Звук","Сохранить только аудиодорожку",()->AudioSaver.start(a.getApplicationContext(),e));
        sheet.root.addView(panel,ui.matchWrap());
        sheet.button("Отмена",false,()->sheet.dialog.dismiss());sheet.show();
    }
    private static void action(Activity a,StreakSheet sheet,LinearLayout panel,int glyph,String title,String detail,Runnable task){
        Ui ui=sheet.ui;int accent=Theme.accent();
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(12),ui.dp(13),ui.dp(12),ui.dp(13));
        GradientDrawable card=ui.round(Ui.FIELD,18);row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ui.alpha(accent,0x30)),card,null));
        FrameLayout chip=new FrameLayout(a);chip.setBackground(ui.round(Ui.alpha(accent,0x24),14));
        chip.addView(ui.icon(glyph,accent,24),new FrameLayout.LayoutParams(ui.dp(24),ui.dp(24),Gravity.CENTER));
        row.addView(chip,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        LinearLayout labels=new LinearLayout(a);labels.setOrientation(1);labels.setPadding(ui.dp(12),0,ui.dp(8),0);
        labels.addView(ui.medium(title,16,Ui.INK));labels.addView(ui.text(detail,12,Ui.MUTED,false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(ui.icon(Icons.CHEVRON_RIGHT,Ui.MUTED,18),new LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)));
        row.setContentDescription("Скачать "+title.toLowerCase(java.util.Locale.ROOT));row.setFocusable(true);
        row.setOnClickListener(v->{sheet.dialog.dismiss();try{task.run();}catch(Throwable t){FlagStore.log(t);Toasts.show(a,"Не удалось сохранить");}});
        LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(8);panel.addView(row,lp);
    }
}
