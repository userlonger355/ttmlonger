package dev.ttmlonger;
import java.util.Set;
/** A confirmed author prefix, not generic colon splitting. Return the original model text unchanged. */
final class CommentCopyText {
    static boolean matches(String raw,String body,Set<String> names){
        if(raw==null||body==null||body.isEmpty()||raw.equals(body)||names==null)return false;
        String normalized=Targets.norm(raw);if(normalized.equals(Targets.norm(body)))return false;
        for(String name:names){
            if(name==null||name.trim().isEmpty()||name.equals("@"))continue;
            for(String sep:new String[]{": ",":","：","： ","\n","\n\n"," · "," — "," - "," "})
                if(normalized.equals(Targets.norm(name+sep+body)))return true;
        }
        return false;
    }
}
