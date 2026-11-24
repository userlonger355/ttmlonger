package dev.ttmlonger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

final class CommentBackgroundScreen extends Screen {
    private TextView preview, value;
    private CommentBackgroundScreen(Activity a) { super(a); }
    static void show(Activity a) { new CommentBackgroundScreen(a).open("Фон комментариев", "Своё изображение, сохранённое на устройстве."); }

    @Override void build(LinearLayout body) {
        body.addView(ui.header("Предпросмотр"));
        preview = ui.text("Комментарии\n\nПример текста комментария\n♡  Ответить", 16, Ui.INK, false);
        preview.setPadding(ui.dp(20),ui.dp(18),ui.dp(20),ui.dp(18));
        LinearLayout.LayoutParams pp=ui.matchWrap();
        pp.height=ui.dp(170); pp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(8));
        body.addView(preview,pp);
        body.addView(ui.menuRow(Icons.SLIDERS,"Выбрать изображение","Открыть галерею",null,v -> pick()));
        body.addView(ui.menuRow(Icons.REFRESH,"Стандартный фон","Удалить своё изображение",null,v -> {
            CommentBackground.clear(act); refresh();
        }));
        body.addView(ui.header("Размытие"));
        value=ui.note(Prefs.commentBlur+"%");
        body.addView(value);
        SeekBar slider=new SeekBar(act);
        slider.setMax(100); slider.setProgress(Prefs.commentBlur);
        LinearLayout.LayoutParams sp=ui.matchWrap(); sp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(8)); body.addView(slider,sp);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s,int p,boolean user) { value.setText(p+"%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                Prefs.setCommentBlur(s.getProgress()); refresh(); CommentBackground.refresh();
            }
        });
        body.addView(ui.note("Изменения видны при повторном открытии комментариев. Если лист не распознан, остаётся стандартный фон."));
        refresh();
    }
    private void refresh() {
        Bitmap b=CommentBackground.image();
        boolean dark=Theme.onColor(Ui.BG)==android.graphics.Color.WHITE;
        preview.setBackground(b==null?ui.round(Ui.FIELD,16):CommentBackground.drawable(b,dark));
    }
    private void pick() {
        Intent i=new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        if (!ActivityResults.start(act,i,(code,data) -> {
            if(code!=Activity.RESULT_OK || data==null || data.getData()==null)return;
            new Thread(() -> {
                try {
                    CommentBackground.importImage(act,data.getData());
                    act.runOnUiThread(() -> { refresh(); CommentBackground.refresh(); Toasts.show(act,"Фон сохранён"); });
                } catch(Throwable t) {
                    FlagStore.log(t);
                    act.runOnUiThread(() -> Toasts.show(act,"Не удалось открыть изображение"));
                }
            },"ttmlonger-wallpaper").start();
        })) Toasts.show(act,"Не удалось открыть галерею");
    }
}
