package dev.ttmlonger;

import android.view.*;
import android.widget.*;
import java.util.*;

final class ChatTicks {
    private static final WeakHashMap<TextView,CharSequence> originals=new WeakHashMap<>();
    private static String norm(CharSequence s){return s==null?"":s.toString().trim().toLowerCase(Locale.ROOT);}
    private static String name(View v){
        String s=v.getClass().getName().toLowerCase(Locale.ROOT);
        try{if(v.getId()!=View.NO_ID)s+=" "+v.getResources().getResourceEntryName(v.getId()).toLowerCase(Locale.ROOT);}catch(Throwable ignored){}
        return s;
    }
    static boolean chat(View root){return findChat(root,0,new int[]{1600});}
    private static boolean findChat(View v,int depth,int[] budget){
        if(v==null||--budget[0]<0||depth>35||v.getVisibility()!=View.VISIBLE||Ui.OWN_TAG.equals(v.getTag()))return false;
        String n=name(v);
        if(n.contains("chatroom")||n.contains("chat_room")||n.contains("conversation_detail"))return true;
        if(v instanceof EditText){String hint=norm(((EditText)v).getHint());if(hint.equals("сообщение")||hint.equals("сообщение…")||hint.equals("сообщение...")||hint.equals("message")||hint.equals("message…")||hint.equals("message..."))return true;}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(findChat(g.getChildAt(i),depth+1,budget))return true;}
        return false;
    }
    static void apply(View root){
        if(root==null||Screen.isOwnDecor(root))return;
        boolean active=!CrashGuard.tripped&&InterfaceHooks.on("chat_ticks")&&chat(root);
        for(TextView v:new ArrayList<>(originals.keySet())){
            if(v==null||v.getRootView()!=root)continue;
            if(!active){CharSequence old=originals.remove(v);String t=v.getText().toString();if(old!=null&&(t.equals("✔️")||t.equals("✔️✔️")))v.setText(old);}
        }
        if(active)walk(root,0,new int[]{2400});
    }
    private static void walk(View v,int depth,int[] budget){
        if(--budget[0]<0||depth>38||v.getVisibility()!=View.VISIBLE||Ui.OWN_TAG.equals(v.getTag()))return;
        if(v instanceof TextView&&!(v instanceof EditText)){
            TextView t=(TextView)v;String s=norm(t.getText());
            String n=name(t);
            // Only status labels, never message bubble content.
            if(!(n.contains("read_status")||n.contains("readstatus")||n.contains("send_status")||n.contains("sendstatus")||n.contains("receipt")||n.contains("message_status")))return;
            String glyph=s.equals("прочитано")||s.equals("read")||s.equals("seen")?"✔️✔️":s.equals("отправлено")||s.equals("sent")?"✔️":null;
            if(glyph!=null){originals.put(t,t.getText());t.setText(glyph);}
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i),depth+1,budget);}
    }
}
