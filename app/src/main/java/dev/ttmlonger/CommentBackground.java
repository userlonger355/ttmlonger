package dev.ttmlonger;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.io.*;
import java.util.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Local, bounded wallpaper for recognized comment sheets only. */
final class CommentBackground {
    private static File file;
    private static volatile Bitmap source;
    private static Bitmap blurred;
    private static Bitmap blurredSource;
    private static int lastBlur = -1;
    private static final Map<View, Applied> changed = new WeakHashMap<>();
    private static final class Applied {
        final Drawable original, ours;
        final Bitmap bitmap;
        Applied(Drawable original, Drawable ours, Bitmap bitmap) {
            this.original = original; this.ours = ours; this.bitmap = bitmap;
        }
    }

    static void install(Context context) {
        file = new File(context.getFilesDir(), "ttmlonger-comment-wallpaper.jpg");
        new Thread(() -> {
            synchronized (CommentBackground.class) {
                if (source == null && file.exists()) {
                    try { source = BitmapFactory.decodeFile(file.getPath()); }
                    catch (Throwable ignored) {}
                }
            }
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(CommentBackground::refresh);
        }, "ttmlonger-wallpaper-load").start();
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Dialog d = (Dialog) param.thisObject;
                    if (Screen.isOwn(d) || d.getWindow() == null) return;
                    View root = d.getWindow().getDecorView();
                    if (root.getTag(0x7ff0cb01) == null) {
                        root.setTag(0x7ff0cb01, Boolean.TRUE);
                        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> apply(root));
                    }
                    root.post(() -> apply(root));
                }
            });
        } catch (Throwable t) { FlagStore.log(t); }
    }

    static synchronized void importImage(Context c, Uri uri) throws IOException {
        if (file == null) file = new File(c.getFilesDir(), "ttmlonger-comment-wallpaper.jpg");
        Bitmap b;
        if (Build.VERSION.SDK_INT >= 28) {
            b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri),
                    (decoder, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float scale = Math.min(1f, 1440f / Math.max(w, h));
                        decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Invalid image");
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 1440) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
        }
        if (b == null) throw new IOException("Invalid image");
        AtomicFile target = new AtomicFile(file);
        FileOutputStream out = target.startWrite();
        try {
            if (!b.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("Encode failed");
            target.finishWrite(out);
        } catch (Throwable t) {
            target.failWrite(out);
            throw new IOException(t);
        }
        source = b;
    }

    static synchronized void clear(Context context) {
        if (file == null) file = new File(context.getFilesDir(), "ttmlonger-comment-wallpaper.jpg");
        new AtomicFile(file).delete();
        source = null;
        blurred = null;
        blurredSource = null;
        refresh();
    }

    static synchronized Bitmap image() {
        Bitmap b = source;
        int amount = Prefs.commentBlur;
        if (b == null || amount == 0) return b;
        if (blurredSource == b && lastBlur == amount) return blurred;
        float scale = Math.min(1f, 384f / Math.max(b.getWidth(), b.getHeight()));
        int w = Math.max(1, Math.round(b.getWidth() * scale)), h = Math.max(1, Math.round(b.getHeight() * scale));
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        int[] in = new int[w*h], temp = new int[w*h];
        small.getPixels(in, 0, w, 0, 0, w, h);
        int r = Math.max(1, amount * 24 / 100);
        for (int pass = 0; pass < 2; pass++) {
            box(in, temp, w, h, r, false);
            box(temp, in, w, h, r, true);
        }
        blurred = Bitmap.createBitmap(in, w, h, Bitmap.Config.ARGB_8888);
        blurredSource = b; lastBlur = amount;
        return blurred;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean vertical) {
        int lines = vertical ? w : h, length = vertical ? h : w;
        int stride = vertical ? w : 1, divisor = 2*r+1;
        for (int l = 0; l < lines; l++) {
            int start = vertical ? l : l*w;
            int red=0, green=0, blue=0;
            for (int k=-r; k<=r; k++) {
                int c=in[start+Math.min(length-1, Math.max(0,k))*stride];
                red+=Color.red(c); green+=Color.green(c); blue+=Color.blue(c);
            }
            for (int i=0; i<length; i++) {
                out[start+i*stride]=Color.rgb(red/divisor, green/divisor, blue/divisor);
                int a=in[start+Math.max(0,i-r)*stride], b=in[start+Math.min(length-1,i+r+1)*stride];
                red+=Color.red(b)-Color.red(a); green+=Color.green(b)-Color.green(a); blue+=Color.blue(b)-Color.blue(a);
            }
        }
    }

    static Drawable drawable(Bitmap b, boolean dark) {
        return new Drawable() {
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect dst = getBounds();
                if (dst.isEmpty()) return;
                float s=Math.max(dst.width()/(float)b.getWidth(), dst.height()/(float)b.getHeight());
                float w=b.getWidth()*s, h=b.getHeight()*s;
                RectF dest=new RectF(dst.centerX()-w/2, dst.centerY()-h/2, dst.centerX()+w/2, dst.centerY()+h/2);
                int saved=canvas.save(); canvas.clipRect(dst); canvas.drawBitmap(b,null,dest,paint);
                canvas.drawColor(dark ? 0x99000000 : 0xBBFFFFFF); canvas.restoreToCount(saved);
            }
            @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    static void refresh() {
        for (View v : new ArrayList<>(changed.keySet())) if (v != null) apply(v.getRootView());
    }

    static void apply(View root) {
        try {
            if (root == null || Screen.isOwnDecor(root)) return;
            CommentTools.apply(root);
            Bitmap b = CrashGuard.tripped ? null : image();
            if (b == null && changed.isEmpty()) return;
            View panel = b == null ? null : find(root, root, new int[]{2500}, 0);
            for (View v : new ArrayList<>(changed.keySet())) {
                if (v == null || v.getRootView() != root || v == panel) continue;
                restore(v);
            }
            if (panel == null) return;
            Applied prior = changed.get(panel);
            if (prior != null && prior.bitmap == b && panel.getBackground() == prior.ours) return;
            Drawable original = prior != null && panel.getBackground() == prior.ours ? prior.original : panel.getBackground();
            boolean dark = (panel.getResources().getConfiguration().uiMode & 0x30) == 0x20;
            if (original instanceof ColorDrawable) {
                int color = ((ColorDrawable) original).getColor();
                if (Color.alpha(color) == 255) dark = Theme.onColor(color) == Color.WHITE;
            }
            Drawable d = drawable(b, dark);
            changed.put(panel, new Applied(original,d,b));
            background(panel,d);
        } catch (Throwable ignored) {}
    }

    private static void restore(View v) {
        Applied a = changed.remove(v);
        if (a != null && v.getBackground() == a.ours) background(v, a.original);
    }
    private static void background(View v, Drawable d) {
        int l=v.getPaddingLeft(), t=v.getPaddingTop(), r=v.getPaddingRight(), b=v.getPaddingBottom();
        v.setBackground(d); v.setPadding(l,t,r,b);
    }

    private static String name(View v) {
        String n=v.getClass().getName().toLowerCase(Locale.ROOT);
        try { if(v.getId()!=View.NO_ID) n+=" "+v.getResources().getResourceEntryName(v.getId()).toLowerCase(Locale.ROOT); }
        catch (Throwable ignored) {}
        return n;
    }
    static View panel(View root) {return find(root,root,new int[]{2500},0);}
    private static View find(View v, View root, int[] budget, int depth) {
        if (--budget[0] < 0 || depth>40 || v.getVisibility()!=View.VISIBLE || !(v instanceof ViewGroup)) return null;
        String n=name(v);
        ViewGroup g=(ViewGroup)v;
        if (v!=root && n.contains("comment") && (n.contains("panel") || n.contains("sheet") ||
                n.contains("container") || n.contains("layout") || n.contains("root")) &&
                v.getWidth() >= root.getWidth()*0.75f && v.getHeight() >= root.getHeight()*0.25f &&
                hasList(g, 0, new int[]{800})) return v;
        for (int i=0;i<g.getChildCount();i++) {
            View hit=find(g.getChildAt(i),root,budget,depth+1); if(hit!=null)return hit;
        }
        // Obfuscated layouts: require a comment header plus a list inside the same lower sheet.
        if (v!=root && v.getWidth() >= root.getWidth()*0.85f && v.getHeight() >= root.getHeight()*0.28f &&
                v.getHeight() < root.getHeight()*0.97f && header(g,0,new int[]{80}) && hasList(g,0,new int[]{500})) return v;
        return null;
    }
    private static boolean header(View v,int depth,int[] budget) {
        if (--budget[0]<0 || depth>4 || v.getVisibility()!=View.VISIBLE) return false;
        if(v instanceof TextView) {
            String t=((TextView)v).getText().toString().trim().toLowerCase(Locale.ROOT);
            return t.matches("(?:comments?|комментарии|комментариев|комментарий)") ||
                    t.matches("[0-9.,\\s]+[kmкм]?\\s+(?:comments?|комментарии|комментариев|комментарий)");
        }
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) if(header(g.getChildAt(i),depth+1,budget)) return true;
        }
        return false;
    }
    private static boolean hasList(View v,int depth,int[] budget) {
        if(--budget[0]<0 || depth>10 || v.getVisibility()!=View.VISIBLE) return false;
        if (v instanceof android.widget.AbsListView || name(v).contains("recyclerview")) return true;
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) if(hasList(g.getChildAt(i),depth+1,budget))return true;
        }
        return false;
    }
}
