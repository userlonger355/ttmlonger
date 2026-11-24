package dev.ttmlonger;
import java.util.*;
import de.robv.android.xposed.*;
/** Only explicitly profile-named list models; never alter general recommendation pages. */
final class ProfileGrid {
    static final String[] MODES={"Как в TikTok","Сначала новые","Сначала старые","Сначала длинные","Сначала короткие","По просмотрам","Меньше всего просмотров","По лайкам","Меньше всего лайков"};
    static int hooks;
    static int mode(){return InterfaceHooks.prefs==null?0:Math.max(0,Math.min(MODES.length-1,InterfaceHooks.prefs.getInt("profile_sort",0)));}
    static void set(int m){if(InterfaceHooks.prefs!=null)InterfaceHooks.prefs.edit().putInt("profile_sort",m).apply();}
    static void install(ClassLoader cl){
        for(String name:new String[]{"com.ss.android.ugc.aweme.profile.model.AwemeList","com.ss.android.ugc.aweme.profile.model.ProfileAwemeList","com.ss.android.ugc.aweme.profile.model.UserAwemeList","com.ss.android.ugc.aweme.profile.model.ProfilePostList"}){
            try{
                Class<?> c=Class.forName(name,false,cl);
                for(java.lang.reflect.Method m:c.getDeclaredMethods()){
                    if(m.getParameterTypes().length!=0||!List.class.isAssignableFrom(m.getReturnType())||!(m.getName().equals("getItems")||m.getName().equals("getAwemeList")))continue;
                    if(java.lang.reflect.Modifier.isStatic(m.getModifiers()))continue;
                    XposedBridge.hookMethod(m,new XC_MethodHook(){
                        @Override protected void afterHookedMethod(MethodHookParam p){
                            if(CrashGuard.tripped||mode()==0||p.hasThrowable()||!(p.getResult() instanceof List))return;
                            List<?> src=(List<?>)p.getResult();
                            List<?> sorted=sort(src,mode());if(sorted!=src && m.getReturnType().isInstance(sorted))p.setResult(sorted);
                        }
                    });hooks++;
                }
            }catch(Throwable ignored){}
        }
    }
    private static boolean pinned(Object a){
        for(String g:new String[]{"isTop","getIsTop","isPinned","getIsPinned"}){Object v=DownloadHooks.call(a,g);if(Boolean.TRUE.equals(v)||v instanceof Number&&((Number)v).intValue()==1)return true;}
        return false;
    }
    private static Long metric(Object a,int mode){
        Object v;
        if(mode<=2)v=DownloadHooks.call(a,"getCreateTime");
        else if(mode<=4){Object video=DownloadHooks.call(a,"getVideo");v=DownloadHooks.call(video,"getDuration");}
        else {Object s=DownloadHooks.call(a,"getStatistics");v=DownloadHooks.call(s,mode<=6?"getPlayCount":"getDiggCount");}
        return v instanceof Number?((Number)v).longValue():null;
    }
    static List<?> sort(List<?> src,int mode){
        if(mode==0||src.size()<2||src.size()>300)return src;
        try{
            List<Object> pinned=new ArrayList<>(),rest=new ArrayList<>();
            Map<Object,Long> values=new IdentityHashMap<>();
            for(Object a:src){
                if(a==null||!a.getClass().getName().equals(DownloadHooks.AWEME))return src;
                if(pinned(a)){pinned.add(a);continue;}
                Long v=metric(a,mode);if(v==null)return src;values.put(a,v);rest.add(a);
            }
            rest.sort((a,b)->mode%2==1?Long.compare(values.get(b),values.get(a)):Long.compare(values.get(a),values.get(b)));
            pinned.addAll(rest);return pinned;
        }catch(Throwable t){return src;}
    }
}
