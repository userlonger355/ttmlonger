package dev.ttmlonger;
import android.content.*;
import java.util.*;
import org.json.*;
final class SearchHistory {
    private final SharedPreferences data;
    SearchHistory(Context c){data=c.getSharedPreferences("ttmlonger_search_history",0);}
    List<String> list(){ArrayList<String> out=new ArrayList<>();try{JSONArray a=new JSONArray(data.getString("recent","[]"));for(int i=0;i<Math.min(a.length(),15);i++){String s=a.optString(i,"");if(!SettingsSearch.normalize(s).isEmpty())out.add(s);}}catch(Exception ignored){}return out;}
    void add(String query){String s=query==null?"":query.trim();if(s.length()>160)s=s.substring(0,160);String key=SettingsSearch.normalize(s);if(key.isEmpty())return;List<String> out=list();out.removeIf(v->SettingsSearch.normalize(v).equals(key));out.add(0,s);save(out);}
    void remove(String s){List<String> out=list();out.removeIf(v->v.equals(s));save(out);}
    void clear(){data.edit().remove("recent").apply();}
    private void save(List<String> list){JSONArray a=new JSONArray();for(int i=0;i<Math.min(15,list.size());i++)a.put(list.get(i));data.edit().putString("recent",a.toString()).apply();}
}
