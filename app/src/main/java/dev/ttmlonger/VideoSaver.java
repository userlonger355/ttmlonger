package dev.ttmlonger;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Своё скачивание видео, историй и фото.
 *
 * Сохранение TikTok у видео с запретом скачивания даёт битый файл: приложение просто пишет на диск
 * то, что ответил сервер, ничего не проверяя. Здесь файл сначала качается во временную папку,
 * проверяется (Mp4Check: заголовок, блоки moov и mdat, файл не обрезан) и только потом попадает в
 * галерею. Если адрес не отдал нормальное видео, берётся следующее зеркало: у каждого размера их
 * несколько. Порядок такой: сначала обычные размеры h264 по выбранному качеству, потом адреса
 * «без водяного знака», воспроизведения и самого TikTok, и в самом конце HEVC (в части галерей
 * он не открывается).
 */
final class VideoSaver {
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final long MAX_BYTES = 600L * 1024 * 1024;
    private static final long TOTAL_MS = 4 * 60_000L;
    private static final int MAX_TRIES = 10;
    private static final int MAX_URLS = 24;

    private static final List<Job> JOBS = new ArrayList<>();
    private static volatile String lastError = "-";
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private VideoSaver() {
    }

    static String lastError() {
        return lastError;
    }

    // ------------------------------------------------------------ задания

    /** Одно сохранение: экран показывает его состояние. */
    static final class Job {
        final Object aweme;
        volatile String state = "";
        volatile boolean running = true;
        volatile boolean ok;

        Job(Object aweme) {
            this.aweme = aweme;
        }
    }

    static Job jobFor(Object aweme) {
        synchronized (JOBS) {
            for (int i = JOBS.size() - 1; i >= 0; i--) {
                if (JOBS.get(i).aweme == aweme) return JOBS.get(i);
            }
        }
        return null;
    }

    private static Job newJob(Object aweme) {
        synchronized (JOBS) {
            for (Job j : JOBS) {
                if (j.aweme == aweme && j.running) return null;
            }
            Job j = new Job(aweme);
            JOBS.add(j);
            while (JOBS.size() > 30) JOBS.remove(0);
            return j;
        }
    }

    // ------------------------------------------------------------ чистая логика (проверяется тестами)

    /**
     * Адреса по порядку попыток. Размеры (options.addr — список адресов) идут по выбранному
     * качеству, h264 раньше HEVC; затем основные адреса в зависимости от «без водяного знака».
     * Повторы убираются, число адресов ограничено.
     */
    static List<String> plan(List<DownloadHooks.Option> options, int mode, boolean noWatermark,
                             List<String> stamped, List<String> clean, List<String> play) {
        List<List<String>> groups = new ArrayList<>();
        List<List<String>> late = new ArrayList<>();
        if (mode != 0 && options != null) {
            for (DownloadHooks.Option o : order(options, mode)) {
                @SuppressWarnings("unchecked")
                List<String> urls = (List<String>) o.addr;
                (o.hevc ? late : groups).add(urls);
            }
        }
        if (noWatermark) {
            groups.add(clean);
            groups.add(play);
            groups.add(stamped);
        } else {
            groups.add(stamped);
            groups.add(clean);
            groups.add(play);
        }
        groups.addAll(late);
        return merge(groups, MAX_URLS);
    }

    /** Все размеры по предпочтению: h264 раньше HEVC, внутри по режиму качества. */
    static List<DownloadHooks.Option> order(List<DownloadHooks.Option> all, int mode) {
        List<DownloadHooks.Option> rest = new ArrayList<>(all);
        List<DownloadHooks.Option> out = new ArrayList<>();
        while (!rest.isEmpty()) {
            DownloadHooks.Option p = DownloadHooks.pick(rest, mode);
            if (p == null) break;
            out.add(p);
            rest.remove(p);
        }
        return out;
    }

