package dev.ttmlonger;

import android.view.View;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class FeedButtons {
    enum Button {
        AUTHOR("Автор", "avatar.FeedAvatarDefaultAssem"),
        LIKE("Лайк", "digg.VideoDiggAssem"),
        COMMENT("Комментарии", "videocomment.VideoCommentAssem"),
        FAVORITE("Избранное", "com.ss.android.ugc.aweme.feed.favorite.VideoFavoriteAssem"),
        SHARE("Поделиться", "share.VideoShareAssem");

        final String label;
        final String className;
        Button(String label, String name) {
            this.label = label;
            className = name.startsWith("com.") ? name : "com.ss.android.ugc.aweme.feed.assem." + name;
        }

        String key() { return "hide_" + name(); }
    }

    private static final class Slot {
        Button button;
        int requested;
        Slot(Button button, int requested) { this.button = button; this.requested = requested; }
    }

    private static final WeakHashMap<View, Slot> slots = new WeakHashMap<>();
    private static boolean applying;

    static void install(ClassLoader cl) {
        XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (applying || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;
                Slot slot = slots.get((View) p.thisObject);
                if (slot == null) return;
                slot.requested = (Integer) p.args[0];
                if (InterfaceHooks.on(slot.button.key())) p.args[0] = View.GONE;
            }
        });
        for (Button button : Button.values()) {
            try {
                Class<?> cls = Class.forName(button.className, false, cl);
                Method content = cls.getMethod("getContentView");
                XC_MethodHook bind = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (p.hasThrowable()) return;
                        Object obj = content.invoke(p.thisObject);
                        if (!(obj instanceof View)) return;
                        View view = (View) obj;
                        if (!slots.containsKey(view)) slots.put(view, new Slot(button, view.getVisibility()));
                        apply(view, slots.get(view));
                    }
                };
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class) continue;
                    if ((m.getName().equals("onViewCreated") && Arrays.equals(m.getParameterTypes(), new Class<?>[]{View.class}))
                            || (m.getName().equals("s4") && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Object.class}))) {
                        XposedBridge.hookMethod(m, bind);
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // This component is optional and varies between releases.
            }
        }
    }

    static String action(View v) {
        for (int depth = 0; v != null && depth < 8; depth++) {
            Slot s = slots.get(v);
            if (s != null) {
                if (s.button == Button.LIKE) return "like";
                if (s.button == Button.SHARE) return "share";
                return null;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    static boolean inFeed(android.app.Activity a) {
        for (View v : slots.keySet()) if (visible(a, v)) return true;
        return false;
    }

    private static boolean visible(android.app.Activity a, View v) {
        return v != null && v.getRootView() == a.getWindow().getDecorView() && v.isShown()
                && v.getGlobalVisibleRect(new android.graphics.Rect());
    }

    static View clickTarget(android.app.Activity a, Button button) {
        View selected = null;
        int largest = 0;
        android.graphics.Rect bounds = new android.graphics.Rect();
        for (java.util.Map.Entry<View, Slot> entry : slots.entrySet()) {
            View v = entry.getKey();
            if (entry.getValue().button != button || !visible(a, v) || !v.getGlobalVisibleRect(bounds)) continue;
            int area = bounds.width() * bounds.height();
            if (area > largest) { selected = v; largest = area; }
        }
        return selected == null ? null : clickable(selected, 0);
    }

    private static View clickable(View v, int depth) {
        if (!v.isShown() || !v.isEnabled()) return null;
        if (v.hasOnClickListeners()) return v;
        if (depth < 5 && v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = clickable(g.getChildAt(i), depth + 1);
                if (child != null) return child;
            }
        }
        return null;
    }

    static boolean click(android.app.Activity a, Button button) {
        View v = clickTarget(a, button);
        return v != null && v.performClick();
    }

    static void refresh() {
        for (View v : slots.keySet().toArray(new View[0])) if (v != null) apply(v, slots.get(v));
    }

    private static void apply(View v, Slot slot) {
        int wanted = InterfaceHooks.on(slot.button.key()) ? View.GONE : slot.requested;
        if (v.getVisibility() == wanted) return;
        applying = true;
        try { v.setVisibility(wanted); } finally { applying = false; }
    }
}
