package dev.ttmlonger;

import android.graphics.Path;
import java.util.Locale;

/** Immutable radial polygon. Rounded corners are bounded by their neighbouring edges. */
final class AvatarShape {
    final int count,rounding;
    private final float[] radii,offsets;
    AvatarShape(float[] r,int smooth){this(r,new float[r.length],smooth);}
    private AvatarShape(float[] r,float[] angles,int smooth){
        count=Math.max(3,Math.min(16,r.length));radii=new float[count];offsets=new float[count];
        for(int i=0;i<count;i++)offsets[i]=i<angles.length&&Float.isFinite(angles[i])?Math.max(-.45f,Math.min(.45f,angles[i])):0;
        for(int i=0;i<count;i++)radii[i]=Float.isFinite(r[i])?Math.max(.32f,Math.min(1f,r[i])):1f;
        rounding=Math.max(0,Math.min(100,smooth));
    }
    static AvatarShape initial(){return preset(8,28,70);}
    static AvatarShape preset(int count,int depth,int round){
        count=Math.max(3,Math.min(16,count));float[] r=new float[count];
        for(int i=0;i<count;i++)r[i]=i%2==0?1f:1f-Math.max(0,Math.min(65,depth))/100f;
        return new AvatarShape(r,round);
    }
    float radius(int i){return radii[i];}
    AvatarShape withRadius(int i,float radius){float[] r=radii.clone();r[i]=radius;return new AvatarShape(r,offsets,rounding);}
    AvatarShape withPoint(int i,float x,float y){float[] r=radii.clone(),a=offsets.clone();r[i]=(float)Math.hypot(x,y);double base=2*Math.PI*i/count-Math.PI/2,delta=Math.atan2(y,x)-base;delta=Math.atan2(Math.sin(delta),Math.cos(delta));a[i]=(float)(delta/(2*Math.PI/count));return new AvatarShape(r,a,rounding);}
    AvatarShape withRounding(int v){return new AvatarShape(radii,offsets,v);}
    AvatarShape resized(int n){
        n=Math.max(3,Math.min(16,n));float[] r=new float[n];
        for(int i=0;i<n;i++){float k=(float)i*count/n;int base=(int)k;float t=k-base;r[i]=radii[base%count]*(1-t)+radii[(base+1)%count]*t;}
        return new AvatarShape(r,rounding);
    }
    String encode(){StringBuilder b=new StringBuilder().append(rounding);for(int i=0;i<count;i++)b.append(',').append(Math.round(radii[i]*1000)).append(':').append(Math.round(offsets[i]*1000));return b.toString();}
    static AvatarShape decode(String value){
        if(value==null)return initial();
        try{String[] x=value.split(",");if(x.length<4||x.length>17)return initial();float[] r=new float[x.length-1],a=new float[r.length];for(int i=0;i<r.length;i++){String[] q=x[i+1].split(":");r[i]=Integer.parseInt(q[0])/1000f;if(q.length>1)a[i]=Integer.parseInt(q[1])/1000f;}return new AvatarShape(r,a,Integer.parseInt(x[0]));}catch(RuntimeException e){return initial();}
    }
    float x(int i,float radius,float angle){return (float)Math.cos(Math.PI*2*(i+offsets[i])/count-Math.PI/2+Math.toRadians(angle))*radius*radii[i];}
    float y(int i,float radius,float angle){return (float)Math.sin(Math.PI*2*(i+offsets[i])/count-Math.PI/2+Math.toRadians(angle))*radius*radii[i];}
    Path path(float cx,float cy,float r,float angle){
        Path p=new Path();float f=rounding*.0048f;
        for(int i=0;i<count;i++){
            int prev=(i+count-1)%count,next=(i+1)%count;
            float x=cx+x(i,r,angle),y=cy+y(i,r,angle);
            float inX=x+(cx+x(prev,r,angle)-x)*f,inY=y+(cy+y(prev,r,angle)-y)*f;
            float outX=x+(cx+x(next,r,angle)-x)*f,outY=y+(cy+y(next,r,angle)-y)*f;
            if(i==0)p.moveTo(inX,inY);else p.lineTo(inX,inY);
            p.quadTo(x,y,outX,outY);
        }p.close();return p;
    }
}
