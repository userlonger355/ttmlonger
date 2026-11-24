package dev.ttmlonger;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared bottom sheet styling; never changes settings until its action is confirmed. */
final class StreakSheet {
    final Activity act;
    final Ui ui;
    final Dialog dialog;
    final LinearLayout root;
    StreakSheet(Activity act,String title){
        this.act=act;ui=new Ui(act);
        dialog=new Dialog(act);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);Screen.markOwn(dialog);
        root=new LinearLayout(act);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ui.corners(Ui.DARK?0xff1e1e1e:0xffffffff,22,0));
        root.setPadding(0,0,0,ui.dp(16));
        View grab=new View(act);grab.setBackground(ui.round(Ui.alpha(Ui.INK,0x44),4));
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(ui.dp(38),ui.dp(4));
        gp.gravity=Gravity.CENTER_HORIZONTAL;gp.topMargin=ui.dp(8);root.addView(grab,gp);
        FrameLayout header=new FrameLayout(act);
        TextView heading=ui.text(title,19,Ui.INK,true);heading.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,ui.dp(58));hp.setMargins(ui.dp(48),0,ui.dp(48),0);header.addView(heading,hp);
        FrameLayout x=new FrameLayout(act);x.setBackground(ui.ripple());x.setContentDescription("Закрыть");
        x.addView(ui.icon(Icons.CLOSE,Ui.MUTED,22),new FrameLayout.LayoutParams(ui.dp(22),ui.dp(22),Gravity.CENTER));
        x.setOnClickListener(v->dialog.dismiss());
        header.addView(x,new FrameLayout.LayoutParams(ui.dp(48),ui.dp(58),Gravity.END));
        root.addView(header,new LinearLayout.LayoutParams(-1,ui.dp(58)));
    }
    TextView note(String text){
        TextView t=ui.text(text,14,Ui.MUTED,true);t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(18),ui.dp(6),ui.dp(18),ui.dp(12));return t;
    }
    TextView button(String text,boolean primary,Runnable onClick){
        TextView t=primary?TtStyle.accent(act,text,50,17):TtStyle.neutral(act,text,44,16);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,ui.dp(primary?50:44));
        lp.setMargins(ui.dp(18),ui.dp(8),ui.dp(18),0);root.addView(t,lp);
        t.setOnClickListener(v->onClick.run());return t;
    }
    void show(){
        dialog.setContentView(root);
        Window w=dialog.getWindow();
        if(w!=null){
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.60f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        boolean animate=android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled();
        if(animate){if(w!=null)w.setWindowAnimations(0);root.setAlpha(0f);root.setTranslationY(ui.dp(40));}
        dialog.show();if(w!=null)w.setLayout(-1,-2);
        if(animate)root.animate().translationY(0f).alpha(1f).setDuration(260)
            .setInterpolator(new android.view.animation.PathInterpolator(.18f,.82f,.22f,1f)).start();
    }
}
