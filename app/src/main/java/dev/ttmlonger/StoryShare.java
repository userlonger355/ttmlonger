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
        TextView report, mute, save, notInterested, favorite, link, soundReport, qr;
        boolean send, storyMarker, reply, storyTime, storyClose;
        final List<String> labels = new ArrayList<>();
        final List<View> downloads = new ArrayList<>();
        boolean sheet() { return soundReport!=null&&send || report != null && (mute != null || send || link != null || notInterested != null || save != null); }
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
        if(n.contains("story") && (n.contains("player") || n.contains("viewer") || n.contains("progress") || n.contains("reply") || n.contains("container") || n.contains("detail"))) s.storyMarker=true;
        if(desc.equals("закрыть")||desc.equals("close"))s.storyClose=true;
        if(desc.equals("скачать") || desc.equals("сохранить историю") || desc.equals("download") ||
                desc.equals("save story") || n.contains("story_download") || n.contains("story_save")) {
            if(v.isClickable() || v.hasOnClickListeners()) s.downloads.add(v);
        }
        if(v instanceof TextView) {
            TextView t=(TextView)v;
            String a=norm(t.getText()), hint=norm(t.getHint());
            if(!a.isEmpty() && a.length()<600) s.labels.add(a);
            if(a.equals("жалоба") || a.equals("пожаловаться") || a.equals("report")) s.report=t;
            String joined=a.replace(" ","");if(joined.equals("пожаловатьсяназвук")||joined.equals("reportsound"))s.soundReport=t;
            if(a.equals("qr-код")||a.equals("qr code"))s.qr=t;
            if(a.contains("отключить уведомления") || a.equals("mute notifications") || a.equals("mute")) s.mute=t;
            if(a.equals("отправить") || a.equals("send to") || a.equals("share to") || a.equals("send") || a.equals("поделиться") || a.equals("share")) s.send=true;
            if(a.equals("не интересно") || a.equals("неинтересно") || a.equals("not interested")) s.notInterested=t;
            if(a.equals("в избранное") || a.equals("добавить в избранное") || a.equals("add to favorites") || a.equals("favorites")) s.favorite=t;
            if(a.equals("ссылка") || a.equals("копировать ссылку") || a.equals("copy link")) s.link=t;
            if(a.matches(".*(?:[0-9]+\\s*(?:ч\\.|мин\\.|дн\\.|сек\\.)\\s*назад|[0-9]+\\s*(?:h|m|d)\\s*ago)$"))s.storyTime=true;
            if(a.equals("скачать") || a.equals("сохранить историю") || a.equals("скачать историю") || a.equals("save story") ||
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

    private static boolean storyContext(Scan s){return s.storyMarker||s.reply||s.storyTime&&s.storyClose;}
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
        // A prefetched story is not sufficient proof of which story is currently shown.
        return null;
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
            return storyContext(s) && (s.storyMarker||select(s)!=null);
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
                if(s.soundReport!=null){soundAction(root,s,act);return;}
                show(root,s,activeActivity.get()==act?active:null,act,storyContext(s)||(activeActivity.get()==act&&active!=null&&active.isStory()));
                return;
            }
            remove(root);
            if(CrashGuard.tripped || !Prefs.dlAlways) { restore(root); return; }
            VideoTracker.Entry e=select(s);
            boolean story=s.storyMarker || storyContext(s)&&e!=null;
            if(story) {
                active=e; activeActivity=new java.lang.ref.WeakReference<>(act);
                // Keep native download controls intact; a custom sheet may not be recognized.
                restore(root);
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
            if(s.soundReport!=null){soundAction(root,s,a);return;}
            boolean story=false;
            if(a!=null) {
                View decor=a.getWindow().getDecorView();
                Scan behind=scan(decor);
                if(!behind.sheet()) {
                    VideoTracker.Entry e=select(behind);
                    story=behind.storyMarker||storyContext(behind)&&e!=null;
                    if(story){NativeDates.Binding nativeCell=NativeDates.visible(decor);if(nativeCell!=null&&nativeCell.entry.isStory())e=nativeCell.entry;}
                    else e=selectVideo(behind);
                    active=e;activeActivity=new java.lang.ref.WeakReference<>(a);
                }else story=storyContext(behind)||(activeActivity.get()==a&&active!=null&&active.isStory());
            }
            show(root,s,activeActivity.get()==a?active:null,a,story);
        } catch(Throwable ignored) {}
    }
    private static void soundAction(View root,Scan s,Activity act){
        if(CrashGuard.tripped||!Prefs.dlAlways){remove(root);return;}
        View old=buttons.get(root);if(old!=null&&old.isAttachedToWindow())return;
        Object music=SoundPage.find(act);if(music==null||s.qr==null||s.soundReport==null)return;
        ViewGroup row=sharedRow(s.qr,s.soundReport);
        if(row==null){soundFallback(root,s,music);return;}
        View sample=childOf(s.soundReport,row);if(sample==null){soundFallback(root,s,music);return;}
        int step=Math.abs(rect(s.soundReport).centerX()-rect(s.qr).centerX());if(step<dp(root.getContext(),40))return;
        if(row.getWidth()<step*3){soundFallback(root,s,music);return;}
        Context c=root.getContext();int ink=s.soundReport.getCurrentTextColor(),size=Math.min(dp(c,56),Math.round(step*.72f));
        LinearLayout tile=new LinearLayout(c);tile.setTag(ACTION,Boolean.TRUE);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        ImageView icon=new ImageView(c);icon.setImageDrawable(Icons.of(Icons.MUSIC,ink));GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);bg.setColor(Theme.onColor(ink)==Color.BLACK?0xff363636:0xffe8e8e8);icon.setBackground(bg);int pad=Math.round(size*.27f);icon.setPadding(pad,pad,pad,pad);tile.addView(icon,new LinearLayout.LayoutParams(size,size));
        TextView label=new TextView(c);label.setText("Сохранить звук");label.setGravity(Gravity.CENTER);label.setTextColor(ink);label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,s.soundReport.getTextSize());label.setTypeface(s.soundReport.getTypeface());label.setMaxLines(2);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,8);tile.addView(label,lp);
        tile.setContentDescription("Сохранить звук");tile.setOnClickListener(v->AudioSaver.startMusic(c,music));
        if(row instanceof LinearLayout){int occupied=0;for(int i=0;i<row.getChildCount();i++)if(row.getChildAt(i).getVisibility()!=View.GONE)occupied+=row.getChildAt(i).getWidth();if(occupied+step>row.getWidth()+dp(c,4)){soundFallback(root,s,music);return;}row.addView(tile,new LinearLayout.LayoutParams(step,-2));}
        else if(row instanceof GridLayout){GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=step;gp.height=-2;row.addView(tile,gp);}else return;
        buttons.put(root,tile);
    }
    private static void soundFallback(View root,Scan s,Object music){
        for(android.view.ViewParent q=s.soundReport.getParent();q instanceof ViewGroup;q=q.getParent()){
            if(!(q instanceof LinearLayout))continue;LinearLayout host=(LinearLayout)q;
            if(host==root||host.getOrientation()!=LinearLayout.VERTICAL||host.getWidth()<root.getWidth()*.6f)continue;
            View a=childOf(s.soundReport,host),b=childOf(s.qr,host);if(a==null||a!=b||a.getWidth()<host.getWidth()*.65f)continue;
            Context c=root.getContext();int ink=s.soundReport.getCurrentTextColor();LinearLayout action=new LinearLayout(c);action.setTag(ACTION,Boolean.TRUE);action.setGravity(Gravity.CENTER_VERTICAL);action.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));action.setMinimumHeight(dp(c,44));
            ImageView icon=new ImageView(c);icon.setImageDrawable(Icons.of(Icons.MUSIC,ink));action.addView(icon,new LinearLayout.LayoutParams(dp(c,20),dp(c,20)));
            TextView label=new TextView(c);label.setText("Сохранить звук");label.setTextColor(ink);label.setTextSize(14);label.setPadding(dp(c,12),0,0,0);action.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            action.setOnClickListener(v->AudioSaver.startMusic(c,music));host.addView(action,host.indexOfChild(a)+1,new LinearLayout.LayoutParams(-1,-2));buttons.put(root,action);return;
        }
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
    private static View tile(Context c,TextView sample,int diameter,VideoTracker.Entry e,boolean audio) {
        int ink=sample.getCurrentTextColor();
        LinearLayout col=new LinearLayout(c);
        col.setTag(ACTION, Boolean.TRUE); col.setOrientation(LinearLayout.VERTICAL);col.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        ImageView icon=new ImageView(c);icon.setImageDrawable(audio?Icons.of(Icons.MUSIC,ink):Glyph.download(ink));
        GradientDrawable bg=new GradientDrawable();bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Theme.onColor(ink)==Color.BLACK?0xff363636:0xffe8e8e8);
        icon.setBackground(bg); int pad=Math.round(diameter*.27f);icon.setPadding(pad,pad,pad,pad);
        col.addView(icon,new LinearLayout.LayoutParams(diameter,diameter));
        String title=audio?"Скачать звук":e==null||e.isStory()?"Скачать":"Сохранить через мод";
        TextView label=new TextView(c);label.setText(title);label.setTextColor(ink);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sample.getTextSize());label.setGravity(Gravity.CENTER);
        label.setTypeface(sample.getTypeface());label.setMaxLines(2);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(c,8);col.addView(label,lp);
        col.setContentDescription(title);col.setClickable(true);col.setFocusable(true);
        col.setOnClickListener(v -> {
            try {
                Context app=c.getApplicationContext();
                if(audio) AudioSaver.start(app==null?c:app,e);
                else VideoSaver.start(app==null?c:app,e);
            } catch(Throwable t){FlagStore.log(t);Toasts.show(c,"Не удалось сохранить");}
        });
        return col;
    }
    private static View compact(Context c,TextView sample,VideoTracker.Entry e){
        LinearLayout r=new LinearLayout(c);r.setTag(ACTION,Boolean.TRUE);r.setGravity(Gravity.CENTER_VERTICAL);
        int ink=sample.getCurrentTextColor();r.setPadding(dp(c,16),dp(c,4),dp(c,16),dp(c,4));r.setMinimumHeight(dp(c,42));
        ImageView icon=new ImageView(c);icon.setImageDrawable(Glyph.download(ink));r.addView(icon,new LinearLayout.LayoutParams(dp(c,20),dp(c,20)));
        TextView label=new TextView(c);label.setText("Скачать через мод");label.setTextSize(14);label.setTextColor(ink);label.setTypeface(sample.getTypeface());label.setPadding(dp(c,12),0,0,0);r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(c);arrow.setImageDrawable(Icons.of(Icons.CHEVRON_RIGHT,ink));r.addView(arrow,new LinearLayout.LayoutParams(dp(c,16),dp(c,16)));
        android.util.TypedValue tv=new android.util.TypedValue();if(c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,tv,true)&&tv.resourceId!=0)r.setBackgroundResource(tv.resourceId);
        r.setClickable(true);r.setFocusable(true);r.setContentDescription("Скачать через мод, открыть варианты");
        r.setOnClickListener(v->{
            android.widget.PopupMenu menu=new android.widget.PopupMenu(c,r,Gravity.END);
            menu.getMenu().add(0,1,0,"Скачать видео").setIcon(Icons.of(Icons.DOWNLOAD,ink));menu.getMenu().add(0,2,1,"Скачать звук").setIcon(Icons.of(Icons.MUSIC,ink));
            if(android.os.Build.VERSION.SDK_INT>=29)menu.setForceShowIcon(true);
            menu.setOnMenuItemClickListener(item->{try{Context app=c.getApplicationContext();if(item.getItemId()==1)VideoSaver.start(app==null?c:app,e);else AudioSaver.start(app==null?c:app,e);}catch(Throwable t){FlagStore.log(t);Toasts.show(c,"Не удалось сохранить");}return true;});
            menu.show();
        });return r;
    }
    private static boolean compactInSheet(View root,Scan s,VideoTracker.Entry e){
        TextView first=s.report,second=neighbor(s);if(first==null||second==null)return false;
        // Find the vertical sheet containing both labels, after their shared native row.
        for(android.view.ViewParent q=first.getParent();q instanceof ViewGroup;q=q.getParent()){
            if(!(q instanceof LinearLayout))continue;LinearLayout host=(LinearLayout)q;
            if(host.getOrientation()!=LinearLayout.VERTICAL||host==root||host.getWidth()<root.getWidth()*.6f)continue;
            View a=childOf(first,host),b=childOf(second,host);
            if(a==null||a!=b||a.getWidth()<host.getWidth()*.65f)continue;
            View entry=compact(root.getContext(),first,e);host.addView(entry,host.indexOfChild(a)+1,new LinearLayout.LayoutParams(-1,-2));
            buttons.put(root,entry);bound.put(root,e);videoActions++;return true;
        }
        return false;
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
    private static void show(View root, Scan s, VideoTracker.Entry e,Activity act,boolean storyContext) {
        if(CrashGuard.tripped || !Prefs.dlAlways || s.report==null) {remove(root);return;}
        VideoTracker.Entry pinned=bound.get(root);
        View old=buttons.get(root);
        boolean story=storyContext||e!=null&&e.isStory();
        if(old!=null&&old.isAttachedToWindow()){
            if(pinned!=null&&!pinned.isStory()&&!Prefs.dlPill||!s.send||s.save!=null&&story){remove(root);return;}
            if(pinned==null&&story&&e!=null&&e.isStory())bound.put(root,e);
            return;
        }
        if(old!=null)remove(root);
        if(!story){
            if(e==null||!Prefs.dlPill||s.mute!=null&&s.notInterested==null&&s.save==null)return;
            compactInSheet(root,s,e);return;
        }
        if(s.save!=null)return; // stock action already exists
        if(!s.send)return;
        if(e!=null&&!e.isStory())e=null;
        TextView first=s.report,second=s.mute;
        // The report-only menu is normal for non-followed users and some story types.
        // Derive the native tile width from the report item, not from a second action.
        Context c=root.getContext();
        for(android.view.ViewParent q=first.getParent();q instanceof ViewGroup;q=q.getParent()){
            if(q==root)break;
            ViewGroup row=(ViewGroup)q;
            if(!(row instanceof GridLayout)&&!(row instanceof LinearLayout&&((LinearLayout)row).getOrientation()==LinearLayout.HORIZONTAL))continue;
            View template=childOf(first,row);if(template==null)continue;
            int w=template.getWidth();if(w<dp(c,44)||w>dp(c,160)||row.getWidth()<w*2-dp(c,2)||template.getHeight()>dp(c,155))continue;
            int occupied=0,visible=0;
            for(int i=0;i<row.getChildCount();i++){View child=row.getChildAt(i);if(child.getVisibility()==View.GONE)continue;visible++;occupied+=child.getWidth();if(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)child.getLayoutParams();occupied+=lp.leftMargin+lp.rightMargin;}}
            int size=Math.min(dp(c,56),Math.round(w*.72f));
            View mine=storyTile(root,first,size,act,e);
            if(row instanceof LinearLayout){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,-2);
                if(template.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams src=(LinearLayout.LayoutParams)template.getLayoutParams();lp.topMargin=src.topMargin;lp.bottomMargin=src.bottomMargin;lp.leftMargin=src.leftMargin;lp.rightMargin=src.rightMargin;lp.gravity=src.gravity;}
                if(occupied+w+lp.leftMargin+lp.rightMargin>row.getWidth()-row.getPaddingLeft()-row.getPaddingRight()+dp(c,2))continue;
                row.addView(mine,row.indexOfChild(template)+1,lp);
            }else{
                GridLayout grid=(GridLayout)row;int cols=grid.getColumnCount();if(cols<2||visible>=cols)continue;
                GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=w;lp.height=-2;grid.addView(mine,lp);
            }
            buttons.put(root,mine);if(e!=null)bound.put(root,e);return;
        }
        // Fallback in the actual measured sheet, immediately after the native report row.
        for(android.view.ViewParent q=first.getParent();q instanceof ViewGroup;q=q.getParent()){
            if(q==root)break;
            if(!(q instanceof LinearLayout))continue;
            LinearLayout host=(LinearLayout)q;if(host.getOrientation()!=LinearLayout.VERTICAL||host.getWidth()<root.getWidth()*.65f)continue;
            View one=childOf(first,host);
            if(one==null||one.getWidth()<host.getWidth()*.65f||one.getHeight()>dp(c,180)||one.getHeight()<dp(c,42))continue;
            if(second!=null&&childOf(second,host)!=one)continue;
            if(s.link!=null&&childOf(s.link,host)==one)continue;
            int step=Math.max(dp(c,56),Math.round(host.getWidth()/6f));int size=Math.min(dp(c,56),Math.round(step*.72f));
            LinearLayout extra=new LinearLayout(c);extra.setTag(ACTION,Boolean.TRUE);extra.setOrientation(LinearLayout.HORIZONTAL);extra.setGravity(Gravity.START);
            extra.setPadding(one.getPaddingLeft(),dp(c,4),one.getPaddingRight(),dp(c,4));
            extra.addView(storyTile(root,first,size,act,e),new LinearLayout.LayoutParams(step,-2));
            host.addView(extra,host.indexOfChild(one)+1,new LinearLayout.LayoutParams(-1,-2));
            buttons.put(root,extra);if(e!=null)bound.put(root,e);return;
        }
    }
    private static View storyTile(View root,TextView sample,int diameter,Activity act,VideoTracker.Entry e){
        // A button can be shown while the native media model is still loading.
        // Do not silently download a previously seen or prefetched story.
        View tile=tile(root.getContext(),sample,diameter,e,false);
        tile.setContentDescription("Скачать историю");
        tile.setOnClickListener(v->{
            if(!Prefs.dlAlways||CrashGuard.tripped)return;
            VideoTracker.Entry entry=bound.get(root);
            if(entry==null||!entry.isStory()){
                if(act!=null&&!act.isFinishing()&&!act.isDestroyed()){
                    View decor=act.getWindow().getDecorView();Scan behind=scan(decor);
                    NativeDates.Binding cell=NativeDates.visible(decor);
                    if(cell!=null&&cell.entry.isStory()&&storyContext(behind))entry=cell.entry;
                    else entry=select(behind);
                }
            }
            if(entry==null||!entry.isStory()){Toasts.show(root.getContext(),"История ещё не распознана. Откройте её заново.");return;}
            Context ctx=root.getContext();Context app=ctx.getApplicationContext();VideoSaver.start(app==null?ctx:app,entry);
        });return tile;
    }
}
