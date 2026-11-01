package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Время и регион видео»: плашки над именем автора в ленте. Работают сразу, перезапуск TikTok не
 * нужен: мод читает данные у видео, которое TikTok уже показывает.
 */
final class VideoInfoScreen extends Screen {
    private TextView state;
    private LinearLayout modeRow;
    private LinearLayout secondsRow;

    private VideoInfoScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new VideoInfoScreen(act).open("Время и регион видео",
                "Над именем автора в ленте появляются плашки: когда выложено видео (с секундами) и из какой оно "
                        + "страны. Нажмите на плашку, чтобы увидеть подробности.");
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

        body.addView(ui.header("Состояние"));
        state = ui.text("", 14f, Ui.INK, false);
        state.setLineSpacing(0f, 1.15f);
        state.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(state, ui.matchWrap());

        body.addView(ui.row(Icons.REFRESH, "Обновить состояние", "Мод ищет видео на экране каждую секунду, "
                + "пока вы в ленте", null, false, new View.OnClickListener() {
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
        refresh();
    }

    private void changed() {
        VideoInfo.kick();
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
}
