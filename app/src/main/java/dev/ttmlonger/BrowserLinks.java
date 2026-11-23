package dev.ttmlonger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class BrowserLinks {
    private static final Set<Method> installed = new HashSet<>();

    static void install() {
        XposedBridge.hookAllMethods(WebView.class, "setWebViewClient", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.args[0] instanceof WebViewClient)) return;
                if (param.args[0].getClass().getName().startsWith(ReaderWindow.class.getName() + "$")) return;
                Method method = param.args[0].getClass().getMethod("shouldOverrideUrlLoading",
                        WebView.class, WebResourceRequest.class);
                synchronized (installed) {
                    if (installed.contains(method)) return;
                    XposedBridge.hookMethod(method, OPEN);
                    installed.add(method);
                }
            }
        });
    }

    private static final XC_MethodHook OPEN = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            boolean external = InterfaceHooks.on("browser");
            boolean reader = InterfaceHooks.on("reader");
            if ((!external && !reader) || CrashGuard.tripped) return;
            if (p.thisObject.getClass().getName().startsWith(ReaderWindow.class.getName() + "$")) return;
            WebResourceRequest request = (WebResourceRequest) p.args[1];
            if (request == null || !request.isForMainFrame() || !request.hasGesture()
                    || !"GET".equalsIgnoreCase(request.getMethod())) return;
            Uri url = request.getUrl();
            String scheme = url.getScheme();
            String host = url.getHost();
            if (host == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) return;
            host = host.toLowerCase(Locale.ROOT);
            for (String internal : new String[]{"tiktok.com", "tiktokv.com", "bytedance.com", "tiktokshop.com"}) {
                if (host.equals(internal) || host.endsWith("." + internal)) return;
            }
            if (!external && reader && ReaderWindow.open(url)) {
                p.setResult(true);
                return;
            }
            WebView web = (WebView) p.args[0];
            Context ctx = web.getContext();
            Intent browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
            android.content.ComponentName component = browser.resolveActivity(ctx.getPackageManager());
            if (component == null || "android".equals(component.getPackageName())
                    || ctx.getPackageName().equals(component.getPackageName())) return;
            Intent view = new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(component.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(view);
                p.setResult(true);
            } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                // System browser unavailable: TikTok can handle the original navigation.
            }
        }
    };
}
