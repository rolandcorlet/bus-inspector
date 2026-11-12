package com.businspector.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Build;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

public class PortalWebChromeClient extends WebChromeClient {

    private final MainActivity activity;

    public PortalWebChromeClient(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    @Override
    public boolean onShowFileChooser(
            WebView webView,
            ValueCallback<Uri[]> filePathCallback,
            FileChooserParams fileChooserParams
    ) {
        if (activity.filePathCallback != null) {
            try {
                activity.filePathCallback.onReceiveValue(null);
            } catch (Exception ignored) {
            }
        }
        activity.filePathCallback = filePathCallback;

        boolean allowGalleryChooser = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && fileChooserParams != null) {
            allowGalleryChooser = !fileChooserParams.isCaptureEnabled();
        }

        boolean launched = activity.launchCameraOrFilePicker(allowGalleryChooser);
        if (!launched) {
            if (activity.filePathCallback != null) {
                activity.filePathCallback.onReceiveValue(null);
                activity.filePathCallback = null;
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
        if (activity.isFinishing()) {
            result.cancel();
            return true;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Bus Inspector")
                    .setMessage(message != null ? message : "")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            result.confirm();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        } catch (Exception e) {
            result.confirm();
            return true;
        }
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
        if (activity.isFinishing()) {
            result.cancel();
            return true;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Confirm")
                    .setMessage(message != null ? message : "")
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            result.confirm();
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        } catch (Exception e) {
            return super.onJsConfirm(view, url, message, result);
        }
    }

    @Override
    public boolean onJsPrompt(
            WebView view,
            String url,
            String message,
            String defaultValue,
            JsPromptResult result
    ) {
        if (message != null) {
            if ("__BUS_CAMERA__".equals(message)) {
                boolean ok = activity.launchCamera();
                result.confirm(ok ? "OK" : "ERROR");
                return true;
            }
            if (message.startsWith("__BUS_SAVE_HTML__:")) {
                String filename = message.substring("__BUS_SAVE_HTML__:".length());
                String res = activity.saveFileInternal(
                        defaultValue != null ? defaultValue : "",
                        filename,
                        "text/html",
                        false,
                        true
                );
                result.confirm(res != null && !res.startsWith("ERROR") ? "SAVED" : "ERROR");
                return true;
            }
            if (message.startsWith("__BUS_SAVE_BASE64__:")) {
                String rest = message.substring("__BUS_SAVE_BASE64__:".length());
                String filename = rest;
                String mime = "image/jpeg";
                int sep = rest.indexOf(':');
                if (sep > 0) {
                    filename = rest.substring(0, sep);
                    mime = rest.substring(sep + 1);
                }
                String res = activity.saveFileInternal(
                        defaultValue != null ? defaultValue : "",
                        filename,
                        mime,
                        true,
                        true
                );
                result.confirm(res != null && !res.startsWith("ERROR") ? "SAVED" : "ERROR");
                return true;
            }
        }
        return super.onJsPrompt(view, url, message, defaultValue, result);
    }
}
