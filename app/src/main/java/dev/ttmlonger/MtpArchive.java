package dev.ttmlonger;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import org.json.*;
/** Validates an untrusted flat archive before any class loading. */
final class MtpArchive {
    static final int DEX_MAX=16*1024*1024, MANIFEST_MAX=65536, ICON_MAX=1024*1024;
    final JSONObject manifest;final byte[] dex,icon;final String digest,id;
    private MtpArchive(JSONObject m,byte[] d,byte[] i)throws Exception{
        manifest=m;dex=d;icon=i;id=m.getString("id");
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        sha.update(m.toString().getBytes("UTF-8"));sha.update((byte)0);sha.update(d);if(i!=null)sha.update(i);
        StringBuilder b=new StringBuilder();for(byte x:sha.digest())b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));digest=b.toString();
    }
    static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")&&!id.contains("..");}
    static MtpArchive read(InputStream raw)throws Exception{
        if(raw==null)throw new IOException("Нет файла");
        Map<String,byte[]> parts=new HashMap<>();
        try(ZipInputStream z=new ZipInputStream(raw)){
            ZipEntry e;byte[] buf=new byte[8192];
            while((e=z.getNextEntry())!=null){
                String n=e.getName();int limit=n.equals("manifest.json")?MANIFEST_MAX:n.equals("classes.dex")?DEX_MAX:n.equals("icon.png")?ICON_MAX:0;
                if(limit==0||e.isDirectory()||parts.containsKey(n))throw new IOException("Недопустимый ZIP");
                ByteArrayOutputStream b=new ByteArrayOutputStream();int k;while((k=z.read(buf))!=-1){if(b.size()+k>limit)throw new IOException("Файл слишком большой");b.write(buf,0,k);}
                parts.put(n,b.toByteArray());z.closeEntry();
            }
        }
        byte[] m=parts.get("manifest.json"),d=parts.get("classes.dex");
        if(m==null||d==null||d.length<112||d[0]!='d'||d[1]!='e'||d[2]!='x'||d[3]!='\n')throw new IOException("Нужны manifest.json и classes.dex");
        JSONObject j=new JSONObject(new String(m,"UTF-8"));
        if(!validId(j.optString("id"))||!j.optString("entry").matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))throw new IOException("Некорректный id или entry");
        if(j.optString("entry").startsWith("cat.narezany.margyt.plugin.")||j.optString("entry").startsWith("dev.ttmlonger."))throw new IOException("Недопустимая точка входа");
        for(String key:new String[]{"name","author","version"})if(j.optString(key).trim().isEmpty()||j.optString(key).length()>160)throw new IOException("Нет "+key);
        if(j.optInt("min_api",1)<1||j.optInt("min_api",1)>5)throw new IOException("Требуется другой API");
        return new MtpArchive(j,d,parts.get("icon.png"));
    }
    void save(File file)throws Exception{
        File tmp=new File(file.getParentFile(),file.getName()+".new");
        try{
            try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(tmp))){
                put(z,"manifest.json",manifest.toString().getBytes("UTF-8"));put(z,"classes.dex",dex);if(icon!=null)put(z,"icon.png",icon);
            }
            if(!tmp.renameTo(file))throw new IOException("Не удалось сохранить");
        }finally{tmp.delete();}
    }
    private static void put(ZipOutputStream z,String n,byte[] b)throws IOException{z.putNextEntry(new ZipEntry(n));z.write(b);z.closeEntry();}
}
