package dev.ttmlonger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * «Видео и истории»: то, что TikTok показывал последним, с кнопкой «Сохранить». Сохраняет своим
 * загрузчиком с проверкой файла (VideoSaver), поэтому подходит и для видео с запретом скачивания,
 * и для историй, у которых в TikTok нет кнопки «Сохранить».
 */
final class VideoScreen extends Screen {
    private static final int MAX_ROWS = 8;
    private static final long PLAYING_MS = 20_000L;
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ttmlonger-thumb");
            t.setDaemon(true);
            return t;
        }
    });

    private final List<Row> rows = new ArrayList<>();
    private LinearLayout bodyView;

    private static final class Row {
        VideoTracker.Entry entry;
        TextView sub;
        TextView button;
        String tags;
        boolean photo;
    }

    private VideoScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new VideoScreen(act).open("Видео и истории",
                "То, что TikTok показывал последним. Откройте видео или историю, вызовите меню двумя "
                        + "пальцами и зайдите сюда");
    }

    @Override
    void build(LinearLayout body) {
        bodyView = body;
        final Context app = act.getApplicationContext() != null ? act.getApplicationContext() : act;

        List<VideoTracker.Entry> all = VideoTracker.recent();
        long now = VideoTracker.now();
        VideoTracker.Entry playing = null;
        for (VideoTracker.Entry e : all) {
            if (e.played > 0 && now - e.played < PLAYING_MS && (playing == null || e.played > playing.played)) {
                playing = e;
            }
        }

        Set<String> seenIds = new HashSet<>();
        int n = 0;
        for (final VideoTracker.Entry e : all) {
            if (n >= MAX_ROWS) break;
            String aid = null;
            try {
                aid = e.aid();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            if (aid != null && !seenIds.add(aid)) continue;

            final Row row = new Row();
            row.entry = e;
            List<String> tags = new ArrayList<>();
            if (e == playing) tags.add("играет сейчас");
            boolean story = false;
            try {
                story = e.isStory();
                row.photo = !VideoSaver.photoCandidates(e.aweme).isEmpty();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            if (story) tags.add("история");
            if (row.photo) tags.add("фото");
            if (e.isProhibited()) tags.add("запрет скачивания");
            row.tags = join(tags);

            String title = titleOf(e, aid);
            body.addView(rowView(row, title, VideoSaver.coverUrl(e), app));
            rows.add(row);
            n++;
        }

        if (rows.isEmpty()) {
            TextView none = ui.text("Пока ничего нет. Закройте меню, откройте видео или историю в TikTok, "
                    + "подождите, пока оно начнёт играть, вызовите меню двумя пальцами и зайдите сюда снова.",
                    16, Ui.MUTED, false);
            none.setPadding(ui.dp(27), ui.dp(16), ui.dp(27), 0);
            body.addView(none, ui.matchWrap());
        }

        TextView foot = ui.text(VideoTracker.status() + "\nХуки: " + DownloadHooks.status()
                + "\nПоследняя ошибка: " + VideoSaver.lastError()
                + "\n\nСохраняйте для себя. Чужие видео и истории не публикуйте без согласия автора.",
                12, Ui.MUTED, false);
        foot.setPadding(ui.dp(27), ui.dp(20), ui.dp(27), 0);
        body.addView(foot, ui.matchWrap());

        tick();
    }

    private static String titleOf(VideoTracker.Entry e, String aid) {
        String author = null;
        String desc = null;
        try {
            author = e.author();
            desc = e.desc();
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        StringBuilder b = new StringBuilder();
        if (author != null) b.append('@').append(author.startsWith("@") ? author.substring(1) : author);
        if (desc != null && !desc.isEmpty()) {
            if (b.length() > 0) b.append(": ");
            b.append(desc.length() > 60 ? desc.substring(0, 60) + "…" : desc);
        }
        if (b.length() == 0) b.append(aid != null ? "Пост " + aid : "Пост без названия");
        return b.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (b.length() > 0) b.append(" · ");
            b.append(p);
        }
        return b.toString();
    }

    private LinearLayout rowView(final Row row, String title, String coverUrl, final Context app) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        card.setBackground(ui.round(Ui.CARD, 24));

        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(ui.round(0xFF262626, 12));
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(64), ui.dp(86));
        lp.setMarginEnd(ui.dp(14));
        card.addView(iv, lp);
        loadThumb(coverUrl, iv);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.text(title, 15, Ui.WHITE, true);
        t.setMaxLines(3);
        col.addView(t);
        row.sub = ui.text(row.tags.isEmpty() ? " " : row.tags, 12, Ui.MUTED, true);
        row.sub.setPadding(0, ui.dp(4), ui.dp(8), 0);
        col.addView(row.sub);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.button = ui.text(row.photo ? "Сохранить фото" : "Сохранить", 14, Color.WHITE, true);
        row.button.setGravity(Gravity.CENTER);
        row.button.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        row.button.setBackground(ui.round(Ui.RED, 20));
        row.button.setClickable(true);
        row.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    VideoSaver.start(app, row.entry);
                    refresh(row);
                } catch (Throwable ex) {
                    FlagStore.log(ex);
                    Toasts.show(app, "Не удалось начать сохранение: " + ex.getClass().getSimpleName());
                }
            }
        });
        card.addView(row.button);

        card.setLayoutParams(sidePadded());
        return card;
    }

    /** Обновляет подпись и кнопку строки по состоянию её задания. */
    private void refresh(Row row) {
        VideoSaver.Job j = VideoSaver.jobFor(row.entry.aweme != null ? row.entry.aweme : row.entry.video);
        String text = row.tags;
        if (j != null && !j.state.isEmpty()) text = (text.isEmpty() ? "" : text + "\n") + j.state;
        row.sub.setText(text.isEmpty() ? " " : text);
        boolean running = j != null && j.running;
        row.button.setAlpha(running ? 0.5f : 1f);
        row.button.setText(running ? "Идёт…" : (j != null && j.ok ? "Ещё раз"
                : (row.photo ? "Сохранить фото" : "Сохранить")));
    }

    /** Пока экран открыт, раз в полсекунды показывает ход сохранения. */
    private void tick() {
        if (bodyView == null) return;
        bodyView.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (dialog == null || !dialog.isShowing()) return;
                    for (Row r : rows) refresh(r);
                    bodyView.postDelayed(this, 500L);
                } catch (Throwable ignored) {
                    // экран закрыт
                }
            }
        }, 500L);
    }

    // ------------------------------------------------------------ превью

    private void loadThumb(final String url, final ImageView iv) {
        if (url == null) return;
        THUMBS.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] data = AvatarSaver.download(url);
                    final Bitmap b = decode(data, 260);
                    if (b == null) return;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            iv.setImageBitmap(b);
                        }
                    });
                } catch (Throwable ignored) {
                    // превью не обязательно
                }
            }
        });
    }

    /** Картинка не крупнее ~target по меньшей стороне: список не должен занимать память под большие обложки. */
    static Bitmap decode(byte[] data, int target) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int side = Math.min(o.outWidth, o.outHeight);
        if (side <= 0) return null;
        int sample = 1;
        while (side / (sample * 2) >= target) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
