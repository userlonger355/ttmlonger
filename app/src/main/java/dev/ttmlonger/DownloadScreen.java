package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** «Скачивание»: видео без водяного знака, любое качество, обход запрета автора, стикеры. */
final class DownloadScreen extends Screen {
    private static final String[] LABELS = {
            "Как у TikTok", "Лучшее", "Не выше 1080p", "Не выше 720p", "Не выше 540p",
            "Не выше 480p", "Не выше 360p", "Наименьшее"};
    private static final int[] VALUES = {0, -1, 1080, 720, 540, 480, 360, -2};

    private LinearLayout qualityRow;

    private DownloadScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new DownloadScreen(act).open("Скачивание", "Видео, фото и стикеры");
    }

    /** Название качества для показа (и для проверок). */
    static String qualityLabel(int value) {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == value) return LABELS[i];
        }
        return value > 0 ? "Не выше " + value + "p" : "Как у TikTok";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(toggleRow("Без водяного знака",
                "Видео и фото сохраняются чистыми, как в плеере",
                Prefs.dlNoWatermark, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setDlNoWatermark(on);
                    }
                }));

        body.addView(toggleRow("Скачивать всегда",
                "Кнопка «Сохранить» остаётся, даже если автор запретил скачивание",
                Prefs.dlAlways, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setDlAlways(on);
                    }
                }));

        qualityRow = ui.menuRow("Качество видео", qualityLabel(Prefs.dlQuality),
                "Какой размер брать при сохранении. «Лучшее» — самый крупный из тех, в которых "
                        + "TikTok выложил это видео",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickQuality();
                    }
                });
        body.addView(qualityRow);

        body.addView(toggleRow("Сохранение стикеров",
                "Кнопка ⬇ на листе стикера в переписке и комментариях",
                Prefs.saveStickers, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setSaveStickers(on);
                    }
                }));

        TextView foot = ui.text("Хуки скачивания: " + DownloadHooks.status()
                + "\n\nСохраняйте для себя. Чужие видео, фото и стикеры не публикуйте без согласия автора.",
                13, Ui.MUTED, false);
        foot.setPadding(ui.dp(27), ui.dp(24), ui.dp(27), 0);
        body.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void pickQuality() {
        int checked = 1;
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == Prefs.dlQuality) checked = i;
        }
        AlertDialog d = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Качество видео")
                .setSingleChoiceItems(LABELS, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        Prefs.setDlQuality(VALUES[which]);
                        TextView sub = Ui.subtitleOf(qualityRow);
                        if (sub != null) sub.setText(qualityLabel(Prefs.dlQuality));
                        dlg.dismiss();
                    }
                })
                .create();
        markOwn(d);
        d.show();
    }
}
