package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
    private static final String[] TYPES = {"Все публикации", "Видео", "Фото", "Истории"};
    private static final String[] SORTS = {"По просмотру", "Сначала новые публикации", "Сначала старые публикации"};
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
    private LinearLayout results;
    private TextView empty;
    private String query = "";
    private int type;
    private int sort;

    private static final class Row {
        VideoTracker.Entry entry;
        TextView sub;
        TextView button;
        android.widget.ProgressBar progress;
        String tags;
        boolean photo;
        boolean story;
        String searchable;
        long published;
        LinearLayout card;
    }

    private VideoScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new VideoScreen(act).open("Видео и истории",
                "То, что TikTok показывал последним. Откройте видео или историю, удерживайте вкладку «Главная» и зайдите сюда");
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

        for (final VideoTracker.Entry e : all) {

            String aid = null;
            try {
                aid = e.aid();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            if (aid != null && !aid.isEmpty() && !seenIds.add(aid)) continue;

            final Row row = new Row();
            row.entry = e;
            List<String> tags = new ArrayList<>();
            if (e == playing) tags.add("играет сейчас");

            try {
                row.story = e.isStory();
                row.photo = !VideoSaver.photoCandidates(e.aweme).isEmpty();
            } catch (Throwable t) {
                FlagStore.log(t);
            }
            if (row.story) tags.add("история");
            if (row.photo) tags.add("фото");
            if (e.isProhibited()) tags.add("запрет скачивания");
            row.tags = join(tags);

            String author = e.author();
            String desc = e.desc();
            VideoInfo.Keys keys = VideoInfo.keysOf(e);
            row.searchable = normalize(android.text.TextUtils.join(" ", keys.names) + " "
                    + (author == null ? "" : author) + " " + (desc == null ? "" : desc) + " " + (aid == null ? "" : aid));
            row.published = VideoInfo.metaOf(e).created;
            row.card = rowView(row, titleOf(author, desc, aid), VideoSaver.coverUrl(e), app);
            rows.add(row);

        }

        if (rows.isEmpty()) {
            body.addView(ui.note("Пока нет недавних публикаций. Откройте ролик в ленте и вернитесь сюда."));
            return;
        }
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        EditText search = new EditText(act);
        search.setSingleLine(true);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setHint("Автор, описание или ID");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(ui.round(Ui.FIELD, 12));
        search.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        LinearLayout.LayoutParams field = ui.matchWrap();
        field.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4));
        body.addView(search, field);
        addChoice(body, true);
        addChoice(body, false);
        results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        empty = ui.note("Совпадений нет. Измените запрос или тип публикации.");
        body.addView(empty);
        arrange();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = normalize(s.toString());
                filter();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        body.addView(ui.note("Список содержит недавние публикации текущего запуска TikTok."));
        tick();
    }

    private void addChoice(LinearLayout body, boolean byType) {
        String[] choices = byType ? TYPES : SORTS;
        String title = byType ? "Тип публикации" : "Порядок";
        LinearLayout row = ui.menuRow(-1, title, null, choices[0], null);
        row.setOnClickListener(v -> ChoiceSheet.show(act,title,choices,byType?type:sort,which->{
            TextView value=Ui.valueOf(row);
            if(value!=null)value.setText(choices[which]);
            if(byType){type=which;filter();}else{sort=which;arrange();}
        }));
        body.addView(row);
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('\u00a0', ' ').trim();
    }

    static boolean matches(String text, String query, int type, boolean photo, boolean story) {
        if (type == 1 && (photo || story) || type == 2 && !photo || type == 3 && !story) return false;
        for (String word : query.split("\\s+")) if (!text.contains(word)) return false;
        return true;
    }

    static int compareDate(long a, long b, int sort) {
        if (a == 0 || b == 0) return a == b ? 0 : a == 0 ? 1 : -1;
        return sort == 1 ? Long.compare(b, a) : Long.compare(a, b);
    }

    private void arrange() {
        List<Row> ordered = new ArrayList<>(rows);
        if (sort != 0) ordered.sort((a,b) -> compareDate(a.published, b.published, sort));
        results.removeAllViews();
        for (Row row : ordered) results.addView(row.card);
        filter();
    }

    private void filter() {
        boolean any = false;
        for (Row row : rows) {
            boolean show = matches(row.searchable, query, type, row.photo, row.story);
            row.card.setVisibility(show ? View.VISIBLE : View.GONE);
            any |= show;
        }
        empty.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    private static String titleOf(String author, String desc, String aid) {
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
        card.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));

        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(ui.round(Ui.FIELD, 8));
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(60), ui.dp(80));
        lp.setMarginEnd(ui.dp(14));
        card.addView(iv, lp);
        loadThumb(coverUrl, iv);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.medium(title, 15f, Ui.INK);
        t.setMaxLines(3);
        col.addView(t);
        row.sub = ui.text(row.tags.isEmpty() ? " " : row.tags, 12.5f, Ui.MUTED, false);
        row.sub.setPadding(0, ui.dp(4), ui.dp(8), 0);
        col.addView(row.sub);
        row.progress=new android.widget.ProgressBar(act,null,android.R.attr.progressBarStyleHorizontal);
        row.progress.setMax(100);
        row.progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TtStyle.primary()));
        row.progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TtStyle.primary()));
        row.progress.setVisibility(View.GONE);
        col.addView(row.progress,new LinearLayout.LayoutParams(-1,ui.dp(8)));
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.button = TtStyle.primary(act, row.photo ? "Сохранить фото" : "Сохранить", 36f, 14f);
        row.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    VideoSaver.start(app, row.entry);
                    refresh(row);
                } catch (Throwable ex) {
                    FlagStore.log(ex);
                    Toasts.show(app, "Не удалось начать сохранение");
                }
            }
        });
        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(row.button);
        TextView audio = TtStyle.neutral(act, "Скачать звук", 36f, 13f);
        android.graphics.drawable.Drawable note=Icons.of(Icons.MUSIC,Ui.INK);note.setBounds(0,0,ui.dp(16),ui.dp(16));audio.setCompoundDrawablesRelative(note,null,null,null);audio.setCompoundDrawablePadding(ui.dp(5));
        audio.setOnClickListener(v -> AudioSaver.start(app, row.entry));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1,-2);
        ap.topMargin=ui.dp(5);
        actions.addView(audio,ap);
        card.addView(actions);

        ui.decorate(card,0xff7361be);
        refresh(row);
        return card;
    }

    /** Обновляет подпись и кнопку строки по состоянию её задания. */
    private void refresh(Row row) {
        VideoSaver.Job j = VideoSaver.jobFor(row.entry.aweme != null ? row.entry.aweme : row.entry.video);
        String text = row.tags;
        if (j != null && !j.state.isEmpty()) text = (text.isEmpty() ? "" : text + "\n") + j.state;
        row.sub.setText(text.isEmpty() ? " " : text);
        boolean running = j != null && j.running;
        row.progress.setVisibility(j!=null?View.VISIBLE:View.GONE);
        if(j!=null){row.progress.setIndeterminate(running&&j.percent<0);if(j.percent>=0)row.progress.setProgress(j.percent);}
        row.button.setEnabled(!running);
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
