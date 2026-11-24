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
        TextView report, mute, save;
        boolean send, storyMarker, reply;
        final List<String> labels = new ArrayList<>();
        final List<View> downloads = new ArrayList<>();
        boolean sheet() { return report != null && (mute != null || send); }
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
            if(!a.isEmpty() && a.length()<100) s.labels.add(a);
            if(a.equals("жалоба") || a.equals("пожаловаться") || a.equals("report")) s.report=t;
            if(a.contains("отключить уведомления") || a.equals("mute notifications") || a.equals("mute")) s.mute=t;
            if(a.equals("отправить") || a.equals("send to") || a.equals("share to") || a.equals("send")) s.send=true;
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
                if(activeActivity.get()==act && !s.send) { active=null; activeActivity.clear(); }
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
            if(a!=null && activeActivity.get()!=a) {
                View decor=a.getWindow().getDecorView();
                Scan behind=scan(decor);
                if(behind.storyMarker || behind.reply) {
                    VideoTracker.Entry e=select(behind);
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
        TextView label=new TextView(c);label.setText("Скачать");label.setTextColor(ink);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sample.getTextSize());label.setGravity(Gravity.CENTER);
        label.setTypeface(sample.getTypeface());label.setMaxLines(2);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,8);col.addView(label,lp);
        col.setContentDescription("Скачать историю");col.setClickable(true);col.setFocusable(true);
        col.setOnClickListener(v -> {
            try {
                Context app=c.getApplicationContext(); VideoSaver.start(app==null?c:app,e);
            } catch(Throwable t){FlagStore.log(t);Toasts.show(c,"Не удалось скачать историю");}
        });
        return col;
    }
    private static void show(View root, Scan s, VideoTracker.Entry e) {
        if(CrashGuard.tripped || !Prefs.dlAlways || s.save!=null || s.report==null) {remove(root);return;}
        // Freeze the selected story for the life of this share sheet, including when playback pauses.
        VideoTracker.Entry pinned=bound.get(root);
        View old=buttons.get(root);
        if(old!=null && old.isAttachedToWindow() && pinned!=null)return;
        if(e==null)return;
        TextView first=s.report, second=s.mute;
        if(second==null)return;
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
            int center=b.centerX()+(b.centerX()>a.centerX()?step:-step);
            int left=center-step/2-rr.left;
            int top=Math.min(a.top,b.top)-size-dp(root.getContext(),8)-rr.top;
            if(left<0||left+step>rr.width()||top<0||top+size+dp(root.getContext(),36)>rr.height())return;
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(step,-2,Gravity.TOP|Gravity.LEFT);
            lp.leftMargin=left;lp.topMargin=top;
            root.addView(mine,lp);
        }
        buttons.put(root,mine);bound.put(root,e);
    }
}
