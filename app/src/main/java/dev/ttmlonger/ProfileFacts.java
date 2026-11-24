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
    private static final LruCache<String, WeakReference<Object>> users = new LruCache<>(128);
    private TextView extras;
    private TextView anchor;
    private boolean wasLongClickable;
    private static final java.util.WeakHashMap<View, String> handles = new java.util.WeakHashMap<>();

    static void install(ClassLoader cl) {
        for (String name : new String[]{AvatarHooks.USER, BadgeHooks.PROFILE}) {
            try {
                Class<?> user = Class.forName(name, false, cl);
                XposedBridge.hookMethod(user.getMethod("getUniqueId"), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!(p.getResult() instanceof String)) return;
                        String key = ((String) p.getResult()).toLowerCase(Locale.ROOT);
                        if (key.isEmpty()) return;
                        WeakReference<Object> old = users.get(key);
                        if (old == null || old.get() != p.thisObject) users.put(key, new WeakReference<>(p.thisObject));
                    }
                });
            } catch (ReflectiveOperationException | LinkageError ignored) { }
        }
        XposedBridge.hookAllMethods(View.class, "performLongClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!InterfaceHooks.on("profile_copy") || CrashGuard.tripped) return;
                View v = (View) p.thisObject;
                String key = handles.get(v);
                if (key == null || !v.isShown() || !(v instanceof TextView)
                        || !("@" + key).equalsIgnoreCase(((TextView) v).getText().toString().trim())) return;
                Activity a = ScreenWatcher.top();
                if (a == null || a.getWindow().getDecorView() != v.getRootView()) return;
                show(a, key);
                p.setResult(true);
            }
        });
    }

    private static String date(Object model) {
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
        WeakReference<Object> weak = users.get(key.toLowerCase(Locale.ROOT));
        return weak == null ? null : weak.get();
    }

    private static void show(Activity a, String key) {
        Object user = model(key);
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        add(labels, values, "Имя", BadgeLogic.strip(text(user, "getNickname")));
        add(labels, values, "Имя пользователя", "@" + key);
        add(labels, values, "Ссылка", "https://www.tiktok.com/@" + key);
        add(labels, values, "ID", BadgeHooks.uidOf(user));
        add(labels, values, "Описание", text(user, "getSignature"));
        AlertDialog d = new AlertDialog.Builder(a, Ui.dialogTheme()).setTitle("Скопировать из профиля")
                .setItems(labels.toArray(new String[0]), (dialog, i) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(labels.get(i), values.get(i)));
                    Toasts.show(a, "Скопировано");
                }).setNegativeButton("Закрыть", null).create();
        Screen.markOwn(d);
        d.show();
        Ui.style(d);
    }

    private static void add(List<String> names, List<String> values, String name, String value) {
        if (value != null && !value.isEmpty()) { names.add(name); values.add(value); }
    }

    void bind(TextView view) {
        if (!InterfaceHooks.on("profile_copy") && !InterfaceHooks.on("profile_follow")
                && !InterfaceHooks.on("profile_date") && !InterfaceHooks.on("profile_counts") && !InterfaceHooks.on("profile_repost")) view = null;
        if (anchor != view || !InterfaceHooks.on("profile_copy")) {
            if (anchor != null && handles.remove(anchor) != null) anchor.setLongClickable(wasLongClickable);
        }
        anchor = view;
        if (view == null) { remove(); return; }
        String key = view.getText().toString().trim().substring(1);
        if (InterfaceHooks.on("profile_copy")) {
            if (!handles.containsKey(view)) wasLongClickable = view.isLongClickable();
            handles.put(view, key);
            view.setLongClickable(true);
        }
        Object user = model(key);
        String created = user != null && InterfaceHooks.on("profile_date") ? date(user) : null;
        Object follower = user != null && InterfaceHooks.on("profile_follow")
                ? DownloadHooks.call(user, "getFollowerStatus") : null;
        boolean follows = follower instanceof Number && ((Number) follower).intValue() == 1;
        String info = follows ? "Подписан(-а) на вас" : "";
        if (created != null) info += (info.isEmpty() ? "" : "  ·  ") + "В TikTok с " + created;
        if(user!=null && InterfaceHooks.on("profile_repost")) {
            for(String getter:new String[]{"getLastRepostTime","getLastRepostTimestamp"}) {
                Object value=DownloadHooks.call(user,getter);
                if(!(value instanceof Number))continue;
                long ts=((Number)value).longValue();if(ts>100000000000L)ts/=1000;
                if(ts>=1262304000L && ts<=System.currentTimeMillis()/1000L) {
                    info+=(info.isEmpty()?"":"\n")+"Последний репост: "+DateFormat.getDateTimeInstance().format(new Date(ts*1000L));break;
                }
            }
        }
        if (user != null && InterfaceHooks.on("profile_counts")) {
            List<String> counts = new ArrayList<>();
            String[] getters = {"getFollowingCount", "getFollowerCount", "getTotalFavorited"};
            String[] titles = {"Подписки", "Подписчики", "Лайки"};
            java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance();
            for (int i = 0; i < getters.length; i++) {
                Object number = DownloadHooks.call(user, getters[i]);
                if (number instanceof Number && ((Number) number).longValue() >= 0)
                    counts.add(titles[i] + " " + format.format(((Number) number).longValue()));
            }
            if (!counts.isEmpty()) info += (info.isEmpty() ? "" : "\n") + android.text.TextUtils.join(" · ", counts);
        }
        if (info.isEmpty() || !(view.getParent() instanceof LinearLayout)) { remove(); return; }
        LinearLayout parent = (LinearLayout) view.getParent();
        if (parent.getOrientation() != LinearLayout.VERTICAL) { remove(); return; }
        if (extras == null || extras.getParent() != parent) {
            remove();
            extras = new TextView(view.getContext());
            extras.setTag(Ui.OWN_TAG);
            extras.setTextSize(12);
            parent.addView(extras, parent.indexOfChild(view) + 1);
        }
        extras.setTextColor(view.getCurrentTextColor());
        if (!info.contentEquals(extras.getText())) extras.setText(info);
    }

    private void remove() {
        if (extras != null && extras.getParent() instanceof ViewGroup) ((ViewGroup) extras.getParent()).removeView(extras);
        extras = null;
    }
}
