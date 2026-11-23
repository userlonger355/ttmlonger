package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

final class ReaderWindow {
    static boolean open(Uri url) {
        Activity a = ScreenWatcher.top();
        if (a == null || a.isFinishing() || a.isDestroyed()) return false;
        WebView reader = new WebView(a);
        android.webkit.WebSettings settings = reader.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDomStorageEnabled(false);
        settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        reader.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return !webScheme(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String link) {
                return !webScheme(Uri.parse(link));
            }
        });
        AlertDialog d = new AlertDialog.Builder(a, Ui.dialogTheme()).setView(reader)
                .setNegativeButton("Закрыть", null).create();
        Screen.markOwn(d);
        d.setOnDismissListener(ignored -> {
            reader.stopLoading();
            if (reader.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) reader.getParent()).removeView(reader);
            reader.destroy();
        });
        d.show();
        Ui.style(d);
        reader.loadUrl(url.toString());
        return true;
    }

    private static boolean webScheme(Uri uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    }
}
