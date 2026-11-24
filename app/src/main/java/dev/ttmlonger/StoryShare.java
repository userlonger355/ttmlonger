package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Story action lives in the share sheet, not in a floating window. */
final class StoryShare {
    private static final int WATCH = 0x7ff0cb12;
    private static final int ACTION = 0x7ff0cb13;
    private static final WeakHashMap<View, View> buttons = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> hidden = new WeakHashMap<>();
    private static final WeakHashMap<View, VideoTracker.Entry> bound = new WeakHashMap<>();
    private static VideoTracker.Entry active;
    private static int videoActions;
    static int videoActions() { return videoActions; }
    private static java.lang.ref.WeakReference<Activity> activeActivity = new java.lang.ref.WeakReference<>(null);

    static void install() {
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Dialog d=(Dialog)p.thisObject;
                    if (Screen.isOwn(d) || d.getWindow()==null) return;
                    View root=d.getWindow().getDecorView();
                    if(root.getTag(WATCH)==null) {
                        root.setTag(WATCH, Boolean.TRUE);
                        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> updateDialog(root));
                    }
                    root.post(() -> updateDialog(root));
                } catch(Throwable ignored) {}
            }
        });
    }

    private static final class Scan {
        TextView report, mute, save, notInterested, favorite, link;
        boolean send, storyMarker, reply;
        final List<String> labels = new ArrayList<>();
        final List<View> downloads = new ArrayList<>();
        boolean sheet() { return report != null && (mute != null || send || link != null || notInterested != null || save != null); }
    }
    private static String norm(CharSequence s) {
        return s==null?"":s.toString().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
    }
    private static String name(View v) {
        String n=v.getClass().getName().toLowerCase(Locale.ROOT);
        try { if(v.getId()!=View.NO_ID) n+=" "+v.getResources().getResourceEntryName(v.getId()).toLowerCase(Locale.ROOT); }
        catch(Throwable ignored){}
        return n;
    }
    private static void scan(View v, Scan s, int depth, int[] budget) {
        if(v==null || --budget[0]<0 || depth>42 || v.getVisibility()!=View.VISIBLE || v.getTag(ACTION)!=null) return;
        String n=name(v), desc=norm(v.getContentDescription());
        if(n.contains("story") && (n.contains("player") || n.contains("viewer") || n.contains("progress") || n.contains("reply") || n.contains("container"))) s.storyMarker=true;
        if(desc.equals("скачать") || desc.equals("сохранить историю") || desc.equals("download") ||
                desc.equals("save story") || n.contains("story_download") || n.contains("story_save")) {
            if(v.isClickable() || v.hasOnClickListeners()) s.downloads.add(v);
        }
        if(v instanceof TextView) {
            TextView t=(TextView)v;
            String a=norm(t.getText()), hint=norm(t.getHint());
            if(!a.isEmpty() && a.length()<600) s.labels.add(a);
            if(a.equals("жалоба") || a.equals("пожаловаться") || a.equals("report")) s.report=t;
            if(a.contains("отключить уведомления") || a.equals("mute notifications") || a.equals("mute")) s.mute=t;
            if(a.equals("отправить") || a.equals("send to") || a.equals("share to") || a.equals("send") || a.equals("поделиться") || a.equals("share")) s.send=true;
            if(a.equals("не интересно") || a.equals("неинтересно") || a.equals("not interested")) s.notInterested=t;
            if(a.equals("в избранное") || a.equals("добавить в избранное") || a.equals("add to favorites") || a.equals("favorites")) s.favorite=t;
            if(a.equals("ссылка") || a.equals("копировать ссылку") || a.equals("copy link")) s.link=t;
            if(a.equals("сохранить историю") || a.equals("скачать историю") || a.equals("save story") ||
                    a.equals("download story") || a.equals("сохранить видео") || a.equals("save video") ||
                    a.equals("сохранить") || a.equals("download")) s.save=t;
            if(a.startsWith("написать") || hint.startsWith("написать") || a.startsWith("send message") ||
                    hint.startsWith("send message") || a.startsWith("reply") || hint.startsWith("reply")) s.reply=true;
        }
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) scan(g.getChildAt(i),s,depth+1,budget);
        }
    }
    private static Scan scan(View root) { Scan s=new Scan(); scan(root,s,0,new int[]{2800}); return s; }

    private static VideoTracker.Entry select(Scan s) {
        VideoTracker.Entry recent=null, matched=null;
        long now=VideoTracker.now();
        for(VideoTracker.Entry e:VideoTracker.candidates()) {
            if(!e.isStory()) continue;
            boolean fresh=now-e.lastTouch()<120000L;
            if(!fresh) continue;
            if(recent==null) recent=e;
            String author=norm(e.author());
            Object u=DownloadHooks.call(e.aweme,"getAuthor");
            String handle=norm(String.valueOf(DownloadHooks.call(u,"getUniqueId")));
            for(String label:s.labels) {
                if((author.length()>1 && (label.equals(author)||label.startsWith(author+" "))) ||
                        (handle.length()>1 && !handle.equals("null") && (label.equals(handle)||label.equals("@"+handle)))) {
                    if(matched==null) matched=e;
                    if(e.played>0 && now-e.played<30000L) return e;
                }
            }
        }
        if(matched!=null) return matched;
        return s.storyMarker && recent!=null && now-recent.lastTouch()<15000L ? recent:null;
    }
    private static VideoTracker.Entry selectVideo(Scan s) {
        VideoTracker.Entry best=null;
        int bestScore=0;
        long now=VideoTracker.now();
        for(VideoTracker.Entry e:VideoTracker.candidates()) {
            if(e.isStory() || e.video==null || now-e.lastTouch()>120000L) continue;
            String author=norm(e.author()), desc=norm(e.desc());
            Object u=DownloadHooks.call(e.aweme,"getAuthor");
            String handle=norm(String.valueOf(DownloadHooks.call(u,"getUniqueId")));
            boolean authorHit=false, descHit=false;
            for(String label:s.labels) {
                if(author.length()>1 && (label.equals(author)||label.startsWith(author+" "))) authorHit=true;
                if(handle.length()>1 && !handle.equals("null") && (label.equals(handle)||label.equals("@"+handle))) authorHit=true;
                if(desc.length()>=8 && label.length()>=8 &&
                        (label.startsWith(desc.substring(0,Math.min(40,desc.length()))) ||
                        desc.startsWith(label.substring(0,Math.min(40,label.length()))))) descHit=true;
            }
            if(!authorHit && !descHit) continue;
            int score=(authorHit?4:0)+(descHit?6:0)+(e.played>0&&now-e.played<30000L?2:0);
            if(score>bestScore) {bestScore=score;best=e;}
        }
        return best;
    }

    static boolean isStoryScreen(View root) {
        try {
            Scan s=scan(root);
            if(s.sheet()) return false;
            return s.storyMarker || s.reply && select(s)!=null;
        } catch(Throwable t) { return false; }
    }
    static boolean excludeSticker(View root) {
        try {
            Scan s=scan(root);
            return s.sheet() || s.storyMarker || s.reply && select(s)!=null;
        } catch(Throwable t) { return false; }
    }

    static void update(Activity act, View root) {
        try {
            if(Screen.isOwnDecor(root)) return;
            Scan s=scan(root);
            if(s.sheet()) {
                show(root,s,activeActivity.get()==act?active:null);
                return;
            }
            remove(root);
            if(CrashGuard.tripped || !Prefs.dlAlways) { restore(root); return; }
            VideoTracker.Entry e=select(s);
            boolean story=s.storyMarker || s.reply && e!=null;
            if(story) {
                if(e!=null) { active=e; activeActivity=new java.lang.ref.WeakReference<>(act); }
                // Only hide explicitly identified native download buttons in a story viewer.
                for(View v:s.downloads) {
                    if(!hidden.containsKey(v)) hidden.put(v,v.getVisibility());
                    v.setVisibility(View.INVISIBLE);
                }
            } else {
                restore(root);
                VideoTracker.Entry video=selectVideo(s);
                if(video!=null) { active=video; activeActivity=new java.lang.ref.WeakReference<>(act); }
                else if(activeActivity.get()==act && !s.send) { active=null; activeActivity.clear(); }
            }
        } catch(Throwable ignored) {}
    }
    private static void restore(View root) {
        for(View v:new ArrayList<>(hidden.keySet())) {
            if(v!=null && v.getRootView()==root) {
                Integer vis=hidden.remove(v);
                if(vis!=null && v.getVisibility()==View.INVISIBLE) v.setVisibility(vis);
            }
        }
    }
    private static void updateDialog(View root) {
        try {
            if(Screen.isOwnDecor(root)) return;
            Scan s=scan(root);
            if(!s.sheet()) { remove(root); return; }
            Activity a=ScreenWatcher.top();
            if(a!=null) {
                View decor=a.getWindow().getDecorView();
                Scan behind=scan(decor);
                if(!behind.sheet()) {
                    VideoTracker.Entry e=behind.storyMarker || behind.reply ? select(behind):selectVideo(behind);
                    if(e!=null){active=e;activeActivity=new java.lang.ref.WeakReference<>(a);}
                }
            }
            show(root,s,activeActivity.get()==a?active:null);
        } catch(Throwable ignored) {}
    }
    private static void remove(View root) {
        View button=buttons.remove(root); bound.remove(root);
        if(button!=null && button.getParent() instanceof ViewGroup) ((ViewGroup)button.getParent()).removeView(button);
    }
    private static int dp(Context c,float x){return Math.round(x*c.getResources().getDisplayMetrics().density);}
    private static View childOf(View v, ViewGroup parent) {
        while(v!=null && v.getParent()!=parent) {
            if(!(v.getParent() instanceof View)) return null;
            v=(View)v.getParent();
        }
        return v;
    }
    private static ViewGroup sharedRow(TextView a, TextView b) {
        if(a==null||b==null)return null;
        for(android.view.ViewParent p=a.getParent();p instanceof ViewGroup;p=p.getParent()) {
            ViewGroup g=(ViewGroup)p;
            View ca=childOf(a,g), cb=childOf(b,g);
            if(ca==null||cb==null||ca==cb)continue;
            if(g instanceof LinearLayout && ((LinearLayout)g).getOrientation()==LinearLayout.HORIZONTAL) return g;
            if(g instanceof GridLayout) return g;
            return null;
        }
        return null;
    }
    private static Rect rect(View v) {
        int[] p=new int[2];v.getLocationOnScreen(p);
        return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());
    }
    private static View tile(Context c,TextView sample,int diameter,VideoTracker.Entry e) {
        int ink=sample.getCurrentTextColor();
        LinearLayout col=new LinearLayout(c);
        col.setTag(ACTION, Boolean.TRUE); col.setOrientation(LinearLayout.VERTICAL);col.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        ImageView icon=new ImageView(c);icon.setImageDrawable(Glyph.download(ink));
        GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Theme.onColor(ink)==Color.BLACK?0xff363636:0xffe8e8e8);
        icon.setBackground(bg); int pad=Math.round(diameter*.27f);icon.setPadding(pad,pad,pad,pad);
        col.addView(icon,new LinearLayout.LayoutParams(diameter,diameter));
        String title=e.isStory()?"Скачать":"Сохранить через мод";
        TextView label=new TextView(c);label.setText(title);label.setTextColor(ink);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sample.getTextSize());label.setGravity(Gravity.CENTER);
        label.setTypeface(sample.getTypeface());label.setMaxLines(2);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,8);col.addView(label,lp);
        col.setContentDescription(title);col.setClickable(true);col.setFocusable(true);
        col.setOnClickListener(v -> {
            try {
                Context app=c.getApplicationContext(); VideoSaver.start(app==null?c:app,e);
            } catch(Throwable t){FlagStore.log(t);Toasts.show(c,"Не удалось сохранить");}
        });
        return col;
    }
    private static TextView neighbor(Scan s) {
        Rect a=rect(s.report);
        TextView best=null; int distance=Integer.MAX_VALUE;
        for(TextView t:new TextView[]{s.notInterested,s.save,s.favorite,s.mute}) {
            if(t==null||t==s.report)continue;
            Rect b=rect(t); int dx=Math.abs(a.centerX()-b.centerX());
            if(b.width()>0 && Math.abs(a.top-b.top)<=dp(t.getContext(),24) && dx>0 && dx<distance) {
                best=t;distance=dx;
            }
        }
        return best;
    }
    private static void show(View root, Scan s, VideoTracker.Entry e) {
        if(CrashGuard.tripped || !Prefs.dlAlways || s.report==null) {remove(root);return;}
        // Freeze the selected story for the life of this share sheet, including when playback pauses.
        VideoTracker.Entry pinned=bound.get(root);
        View old=buttons.get(root);
        if(old!=null && old.isAttachedToWindow() && pinned!=null) {
            if(!pinned.isStory() && !Prefs.dlPill) remove(root);
            return;
        }
        if(e==null || (!e.isStory() && !Prefs.dlPill) || (e.isStory() && s.save!=null)) return;
        if(!e.isStory() && s.mute!=null && s.notInterested==null && s.save==null) return;
        TextView first=s.report, second=e.isStory()?s.mute:neighbor(s);
        if(second==null || second==first)return;
        Rect a=rect(first), b=rect(second);
        if(a.width()==0||b.width()==0||Math.abs(a.top-b.top)>dp(root.getContext(),24))return;
        ViewGroup row=sharedRow(first,second);
        int step=Math.abs(b.centerX()-a.centerX());
        if(step<dp(root.getContext(),45))return;
        int size=Math.min(dp(root.getContext(),56),Math.round(step*.72f));
        View mine=tile(root.getContext(),first,size,e);
        if(row instanceof LinearLayout) {
            View template=childOf(second,row);
            int w=template==null?step:template.getWidth();
            if(w<=0)w=step;
            int occupied=0;
            for(int i=0;i<row.getChildCount();i++) {
                View child=row.getChildAt(i);
                if(child.getVisibility()!=View.GONE) occupied+=child.getWidth();
            }
            if(!e.isStory() && occupied+w>row.getWidth()) {
                // A full native action row stays unchanged; append an additional in-sheet row.
                if(!(row.getParent() instanceof LinearLayout))return;
                LinearLayout parent=(LinearLayout)row.getParent();
                if(parent.getOrientation()!=LinearLayout.VERTICAL)return;
                LinearLayout extra=new LinearLayout(root.getContext());
                extra.setTag(ACTION,Boolean.TRUE);extra.setOrientation(LinearLayout.HORIZONTAL);
                extra.setPadding(row.getPaddingLeft(),dp(root.getContext(),8),row.getPaddingRight(),0);
                extra.addView(mine,new LinearLayout.LayoutParams(Math.max(w,dp(root.getContext(),84)),-2));
                parent.addView(extra,parent.indexOfChild(row)+1,new LinearLayout.LayoutParams(-1,-2));
                buttons.put(root,extra);bound.put(root,e);videoActions++;return;
            }
            if(row.getWidth()<w*3)return;
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,-2);
            if(template!=null && template.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams src=(LinearLayout.LayoutParams)template.getLayoutParams();
                lp=new LinearLayout.LayoutParams(src); lp.height=-2;
            }
            row.addView(mine,row.indexOfChild(template)+1,lp);
        } else if(row instanceof GridLayout && ((GridLayout)row).getColumnCount()>=3) {
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=step;lp.height=-2;
            row.addView(mine,lp);
        } else {
            // Recycler/obfuscated row: attach a normal clickable child to the sheet window's
            // frame only if the third slot is visibly empty. No separate Dialog or timeout.
            if(!(root instanceof FrameLayout))return;
            Rect rr=rect(root);
            if(!e.isStory())return; // no guessed overlays on full video share rows
            int center=b.centerX()+(b.centerX()>a.centerX()?step:-step);
            int left=center-step/2-rr.left;
            int top=Math.min(a.top,b.top)-size-dp(root.getContext(),8)-rr.top;
            if(left<0||left+step>rr.width()||top<0||top+size+dp(root.getContext(),36)>rr.height())return;
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(step,-2,Gravity.TOP|Gravity.LEFT);
            lp.leftMargin=left;lp.topMargin=top;
            root.addView(mine,lp);
        }
        buttons.put(root,mine);bound.put(root,e);if(!e.isStory())videoActions++;
    }
}
