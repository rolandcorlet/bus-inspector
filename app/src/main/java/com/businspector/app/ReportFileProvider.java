package com.businspector.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class ReportFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.businspector.app.provider";

    public static Uri getUriForReport(Context context, String filename) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("reports")
                .appendPath(filename)
                .build();
    }

    public static Uri getUriForCameraFile(Context context, String filename) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("camera")
                .appendPath(filename)
                .build();
    }

    public static File getReportFile(Context context, String filename) {
        File dir = new File(context.getCacheDir(), "reports");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safeName = new File(filename).getName();
        return new File(dir, safeName);
    }

    public static File getCameraFile(Context context, String filename) {
        File dir = new File(context.getCacheDir(), "camera");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safeName = new File(filename).getName();
        return new File(dir, safeName);
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri == null) {
            throw new FileNotFoundException("Invalid context or URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) {
            throw new FileNotFoundException("Invalid URI path: " + uri);
        }
        String folder = segments.get(0);
        String name = segments.get(segments.size() - 1);
        if ("camera".equals(folder)) {
            return getCameraFile(context, name);
        }
        return getReportFile(context, name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolveFile(uri);
        } catch (FileNotFoundException e) {
            return null;
        }

        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }

        String[] cols = new String[projection.length];
        Object[] values = new Object[projection.length];
        int i = 0;
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cols[i] = OpenableColumns.DISPLAY_NAME;
                values[i++] = file.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                cols[i] = OpenableColumns.SIZE;
                values[i++] = file.exists() ? file.length() : 0L;
            } else if ("_data".equals(col)) {
                cols[i] = "_data";
                values[i++] = file.getAbsolutePath();
            }
        }

        String[] finalCols = new String[i];
        Object[] finalValues = new Object[i];
        System.arraycopy(cols, 0, finalCols, 0, i);
        System.arraycopy(values, 0, finalValues, 0, i);

        MatrixCursor cursor = new MatrixCursor(finalCols, 1);
        cursor.addRow(finalValues);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) return "application/octet-stream";
        String path = uri.getLastPathSegment();
        if (path == null) return "application/octet-stream";
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".csv")) {
            return "text/csv";
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No external inserts");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            File file = resolveFile(uri);
            return file.delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No external updates");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveFile(uri);
        int fileMode = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && (mode.contains("w") || mode.contains("W"))) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!file.exists()) {
                try {
                    file.createNewFile();
                } catch (IOException ignored) {
                }
            }
            fileMode = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(file, fileMode);
    }
}
