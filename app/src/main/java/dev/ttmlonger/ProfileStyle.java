package dev.ttmlonger;
import android.view.*;
import android.widget.*;
import java.util.*;

final class ProfileStyle {
    static final String[] KEYS={"profile_view","profile_add","profile_balance","profile_minis"};
    static final String[] TITLES={"Кнопка «Просмотры профиля»","Кнопка «Добавить друзей»","Баланс в меню профиля","Иконки TikTok Minis"};
    private static final WeakHashMap<View,Integer> hidden=new WeakHashMap<>();
    private static String norm(CharSequence t){return t==null?"":t.toString().toLowerCase(Locale.ROOT).trim();}
    static void apply(View root,boolean profile){
        for(View v:new ArrayList<>(hidden.keySet())){
            if(v==null||v.getRootView()!=root)continue;
            if(!profile||!shouldHide(v)){Integer old=hidden.remove(v);if(old!=null&&v.getVisibility()==View.GONE)v.setVisibility(old);}
        }
        if(profile)walk(root,0,new int[]{2500});
    }
    private static boolean shouldHide(View v){
        String s=norm(v.getContentDescription());
        if(v instanceof TextView)s+=" "+norm(((TextView)v).getText());
        s=s.trim();
        if(s.equals("просмотры профиля")||s.equals("profile views")||s.equals("profile view history"))return InterfaceHooks.on(KEYS[0]);
        if(s.equals("добавить друзей")||s.equals("add friends")||s.equals("find friends"))return InterfaceHooks.on(KEYS[1]);
        if(s.equals("баланс")||s.equals("balance"))return InterfaceHooks.on(KEYS[2]);
        if(s.equals("minis")||s.equals("tiktok minis"))return InterfaceHooks.on(KEYS[3]);
        return false;
    }
    private static void walk(View v,int depth,int[] budget){
        if(--budget[0]<0||depth>35||v.getVisibility()!=View.VISIBLE||Ui.OWN_TAG.equals(v.getTag()))return;
        if(shouldHide(v)){hidden.put(v,v.getVisibility());v.setVisibility(View.GONE);return;}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i),depth+1,budget);}
    }
}
