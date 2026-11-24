package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class InterfaceHooks {
    static SharedPreferences prefs;
    private static final Map<TextView, Float> hidden = new WeakHashMap<>();
    private static WeakReference<View> last = new WeakReference<>(null);
    private static long lastAt;
    private static String lastAction = "";

    static boolean on(String key) {
        if(key.equals("comment_copy"))key="comment_copy_body";
        return prefs != null && prefs.getBoolean(key, (key.equals("details") || key.equals("comment_copy_body") || key.equals("profile_date_jump") || key.equals("profile_copy")));
    }

    static void set(String key, boolean value) {
        if(key.equals("comment_copy"))key="comment_copy_body";
        prefs.edit().putBoolean(key, value).apply();
    }

    static void install(Context ctx) {
        prefs = ctx.getSharedPreferences("ttmlonger_interface", Context.MODE_PRIVATE);
        if(!prefs.contains("comment_copy_body")&&prefs.contains("comment_copy"))prefs.edit().putBoolean("comment_copy_body",prefs.getBoolean("comment_copy",true)).remove("comment_copy").apply();
        XC_MethodHook clean = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!on("clean") || CrashGuard.tripped || p.args.length < 2) return;
                if (p.thisObject instanceof Intent && !Intent.EXTRA_TEXT.equals(p.args[0])) return;
                if (!(p.args[1] instanceof CharSequence)) return;
                String raw = p.args[1].toString();
                if (!raw.toLowerCase(Locale.ROOT).contains("tiktok.com")) return;
                String cleaned = ShareLinks.cleanText(raw);
                if (!raw.equals(cleaned)) p.args[1] = cleaned;
            }
        };
        XposedBridge.hookAllMethods(ClipData.class, "newPlainText", clean);
        XposedBridge.hookAllMethods(Intent.class, "putExtra", clean);
        XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (CrashGuard.tripped) return;
                View v = (View) p.thisObject;
                CharSequence desc = v.getContentDescription();
                String action = FeedButtons.action(v);
                if (action == null && desc != null && desc.length() <= 100) {
                    action = actionFor(desc.toString());
                    if (action == null || !on(action)) return;
                    Activity top = ScreenWatcher.top();
                    if (top == null || v.getRootView() != top.getWindow().getDecorView() || !FeedButtons.inFeed(top)) return;
                }
                if (action == null || !on(action) || Screen.isOwnDecor(v.getRootView())) return;
                long now = SystemClock.uptimeMillis();
                if (last.get() == v && action.equals(lastAction) && now - lastAt <= 2000) {
                    last.clear();
                    return;
                }
                last = new WeakReference<>(v);
                lastAt = now;
                lastAction = action;
                Toasts.show(v.getContext(), "Нажмите ещё раз для подтверждения");
                p.setResult(true);
            }
        });
    }

    static String actionFor(String label) {
        String s = label.toLowerCase(Locale.ROOT).trim().split("[,·\\n]", 2)[0].trim();
        switch (s) {
            case "like": case "лайк": case "нравится": case "поставить лайк": return "like";
            case "follow": case "подписаться": return "follow";
            case "repost": case "репост": case "сделать репост": return "repost";
            case "share": case "поделиться": return "share";
            default: return null;
        }
    }

    static void labels(Activity act) {
        View decor = act.getWindow().getDecorView();
        if (!CrashGuard.tripped && TabOrder.custom(act, true))
            TabOrder.applyTop(decor, 0, new int[]{600}, decor.getHeight());
        else TabOrder.reset(true);
        TopTabs.apply(act);
        boolean reorder = !CrashGuard.tripped && TabOrder.custom(act, false);
        if (!reorder) TabOrder.reset(false);
        if (!on("labels") || CrashGuard.tripped) {
            for (Map.Entry<TextView, Float> entry : hidden.entrySet()) {
                if (entry.getKey().getAlpha() == 0f) entry.getKey().setAlpha(entry.getValue());
            }
            hidden.clear();
            if (!reorder) return;
        }
        BarFinder.Found f = BarFinder.find(act.getWindow().getDecorView());
        if (f == null) return;
        if (reorder) TabOrder.apply(f);
        if (on("labels")) for (View tab : f.tabs) hideName(tab, 0);
    }

    private static void hideName(View view, int depth) {
        if (depth > 5) return;
        if (view instanceof TextView) {
            TextView t = (TextView) view;
            String label = t.getText().toString().trim().toLowerCase(Locale.ROOT);
            switch (label) {
                case "home": case "главная": case "friends": case "друзья": case "inbox": case "входящие":
                case "profile": case "профиль": case "create": case "создать": case "shop": case "магазин":
                    if (t.getAlpha() != 0f) {
                        hidden.put(t, t.getAlpha());
                        t.setAlpha(0f);
                    }
                    break;
                default:
                    Float prior = hidden.remove(t);
                    if (prior != null && t.getAlpha() == 0f) t.setAlpha(prior);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideName(group.getChildAt(i), depth + 1);
        }
    }
}
