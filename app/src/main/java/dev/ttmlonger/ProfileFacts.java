package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class ProfileFacts {
    private static final LruCache<String, Object> users = new LruCache<>(64);
    private static final ThreadLocal<Boolean> remembering=new ThreadLocal<>();
    static String diagnostics(){return "Модели профиля в памяти: "+users.size();}
    private TextView extras;
    private TextView dateAction;
    private TextView anchor;
    private boolean wasLongClickable;
    private static final java.util.WeakHashMap<View, String> handles = new java.util.WeakHashMap<>();

    static void install(ClassLoader cl) {
        for (String name : new String[]{AvatarHooks.USER, BadgeHooks.PROFILE}) {
            try {
                Class<?> user = Class.forName(name, false, cl);
                XC_MethodHook observe=new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam p){
                    if(p.hasThrowable()||remembering.get()!=null)return;remembering.set(true);
                    try{Object raw=RepostTimes.readField(p.thisObject,"unique_id");if(raw==null)return;String key=raw.toString().toLowerCase(Locale.ROOT);
                        if(!key.matches("[a-z0-9_.]{1,64}"))return;
                        Object old=users.get(key);if(old==null||richness(p.thisObject)>=richness(old))users.put(key,p.thisObject);
                    }catch(Throwable ignored){}finally{remembering.remove();}
                }};
                for(String method:new String[]{"getUniqueId","getNickname","getFollowerCount","getFollowingCount","getSignature","getFollowerStatus","setUniqueId"})try{XposedBridge.hookAllMethods(user,method,observe);}catch(Throwable ignored){}
            }catch(Throwable ignored){}
        }
        XposedBridge.hookAllMethods(View.class, "performLongClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ((!InterfaceHooks.on("profile_copy")&&!InterfaceHooks.on("profile_date_jump")) || CrashGuard.tripped) return;
                View v = (View) p.thisObject;
                String key = handles.get(v);
                if (key == null || !v.isShown() || !(v instanceof TextView)
                        || !("@" + key).equalsIgnoreCase(((TextView) v).getText().toString().trim())) return;
                Activity a = ScreenWatcher.top();
                if (a == null || a.getWindow().getDecorView() != v.getRootView()) return;
                if(InterfaceHooks.on("profile_copy"))show(a,key);else ProfileDatePicker.show(a,key);
                p.setResult(true);
            }
        });
    }

    private static int richness(Object o){int n=0;for(String k:new String[]{"nickname","signature","follower_count","following_count","total_favorited","follower_status"})if(RepostTimes.readField(o,k)!=null)n++;return n;}
    private static String date(Object model) {
        long explicit=VideoInfo.parseEventTime(RepostTimes.readField(model,"create_time"));if(explicit>=1262304000L&&explicit<=System.currentTimeMillis()/1000L)return DateFormat.getDateInstance(DateFormat.LONG).format(new Date(explicit*1000L));
        String id = BadgeHooks.uidOf(model);
        try {
            long seconds = Long.parseLong(id) >>> 32;
            if (seconds < 1262304000L || seconds > System.currentTimeMillis() / 1000) return null;
            return DateFormat.getDateInstance(DateFormat.LONG).format(new Date(seconds * 1000));
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String text(Object model, String getter) {
        Object value = DownloadHooks.call(model, getter);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static Object model(String key) {
        Object hit=users.get(key.toLowerCase(Locale.ROOT));
        return hit!=null&&key.equalsIgnoreCase(String.valueOf(RepostTimes.readField(hit,"unique_id")))?hit:null;
    }

    private static void show(Activity a, String key) {
        Object user = model(key);
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        add(labels, values, "Имя", BadgeLogic.strip(text(user, "getNickname")));
        add(labels, values, "Имя пользователя", "@" + key);
        add(labels, values, "Ссылка на профиль", "https://www.tiktok.com/@" + key);
        add(labels, values, "ID пользователя", BadgeHooks.uidOf(user));
        add(labels, values, "Описание", text(user, "getSignature"));
        StreakSheet sheet=new StreakSheet(a,"Скопировать");Ui ui=sheet.ui;
        android.widget.ScrollView scroll=new android.widget.ScrollView(a);LinearLayout rows=new LinearLayout(a);rows.setOrientation(1);rows.setPadding(ui.dp(16),0,ui.dp(16),0);scroll.addView(rows);
        for(int n=0;n<labels.size();n++){
            final int index=n;LinearLayout row=ui.row(-1,labels.get(n),values.get(n),null,false,null);row.setBackground(ui.round(Ui.FIELD,14));
            row.addView(ui.icon(Icons.COPY,Ui.MUTED,20));
            row.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null){cm.setPrimaryClip(new ClipData(labels.get(index),new String[]{"text/plain"},new ClipData.Item(values.get(index))));Toasts.show(a,"Скопировано: "+labels.get(index));}});
            LinearLayout.LayoutParams lp=ui.matchWrap();lp.bottomMargin=ui.dp(5);rows.addView(row,lp);
        }
        sheet.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(390),(int)(a.getResources().getDisplayMetrics().heightPixels*.50f))));
        if(InterfaceHooks.on("profile_date_jump"))sheet.button("Перейти к дате",false,()->{sheet.dialog.dismiss();ProfileDatePicker.show(a,key);});
        sheet.button("Закрыть",false,()->sheet.dialog.dismiss());sheet.show();
    }

    private static void add(List<String> names, List<String> values, String name, String value) {
        if (value != null && !value.isEmpty()) { names.add(name); values.add(value); }
    }

    void bind(TextView view) {
        if (!InterfaceHooks.on("profile_copy") && !InterfaceHooks.on("profile_follow")
                && !InterfaceHooks.on("profile_date") && !InterfaceHooks.on("profile_counts") && !InterfaceHooks.on("profile_repost")&&!InterfaceHooks.on("profile_date_jump")) view = null;
        if (anchor != view || (!InterfaceHooks.on("profile_copy")&&!InterfaceHooks.on("profile_date_jump"))) {
            if (anchor != null && handles.remove(anchor) != null) anchor.setLongClickable(wasLongClickable);
        }
        anchor = view;
        if (view == null) { remove(); return; }
        String raw=view.getText().toString().trim();if(!raw.matches("@[A-Za-z0-9_.]{1,64}")){remove();return;}String key=raw.substring(1);
        if (InterfaceHooks.on("profile_copy")||InterfaceHooks.on("profile_date_jump")) {
            if (!handles.containsKey(view)) wasLongClickable = view.isLongClickable();
            handles.put(view, key);
            view.setLongClickable(true);
        }
        Object user = model(key);
        String created = user != null && InterfaceHooks.on("profile_date") ? date(user) : null;
        Object follower = user != null && InterfaceHooks.on("profile_follow")
                ? RepostTimes.readField(user, "follower_status") : null;
        boolean follows = Boolean.TRUE.equals(follower)||follower instanceof Number && ((Number) follower).intValue() == 1;
        String info = follows ? "Подписан(-а) на вас" : "";
        if (created != null) info += (info.isEmpty() ? "" : "  ·  ") + "В TikTok с " + created;
        if(user!=null && InterfaceHooks.on("profile_repost")) {
            for(String getter:new String[]{"last_repost_time","last_repost_timestamp","last_upvote_time"}) {
                Object value=RepostTimes.readField(user,getter);
                long ts=VideoInfo.parseEventTime(value);
                if(ts>=1262304000L && ts<=System.currentTimeMillis()/1000L) {
                    info+=(info.isEmpty()?"":"\n")+"Последний репост: "+DateFormat.getDateTimeInstance().format(new Date(ts*1000L));break;
                }
            }
        }
        if (user != null && InterfaceHooks.on("profile_counts")) {
            List<String> counts = new ArrayList<>();
            String[] getters = {"following_count", "follower_count", "total_favorited"};
            String[] titles = {"Подписки", "Подписчики", "Лайки"};
            java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance();
            for (int i = 0; i < getters.length; i++) {
                Object number = RepostTimes.readField(user, getters[i]);
                if (number instanceof Number && ((Number) number).longValue() >= 0)
                    counts.add(titles[i] + " " + format.format(((Number) number).longValue()));
            }
            if (!counts.isEmpty()) info += (info.isEmpty() ? "" : "\n") + android.text.TextUtils.join(" · ", counts);
        }
        LinearLayout parent=null;View slot=view;
        for(android.view.ViewParent q=view.getParent();q instanceof ViewGroup;q=q.getParent()){
            ViewGroup g=(ViewGroup)q;if(g==view.getRootView()||g.getHeight()>view.getRootView().getHeight()*.65f)break;
            if(g instanceof LinearLayout&&((LinearLayout)g).getOrientation()==LinearLayout.VERTICAL){parent=(LinearLayout)g;break;}slot=g;
        }
        if(parent==null){remove();return;}
        if (extras == null || extras.getParent() != parent) {
            remove();
            extras = new TextView(view.getContext());
            extras.setTag(Ui.OWN_TAG);
            extras.setTextSize(12);
            parent.addView(extras, parent.indexOfChild(slot) + 1);
        }
        extras.setVisibility(info.isEmpty()?View.GONE:View.VISIBLE);
        if(InterfaceHooks.on("profile_date_jump")){
            if(dateAction==null||dateAction.getParent()!=parent){if(dateAction!=null&&dateAction.getParent() instanceof ViewGroup)((ViewGroup)dateAction.getParent()).removeView(dateAction);dateAction=new TextView(view.getContext());dateAction.setTag(Ui.OWN_TAG);dateAction.setText("Перейти к дате ›");dateAction.setTextSize(13);dateAction.setPadding(0,6,0,6);parent.addView(dateAction,parent.indexOfChild(extras)+1);}
            dateAction.setTextColor(Theme.accent());dateAction.setOnClickListener(v->{Activity a=ScreenWatcher.top();if(a!=null)ProfileDatePicker.show(a,key);});
        }else if(dateAction!=null){if(dateAction.getParent() instanceof ViewGroup)((ViewGroup)dateAction.getParent()).removeView(dateAction);dateAction=null;}
        extras.setTextColor(view.getCurrentTextColor());
        if (!info.contentEquals(extras.getText())) extras.setText(info);
    }

    private void remove() {
        if (extras != null && extras.getParent() instanceof ViewGroup) ((ViewGroup) extras.getParent()).removeView(extras);
        extras = null;
        if(dateAction!=null&&dateAction.getParent() instanceof ViewGroup)((ViewGroup)dateAction.getParent()).removeView(dateAction);dateAction=null;
    }
}
