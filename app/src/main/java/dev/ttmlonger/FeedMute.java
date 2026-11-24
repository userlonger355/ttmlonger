package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Device-local author mute; does not call the account block API. */
final class FeedMute {
    private static final String KEY="muted_author_uids";
    private static volatile Set<String> ids=Collections.emptySet();
    static boolean valid(String s){return s!=null&&s.matches("[0-9]{1,24}")&&!s.matches("0+");}
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("ttmlonger_interface",0);}
    static void install(Context c,ClassLoader cl){
        Set<String> values=new LinkedHashSet<>();
        for(String id:prefs(c).getStringSet(KEY,Collections.emptySet()))if(valid(id)&&values.size()<50)values.add(id);
        ids=Collections.unmodifiableSet(values);
        Class<?> page=XposedHelpers.findClassIfExists(AdHooks.ITEM_LIST,cl);
        if(page==null)return;
        XposedBridge.hookAllMethods(page,"getItems",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                if(ids.isEmpty()||CrashGuard.tripped||p.hasThrowable()||!(p.getResult() instanceof List))return;
                // Never change cached pages in place. Limit local exclusion to the native getter.
                p.setResult(filter((List<?>)p.getResult(),ids));
            }
        });
    }
    static List<?> filter(List<?> list,Set<String> excluded){
        if(excluded.isEmpty())return list;
        ArrayList<Object> out=new ArrayList<>(list.size());
        for(Object entry:list){
            Object post=DownloadHooks.call(entry,"getAweme");if(post==null)post=entry;
            Object id=DownloadHooks.call(post,"getAuthorUid");
            if(id==null)id=DownloadHooks.call(DownloadHooks.call(post,"getAuthor"),"getUid");
            if(id==null||!excluded.contains(String.valueOf(id)))out.add(entry);
        }
        return out.size()==list.size()?list:out;
    }
    private static void save(Activity a,Set<String> next){ids=Collections.unmodifiableSet(new LinkedHashSet<>(next));prefs(a).edit().putStringSet(KEY,new LinkedHashSet<>(next)).apply();}
    static void show(Activity a){
        Ui ui=new Ui(a);android.widget.LinearLayout body=new android.widget.LinearLayout(a);body.setOrientation(1);
        EditText input=new EditText(a);input.setHint("Числовой UID автора");input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        body.addView(ui.note("До 50 авторов на этом устройстве. Фильтр влияет на новые списки публикаций; обновите ленту. Не блокирует профиль на сервере."));
        body.addView(input,ui.matchWrap());
        final String[] current=ids.toArray(new String[0]);boolean[] remove=new boolean[current.length];
        for(int n=0;n<current.length;n++){final int i=n;android.widget.CheckBox box=new android.widget.CheckBox(a);box.setText("Убрать из списка: "+current[i]);box.setTextColor(Ui.INK);box.setOnCheckedChangeListener((v,b)->remove[i]=b);body.addView(box);}
        android.widget.ScrollView scroll=new android.widget.ScrollView(a);scroll.addView(body);
        AlertDialog d=new AlertDialog.Builder(a,Ui.dialogTheme()).setTitle("Скрытые авторы · "+current.length).setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();
        Screen.markOwn(d);d.show();Ui.style(d);d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            Set<String> next=new LinkedHashSet<>(ids);for(int i=0;i<current.length;i++)if(remove[i])next.remove(current[i]);
            String uid=input.getText().toString().trim();
            if(!uid.isEmpty()&&!valid(uid)){input.setError("Нужен числовой UID");return;}
            if(!uid.isEmpty())next.add(uid);if(next.size()>50){input.setError("Максимум 50 авторов");return;}
            save(a,next);d.dismiss();Toasts.show(a,"Сохранено. Обновите ленту.");
        });
    }
}
