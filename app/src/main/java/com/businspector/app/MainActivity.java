package com.businspector.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQUEST_CAMERA_CAPTURE = 1001;
    private static final String DEFAULT_EMAIL = "transportbusreports@gmail.com";
    private static final String STATE_CAMERA_FILE = "state_camera_file_path";
    private static final String STATE_BRIDGE_CAMERA = "state_bridge_camera";

    private static final Pattern DATA_IMAGE_PATTERN = Pattern.compile(
            "data:image/(?:jpeg|jpg|png|webp);base64,([A-Za-z0-9+/=\\r\\n]+)"
    );

    private WebView webView;
    public ValueCallback<Uri[]> filePathCallback;
    private File currentCameraFile;
    private volatile boolean pendingBridgeCamera = false;
    private volatile String lastCapturedPhotoDataUrl = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            String savedPath = savedInstanceState.getString(STATE_CAMERA_FILE);
            if (savedPath != null && !savedPath.isEmpty()) {
                currentCameraFile = new File(savedPath);
            }
            pendingBridgeCamera = savedInstanceState.getBoolean(STATE_BRIDGE_CAMERA, false);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(true);
            settings.setAllowUniversalAccessFromFileURLs(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        AndroidJsBridge bridge = new AndroidJsBridge();
        webView.addJavascriptInterface(bridge, "AndroidBridge");
        webView.addJavascriptInterface(bridge, "Android");

        webView.setWebViewClient(new PortalWebViewClient(this));
        webView.setWebChromeClient(new PortalWebChromeClient(this));

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentCameraFile != null) {
            outState.putString(STATE_CAMERA_FILE, currentCameraFile.getAbsolutePath());
        }
        outState.putBoolean(STATE_BRIDGE_CAMERA, pendingBridgeCamera);
    }

    public class AndroidJsBridge {

        @JavascriptInterface
        public boolean takePhoto() {
            pendingBridgeCamera = true;
            return launchCamera();
        }

        @JavascriptInterface
        public String consumeLastCapturedPhoto() {
            String data = lastCapturedPhotoDataUrl;
            lastCapturedPhotoDataUrl = null;
            return data != null ? data : "";
        }

        @JavascriptInterface
        public String saveFileToDownloads(String content, String filename, String mimeType, boolean isBase64) {
            return saveFileInternal(content, filename, mimeType, isBase64, true);
        }

        @JavascriptInterface
        public boolean saveHtmlToDownloads(String htmlContent, String filename) {
            String res = saveFileInternal(htmlContent, filename, "text/html", false, true);
            return res != null && !res.startsWith("ERROR");
        }

        @JavascriptInterface
        public String sendEmail(String to, String subject, String body, String htmlContent, String filename) {
            boolean saveIndividualPhotos = filename != null &&
                    filename.toLowerCase().contains("condition");
            return sendEmailInternal(to, subject, body, htmlContent, filename, saveIndividualPhotos);
        }

        @JavascriptInterface
        public String sendEmailWithPhotos(
                String to,
                String subject,
                String body,
                String htmlContent,
                String filename,
                boolean savePhotosToDownloads
        ) {
            return sendEmailInternal(to, subject, body, htmlContent, filename, savePhotosToDownloads);
        }

        @JavascriptInterface
        public void showToast(final String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    public boolean launchCamera() {
        return launchCameraOrFilePicker(false);
    }

    public boolean launchCameraOrFilePicker(final boolean allowGalleryChooser) {
        final boolean[] started = new boolean[]{true};
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    String fileName = "capture_" + System.currentTimeMillis() + ".jpg";
                    currentCameraFile = ReportFileProvider.getCameraFile(MainActivity.this, fileName);
                    if (currentCameraFile.exists()) {
                        currentCameraFile.delete();
                    }
                    Uri photoUri = ReportFileProvider.getUriForCameraFile(MainActivity.this, fileName);

                    Intent captureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        captureIntent.setClipData(ClipData.newRawUri("photo", photoUri));
                    }
                    captureIntent.addFlags(
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );

                    try {
                        List<ResolveInfo> cameraApps = getPackageManager().queryIntentActivities(
                                captureIntent, PackageManager.MATCH_DEFAULT_ONLY
                        );
                        if (cameraApps != null) {
                            for (ResolveInfo info : cameraApps) {
                                if (info.activityInfo != null && info.activityInfo.packageName != null) {
                                    grantUriPermission(
                                            info.activityInfo.packageName,
                                            photoUri,
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    );
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    if (allowGalleryChooser) {
                        Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
                        pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        pickIntent.setType("image/*");
                        Intent chooser = Intent.createChooser(pickIntent, "Select or Capture Photo");
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{captureIntent});
                        startActivityForResult(chooser, REQUEST_CAMERA_CAPTURE);
                    } else {
                        startActivityForResult(captureIntent, REQUEST_CAMERA_CAPTURE);
                    }
                } catch (Exception e) {
                    try {
                        Intent fallbackIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        startActivityForResult(fallbackIntent, REQUEST_CAMERA_CAPTURE);
                    } catch (Exception ex) {
                        started[0] = false;
                        pendingBridgeCamera = false;
                        Toast.makeText(MainActivity.this, "Could not open camera.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        return started[0];
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAMERA_CAPTURE) {
            return;
        }

        if (resultCode != RESULT_OK) {
            pendingBridgeCamera = false;
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            if (currentCameraFile != null && currentCameraFile.exists()) {
                currentCameraFile.delete();
            }
            return;
        }

        final File capturedFile = currentCameraFile;
        final boolean wasBridgeCamera = pendingBridgeCamera;
        pendingBridgeCamera = false;

        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] jpegBytes = null;
                try {
                    if (capturedFile != null && capturedFile.exists() && capturedFile.length() > 0) {
                        byte[] rawBytes = readAllBytes(new FileInputStream(capturedFile));
                        jpegBytes = decodeScaleAndOrientJpeg(rawBytes, 1440, 85);
                    }
                    if (jpegBytes == null && data != null && data.getData() != null) {
                        InputStream is = getContentResolver().openInputStream(data.getData());
                        if (is != null) {
                            byte[] rawBytes = readAllBytes(is);
                            jpegBytes = decodeScaleAndOrientJpeg(rawBytes, 1440, 85);
                        }
                    }
                    if (jpegBytes == null && data != null && data.getExtras() != null) {
                        Object rawBmp = data.getExtras().get("data");
                        if (rawBmp instanceof Bitmap) {
                            Bitmap bmp = scaleBitmap((Bitmap) rawBmp, 1440);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                            jpegBytes = baos.toByteArray();
                        }
                    }
                } catch (Exception ignored) {
                }

                if (capturedFile != null && capturedFile.exists()) {
                    try {
                        capturedFile.delete();
                    } catch (Exception ignored) {
                    }
                }

                if (jpegBytes == null || jpegBytes.length == 0) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (filePathCallback != null) {
                                filePathCallback.onReceiveValue(null);
                                filePathCallback = null;
                            }
                            Toast.makeText(MainActivity.this, "Failed to read captured photo.", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                final byte[] finalJpeg = jpegBytes;
                final String base64 = Base64.encodeToString(finalJpeg, Base64.NO_WRAP);
                final String dataUrl = "data:image/jpeg;base64," + base64;
                lastCapturedPhotoDataUrl = dataUrl;

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (filePathCallback != null && !wasBridgeCamera) {
                            try {
                                String chooserFileName = "photo_" + System.currentTimeMillis() + ".jpg";
                                File chooserOut = ReportFileProvider.getCameraFile(MainActivity.this, chooserFileName);
                                FileOutputStream fos = new FileOutputStream(chooserOut);
                                fos.write(finalJpeg);
                                fos.flush();
                                fos.close();
                                Uri contentUri = ReportFileProvider.getUriForCameraFile(MainActivity.this, chooserFileName);
                                filePathCallback.onReceiveValue(new Uri[]{contentUri});
                            } catch (Exception e) {
                                filePathCallback.onReceiveValue(null);
                            }
                            filePathCallback = null;
                            return;
                        }

                        if (filePathCallback != null) {
                            filePathCallback.onReceiveValue(null);
                            filePathCallback = null;
                        }

                        String js = "if(window.__onAndroidCameraReady){window.__onAndroidCameraReady();}"
                                + "else if(window.__onAndroidCameraResult){window.__onAndroidCameraResult('" + dataUrl + "');}";
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            webView.evaluateJavascript(js, null);
                        } else {
                            webView.loadUrl("javascript:" + js);
                        }
                    }
                });
            }
        }).start();
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = is.read(chunk, 0, chunk.length)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } finally {
            try {
                is.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] decodeScaleAndOrientJpeg(byte[] rawBytes, int maxDim, int quality) {
        if (rawBytes == null || rawBytes.length == 0) return null;
        try {
            int rotation = getJpegExifRotation(rawBytes);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, bounds);

            int sampleSize = 1;
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            while (w / sampleSize > maxDim * 2 || h / sampleSize > maxDim * 2) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            Bitmap decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, opts);
            if (decoded == null) {
                return rawBytes;
            }

            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(
                        decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true
                );
                if (rotated != decoded) {
                    decoded.recycle();
                    decoded = rotated;
                }
            }

            Bitmap scaled = scaleBitmap(decoded, maxDim);
            if (scaled != decoded) {
                decoded.recycle();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            scaled.recycle();
            return baos.toByteArray();
        } catch (Throwable t) {
            return rawBytes;
        }
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxDim) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxDim && h <= maxDim) {
            return src;
        }
        int newW;
        int newH;
        if (w >= h) {
            newW = maxDim;
            newH = Math.max(1, Math.round((h * (float) maxDim) / w));
        } else {
            newH = maxDim;
            newW = Math.max(1, Math.round((w * (float) maxDim) / h));
        }
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    /**
     * Pure-Java JPEG EXIF Orientation reader (handles portrait photos from Samsung/Pixel cameras).
     */
    private static int getJpegExifRotation(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 14) return 0;
        if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return 0;

        int offset = 2;
        while (offset + 4 < jpeg.length) {
            if ((jpeg[offset] & 0xFF) != 0xFF) break;
            int marker = jpeg[offset + 1] & 0xFF;
            if (marker == 0xFF) {
                offset++;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) break;

            int length = ((jpeg[offset + 2] & 0xFF) << 8) | (jpeg[offset + 3] & 0xFF);
            if (length < 2 || offset + 2 + length > jpeg.length) break;

            if (marker == 0xE1 && length >= 14) {
                int exifStart = offset + 4;
                if (jpeg[exifStart] == 'E' &&
                        jpeg[exifStart + 1] == 'x' &&
                        jpeg[exifStart + 2] == 'i' &&
                        jpeg[exifStart + 3] == 'f' &&
                        jpeg[exifStart + 4] == 0 &&
                        jpeg[exifStart + 5] == 0) {
                    int tiffStart = exifStart + 6;
                    boolean littleEndian;
                    if (jpeg[tiffStart] == 'I' && jpeg[tiffStart + 1] == 'I') {
                        littleEndian = true;
                    } else if (jpeg[tiffStart] == 'M' && jpeg[tiffStart + 1] == 'M') {
                        littleEndian = false;
                    } else {
                        return 0;
                    }

                    int ifdOffset = readInt32(jpeg, tiffStart + 4, littleEndian);
                    int dirStart = tiffStart + ifdOffset;
                    if (dirStart + 2 > jpeg.length) return 0;

                    int numEntries = readInt16(jpeg, dirStart, littleEndian);
                    for (int i = 0; i < numEntries; i++) {
                        int entryOffset = dirStart + 2 + (i * 12);
                        if (entryOffset + 12 > jpeg.length) break;
                        int tag = readInt16(jpeg, entryOffset, littleEndian);
                        if (tag == 0x0112) {
                            int orientation = readInt16(jpeg, entryOffset + 8, littleEndian);
                            switch (orientation) {
                                case 3:
                                case 4:
                                    return 180;
                                case 5:
                                case 6:
                                    return 90;
                                case 7:
                                case 8:
                                    return 270;
                                default:
                                    return 0;
                            }
                        }
                    }
                    return 0;
                }
            }
            offset += 2 + length;
        }
        return 0;
    }

    private static int readInt16(byte[] data, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        } else {
            return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        }
    }

    private static int readInt32(byte[] data, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        } else {
            return ((data[offset] & 0xFF) << 24)
                    | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
        }
    }

    private static String sanitizeFilename(String filename, String fallback) {
        String raw = (filename != null && !filename.trim().isEmpty()) ? filename.trim() : fallback;
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sanitizeEmail(String email) {
        if (email == null) return DEFAULT_EMAIL;
        String cleaned = email.trim().replaceAll("\\s+", "");
        return cleaned.isEmpty() ? DEFAULT_EMAIL : cleaned;
    }

    /**
     * Saves a file (HTML, JSON, CSV, or Base64 Image) directly to the device's local
     * Downloads folder (/storage/emulated/0/Download/<filename>) AND to app cache for email sharing.
     */
    public String saveFileInternal(
            String content,
            String filename,
            String mimeType,
            boolean isBase64,
            final boolean showToast
    ) {
        if (content == null) return "ERROR:Empty content";
        final String safeName = sanitizeFilename(filename, "bus_report_" + System.currentTimeMillis() + ".html");
        final String mime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "text/html";

        byte[] dataBytes;
        try {
            if (isBase64) {
                String cleanB64 = content;
                int commaIdx = cleanB64.indexOf(',');
                if (cleanB64.startsWith("data:") && commaIdx != -1) {
                    cleanB64 = cleanB64.substring(commaIdx + 1);
                }
                dataBytes = Base64.decode(cleanB64, Base64.DEFAULT);
            } else {
                dataBytes = content.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "ERROR:" + e.getMessage();
        }

        return writeBytesToDownloadsAndCache(dataBytes, safeName, mime, showToast);
    }

    private String writeBytesToDownloadsAndCache(
            byte[] dataBytes,
            final String safeName,
            String mime,
            final boolean showToast
    ) {
        if (dataBytes == null || dataBytes.length == 0) {
            return "ERROR:Empty bytes";
        }

        // 1. Always save a copy in <cacheDir>/reports/<safeName> for ReportFileProvider email attachments
        try {
            File cacheReport = ReportFileProvider.getReportFile(this, safeName);
            FileOutputStream cacheFos = new FileOutputStream(cacheReport);
            cacheFos.write(dataBytes);
            cacheFos.flush();
            cacheFos.close();
        } catch (Exception ignored) {
        }

        boolean savedToDownloads = false;

        // 2. Android 10+ (API 29+): Save to public Downloads folder via MediaStore.Downloads
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = getContentResolver();
                Uri downloadsUri = Uri.parse("content://media/external/downloads");

                try {
                    resolver.delete(
                            downloadsUri,
                            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{safeName}
                    );
                } catch (Exception ignored) {
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put("relative_path", Environment.DIRECTORY_DOWNLOADS);
                values.put("is_pending", 1);

                Uri itemUri = resolver.insert(downloadsUri, values);
                if (itemUri == null) {
                    int dot = safeName.lastIndexOf('.');
                    String altName = (dot > 0)
                            ? safeName.substring(0, dot) + "_" + (System.currentTimeMillis() % 10000) + safeName.substring(dot)
                            : safeName + "_" + (System.currentTimeMillis() % 10000);
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, altName);
                    itemUri = resolver.insert(downloadsUri, values);
                }

                if (itemUri != null) {
                    OutputStream os = resolver.openOutputStream(itemUri);
                    if (os != null) {
                        os.write(dataBytes);
                        os.flush();
                        os.close();
                        ContentValues doneValues = new ContentValues();
                        doneValues.put("is_pending", 0);
                        resolver.update(itemUri, doneValues, null, null);
                        savedToDownloads = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Fallback / Android 9 and below: Direct write to /storage/emulated/0/Download/<safeName>
        if (!savedToDownloads) {
            try {
                File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dlDir != null) {
                    if (!dlDir.exists()) {
                        dlDir.mkdirs();
                    }
                    File outFile = new File(dlDir, safeName);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    fos.write(dataBytes);
                    fos.flush();
                    fos.close();
                    MediaScannerConnection.scanFile(
                            this,
                            new String[]{outFile.getAbsolutePath()},
                            new String[]{mime},
                            null
                    );
                    savedToDownloads = true;
                }
            } catch (Exception ignored) {
            }
        }

        // 4. Secondary fallback to app-specific external Downloads folder if needed
        if (!savedToDownloads) {
            try {
                File appDl = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (appDl != null) {
                    if (!appDl.exists()) {
                        appDl.mkdirs();
                    }
                    File outFile = new File(appDl, safeName);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    fos.write(dataBytes);
                    fos.flush();
                    fos.close();
                    savedToDownloads = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (showToast) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(
                            MainActivity.this,
                            "Saved to Downloads: " + safeName,
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }

        return "SAVED:/Download/" + safeName;
    }

    /**
     * Saves the HTML report (and embedded photos) to local Downloads AND opens the user's email app
     * with To, Subject, Body, the HTML report, and individual JPEG photos automatically attached.
     */
    public String sendEmailInternal(
            final String to,
            final String subject,
            final String body,
            final String htmlContent,
            final String filename,
            final boolean savePhotosToDownloads
    ) {
        final String safeName = sanitizeFilename(
                filename,
                "bus_report_" + System.currentTimeMillis() + ".html"
        );
        final String recipient = sanitizeEmail(to);
        final String emailSubject = (subject != null && !subject.trim().isEmpty())
                ? subject.trim()
                : "Bus Inspector Report";
        final String emailBody = (body != null) ? body : "";

        final ArrayList<Uri> attachmentUris = new ArrayList<>();
        final ArrayList<String> attachmentNames = new ArrayList<>();

        if (htmlContent != null && !htmlContent.isEmpty()) {
            saveFileInternal(htmlContent, safeName, "text/html", false, false);
            File reportFile = ReportFileProvider.getReportFile(this, safeName);
            if (reportFile.exists() && reportFile.length() > 0) {
                attachmentUris.add(ReportFileProvider.getUriForReport(this, safeName));
                attachmentNames.add(safeName);
            }

            // Extract embedded data:image/...;base64,... photos so they are ALSO attached as direct .jpg files
            try {
                String basePrefix = safeName;
                int dotIdx = basePrefix.lastIndexOf('.');
                if (dotIdx > 0) {
                    basePrefix = basePrefix.substring(0, dotIdx);
                }
                Matcher matcher = DATA_IMAGE_PATTERN.matcher(htmlContent);
                int photoIndex = 1;
                while (matcher.find() && photoIndex <= 25) {
                    String b64Data = matcher.group(1);
                    if (b64Data == null || b64Data.isEmpty()) continue;
                    byte[] imgBytes = Base64.decode(b64Data, Base64.DEFAULT);
                    if (imgBytes == null || imgBytes.length == 0) continue;

                    String photoFilename = sanitizeFilename(
                            basePrefix + "_Photo_" + photoIndex + ".jpg",
                            "Bus_Photo_" + photoIndex + ".jpg"
                    );
                    if (savePhotosToDownloads) {
                        writeBytesToDownloadsAndCache(imgBytes, photoFilename, "image/jpeg", false);
                    } else {
                        File cachePhoto = ReportFileProvider.getReportFile(this, photoFilename);
                        FileOutputStream fos = new FileOutputStream(cachePhoto);
                        fos.write(imgBytes);
                        fos.flush();
                        fos.close();
                    }
                    attachmentUris.add(ReportFileProvider.getUriForReport(this, photoFilename));
                    attachmentNames.add(photoFilename);
                    photoIndex++;
                }
            } catch (Exception ignored) {
            }
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(
                        MainActivity.this,
                        "Saved to Downloads (" + safeName + ") & opening email...",
                        Toast.LENGTH_LONG
                ).show();

                try {
                    if (!attachmentUris.isEmpty()) {
                        PackageManager pm = getPackageManager();
                        Intent mailtoProbe = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
                        List<ResolveInfo> emailApps = pm.queryIntentActivities(mailtoProbe, 0);

                        List<Intent> targetedEmailIntents = new ArrayList<>();
                        Set<String> seenPackages = new HashSet<>();

                        if (emailApps != null) {
                            for (ResolveInfo info : emailApps) {
                                if (info.activityInfo == null || info.activityInfo.packageName == null) {
                                    continue;
                                }
                                String pkg = info.activityInfo.packageName;
                                if (seenPackages.contains(pkg)) continue;
                                seenPackages.add(pkg);

                                for (Uri uri : attachmentUris) {
                                    try {
                                        grantUriPermission(
                                                pkg,
                                                uri,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        );
                                    } catch (Exception ignored) {
                                    }
                                }

                                Intent targeted = buildSendAttachmentIntent(
                                        recipient,
                                        emailSubject,
                                        emailBody,
                                        attachmentUris,
                                        attachmentNames
                                );
                                targeted.setPackage(pkg);
                                targetedEmailIntents.add(targeted);
                            }
                        }

                        if (targetedEmailIntents.size() == 1) {
                            try {
                                startActivity(targetedEmailIntents.get(0));
                                return;
                            } catch (Exception ignored) {
                            }
                        } else if (targetedEmailIntents.size() > 1) {
                            try {
                                Intent primary = targetedEmailIntents.remove(0);
                                Intent chooser = Intent.createChooser(primary, "Send Report via Email");
                                chooser.putExtra(
                                        Intent.EXTRA_INITIAL_INTENTS,
                                        targetedEmailIntents.toArray(new Parcelable[0])
                                );
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(chooser);
                                return;
                            } catch (Exception ignored) {
                            }
                        }

                        // General ACTION_SEND / ACTION_SEND_MULTIPLE chooser with attachments
                        Intent genericSend = buildSendAttachmentIntent(
                                recipient,
                                emailSubject,
                                emailBody,
                                attachmentUris,
                                attachmentNames
                        );
                        Intent chooser = Intent.createChooser(genericSend, "Send Report via Email");
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(chooser);
                        return;
                    }
                } catch (Exception ignored) {
                }

                // Fallback to mailto: if ACTION_SEND fails
                try {
                    String mailtoUri = "mailto:" + Uri.encode(recipient)
                            + "?subject=" + Uri.encode(emailSubject)
                            + "&body=" + Uri.encode(emailBody);
                    Intent mailtoIntent = new Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUri));
                    mailtoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(mailtoIntent);
                } catch (Exception e) {
                    Toast.makeText(
                            MainActivity.this,
                            "Report saved to Downloads (" + safeName + "). No email app found.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        });

        return "OK";
    }

    private Intent buildSendAttachmentIntent(
            String recipient,
            String subject,
            String body,
            ArrayList<Uri> attachmentUris,
            ArrayList<String> attachmentNames
    ) {
        boolean multiple = attachmentUris.size() > 1;
        Intent sendIntent = new Intent(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        sendIntent.setType(multiple ? "*/*" : "text/html");
        sendIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{recipient});
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        sendIntent.putExtra(Intent.EXTRA_TEXT, body);

        if (multiple) {
            sendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachmentUris);
        } else {
            sendIntent.putExtra(Intent.EXTRA_STREAM, attachmentUris.get(0));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && !attachmentUris.isEmpty()) {
            String firstLabel = !attachmentNames.isEmpty() ? attachmentNames.get(0) : "report";
            ClipData clipData = ClipData.newRawUri(firstLabel, attachmentUris.get(0));
            for (int i = 1; i < attachmentUris.size(); i++) {
                clipData.addItem(new ClipData.Item(attachmentUris.get(i)));
            }
            sendIntent.setClipData(clipData);
        }

        sendIntent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK
        );
        return sendIntent;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
