package dev.ttmlonger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import android.view.*;

/** Preview and local photo selection. Blur and contrast affect only the image. */
final class CommentWallpaperScreen extends Screen {
    private LinearLayout sample;
    private TextView blurText,dimText;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean loading;
    private int blur=CommentWallpaper.blur(),dim=CommentWallpaper.dim();
    private CommentWallpaperScreen(Activity a){super(a);}
    static void show(Activity a){new CommentWallpaperScreen(a).open("Фон комментариев","Своя картинка под списком комментариев. Фото хранится только на устройстве.");}
    @Override void build(LinearLayout body){
        sample=new LinearLayout(act);sample.setOrientation(LinearLayout.VERTICAL);sample.setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(16));
        LinearLayout.LayoutParams lp=ui.matchWrap();lp.setMargins(ui.dp(16),ui.dp(8),ui.dp(16),ui.dp(8));body.addView(sample,lp);
        TextView title=ui.medium("Комментарии",16,Ui.INK);title.setGravity(Gravity.CENTER);sample.addView(title);
        for(String text:new String[]{"@user  ·  Только что\nОтличное видео!","@friend  ·  2 мин\nКрасивый фон ✨","@viewer  ·  5 мин\nТекст остаётся чётким."}){
            TextView row=ui.text(text,14,Ui.INK,false);row.setPadding(0,ui.dp(15),0,ui.dp(8));sample.addView(row);
        }
        body.addView(ui.menuRow(Icons.IMAGE,"Выбрать картинку","Из галереи телефона",null,v->pick()));
        body.addView(ui.toggleRow(-1,"Свой фон",null,CommentWallpaper.enabled(),on->{
            if(on&&!CommentWallpaper.exists()){Toasts.show(act,"Сначала выберите картинку");dialog.dismiss();show(act);return;}
            CommentWallpaper.configure(on,blur,dim);render();
        }));
        blurText=ui.header("Размытие · "+blur+"%");body.addView(blurText);
        slider(body,blur,true);
        dimText=ui.header("Приглушение фона · "+dim+"%");body.addView(dimText);
        slider(body,dim,false);
        body.addView(ui.menuRow(Icons.REFRESH,"Вернуть стандартный фон",null,null,v->{CommentWallpaper.reset();dialog.dismiss();show(act);}));
        body.addView(ui.note("Фон применяется при открытии комментариев. Если TikTok использует неподдерживаемую разметку, стандартный фон сохраняется."));
        render();main.postDelayed(()->waitRender(0),150);
    }
    private void slider(LinearLayout body,int initial,boolean isBlur){
        SeekBar bar=new SeekBar(act);bar.setMax(100);bar.setProgress(initial);
        LinearLayout.LayoutParams lp=ui.matchWrap();lp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(8));body.addView(bar,lp);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar v){}
            public void onProgressChanged(SeekBar v,int n,boolean user){
                if(isBlur){blur=n;blurText.setText("Размытие · "+n+"%");}
                else{dim=n;dimText.setText("Приглушение фона · "+n+"%");render();}
                if(user){main.removeCallbacks(commit);main.postDelayed(commit,160);}
            }
            public void onStopTrackingTouch(SeekBar v){main.removeCallbacks(commit);commit.run();}
        });
    }
    private final Runnable commit=()->{CommentWallpaper.configure(CommentWallpaper.enabled(),blur,dim);waitRender(0);};
    private void waitRender(int n){
        if(dialog==null||!dialog.isShowing())return;render();
        if(n<8)main.postDelayed(()->waitRender(n+1),140);
    }
    private void render(){
        Bitmap b=CommentWallpaper.image();
        if(b==null){sample.setBackground(ui.round(Ui.CARD,18));return;}
        sample.setBackground(CommentWallpaper.drawable(b,dim,Ui.DARK,ui.dp(18)));
        sample.setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(16));
    }
    private void pick(){
        if(loading)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        if(!ActivityResults.start(act,intent,(code,data)->{
            if(code!=Activity.RESULT_OK||data==null||data.getData()==null)return;
            loading=true;CommentWallpaper.importImage(data.getData(),ok->{
                loading=false;if(act.isFinishing()||act.isDestroyed())return;
                if(!ok){Toasts.show(act,"Не удалось открыть картинку");return;}
                dialog.dismiss();show(act);
            });
        }))Toasts.show(act,"Не удалось открыть галерею");
    }
}
