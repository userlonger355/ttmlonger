package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Runtime adaptation of the round-loader integration points documented by MargyT.
 * Original circles remain untouched; only known circle draw/clip calls are substituted. */
final class AvatarContours {
    static final String[] LABELS={"Круг","Скруглённый квадрат","Квадрат","Печенье","Клевер","Вспышка","Солнце","Пятиугольник","Шестиугольник","Сердце"};
    private static final String[] OWNERS={"X.1Fnt","X.1Fnu","X.0HEe","X.0GvU"};
    private static final WeakHashMap<Path,float[]> circles=new WeakHashMap<>();
    private static final ThreadLocal<Drawable> drawing=new ThreadLocal<>();
    private static volatile int shape;
    private static volatile boolean spin;
    private static volatile int hooks;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Drawable,Boolean> waiting=new WeakHashMap<>();
    private static boolean posted;
    static int clamp(int n){return n>=0&&n<LABELS.length?n:0;}
    static Path contour(int id,float cx,float cy,float radius,float angle){
        Path path=new Path();
        id=clamp(id);
        if(id==0){path.addCircle(cx,cy,radius,Path.Direction.CW);return path;}
        if(id==1||id==2){
            float side=radius*.90f;
            path.addRoundRect(new RectF(-side,-side,side,side),id==1?radius*.30f:0,id==1?radius*.30f:0,Path.Direction.CW);
        }else if(id==9){
            path.moveTo(0,radius*.86f);
            path.cubicTo(-radius*.18f,radius*.62f,-radius,radius*.10f,-radius,-radius*.32f);
            path.cubicTo(-radius,-radius*.94f,-radius*.34f,-radius,0,-radius*.51f);
            path.cubicTo(radius*.34f,-radius,radius,-radius*.94f,radius,-radius*.32f);
            path.cubicTo(radius,radius*.10f,radius*.18f,radius*.62f,0,radius*.86f);
            path.close();
        }else{
            int n=id==7?5:id==8?6:120;
            for(int j=0;j<n;j++){
                double t=j*2*Math.PI/n-Math.PI/2;
                double q=id==3?.91+.09*Math.cos(8*t):id==4?.81+.19*Math.cos(4*t):id==5?.82+.18*Math.cos(6*t):id==6?.88+.12*Math.cos(12*t):1;
                float x=(float)(radius*q*Math.cos(t)),y=(float)(radius*q*Math.sin(t));
                if(j==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            path.close();
        }
        Matrix matrix=new Matrix();matrix.setRotate(angle);matrix.postTranslate(cx,cy);path.transform(matrix);
        return path;
    }
    static float angle(long ms){return (ms%9000L)*360f/9000f;}
    private static boolean animates(){return spin&&shape!=0&&(android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled());}
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("ttmlonger_interface",0);}
    static void install(ClassLoader cl,Context c){
        shape=clamp(prefs(c).getInt("avatar_contour",0));spin=prefs(c).getBoolean("avatar_contour_spin",false);
        try{
            java.util.HashSet<Method> done=new java.util.HashSet<>();
            for(String name:OWNERS){
                Class<?> type=XposedHelpers.findClassIfExists(name,cl);
                if(type==null||!Drawable.class.isAssignableFrom(type))continue;
                for(Method method:type.getDeclaredMethods()){
                    Class<?>[] args=method.getParameterTypes();
                    if(!method.getName().equals("draw")||args.length!=1||args[0]!=Canvas.class||!done.add(method)
                            ||java.lang.reflect.Modifier.isAbstract(method.getModifiers()))continue;
                    XposedBridge.hookMethod(method,new XC_MethodHook(){
                        @Override protected void beforeHookedMethod(MethodHookParam p){
                            p.setObjectExtra("ttm_avatar_previous",drawing.get());drawing.set((Drawable)p.thisObject);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p){
                            Object previous=p.getObjectExtra("ttm_avatar_previous");
                            if(previous instanceof Drawable)drawing.set((Drawable)previous);else drawing.remove();
                        }
                    });hooks++;
                }
            }
            if(hooks==0)return;
            XposedBridge.hookAllMethods(Path.class,"addCircle",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(p.args.length!=4||!(p.thisObject instanceof Path)||!((Path)p.thisObject).isEmpty())return;
                    boolean known=drawing.get()!=null;
                    if(!known)for(StackTraceElement frame:Thread.currentThread().getStackTrace())
                        for(String n:OWNERS)if(n.equals(frame.getClassName()))known=true;
                    if(!known)return;
                    float r=((Number)p.args[2]).floatValue();
                    if(r>0)synchronized(circles){circles.put((Path)p.thisObject,new float[]{((Number)p.args[0]).floatValue(),((Number)p.args[1]).floatValue(),r});}
                }
            });
            XC_MethodHook reset=new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){synchronized(circles){circles.remove(p.thisObject);}}
            };
            XposedBridge.hookAllMethods(Path.class,"reset",reset);
            XposedBridge.hookAllMethods(Path.class,"rewind",reset);
            XC_MethodHook draw=new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(shape==0||CrashGuard.tripped||drawing.get()==null||p.args.length==0||!(p.args[0] instanceof Path))return;
                    try{
                        Path original=(Path)p.args[0];float[] at;
                        synchronized(circles){at=circles.get(original);}
                        if(at==null)return;
                        RectF b=new RectF();original.computeBounds(b,true);
                        if(Math.abs(b.left-(at[0]-at[2]))>1||Math.abs(b.right-(at[0]+at[2]))>1||
                           Math.abs(b.top-(at[1]-at[2]))>1||Math.abs(b.bottom-(at[1]+at[2]))>1)return;
                        p.args[0]=contour(shape,at[0],at[1],at[2],animates()?angle(SystemClock.uptimeMillis()):0);
                        if(animates())again(drawing.get());
                    }catch(Throwable ignored){}
                }
            };
            XposedBridge.hookAllMethods(Canvas.class,"drawPath",draw);
            XposedBridge.hookAllMethods(Canvas.class,"clipPath",draw);
        }catch(Throwable t){FlagStore.log(t);}
        android.util.Log.i("TTMLonger","Avatar contour draw hooks="+hooks);
    }
    private static void again(Drawable d){
        if(Looper.myLooper()!=Looper.getMainLooper()||d.getCallback()==null)return;
        waiting.put(d,true);
        if(posted)return;
        posted=true;
        MAIN.postDelayed(()->{
            posted=false;Drawable[] due=waiting.keySet().toArray(new Drawable[0]);waiting.clear();
            if(animates())for(Drawable one:due)if(one!=null&&one.isVisible()&&one.getCallback()!=null)one.invalidateSelf();
        },33);
    }
    static void editor(Activity act){
        Ui ui=new Ui(act);
        LinearLayout body=new LinearLayout(act);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(ui.dp(16),0,ui.dp(16),ui.dp(8));
        final int[] draft={shape};final boolean[] rotating={spin};
        View sample=new View(act){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c){
                float r=Math.min(getWidth(),getHeight())*.38f,cx=getWidth()/2f,cy=getHeight()/2f;
                Path mask=contour(draft[0],cx,cy,r,rotating[0]?angle(SystemClock.uptimeMillis()):0);
                int save=c.save();c.clipPath(mask);
                paint.setShader(new LinearGradient(cx-r,cy-r,cx+r,cy+r,new int[]{0xff774bd3,0xfff48ea5,0xff369dcc},null,Shader.TileMode.CLAMP));
                c.drawRect(cx-r,cy-r,cx+r,cy+r,paint);paint.setShader(null);
                paint.setColor(0xfff8e9db);c.drawCircle(cx,cy-r*.26f,r*.28f,paint);
                c.drawOval(new RectF(cx-r*.58f,cy+r*.10f,cx+r*.58f,cy+r*1.10f),paint);c.restoreToCount(save);
                paint.setColor(0xffc8baff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(2));c.drawPath(mask,paint);paint.setStyle(Paint.Style.FILL);
                if(rotating[0]&&draft[0]!=0&&isShown())postInvalidateDelayed(33);
            }
        };
        body.addView(sample,new LinearLayout.LayoutParams(-1,ui.dp(150)));
        TextView selected=ui.text(LABELS[draft[0]],16,Ui.INK,true);body.addView(selected);
        android.widget.Spinner picker=new android.widget.Spinner(act);
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(act,android.R.layout.simple_spinner_dropdown_item,LABELS);
        picker.setAdapter(adapter);picker.setSelection(draft[0]);body.addView(picker,ui.matchWrap());
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){draft[0]=position;selected.setText(LABELS[position]);sample.invalidate();}
        });
        body.addView(ui.toggleRow(-1,"Вращать контур","Фотография остаётся неподвижной",rotating[0],on->{rotating[0]=on;sample.invalidate();}));
        body.addView(ui.note(hooks>0?"Эффект применяется к распознанным круглым изображениям. При необходимости перезапустите TikTok.":"Предпросмотр доступен; обработчики изображений этой версии ещё не распознаны."));
        AlertDialog dialog=new AlertDialog.Builder(act,Ui.dialogTheme()).setTitle("Форма аватарок").setView(body)
                .setNegativeButton("Отмена",null).setPositiveButton("Применить",(d,w)->{
                    shape=clamp(draft[0]);spin=rotating[0];prefs(act).edit().putInt("avatar_contour",shape).putBoolean("avatar_contour_spin",spin).apply();
                    act.getWindow().getDecorView().invalidate();
                }).create();
        Screen.markOwn(dialog);dialog.show();Ui.style(dialog);
    }
}
