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
    static final String[] LABELS={"Круг","Скруглённый","Скверкл","Печенье","Клевер","Вспышка","Волна","Ромб","Шестигранник","Сердечко","Цветок","Арка"};
    private static final String[] OWNERS={"X.1Fnt","X.1Fnu","X.0HEe","X.0GvU"};
    private static final WeakHashMap<Path,float[]> circles=new WeakHashMap<>();
    private static final java.util.Set<String> BUILDERS=new java.util.HashSet<>(java.util.Arrays.asList("X.1Fnt","X.0HEe","X.0GvU"));
    private static final java.util.Set<String> DRAWERS=new java.util.HashSet<>(java.util.Arrays.asList("X.1Fnu","X.0HEe","X.0GvU"));
    private static final ThreadLocal<Integer> building=new ThreadLocal<>();
    private static volatile int builderHooks;
    private static volatile int matchedBuilders;
    private static final java.util.concurrent.atomic.AtomicInteger substitutions=new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<Drawable> drawing=new ThreadLocal<>();
    private static final ThreadLocal<java.util.ArrayList<Drawable>> stack=new ThreadLocal<>();
    private static volatile int shape;
    private static volatile boolean spin;
    private static volatile int spinSeconds=9;
    private static volatile long spinStart=SystemClock.uptimeMillis();
    private static volatile int hooks;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Drawable,Boolean> waiting=new WeakHashMap<>();
    private static boolean posted;
    static int clamp(int n){return n>=0&&n<LABELS.length?n:0;}
    static Path contour(int id,float cx,float cy,float radius,float angle){
        Path path=new Path();
        id=clamp(id);
        if(id==0){path.addCircle(cx,cy,radius,Path.Direction.CW);return path;}
        if(id==1){
            float side=radius*.96f;
            path.addRoundRect(new RectF(-side,-side,side,side),radius*.35f,radius*.35f,Path.Direction.CW);
        }else if(id==2){
            // Superellipse, not a hard square.
            for(int i=0;i<128;i++){
                double t=i*2*Math.PI/128;
                float x=(float)(radius*.94*Math.copySign(Math.sqrt(Math.abs(Math.cos(t))),Math.cos(t)));
                float y=(float)(radius*.94*Math.copySign(Math.sqrt(Math.abs(Math.sin(t))),Math.sin(t)));
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
            }path.close();
        }else if(id==9){
            path.moveTo(0,radius*.86f);
            path.cubicTo(-radius*.18f,radius*.62f,-radius,radius*.10f,-radius,-radius*.32f);
            path.cubicTo(-radius,-radius*.94f,-radius*.34f,-radius,0,-radius*.51f);
            path.cubicTo(radius*.34f,-radius,radius,-radius*.94f,radius,-radius*.32f);
            path.cubicTo(radius,radius*.10f,radius*.18f,radius*.62f,0,radius*.86f);
            path.close();
        }else if(id==11){
            float r=radius*.94f;
            path.addRoundRect(new RectF(-r,-r,r,r),new float[]{r,r,r,r,r*.16f,r*.16f,r*.16f,r*.16f},Path.Direction.CW);
        }else{
            int n=id==7?4:id==8?6:144;
            for(int j=0;j<n;j++){
                double t=j*2*Math.PI/n-Math.PI/2;
                double q=id==3?.91+.09*Math.cos(8*t):id==4?.80+.20*Math.cos(4*t):id==5?.82+.18*Math.cos(8*t):id==6?.90+.10*Math.cos(12*t):id==10?.82+.18*Math.cos(6*t):1;
                float x=(float)(radius*q*Math.cos(t)),y=(float)(radius*q*Math.sin(t));
                if(j==0)path.moveTo(x,y);else path.lineTo(x,y);
            }path.close();
        }
        Matrix matrix=new Matrix();matrix.setRotate(angle);matrix.postTranslate(cx,cy);path.transform(matrix);
        return path;
    }
    static void addMask(Path destination,int selected,float x,float y,float r){
        if(clamp(selected)==0){destination.addCircle(x,y,r,Path.Direction.CW);return;}
        destination.addPath(contour(selected,x,y,r,0));
    }
    static float angle(long ms){long period=1000L*spinSeconds;return (Math.max(0,ms-spinStart)%period)*360f/period;}
    static boolean rotating(){return spin;}
    static void setRotation(Context ctx,boolean enabled){spin=enabled;spinStart=SystemClock.uptimeMillis();prefs(ctx).edit().putBoolean("avatar_contour_spin",enabled).apply();refreshVisible();}
    static void setSpeed(Context ctx,int seconds){spinSeconds=Math.max(3,Math.min(20,seconds));spinStart=SystemClock.uptimeMillis();prefs(ctx).edit().putInt("avatar_contour_seconds",spinSeconds).apply();refreshVisible();}
    private static void refreshVisible(){for(View v:visibleAvatars.keySet().toArray(new View[0]))if(v!=null)v.invalidate();for(Drawable d:knownDrawables.keySet().toArray(new Drawable[0]))if(d!=null)d.invalidateSelf();}
    private static final WeakHashMap<Drawable,Boolean> knownDrawables=new WeakHashMap<>();
    private static boolean animates(){return spin&&shape!=0&&(android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled());}
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("ttmlonger_interface",0);}
    static void install(ClassLoader cl,Context c){
        shape=clamp(prefs(c).getInt("avatar_contour",0));spin=prefs(c).getBoolean("avatar_contour_spin",false);spinSeconds=Math.max(3,Math.min(20,prefs(c).getInt("avatar_contour_seconds",9)));
        installAvatarViews();
        try{
            java.util.HashSet<Method> done=new java.util.HashSet<>();
            java.util.HashSet<Method> builders=new java.util.HashSet<>();
            for(String name:OWNERS){
                Class<?> type=XposedHelpers.findClassIfExists(name,cl);
                if(type==null){android.util.Log.i("TTMLonger","Avatar anchor absent "+name);continue;}
                android.util.Log.i("TTMLonger","Avatar anchor present "+name);
                if(BUILDERS.contains(name)){
                    matchedBuilders++;
                    for(Method method:type.getDeclaredMethods()){
                        if(java.lang.reflect.Modifier.isAbstract(method.getModifiers())||java.lang.reflect.Modifier.isNative(method.getModifiers())||!builders.add(method))continue;
                        try{XposedBridge.hookMethod(method,new XC_MethodHook(){
                            protected void beforeHookedMethod(MethodHookParam p){Integer d=building.get();building.set(d==null?1:d+1);}
                            protected void afterHookedMethod(MethodHookParam p){Integer d=building.get();if(d==null||d<=1)building.remove();else building.set(d-1);}
                        });builderHooks++;}catch(Throwable ignored){}
                    }
                }
                if(!Drawable.class.isAssignableFrom(type))continue;
                for(Method method:type.getDeclaredMethods()){
                    Class<?>[] args=method.getParameterTypes();
                    if(!method.getName().equals("draw")||args.length!=1||args[0]!=Canvas.class||!done.add(method)
                            ||java.lang.reflect.Modifier.isAbstract(method.getModifiers()))continue;
                    XposedBridge.hookMethod(method,new XC_MethodHook(){
                        @Override protected void beforeHookedMethod(MethodHookParam p){
                            java.util.ArrayList<Drawable> history=stack.get();
                            if(history==null){history=new java.util.ArrayList<>();stack.set(history);}
                            history.add(drawing.get());drawing.set((Drawable)p.thisObject);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p){
                            java.util.ArrayList<Drawable> history=stack.get();
                            Drawable previous=history==null||history.isEmpty()?null:history.remove(history.size()-1);
                            if(previous!=null)drawing.set(previous);else drawing.remove();
                            if(history!=null&&history.isEmpty())stack.remove();
                        }
                    });hooks++;
                }
            }
            android.util.Log.i("TTMLonger","Avatar builder anchors="+matchedBuilders+" methods="+builderHooks+" draw="+hooks);
            if(matchedBuilders==0)return;
            XposedBridge.hookAllMethods(Path.class,"addCircle",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(CrashGuard.tripped||p.args.length!=4||!(p.thisObject instanceof Path))return;
                    boolean known=building.get()!=null;
                    if(!known)for(StackTraceElement frame:Thread.currentThread().getStackTrace()){
                        if(BUILDERS.contains(frame.getClassName())){known=true;break;}
                    }
                    if(!known)return;
                    try{
                        Path path=(Path)p.thisObject;
                        float x=((Number)p.args[0]).floatValue(),y=((Number)p.args[1]).floatValue(),r=((Number)p.args[2]).floatValue();
                        if(!(r>0)||!Float.isFinite(r)||!Float.isFinite(x)||!Float.isFinite(y))return;
                        boolean empty=path.isEmpty();
                        if(empty)synchronized(circles){circles.put(path,new float[]{x,y,r,shape});}
                        if(shape==0)return;
                        addMask(path,shape,x,y,r);
                        p.setResult(null);
                        if(substitutions.getAndIncrement()==0)android.util.Log.i("TTMLonger","AVATAR_NATIVE_MASK_REPLACED");
                    }catch(Throwable ignored){}
                }
            });
            XC_MethodHook reset=new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){synchronized(circles){circles.remove(p.thisObject);}}
            };
            XposedBridge.hookAllMethods(Path.class,"reset",reset);
            XposedBridge.hookAllMethods(Path.class,"rewind",reset);
            XC_MethodHook draw=new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(CrashGuard.tripped||drawing.get()==null||p.args.length==0||!(p.args[0] instanceof Path))return;
                    try{
                        Path original=(Path)p.args[0];float[] at;
                        synchronized(circles){at=circles.get(original);}
                        if(at==null)return;
                        // Static shape was already inserted at construction. Rotate only a pure mask
                        // with the same bounds; preserve composite border paths.
                        if(Looper.myLooper()==Looper.getMainLooper())knownDrawables.put(drawing.get(),true);
                        if(!animates()&&(int)at[3]==shape)return;
                        Path unturned=contour((int)at[3],at[0],at[1],at[2],0);
                        RectF originalBounds=new RectF(),maskBounds=new RectF();
                        original.computeBounds(originalBounds,true);unturned.computeBounds(maskBounds,true);
                        if(Math.abs(originalBounds.left-maskBounds.left)>1||Math.abs(originalBounds.right-maskBounds.right)>1||
                            Math.abs(originalBounds.top-maskBounds.top)>1||Math.abs(originalBounds.bottom-maskBounds.bottom)>1)return;
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
    // Additional rendering route for explicitly named avatar image views.
    // Never match generic square pictures or arbitrary profile illustrations.
    private static final WeakHashMap<View,Boolean> avatarViews=new WeakHashMap<>();
    private static final WeakHashMap<View,Boolean> visibleAvatars=new WeakHashMap<>();
    private static boolean avatarView(View v){
        Boolean hit=avatarViews.get(v);if(hit!=null)return hit;
        boolean yes=false;
        try{
            String name=v.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            if(name.contains("avatar")&&!name.contains("enlarge")&&!name.contains("editor"))yes=true;
            if(!yes&&v.getId()!=View.NO_ID){
                String id=v.getResources().getResourceEntryName(v.getId()).toLowerCase(java.util.Locale.ROOT);
                yes=(id.contains("avatar")||id.contains("head_image")||id.contains("user_head"))&&!id.contains("border")&&!id.contains("ring")&&!id.contains("edit")&&!id.contains("badge");
            }
        }catch(Throwable ignored){}
        avatarViews.put(v,yes);return yes;
    }
    private static void installAvatarViews(){
        try{
            XposedHelpers.findAndHookMethod(View.class,"draw",Canvas.class,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(CrashGuard.tripped||!(p.thisObject instanceof android.widget.ImageView))return;
                    View v=(View)p.thisObject;
                    if(!avatarView(v)||v.getWidth()<12||v.getHeight()<12||Math.abs(v.getWidth()-v.getHeight())>Math.min(v.getWidth(),v.getHeight())*.15f)return;
                    if(Screen.isOwnDecor(v.getRootView()))return;
                    if(Looper.myLooper()==Looper.getMainLooper())visibleAvatars.put(v,true);
                    if(shape==0)return;
                    Canvas canvas=(Canvas)p.args[0];
                    if(canvas==null)return;
                    float cx=v.getWidth()/2f,cy=v.getHeight()/2f,r=Math.min(cx,cy);
                    // Round native masks remain handled by the original builder hooks.
                    // Clip this rendering route to the same geometric contour, leaving the photo stationary.
                    int save=canvas.save();p.setObjectExtra("ttm_avatar_save",save);
                    canvas.clipPath(contour(shape,cx,cy,r,animates()?angle(SystemClock.uptimeMillis()):0));
                    if(Looper.myLooper()==Looper.getMainLooper())visibleAvatars.put(v,true);
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Object save=p.getObjectExtra("ttm_avatar_save");
                    if(save instanceof Integer){try{((Canvas)p.args[0]).restoreToCount((Integer)save);}catch(Throwable ignored){}
                        if(animates())((View)p.thisObject).postInvalidateDelayed(33);}
                }
            });
        }catch(Throwable t){FlagStore.log(t);}
    }
    private static final int[][] GROUPS={{0,1,2},{3,4,10,5,6},{7,8,9,11}};
    private static final String[] GROUP_NAMES={"Классические","Material You","Другие"};
    static void editor(Activity act){
        StreakSheet sheet=new StreakSheet(act,"Форма аватарок");Ui ui=sheet.ui;
        android.widget.ScrollView scroll=new android.widget.ScrollView(act);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body=new LinearLayout(act);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(ui.dp(12),0,ui.dp(12),ui.dp(8));scroll.addView(body);
        final boolean[] rotating={spin};
        final java.util.ArrayList<MaskSample> samples=new java.util.ArrayList<>();
        final java.util.ArrayList<TextView> labels=new java.util.ArrayList<>();
        for(int group=0;group<GROUPS.length;group++){
            TextView title=ui.text(GROUP_NAMES[group],14,Ui.MUTED,false);title.setPadding(ui.dp(4),ui.dp(12),0,ui.dp(8));body.addView(title);
            LinearLayout row=new LinearLayout(act);row.setGravity(android.view.Gravity.CENTER);
            for(int id:GROUPS[group]){
                LinearLayout cell=new LinearLayout(act);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                MaskSample sample=new MaskSample(act,id,rotating);
                samples.add(sample);
                cell.addView(sample,new LinearLayout.LayoutParams(-1,ui.dp(57)));
                TextView label=ui.text(LABELS[id],12,shape==id?Theme.accent():Ui.MUTED,shape==id);label.setGravity(android.view.Gravity.CENTER);label.setMaxLines(2);
                labels.add(label);cell.addView(label,new LinearLayout.LayoutParams(-1,ui.dp(33)));
                cell.setContentDescription(LABELS[id]);cell.setOnClickListener(v->{
                    shape=id;spin=rotating[0];prefs(act).edit().putInt("avatar_contour",shape).putBoolean("avatar_contour_spin",spin).apply();
                    for(MaskSample item:samples)item.invalidate();
                    refreshVisible();
                    sheet.dialog.dismiss();Restart.offer(act);
                });
                row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
            }
            body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        body.addView(ui.toggleRow(-1,"Вращать контур","Фотография остаётся неподвижной",rotating[0],on->{rotating[0]=on;setRotation(act,on);for(MaskSample sample:samples)sample.invalidate();}));
        TextView speed=ui.text("Один оборот: "+spinSeconds+" с",14,Ui.INK,true);speed.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),0);body.addView(speed);
        android.widget.SeekBar slider=new android.widget.SeekBar(act);slider.setMax(17);slider.setProgress(spinSeconds-3);slider.setContentDescription("Скорость вращения");body.addView(slider,ui.matchWrap());
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(android.widget.SeekBar bar){}public void onStopTrackingTouch(android.widget.SeekBar bar){setSpeed(act,bar.getProgress()+3);}public void onProgressChanged(android.widget.SeekBar bar,int value,boolean user){speed.setText("Один оборот: "+(value+3)+" с");if(user){spinSeconds=value+3;for(MaskSample sample:samples)sample.invalidate();}}});
        sheet.root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(ui.dp(475),(int)(act.getResources().getDisplayMetrics().heightPixels*.65f))));
        sheet.button("Отмена",false,()->sheet.dialog.dismiss());sheet.show();
    }
    private static final class MaskSample extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int id;private final boolean[] rotation;private final Ui ui;
        MaskSample(Activity act,int id,boolean[] rotation){super(act);this.id=id;this.rotation=rotation;ui=new Ui(act);}
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)*.84f;
            boolean move=rotation[0]&&id!=0&&(android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled());
            Path mask=contour(id,cx,cy,r,move?angle(SystemClock.uptimeMillis()):0);
            int saved=canvas.save();canvas.clipPath(mask);
            paint.setShader(new LinearGradient(cx-r,cy-r,cx+r,cy+r,new int[]{0xff6b427c,0xfff48ea5,0xffb05db5},null,Shader.TileMode.CLAMP));
            canvas.drawRect(cx-r,cy-r,cx+r,cy+r,paint);paint.setShader(null);
            paint.setColor(0xfff7dbe6);canvas.drawCircle(cx,cy-r*.22f,r*.30f,paint);
            canvas.drawOval(new RectF(cx-r*.62f,cy+r*.12f,cx+r*.62f,cy+r*1.05f),paint);canvas.restoreToCount(saved);
            if(shape==id){paint.setColor(Theme.accent());paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(2));canvas.drawPath(mask,paint);paint.setStyle(Paint.Style.FILL);}
            if(move&&isShown())postInvalidateDelayed(33);
        }
    }
}
