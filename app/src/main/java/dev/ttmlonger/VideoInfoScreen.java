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
        return Prefs.vtTime || Prefs.vtRegion ? "Вкл" : "Выкл";
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
        formatRow = ui.menuRow(-1, "Вид времени", "«Давность, потом дата»: «5 ч. назад», а видео старше недели — "
                        + "«26.09.2026». «Точное время»: «26.09 02:32:00»", VideoInfo.formatLabel(Prefs.vtFormat),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setVtFormat(VideoInfo.nextFormat(Prefs.vtFormat));
                        TextView val = Ui.valueOf(formatRow);
                        if (val != null) val.setText(VideoInfo.formatLabel(Prefs.vtFormat));
                        changed();
                    }
                });
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
        modeRow = ui.menuRow(-1, "Вид региона", null, VideoInfo.modeLabel(Prefs.vtRegionMode),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setVtRegionMode(VideoInfo.nextMode(Prefs.vtRegionMode));
                        TextView val = Ui.valueOf(modeRow);
                        if (val != null) val.setText(VideoInfo.modeLabel(Prefs.vtRegionMode));
                        changed();
                    }
                });
        body.addView(modeRow);

        body.addView(ui.header("Вид"));
        previewHost = new FrameLayout(act);
        previewHost.setBackground(ui.round(0xFF17171B, 14));
        previewHost.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroupLP.MATCH, ViewGroupLP.WRAP);
        pl.setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(10));
        body.addView(previewHost, pl);
        styleRow = ui.menuRow(-1, "Оформление", "Контраст текста даты и флага",
                VideoInfo.styleLabel(Prefs.vtStyle), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setVtStyle(VideoInfo.nextStyle(Prefs.vtStyle));
                        TextView val = Ui.valueOf(styleRow);
                        if (val != null) val.setText(VideoInfo.styleLabel(Prefs.vtStyle));
                        changed();
                    }
                });
        body.addView(styleRow);
        posRow = ui.menuRow(-1, "Положение", "«Авто» ставит плашки туда, где они ничего не закрывают: справа от "
                        + "имени, иначе над ним или под ним", VideoInfo.posLabel(Prefs.vtPos),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setVtPos(VideoInfo.nextPos(Prefs.vtPos));
                        TextView val = Ui.valueOf(posRow);
                        if (val != null) val.setText(VideoInfo.posLabel(Prefs.vtPos));
                        changed();
                    }
                });
        body.addView(posRow);
        body.addView(opacityRow());

        refreshPreview();
        refreshOptions();
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
