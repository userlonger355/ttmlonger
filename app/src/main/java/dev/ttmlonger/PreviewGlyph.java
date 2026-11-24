package dev.ttmlonger;
import android.content.Context;
import android.graphics.*;
import android.view.View;
/** Local vector glyphs for the simulated feed; no network image or emoji font dependency. */
final class PreviewGlyph extends View {
    final int kind,ink;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    PreviewGlyph(Context c,int k,int color){super(c);kind=k;ink=color;}
    protected void onDraw(Canvas c){float s=Math.min(getWidth(),getHeight())/32f;int save=c.save();c.translate((getWidth()-32*s)/2,(getHeight()-32*s)/2);c.scale(s,s);p.setColor(ink);p.setStyle(Paint.Style.FILL);p.setStrokeWidth(2.2f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path a=new Path();
        switch(kind){
        case 0:p.setColor(0xffa786b3);c.drawCircle(16,16,14,p);p.setColor(0xfff2d8d8);c.drawCircle(16,12,5,p);c.drawOval(7,19,25,32,p);break;
        case 1:a.moveTo(16,28);a.cubicTo(12,23,2,18,2,10);a.cubicTo(2,2,12,1,16,8);a.cubicTo(20,1,30,2,30,10);a.cubicTo(30,18,20,24,16,28);c.drawPath(a,p);break;
        case 2:c.drawRoundRect(3,3,29,25,9,9,p);a.moveTo(11,23);a.lineTo(11,30);a.lineTo(20,24);a.close();c.drawPath(a,p);p.setColor(0xff333333);for(int x:new int[]{10,16,22})c.drawCircle(x,14,1.7f,p);break;
        case 3:a.moveTo(8,3);a.lineTo(24,3);a.lineTo(24,29);a.lineTo(16,23);a.lineTo(8,29);a.close();c.drawPath(a,p);break;
        case 4:a.moveTo(29,14);a.lineTo(18,3);a.lineTo(18,10);a.cubicTo(5,10,3,20,3,28);a.cubicTo(8,21,12,20,18,20);a.lineTo(18,27);a.close();c.drawPath(a,p);break;
        case 5:p.setStyle(Paint.Style.STROKE);a.moveTo(3,14);a.lineTo(16,3);a.lineTo(29,14);a.lineTo(27,14);a.lineTo(27,28);a.lineTo(5,28);a.lineTo(5,14);a.close();c.drawPath(a,p);break;
        case 6:p.setStyle(Paint.Style.STROKE);c.drawCircle(12,9,5,p);c.drawCircle(23,12,4,p);c.drawRoundRect(3,18,21,28,5,5,p);c.drawRoundRect(22,20,29,28,3,3,p);break;
        case 7:c.drawRoundRect(1,5,31,27,8,8,p);p.setColor(0xff111111);p.setStrokeWidth(3);c.drawLine(10,16,22,16,p);c.drawLine(16,10,16,22,p);break;
        case 8:p.setStyle(Paint.Style.STROKE);c.drawRoundRect(3,4,29,24,5,5,p);a.moveTo(10,24);a.lineTo(10,29);a.lineTo(17,24);c.drawPath(a,p);break;
        }c.restoreToCount(save);
    }
}
