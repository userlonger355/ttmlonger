package dev.ttmlonger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Запоминает видео и истории, которые TikTok недавно показывал (последние несколько штук), чтобы
 * экран «Видео и истории» мог предложить их сохранить. TikTok не говорит «сейчас открыта вот эта
 * история», поэтому берём то, что приложение недавно читало: Aweme.getVideo и Video.getPlayAddr.
 * Это модели данных TikTok с настоящими, не обфусцированными именами.
 *
 * Хуки вызываются часто (при прокрутке ленты), поэтому здесь только сравнение ссылок и короткая
 * блокировка. Всё остальное (название, автор, обложка) читается позже, когда экран открыт.
 */
final class VideoTracker {
    private static final int MAX = 14;

    /** Одно видео, история или фотопост. */
    static final class Entry {
        final Object aweme;
        volatile Object video;
        volatile long seen;
        volatile long played;
        volatile boolean prohibited;
        /** Время публикации и регион (считаются при первом обращении), см. VideoInfo. */
        volatile VideoInfo.Meta meta;
        /** Имена автора и начало описания для поиска на экране, см. VideoInfo. */
        volatile VideoInfo.Keys keys;

        Entry(Object aweme) {
            this.aweme = aweme;
        }

        long lastTouch() {
            return Math.max(seen, played);
        }

        boolean isProhibited() {
            return prohibited || wasProhibited(aweme);
        }

        String aid() {
            Object v = DownloadHooks.call(aweme, "getAid");
            return v instanceof String ? (String) v : null;
        }

        String desc() {
            Object v = DownloadHooks.call(aweme, "getDesc");
            return v instanceof String ? ((String) v).trim() : null;
        }

        String author() {
            Object u = DownloadHooks.call(aweme, "getAuthor");
            if (u == null) return null;
            for (String name : new String[]{"getNickname", "getUniqueId", "getShortId"}) {
                Object v = DownloadHooks.call(u, name);
                if (v instanceof String && !((String) v).trim().isEmpty()) return ((String) v).trim();
            }
            return null;
        }

        boolean isStory() {
            for (String name : new String[]{"getIsTikTokStory", "isTikTokStory", "isStory"}) {
                Object v = DownloadHooks.call(aweme, name);
                if (Boolean.TRUE.equals(v)) return true;
            }
            return false;
        }
    }

    private static final List<Entry> LIST = new ArrayList<>();
    private static final List<WeakReference<Object>> PROHIBITED = new ArrayList<>();
    private static volatile Entry last;
    private static volatile int total;

    private VideoTracker() {
    }

    /** Монотонное время в миллисекундах. */
    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    static int total() {
        return total;
    }

    static int size() {
        synchronized (LIST) {
            return LIST.size();
        }
    }

    /** TikTok прочитал видео у этого поста. */
    static void seen(Object aweme, Object video) {
        if (aweme == null) return;
        long now = now();
        Entry l = last;
        if (l != null && l.aweme == aweme) {
            l.seen = now;
            if (video != null) l.video = video;
            return;
        }
        synchronized (LIST) {
            Entry e = null;
            for (Entry x : LIST) {
                if (x.aweme == aweme) {
                    e = x;
                    break;
                }
            }
            if (e == null) {
                e = new Entry(aweme);
                LIST.add(e);
                total++;
                if (LIST.size() > MAX) {
                    Entry oldest = null;
                    for (Entry x : LIST) {
                        if (x != e && (oldest == null || x.lastTouch() < oldest.lastTouch())) oldest = x;
                    }
                    if (oldest != null) LIST.remove(oldest);
                }
            }
            e.seen = now;
            if (video != null) e.video = video;
            last = e;
        }
    }

    /** Плеер взял адрес воспроизведения у этого видео: оно, скорее всего, играет прямо сейчас. */
    static void played(Object video) {
        if (video == null) return;
        long now = now();
        Entry l = last;
        if (l != null && l.video == video) {
            l.played = now;
            return;
        }
        synchronized (LIST) {
            for (Entry x : LIST) {
                if (x.video == video) {
                    x.played = now;
                    return;
                }
            }
        }
    }

    /** Приложение сказало, что у этого поста запрещено скачивание. */
    static void markProhibited(Object aweme) {
        if (aweme == null) return;
        synchronized (LIST) {
            for (Entry x : LIST) {
                if (x.aweme == aweme) {
                    x.prohibited = true;
                    return;
                }
            }
            for (WeakReference<Object> r : PROHIBITED) {
                if (r.get() == aweme) return;
            }
            PROHIBITED.add(new WeakReference<>(aweme));
            while (PROHIBITED.size() > 64) PROHIBITED.remove(0);
        }
    }

    private static boolean wasProhibited(Object aweme) {
        synchronized (LIST) {
            for (WeakReference<Object> r : PROHIBITED) {
                if (r.get() == aweme) return true;
            }
        }
        return false;
    }

    /** У этого Video запрещена загрузка (по посту, к которому оно относится). */
    static boolean isProhibited(Object video) {
        Entry e = entryFor(video);
        return e != null && e.isProhibited();
    }

    static Entry entryFor(Object video) {
        if (video == null) return null;
        synchronized (LIST) {
            for (Entry x : LIST) {
                if (x.video == video) return x;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ посты, привязанные к экрану

    private static final int BOUND_MAX = 24;
    private static final List<Entry> BOUND = new ArrayList<>();
    private static volatile Entry lastBound;

    /**
     * Экран читает у поста имя автора или описание: значит, пост уже показан (или вот-вот будет), а плеер
     * ещё не взял адрес воспроизведения. Эти посты нужны только плашкам времени и региона (VideoInfo),
     * в историю «Видео и истории» они не попадают.
     */
    static void bound(Object aweme) {
        if (aweme == null) return;
        Entry l = lastBound;
        if (l != null && l.aweme == aweme) {
            l.seen = now();
            return;
        }
        boolean fresh = false;
        synchronized (BOUND) {
            Entry e = null;
            for (Entry x : BOUND) {
                if (x.aweme == aweme) {
                    e = x;
                    break;
                }
            }
            if (e == null) {
                e = new Entry(aweme);
                BOUND.add(e);
                fresh = true;
                if (BOUND.size() > BOUND_MAX) {
                    Entry oldest = null;
                    for (Entry x : BOUND) {
                        if (x != e && (oldest == null || x.seen < oldest.seen)) oldest = x;
                    }
                    if (oldest != null) BOUND.remove(oldest);
                }
            }
            e.seen = now();
            lastBound = e;
        }
        if (fresh) VideoInfo.nudge();
    }

    /**
     * Кого искать на экране: недавние видео из истории плюс посты, привязанные к экрану. Первыми идут те,
     * что недавно играли, затем самые свежие по чтению; один пост дважды не попадает.
     */
    static List<Entry> candidates() {
        List<Entry> out;
        synchronized (LIST) {
            out = new ArrayList<>(LIST);
        }
        synchronized (BOUND) {
            for (Entry b : BOUND) {
                boolean dup = false;
                for (Entry x : out) {
                    if (x.aweme == b.aweme) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) out.add(b);
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = Long.compare(b.played, a.played);
                return c != 0 ? c : Long.compare(b.seen, a.seen);
            }
        });
        return out;
    }

    /** Свежие первыми: недавно игравшие выше, остальные по времени чтения. */
    static List<Entry> recent() {
        List<Entry> out;
        synchronized (LIST) {
            out = new ArrayList<>(LIST);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.lastTouch(), a.lastTouch());
            }
        });
        return out;
    }

}
