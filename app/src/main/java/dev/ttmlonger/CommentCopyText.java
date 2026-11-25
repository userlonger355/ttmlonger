package dev.ttmlonger;
import java.util.Set;
/** A confirmed author prefix, not generic colon splitting. Return the original model text unchanged. */
final class CommentCopyText {
    // Only used after the stock Copy action with a confirmed model, not on arbitrary clipboard data.
    static boolean verifiedSuffix(String raw,String body){
        if(raw==null||body==null||body.isEmpty())return false;
        String a=Targets.norm(raw),b=Targets.norm(body);if(b.isEmpty()||!a.endsWith(b)||a.equals(b))return false;
        String prefix=a.substring(0,a.length()-b.length()).trim();
        return prefix.length()>1&&prefix.length()<=160&&(prefix.endsWith(":")||prefix.endsWith("：")||prefix.endsWith("·")||prefix.endsWith("—"));
    }
    static boolean matches(String raw,String body,Set<String> names){
        if(raw==null||body==null||body.isEmpty()||raw.equals(body)||names==null)return false;
        String normalized=Targets.norm(raw);if(normalized.equals(Targets.norm(body)))return false;
        for(String name:names){
            if(name==null||name.trim().isEmpty()||name.equals("@"))continue;
            for(String sep:new String[]{": ",":","：","： ","\n","\n\n"," · "," — "," - "," ","\t","\n: ","：\n"," : ","\n\n: ","： "," • "," – "})
                if(normalized.equals(Targets.norm(name+sep+body)))return true;
        }
        return false;
    }
}
