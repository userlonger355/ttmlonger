package dev.ttmlonger;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * «Время и регион видео»: плашки над именем автора в ленте. Работают сразу, перезапуск TikTok не
 * нужен: мод читает данные у видео, которое TikTok уже показывает.
 */
final class VideoInfoScreen extends Screen {
    private LinearLayout modeRow;
    private LinearLayout secondsRow;
    private LinearLayout formatRow;
    private LinearLayout styleRow;
    private LinearLayout posRow;
    private LinearLayout weightRow;
    private FrameLayout previewHost;
    private TextView opacityValue;

    private VideoInfoScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new VideoInfoScreen(act).open("Время и регион видео",
                "Рядом с именем автора в ленте появляется строка: когда выложено видео («3 дн. назад», у старых — "
                        + "дата, или точное время) и из какой оно страны. Нажмите на дату, чтобы увидеть подробности.");
    }

    static String menuSummary() {
        return Prefs.vtMaster() ? "Вкл" : "Выкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Время"));
        body.addView(toggleRow(Icons.CLOCK, "Время публикации",
                "Когда автор выложил видео: «3 дн. назад», «26.09.2026» или точное время",
                Prefs.vtTime, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setVtTime(on);
                        changed();
                    }
                }));
        body.addView(toggleRow(Icons.CLOCK,"Точное время репоста","Отдельная дата и время с секундами из данных репоста",Prefs.vtRepost,on->{Prefs.setVtRepost(on);changed();}));
        formatRow=ui.menuRow(-1,"Вид времени",null,VideoInfo.formatLabel(Prefs.vtFormat),v->ChoiceSheet.show(act,"Вид времени",new String[]{"Точное время","Давность, потом дата","Месяц-день (10-13)"},Prefs.vtFormat,which->{Prefs.setVtFormat(which);TextView val=Ui.valueOf(formatRow);if(val!=null)val.setText(VideoInfo.formatLabel(Prefs.vtFormat));changed();}));
        body.addView(formatRow);
        secondsRow = toggleRow(-1, "Секунды", "Для точного времени: 02:32:00 вместо 02:32",
                Prefs.vtSeconds, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setVtSeconds(on);
                        changed();
                    }
                });
        body.addView(secondsRow);

        body.addView(ui.header("Регион"));
        body.addView(toggleRow(Icons.GLOBE, "Регион видео",
                "Флаг и код региона публикации, если он указан в модели видео",
                Prefs.vtRegion, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setVtRegion(on);
                        changed();
                    }
                }));
        modeRow=ui.menuRow(-1,"Вид региона",null,VideoInfo.modeLabel(Prefs.vtRegionMode),v->ChoiceSheet.show(act,"Вид региона",new String[]{"Флаг и код","Только флаг","Только код"},Prefs.vtRegionMode,which->{Prefs.setVtRegionMode(which);TextView val=Ui.valueOf(modeRow);if(val!=null)val.setText(VideoInfo.modeLabel(Prefs.vtRegionMode));changed();}));
        body.addView(modeRow);

        body.addView(ui.header("Вид"));
        previewHost = new FrameLayout(act);
        previewHost.setBackground(ui.round(0xFF17171B, 14));
        previewHost.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroupLP.MATCH, ViewGroupLP.WRAP);
        pl.setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(10));
        body.addView(previewHost, pl);
        styleRow=ui.menuRow(-1,"Оформление",null,VideoInfo.styleLabel(Prefs.vtStyle),v->ChoiceSheet.show(act,"Оформление",new String[]{"По теме приложения","Тёмные","Светлые","Компактная строка"},Prefs.vtStyle,which->{Prefs.setVtStyle(which);TextView val=Ui.valueOf(styleRow);if(val!=null)val.setText(VideoInfo.styleLabel(Prefs.vtStyle));changed();}));
        body.addView(styleRow);
        weightRow=ui.menuRow(-1,"Жирность шрифта",null,weightLabel(),v->weight());body.addView(weightRow);
        body.addView(toggleRow(-1,"Подложка","Фон за датой и регионом",Prefs.vtBackground,on->{Prefs.setVtBackground(on);changed();}));
        posRow=ui.menuRow(-1,"Положение",null,VideoInfo.posLabel(Prefs.vtPos),v->ChoiceSheet.show(act,"Положение",new String[]{"Авто","Справа от имени","Над именем","Под именем"},Prefs.vtPos,which->{Prefs.setVtPos(which);TextView val=Ui.valueOf(posRow);if(val!=null)val.setText(VideoInfo.posLabel(Prefs.vtPos));changed();}));
        body.addView(posRow);
        body.addView(opacityRow());

        refreshPreview();
        refreshOptions();
    }

    private static final String[] WEIGHTS={"Лёгкий","Обычный","Средний","Полужирный","Жирный","Очень жирный"};
    private String weightLabel(){return WEIGHTS[Math.max(0,Math.min(5,(Prefs.vtWeight-300)/100))];}
    private void weight(){
        StreakSheet s=new StreakSheet(act,"Жирность шрифта");int[] picked={Math.max(0,Math.min(5,(Prefs.vtWeight-300)/100))};
        LinearLayout rows=new LinearLayout(act);rows.setOrientation(1);rows.setPadding(ui.dp(16),0,ui.dp(16),0);
        final Runnable[] draw=new Runnable[1];draw[0]=()->{rows.removeAllViews();for(int i=0;i<WEIGHTS.length;i++){final int n=i;LinearLayout row=new LinearLayout(act);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(ui.dp(12),0,ui.dp(12),0);row.setBackground(ui.round(i==picked[0]?Ui.alpha(Theme.accent(),0x25):Ui.FIELD,14));TextView name=ui.medium(WEIGHTS[i],14,Ui.INK);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView sample=ui.text("· 10-13 · 🇫🇷 FR",14,Ui.INK,false);sample.setTypeface(android.os.Build.VERSION.SDK_INT>=28?android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,300+i*100,false):android.graphics.Typeface.create("sans-serif",i>=3?1:0));row.addView(sample);if(i==picked[0])row.addView(ui.icon(Icons.CHECK,Theme.accent(),20));row.setOnClickListener(v->{picked[0]=n;draw[0].run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,ui.dp(46));lp.bottomMargin=ui.dp(4);rows.addView(row,lp);}};draw[0].run();
        android.widget.ScrollView sc=new android.widget.ScrollView(act);sc.addView(rows);s.root.addView(sc,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(300),(int)(act.getResources().getDisplayMetrics().heightPixels*.55f))));s.button("Сохранить",true,()->{Prefs.setVtWeight(300+100*picked[0]);TextView val=Ui.valueOf(weightRow);if(val!=null)val.setText(weightLabel());changed();s.dialog.dismiss();});s.show();
    }

    /** Строка с ползунком непрозрачности плашек: от 20 до 100 процентов. */
    private LinearLayout opacityRow() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text("Непрозрачность", 16f, Ui.INK, false);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroupLP.WRAP, 1f));
        opacityValue = ui.text(Prefs.vtOpacity + "%", 15f, Ui.MUTED, false);
        head.addView(opacityValue, new LinearLayout.LayoutParams(ViewGroupLP.WRAP, ViewGroupLP.WRAP));
        col.addView(head, ui.matchWrap());
        TextView sub = ui.text("Ниже процент — плашки прозрачнее и меньше отвлекают от видео", 13f, Ui.MUTED, false);
        sub.setPadding(0, ui.dp(2), 0, ui.dp(4));
        col.addView(sub, ui.matchWrap());
        SeekBar bar = new SeekBar(act);
        bar.setMax(80);
        bar.setProgress(VideoInfo.clampOpacity(Prefs.vtOpacity) - 20);
        ColorStateList accent = ColorStateList.valueOf(Theme.accent());
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.DIVIDER));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                int v = VideoInfo.clampOpacity(progress + 20);
                opacityValue.setText(v + "%");
                if (fromUser) {
                    Prefs.vtOpacity = v;
                    refreshPreview();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                Prefs.setVtOpacity(b.getProgress() + 20);
                changed();
            }
        });
        col.addView(bar, ui.matchWrap());
        return col;
    }

    /** Образец плашек на тёмном фоне: показывает оформление и непрозрачность, не заходя в ленту. */
    private void refreshPreview() {
        if (previewHost == null) return;
        previewHost.removeAllViews();
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.text("Автор", 16f, 0xFFFFFFFF, true);
        row.addView(name, new LinearLayout.LayoutParams(ViewGroupLP.WRAP, ViewGroupLP.WRAP));
        View pills = VideoInfo.preview(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroupLP.WRAP, ViewGroupLP.WRAP);
        lp.setMarginStart(ui.dp(8));
        row.addView(pills, lp);
        previewHost.addView(row, new FrameLayout.LayoutParams(ViewGroupLP.WRAP, ViewGroupLP.WRAP,
                Gravity.CENTER_VERTICAL | Gravity.START));
    }

    private void changed() {
        VideoInfo.kick();
        refreshPreview();
        refreshOptions();
    }

    private void refreshOptions() {
        formatRow.setVisibility(Prefs.vtTime ? View.VISIBLE : View.GONE);
        secondsRow.setVisibility(Prefs.vtTime && Prefs.vtFormat == VideoInfo.FORMAT_EXACT ? View.VISIBLE : View.GONE);
        modeRow.setVisibility(Prefs.vtRegion ? View.VISIBLE : View.GONE);
    }

    /** Короткие имена размеров макета. */
    private static final class ViewGroupLP {
        static final int MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
        static final int WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
    }
}