    /** Склеивает списки адресов без повторов. */
    static List<String> merge(List<List<String>> groups, int cap) {
        List<String> out = new ArrayList<>();
        for (List<String> g : groups) {
            if (g == null) continue;
            for (String u : g) {
                if (u != null && !out.contains(u)) {
                    out.add(u);
                    if (out.size() >= cap) return out;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ адреса из моделей TikTok

    /** Адреса одной модели UrlModel: только https (http поднимается), без повторов, не больше 16. */
    static List<String> urlsOf(Object model) {
        List<String> out = new ArrayList<>();
        if (model == null) return out;
        Object list = DownloadHooks.call(model, "getUrlList");
        if (!(list instanceof List)) return out;
        int n = 0;
        for (Object o : (List<?>) list) {
            if (n++ >= 16) break;
            if (!(o instanceof String)) continue;
            String s = AvatarHooks.secure(((String) o).trim());
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Адреса видео по порядку попыток. */
    static List<String> videoCandidates(Object video) {
        if (video == null) return new ArrayList<>();
        // getDownloadAddr у нас перехвачен: здесь нужен ответ самого TikTok, без подмен и без подсказки.
        DownloadHooks.QUIET.set(Boolean.TRUE);
        List<String> stamped;
        try {
            stamped = urlsOf(DownloadHooks.call(video, "getDownloadAddr"));
        } finally {
            DownloadHooks.QUIET.remove();
        }
        List<String> clean = urlsOf(DownloadHooks.call(video, "getDownloadNoWatermarkAddr"));
        List<String> play = urlsOf(DownloadHooks.call(video, "getPlayAddr"));
        List<DownloadHooks.Option> opts = new ArrayList<>();
        for (DownloadHooks.Option o : DownloadHooks.options(video)) {
            List<String> u = urlsOf(o.addr);
            if (!u.isEmpty()) opts.add(new DownloadHooks.Option(u, o.rate, o.height, o.hevc));
        }
        return plan(opts, Prefs.dlQuality, Prefs.dlNoWatermark, stamped, clean, play);
    }

    /** Фотопост: по каждой картинке список зеркал (чистая копия первой, если включено). */
    static List<List<String>> photoCandidates(Object aweme) {
        List<List<String>> out = new ArrayList<>();
        Object info = DownloadHooks.call(aweme, "getPhotoModeImageInfo");
        if (info == null) return out;
        Object list = DownloadHooks.call(info, "getImageList");
        try {
            if (!(list instanceof List)) {
                java.lang.reflect.Field f = DownloadHooks.findField(info.getClass(), "imageList");
                list = f == null ? null : f.get(info);
            }
        } catch (Throwable t) {
            return out;
        }
        if (!(list instanceof List)) return out;
        int n = 0;
        for (Object image : (List<?>) list) {
            if (image == null || n++ >= 256) continue;
            List<List<String>> groups = new ArrayList<>();
            String[] names = Prefs.dlNoWatermark
                    ? new String[]{"displayImageNoWatermark", "ownerWatermarkImage", "userWatermarkImage"}
                    : new String[]{"ownerWatermarkImage", "userWatermarkImage", "displayImageNoWatermark"};
            for (String name : names) {
                java.lang.reflect.Field f = DownloadHooks.findField(image.getClass(), name);
                if (f == null) continue;
                try {
                    groups.add(urlsOf(f.get(image)));
                } catch (Throwable ignored) {
                    // это поле не читается
                }
            }
            List<String> urls = merge(groups, 12);
            if (!urls.isEmpty()) out.add(urls);
        }
        return out;
    }

    /** Адрес обложки для превью в списке (первое фото у фотопоста). */
    static String coverUrl(VideoTracker.Entry e) {
        try {
            Object video = e.video != null ? e.video : DownloadHooks.call(e.aweme, "getVideo");
            for (String name : new String[]{"getCover", "getOriginCover", "getDynamicCover"}) {
                List<String> u = urlsOf(DownloadHooks.call(video, name));
                if (!u.isEmpty()) return u.get(0);
            }
            List<List<String>> photos = photoCandidates(e.aweme);
            if (!photos.isEmpty()) return photos.get(0).get(0);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        return null;
    }

    // ------------------------------------------------------------ запуск

    /**
     * Сохраняет пост (видео, историю или фото) в фоне; возвращает задание или null, если уже идёт
     * или сохранять нечего. Вызывать из любого потока.
     */
    static Job start(final Context app, final VideoTracker.Entry e) {
        if (e == null || e.aweme == null && e.video == null) return null;
        final Object key = e.aweme != null ? e.aweme : e.video;
        final List<List<String>> photos = e.aweme != null ? photoCandidates(e.aweme) : new ArrayList<List<String>>();
        final List<String> urls;
        if (photos.isEmpty()) {
            Object video = e.video != null ? e.video : DownloadHooks.call(e.aweme, "getVideo");
            urls = videoCandidates(video);
            if (urls.isEmpty()) {
                lastError = "у поста нет адреса видео";
                Toasts.show(app, "У этого поста нет адреса видео");
                return null;
            }
        } else {
            urls = null;
        }
        final Job job = newJob(key);
        if (job == null) {
            Toasts.show(app, "Это уже сохраняется");
            return null;
        }
        final String base = baseName(e);
        final boolean story = e.aweme != null && e.isStory();
        Toasts.show(app, photos.isEmpty() ? (story ? "Скачиваю историю…" : "Скачиваю видео…") : "Скачиваю фото…");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!photos.isEmpty()) savePhotos(app, job, photos, base);
                    else saveVideo(app, job, urls, base);
                } catch (Throwable t) {
                    FlagStore.log(t);
                    fail(app, job, t.getClass().getSimpleName() + ": " + t.getMessage());
                } finally {
                    job.running = false;
                }
            }
        }, "ttmlonger-video");
        t.setDaemon(true);
        t.start();
        return job;
    }

    private static String baseName(VideoTracker.Entry e) {
        String aid = e.aweme == null ? null : e.aid();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "_" + (COUNTER.incrementAndGet() % 1000);
        String id = aid == null ? "" : aid.replaceAll("[^A-Za-z0-9_-]", "_");
        if (id.length() > 24) id = id.substring(id.length() - 24);
        String kind = e.aweme != null && e.isStory() ? "story" : "video";
        return "ttm_" + kind + (id.isEmpty() ? "" : "_" + id) + "_" + stamp;
    }

    private static void fail(Context app, Job job, String why) {
        lastError = why;
        job.ok = false;
        job.state = "Не вышло: " + why;
        Diag.note("видео: не сохранено — " + why);
        Toasts.show(app, "Не удалось сохранить: " + why);
    }

    // ------------------------------------------------------------ видео

    private static void saveVideo(Context app, Job job, List<String> urls, String base) throws Exception {
        File tmp = new File(app.getCacheDir(), "ttm_" + System.nanoTime() + ".part");
        try {
            long deadline = System.currentTimeMillis() + TOTAL_MS;
            String why = null;
            String used = null;
            int tries = 0;
            for (String url : urls) {
                if (tries++ >= MAX_TRIES || System.currentTimeMillis() > deadline) break;
                job.state = "Скачиваю: адрес " + tries + " из " + Math.min(urls.size(), MAX_TRIES);
                try {
                    fetch(url, tmp, job, deadline);
                    String bad = Mp4Check.check(tmp);
                    if (bad == null) {
                        used = url;
                        break;
                    }
                    why = host(url) + ": " + bad;
                } catch (IOException ex) {
                    why = host(url) + ": " + ex.getMessage();
                }
                Diag.note("видео: адрес не подошёл — " + why);
            }
            if (used == null) {
                fail(app, job, why == null ? "нет рабочих адресов" : why);
                return;
            }
            byte[] head = new byte[16];
            java.io.FileInputStream in = new java.io.FileInputStream(tmp);
            int n;
            try {
                n = in.read(head);
            } finally {
                in.close();
            }
            String ext = "webm".equals(Mp4Check.formatOf(head, Math.max(n, 0))) ? "webm" : "mp4";
            String where = Media.saveFile(app, tmp, base + "." + ext, Media.mimeFor(ext));
            job.ok = true;
            job.state = "Сохранено: " + where;
            lastError = "-";
            Diag.note("видео сохранено: " + tmp.length() + " байт, " + host(used) + ", попыток " + tries);
            Toasts.show(app, "Сохранено: " + where);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Скачивает адрес в файл; следует за переадресациями (по https) и проверяет ответ. */
    static void fetch(String url, File out, Job job, long deadline) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setConnectTimeout(12_000);
                c.setReadTimeout(25_000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("переадресация без адреса");
                    String next = AvatarHooks.secure(new URL(new URL(cur), loc).toString());
                    if (next == null) throw new IOException("переадресация не на https");
                    cur = next;
                    continue;
                }
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                String type = c.getContentType();
                if (type != null) {
                    String t = type.toLowerCase(Locale.ROOT);
                    if (t.startsWith("text/") || t.contains("json") || t.contains("html") || t.contains("xml")
                            || t.contains("mpegurl")) {
                        throw new IOException("вместо видео пришёл " + type);
                    }
                }
                long expected = -1;
                String len = c.getHeaderField("Content-Length");
                if (len != null) {
                    try {
                        expected = Long.parseLong(len.trim());
                    } catch (NumberFormatException ignored) {
                        // длина неизвестна
                    }
                }
                if (expected > MAX_BYTES) throw new IOException("файл больше 600 МБ");

                InputStream in = c.getInputStream();
                FileOutputStream os = new FileOutputStream(out);
                long written = 0;
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    int shown = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        written += n;
                        if (written > MAX_BYTES) throw new IOException("файл больше 600 МБ");
                        if (System.currentTimeMillis() > deadline) throw new IOException("слишком долго");
                        int pct = expected > 0 ? (int) (written * 100 / expected) : (int) (written >> 20);
                        if (pct != shown && job != null) {
                            shown = pct;
                            job.state = expected > 0 ? "Скачиваю: " + pct + "%" : "Скачиваю: " + pct + " МБ";
                        }
                    }
                } finally {
                    os.close();
                    in.close();
                }
                if (expected > 0 && written != expected) {
                    throw new IOException("скачано " + written + " из " + expected + " байт");
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("слишком много переадресаций");
    }

    // ------------------------------------------------------------ фото

    private static void savePhotos(Context app, Job job, List<List<String>> photos, String base) {
        int saved = 0;
        String where = null;
        String why = null;
        for (int i = 0; i < photos.size(); i++) {
            job.state = "Скачиваю фото " + (i + 1) + " из " + photos.size();
            boolean done = false;
            for (String url : photos.get(i)) {
                try {
                    byte[] data = AvatarSaver.download(url);
                    String ext = Media.imageExtension(data);
                    if (ext == null) {
                        why = host(url) + ": пришла не картинка";
                        continue;
                    }
                    where = Media.saveBytes(app, data, base + "_" + (i + 1) + "." + ext, Media.mimeFor(ext));
                    saved++;
                    done = true;
                    break;
                } catch (Throwable t) {
                    why = host(url) + ": " + t.getMessage();
                }
            }
            if (!done) Diag.note("фото " + (i + 1) + " не сохранено — " + why);
        }
        if (saved == 0) {
            fail(app, job, why == null ? "нет рабочих адресов" : why);
            return;
        }
        job.ok = true;
        job.state = saved == photos.size() ? "Сохранено фото: " + saved
                : "Сохранено фото: " + saved + " из " + photos.size();
        lastError = "-";
        Toasts.show(app, job.state + " (" + where + ")");
    }

    private static String host(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "?" : h;
        } catch (Throwable t) {
            return "?";
        }
    }
}
