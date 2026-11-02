package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
    private TextView state;
    private LinearLayout modeRow;
    private LinearLayout secondsRow;
    private LinearLayout styleRow;
    private LinearLayout posRow;
    private FrameLayout previewHost;
    private TextView opacityValue;

    private VideoInfoScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new VideoInfoScreen(act).open("Время и регион видео",
                "Рядом с именем автора в ленте появляются плашки: когда выложено видео (с секундами) и из какой "
                        + "оно страны. Нажмите на плашку, чтобы увидеть подробности.");
    }

    static String menuSummary() {
        return Prefs.vtTime || Prefs.vtRegion ? "Вкл" : "Выкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Точное время"));
        body.addView(toggleRow(Icons.CLOCK, "Время публикации",
                "Дата и время, когда автор выложил видео: 26.09 02:32:00",
                Prefs.vtTime, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setVtTime(on);
                        changed();
                    }
                }));
        secondsRow = toggleRow(-1, "Секунды", "Показывать секунды: 02:32:00 вместо 02:32",
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
                "Флаг и код страны, из которой видео. Берётся тот регион, который TikTok указал у видео "
                        + "или, если его нет, у автора",
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
        styleRow = ui.menuRow(-1, "Оформление", "«По теме» — тёмные плашки в тёмной теме TikTok и светлые в светлой",
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

        body.addView(ui.header("Состояние"));
        state = ui.text("", 14f, Ui.INK, false);
        state.setLineSpacing(0f, 1.15f);
        state.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(state, ui.matchWrap());

        body.addView(ui.row(Icons.REFRESH, "Обновить состояние", "Мод ищет видео на экране, пока вы в ленте, "
                + "и сразу при смене видео", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                VideoInfo.kick();
                refreshLater();
            }
        }));
        body.addView(ui.row(Icons.COPY, "Скопировать сведения", "Если плашек нет, пришлите это разработчику "
                + "(имён авторов и описаний в сведениях нет)", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }));
        body.addView(ui.note("Время показывается в часовом поясе телефона. Состояние — результат последней "
                + "проверки экрана: откройте ленту, подождите пару секунд и вернитесь сюда."));
        refreshPreview();
        refresh();
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
        refresh();
    }

    private void refresh() {
        state.setText(VideoInfo.status());
    }

    private void refreshLater() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) refresh();
            }
        }, 700L);
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", VideoInfo.details()));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }

    /** Короткие имена размеров макета. */
    private static final class ViewGroupLP {
        static final int MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
        static final int WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
    }
}
