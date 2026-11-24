package dev.ttmlonger;

import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.*;

final class ReadPrivacy {
    private static final String PROTO="com.bytedance.im.core.proto.";
    private static volatile int readHooks, sendHooks;
    private static final AtomicInteger dropped=new AtomicInteger(), allowed=new AtomicInteger();
    private static final Map<String,Long> mod=new ConcurrentHashMap<>(), manual=new ConcurrentHashMap<>();
    private static volatile long tapAt;
    private static final String[] READ={"MarkConversationReadRequestBody","ConversationMarkReadRequestBody","MarkMessageReadRequestBody","MarkReadRequestBody"};
    static boolean enabled(){return InterfaceHooks.on("read_private");}
    static boolean ready(){return readHooks>0;}
    static boolean replyReady(){return readHooks>0&&sendHooks>0;}
    static String status(){return "Прочтение: "+(ready()?"найдено мест "+readHooks+", блокировано "+dropped.get()+", разрешено после ответа "+allowed.get():"запрос не распознан");}
    static void modSend(String conv){
        if(conv==null)return;
        long now=SystemClock.elapsedRealtime();
        if(mod.size()>512)prune(mod,now,120000L);
        mod.put(conv,now);manual.remove(conv);tapAt=0;
    }
    private static void prune(Map<String,Long> map,long now,long age){
        for(Map.Entry<String,Long> e:map.entrySet())if(now-e.getValue()>age)map.remove(e.getKey(),e.getValue());
        if(map.size()>512)map.clear();
    }
    private static String conversation(Object body){
        if(body==null)return null;
        for(String getter:new String[]{"getConversationId","getConversationID"}){
            Object v=DownloadHooks.call(body,getter);
            if(v instanceof String&&!((String)v).isEmpty())return (String)v;
        }
        for(String name:new String[]{"conversation_id","conversationId"}){
            try{Field f=DownloadHooks.findField(body.getClass(),name);Object v=f==null?null:f.get(body);
                if(v instanceof String&&!((String)v).isEmpty())return (String)v;
            }catch(Throwable ignored){}
        }
        return null;
    }
    static void install(ClassLoader cl){
        Class<?> request=XposedHelpers.findClassIfExists(Invisible.REQUEST,cl), builder=XposedHelpers.findClassIfExists(Invisible.BUILDER,cl);
        Set<String> hooked=new HashSet<>();
        for(String name:READ){
            Class<?> body=XposedHelpers.findClassIfExists(PROTO+name,cl);
            for(Invisible.Slot slot:Invisible.typingSlots(body,request,builder)){
                if(!hooked.add(slot.member.toString()+":"+slot.arg))continue;
                try{
                    XposedBridge.hookMethod(slot.member,new XC_MethodHook(){
                        @Override protected void beforeHookedMethod(MethodHookParam p){
                            if(CrashGuard.tripped||!enabled()||p.args==null||p.args[slot.arg]==null)return;
                            String conv=conversation(p.args[slot.arg]);
                            long now=SystemClock.elapsedRealtime();
                            if(InterfaceHooks.on("read_reply")&&conv!=null){
                                Long sent=manual.get(conv), auto=mod.get(conv);
                                if(sent!=null&&now-sent<=10000L&&(auto==null||now-auto>120000L)){
                                    allowed.incrementAndGet();return;
                                }
                            }
                            p.args[slot.arg]=null;dropped.incrementAndGet();
                        }
                    });readHooks++;
                }catch(Throwable ignored){}
            }
        }
        if(!ready())return;
        Class<?> send=XposedHelpers.findClassIfExists(PROTO+"SendMessageRequestBody",cl);
        for(Invisible.Slot slot:Invisible.typingSlots(send,request,builder)){
            try{
                XposedBridge.hookMethod(slot.member,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p){
                        if(!enabled()||!InterfaceHooks.on("read_reply")||CrashGuard.tripped)return;
                        long now=SystemClock.elapsedRealtime(),tap=tapAt;
                        if(tap==0||now-tap>2500L)return;
                        String conv=conversation(p.args[slot.arg]);if(conv==null)return;
                        Long auto=mod.get(conv);if(auto!=null&&now-auto<=120000L)return;
                        tapAt=0;
                        if(manual.size()>512)prune(manual,now,10000L);
                        manual.put(conv,now);
                    }
                });sendHooks++;
            }catch(Throwable ignored){}
        }
        if(sendHooks>0)try{
            XposedBridge.hookAllMethods(View.class,"performClick",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(CrashGuard.tripped||!enabled()||!InterfaceHooks.on("read_reply"))return;
                    View v=(View)p.thisObject;
                    if(Screen.isOwnDecor(v.getRootView())||!ChatTicks.chat(v.getRootView()))return;
                    CharSequence d=v.getContentDescription();
                    if(d==null&&v instanceof android.widget.TextView)d=((android.widget.TextView)v).getText();
                    String label=d==null?"":d.toString().trim().toLowerCase(Locale.ROOT);
                    if(label.equals("отправить")||label.equals("send")||label.equals("send message")||label.equals("отправить сообщение"))tapAt=SystemClock.elapsedRealtime();
                }
            });
        }catch(Throwable ignored){sendHooks=0;}
    }
}
