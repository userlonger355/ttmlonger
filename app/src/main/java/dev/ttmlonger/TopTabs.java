package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import java.util.*;
/** Reversible visibility on a verified top-feed navigation row. Native pages remain intact. */
final class TopTabs {
    private static final WeakHashMap<View,String> hidden=new WeakHashMap<>();
    private static final Set<String> known=new HashSet<>(Arrays.asList("live","community","following","friends","recommend"));
    static boolean hidden(View v){return hidden.containsKey(v)&&v.getVisibility()==View.INVISIBLE;}
    static String setting(String key){
        if("community".equals(key)||"friends".equals(key)||"recommend".equals(key))return "top_hide_"+key;
        return null;
    }
    static boolean wanted(String key){String pref=setting(key);return pref!=null&&InterfaceHooks.on(pref)&&!CrashGuard.tripped;}
    private static void restore(View v){if(hidden.remove(v)!=null&&v.getVisibility()==View.INVISIBLE)v.setVisibility(View.VISIBLE);}
    static void apply(Activity a){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())return;
        View root=a.getWindow().getDecorView();
        for(View v:new ArrayList<>(hidden.keySet())){
            if(v==null)continue;
            String key=hidden.get(v);
            if(!wanted(key)||!key.equals(TabOrder.name(v,0))||v.getRootView()!=root||!v.isAttachedToWindow())restore(v);
        }
        if(!wanted("community")&&!wanted("friends")&&!wanted("recommend"))return;
        if(root.getWidth()<=0||root.getHeight()<=0||Screen.isOwnDecor(root))return;
        int[] origin=new int[2];root.getLocationOnScreen(origin);
        ViewGroup group=find(root,root.getWidth(),root.getHeight(),origin[1],0,new int[]{800});
        if(group==null)return;
        Set<View> keep=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i=0;i<group.getChildCount();i++){
            View child=group.getChildAt(i);
            String key=TabOrder.name(child,0);
            if(wanted(key)&&(child.getVisibility()==View.VISIBLE||hidden(child))){
                keep.add(child);hidden.put(child,key);
                if(child.getVisibility()!=View.INVISIBLE)child.setVisibility(View.INVISIBLE);
            }
        }
        for(View v:new ArrayList<>(hidden.keySet()))if(v!=null&&v.getRootView()==root&&!keep.contains(v))restore(v);
    }
    static boolean navigation(Set<String> keys,boolean named){
        return keys.contains("recommend")&&(keys.size()>=3||named&&keys.size()>=2);
    }
    private static ViewGroup find(View v,int width,int height,int originY,int depth,int[] budget){
        if(depth>18||--budget[0]<=0||!(v instanceof ViewGroup)||!v.isShown())return null;
        ViewGroup g=(ViewGroup)v;int[] at=new int[2];g.getLocationOnScreen(at);
        if(at[1]-originY>height*.23f)return null;
        if(g.getChildCount()>=2&&g.getChildCount()<=12&&g.getWidth()>=width*.45f&&g.getHeight()>0&&g.getHeight()<height*.16f){
            Set<String> keys=new HashSet<>();boolean duplicate=false;
            for(int i=0;i<g.getChildCount();i++){
                View child=g.getChildAt(i);
                if(child.getWidth()<=0||child.getVisibility()!=View.VISIBLE&&!hidden(child))continue;
                String key=TabOrder.name(child,0);
                if(known.contains(key)&&!keys.add(key))duplicate=true;
            }
            String name=g.getClass().getName().toLowerCase(Locale.ROOT);
            try{name+=" "+g.getResources().getResourceEntryName(g.getId()).toLowerCase(Locale.ROOT);}catch(Exception ignored){}
            if(!duplicate&&navigation(keys,name.contains("tab")||name.contains("feed")))return g;
        }
        for(int i=0;i<g.getChildCount();i++){ViewGroup hit=find(g.getChildAt(i),width,height,originY,depth+1,budget);if(hit!=null)return hit;}
        return null;
    }
}
