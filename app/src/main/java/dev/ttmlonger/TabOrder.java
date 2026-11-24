package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

final class TabOrder {
    private static final String DEFAULT = "friends,create,inbox,profile";
    private static final List<String> BASE = Arrays.asList(DEFAULT.split(","));
    private static final List<String> TOP = Arrays.asList("live", "community", "following", "friends");
    private static final String[] TOP_LABELS = {"LIVE", "Сообщество", "Подписки", "Друзья"};
    private static final String[] LABELS = {"Друзья", "Создать", "Входящие", "Профиль"};

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ttmlonger_interface", Context.MODE_PRIVATE);
    }

    static List<String> order(Context c, boolean top) {
        List<String> base = top ? TOP : BASE;
        String fallback = android.text.TextUtils.join(",", base);
        List<String> saved = Arrays.asList(prefs(c).getString(top ? "top_tabs" : "tabs", fallback).split(","));
        return new ArrayList<>(saved.size() == base.size() && saved.containsAll(base) ? saved : base);
    }

    static boolean custom(Context c, boolean top) {
        return prefs(c).contains(top ? "top_tabs" : "tabs");
    }

    static String name(View v, int depth) {
        if ("LIVE".contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return "live";
        if (v instanceof TextView) {
            String text = ((TextView) v).getText().toString().trim().toLowerCase(Locale.ROOT);
            switch (text) {
                case "подписки": case "following": return "following";
                case "сообщество": case "community": return "community";
                case "рекомендации": case "для вас": case "for you": return "recommend";
                case "live": return "live";
                case "главная": case "home": return "home";
                case "друзья": case "friends": return "friends";
                case "входящие": case "inbox": return "inbox";
                case "создать": case "create": return "create";
                case "профиль": case "profile": return "profile";
            }
        } else if (depth < 5 && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String found = name(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final java.util.WeakHashMap<View, Float> offsets = new java.util.WeakHashMap<>();

    private static final java.util.WeakHashMap<View, Float> topOffsets = new java.util.WeakHashMap<>();

    static void reset(boolean top) {
        java.util.WeakHashMap<View, Float> offsets = top ? topOffsets : TabOrder.offsets;
        for (View v : offsets.keySet().toArray(new View[0])) if (v != null)
            v.setTranslationX(v.getTranslationX() - offsets.get(v));
        offsets.clear();
    }

    static void apply(BarFinder.Found bar) {
        HashMap<String, View> known = new HashMap<>();
        ArrayList<View> places = new ArrayList<>();
        for (int i = 0; i < bar.tabs.size(); i++) {
            View tab = bar.tabs.get(i);
            String key = name(tab, 0);
            if (key == null && i == bar.create && bar.tabs.size() == 5) key = "create";
            if (!BASE.contains(key) || known.containsKey(key)) continue;
            known.put(key, tab);
            places.add(tab);
        }
        if (places.size() < 2) return;
        List<String> order = order(bar.bar.getContext(), false);
        if (bar.bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) java.util.Collections.reverse(order);
        int index = 0;
        for (String key : order) {
            View v = known.get(key);
            if (v == null) continue;
            View target = places.get(index++);
            float delta = target.getLeft() + target.getWidth() / 2f - v.getLeft() - v.getWidth() / 2f;
            Float old = offsets.put(v, delta);
            if (old == null || old != delta) v.setTranslationX(v.getTranslationX() - (old == null ? 0 : old) + delta);
        }
    }

    static boolean applyTop(View view, int depth, int[] budget, int height) {
        if (depth > 18 || --budget[0] <= 0 || !(view instanceof ViewGroup) || !view.isShown()) return false;
        ViewGroup group = (ViewGroup) view;
        int[] pos = new int[2];
        group.getLocationOnScreen(pos);
        if (pos[1] > height * .23f) return false;
        if (group.getChildCount() >= 3 && group.getChildCount() <= 12 && group.getHeight() < height * .16f
                && group.getWidth() >= view.getResources().getDisplayMetrics().widthPixels * .45f) {
            HashMap<String, View> tabs = new HashMap<>();
            ArrayList<View> places = new ArrayList<>();
            boolean recommend = false;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if ((!child.isShown() && !TopTabs.hidden(child)) || child.getWidth() == 0) continue;
                String key = name(child, 0);
                if ("recommend".equals(key)) recommend = true;
                if (TOP.contains(key) && !tabs.containsKey(key)) { tabs.put(key, child); places.add(child); }
            }
            if (recommend && places.size() >= 2) {
                places.sort((a,b) -> Integer.compare(a.getLeft(), b.getLeft()));
                List<String> order = order(group.getContext(), true);
                if (group.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) java.util.Collections.reverse(order);
                // Keep fixed tabs in place. Pack each uninterrupted run using actual title widths.
                ArrayList<View> ordered = new ArrayList<>();
                int first = 0;
                while (first < places.size()) {
                    int end = first + 1;
                    while (end < places.size() && Math.abs(group.indexOfChild(places.get(end))
                            - group.indexOfChild(places.get(end - 1))) == 1) end++;
                    List<View> run = places.subList(first, end);
                    for (String key : order) if (run.contains(tabs.get(key))) ordered.add(tabs.get(key));
                    first = end;
                }
                int cursor = places.get(0).getLeft();
                for (int i = 0; i < ordered.size(); i++) {
                    View tab = ordered.get(i);
                    if (i > 0) {
                        View before = places.get(i - 1), at = places.get(i);
                        if (Math.abs(group.indexOfChild(before) - group.indexOfChild(at)) > 1) cursor = at.getLeft();
                        else cursor += at.getLeft() - before.getRight();
                    }
                    float delta = cursor - tab.getLeft();
                    cursor += tab.getWidth();
                    Float previous = topOffsets.put(tab, delta);
                    if (previous == null || previous != delta)
                        tab.setTranslationX(tab.getTranslationX() - (previous == null ? 0 : previous) + delta);
                }
                return true;
            }
        }
        for (int i = 0; i < group.getChildCount(); i++)
            if (applyTop(group.getChildAt(i), depth + 1, budget, height)) return true;
        return false;
    }

    static void editor(Activity a, boolean top) {
        List<String> items = order(a, top);
        List<String> base = top ? TOP : BASE;
        String[] labels = top ? TOP_LABELS : LABELS;
        String pref = top ? "top_tabs" : "tabs";
        StreakSheet sheet=new StreakSheet(a,top?"Верхние вкладки":"Нижние вкладки");Ui ui=sheet.ui;
        sheet.root.addView(sheet.note("Перетащите вкладку за ручку или нажмите, чтобы изменить порядок."));
        LinearLayout box=new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(14),ui.dp(6),ui.dp(14),ui.dp(12));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list,ui.matchWrap());
        LinearLayout demo=new LinearLayout(a);
        demo.setOrientation(LinearLayout.VERTICAL);
        demo.setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(12));
        demo.setBackground(ui.round(0xff08090c,18));
        demo.addView(ui.text("Предпросмотр",14,0xffeeeeee,true));
        android.widget.HorizontalScrollView sc=new android.widget.HorizontalScrollView(a);
        sc.setHorizontalScrollBarEnabled(false);
        LinearLayout rail=new LinearLayout(a);
        rail.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sc.addView(rail);
        demo.addView(sc,ui.matchWrap());
        LinearLayout.LayoutParams dp=ui.matchWrap();dp.setMargins(ui.dp(14),0,ui.dp(14),ui.dp(6));sheet.root.addView(demo,dp);
        final boolean[] reset={false};
        Runnable draw = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                rail.removeAllViews();
                if(!top) rail.addView(previewTab(a,ui,"Главная",true,false));
                for (String key : items) {
                    Button b = new Button(a);
                    b.setAllCaps(false);
                    b.setText("☰    "+labels[base.indexOf(key)]);b.setTextSize(15);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));b.setStateListAnimator(null);
                    b.setTextColor(Ui.INK);
                    b.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    b.setPadding(ui.dp(14),0,ui.dp(14),0);
                    b.setBackground(ui.round(Ui.FIELD,14));
                    b.setForeground(ui.ripple());
                    rail.addView(previewTab(a,ui,labels[base.indexOf(key)],false,top));
                    b.setOnLongClickListener(v -> v.startDragAndDrop(null, new View.DragShadowBuilder(v), key, 0));
                    b.setOnDragListener((v, event) -> {
                        if (event.getAction() == DragEvent.ACTION_DROP && items.contains(event.getLocalState())) {
                            int to = items.indexOf(key);
                            String moving = (String) event.getLocalState();
                            items.remove(moving);
                            items.add(Math.min(to,items.size()), moving);
                            reset[0]=false;
                            list.post(this);
                        }
                        return true;
                    });
                    b.setOnClickListener(v -> {
                        int i = items.indexOf(key);
                        java.util.Collections.swap(items, i, (i + 1) % items.size());
                        reset[0]=false;
                        run();
                    });
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,ui.dp(48));
                    lp.bottomMargin=ui.dp(6);
                    list.addView(b,lp);
                }
                if(top) rail.addView(previewTab(a,ui,"Рекомендации",true,true));
            }
        };
        draw.run();
        android.widget.ScrollView scroll=new android.widget.ScrollView(a);
        scroll.addView(box);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sheet.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(390),(int)(a.getResources().getDisplayMetrics().heightPixels*.40f))));
        sheet.button("Как в TikTok",false,()->{items.clear();items.addAll(base);reset[0]=true;draw.run();});
        sheet.button("Сохранить",true,()->{
            if(reset[0])prefs(a).edit().remove(pref).apply();
            else prefs(a).edit().putString(pref,android.text.TextUtils.join(",",items)).apply();
            InterfaceHooks.labels(a);sheet.dialog.dismiss();
        });sheet.show();
    }
    private static View previewTab(Activity a,Ui ui,String text,boolean selected,boolean top){
        LinearLayout col=new LinearLayout(a);col.setOrientation(LinearLayout.VERTICAL);col.setGravity(android.view.Gravity.CENTER);
        col.setPadding(ui.dp(top?5:6),ui.dp(8),ui.dp(top?5:6),ui.dp(8));
        if(!top){
            PreviewGlyph icon=new PreviewGlyph(a,text.equals("Главная")?5:text.equals("Друзья")?6:text.equals("Создать")?7:text.equals("Входящие")?8:0,0xffffffff);
            col.addView(icon,new LinearLayout.LayoutParams(ui.dp(24),ui.dp(24)));
        }
        TextView label=ui.medium(text,top?11:10,selected?0xffffffff:0xffaaaaaa);label.setGravity(android.view.Gravity.CENTER);label.setSingleLine(true);col.addView(label);
        if(top&&selected){View underline=new View(a);underline.setBackground(ui.round(0xffffffff,2));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(22),ui.dp(2));lp.topMargin=ui.dp(4);col.addView(underline,lp);}
        return col;
    }
}
