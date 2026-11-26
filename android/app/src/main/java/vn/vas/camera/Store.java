package vn.vas.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.location.Location;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Ghi EXIF, gắn dữ liệu khung, lưu vào Bộ sưu tập. */
public final class Store {
    private Store() { }

    public static String fileName(long time, String suffix) {
        return "VAS_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(time)) + suffix + ".jpg";
    }

    public static void writeExif(File f, long time, Stamp.Data d) {
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            String t = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(time));
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, t);
            ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, t);
            ex.setAttribute(ExifInterface.TAG_DATETIME, t);
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "VAS Camera");
            ex.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            if (d != null && d.hasGps) {
                Location l = new Location("gps");
                l.setLatitude(d.lat);
                l.setLongitude(d.lon);
                l.setTime(time);
                ex.setGpsInfo(l);
            }
            ex.saveAttributes();
        } catch (Throwable ignored) { }
    }

    /** Gắn dữ liệu khung vào cuối file (phải làm SAU khi ghi EXIF). */
    public static void appendMeta(File f, VasMeta m) throws Exception {
        byte[] b = m.encode();
        try (FileOutputStream fo = new FileOutputStream(f, true)) { fo.write(b); }
    }

    private static void copy(InputStream is, OutputStream os) throws Exception {
        byte[] b = new byte[65536];
        int n;
        while ((n = is.read(b)) > 0) os.write(b, 0, n);
    }

    public static Uri saveToGallery(Context ctx, File f, String name, long time) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            v.put(MediaStore.Images.Media.DATE_TAKEN, time);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("không tạo được ảnh trong Bộ sưu tập");
            try (OutputStream os = cr.openOutputStream(uri); InputStream is = new FileInputStream(f)) { copy(is, os); }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
            dir.mkdirs();
            File dst = new File(dir, name);
            int i = 1;
            while (dst.exists()) dst = new File(dir, name.replace(".jpg", "_" + (i++) + ".jpg"));
            try (InputStream is = new FileInputStream(f); OutputStream os = new FileOutputStream(dst)) { copy(is, os); }
            MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return Uri.fromFile(dst);
        }
    }

    /** Ghi đè ảnh cũ. Trả về false nếu không có quyền (ví dụ ảnh không do app tạo). */
    public static boolean overwrite(Context ctx, Uri uri, File f) {
        try {
            if ("file".equals(uri.getScheme())) {
                try (InputStream is = new FileInputStream(f); OutputStream os = new FileOutputStream(new File(uri.getPath()))) { copy(is, os); }
                MediaScannerConnection.scanFile(ctx, new String[]{uri.getPath()}, new String[]{"image/jpeg"}, null);
                return true;
            }
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt"); InputStream is = new FileInputStream(f)) {
                if (os == null) return false;
                copy(is, os);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
