package com.businspector.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class PortalWebViewClient extends WebViewClient {

    private final MainActivity activity;

    public PortalWebViewClient(MainActivity activity) {
        this.activity = activity;
    }

    private boolean handleUrl(WebView view, String url) {
        if (url == null) return false;
        if (url.startsWith("mailto:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity, "No email application found.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (url.startsWith("tel:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
                activity.startActivity(intent);
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrl(view, url);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.getUrl() != null) {
            return handleUrl(view, request.getUrl().toString());
        }
        return false;
    }
}
