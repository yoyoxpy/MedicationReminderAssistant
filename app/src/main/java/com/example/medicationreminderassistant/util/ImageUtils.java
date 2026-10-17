package com.example.medicationreminderassistant.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 图片处理：按需采样解码（避免 OOM）、把缓存目录的处方照片持久化到 filesDir。
 */
public final class ImageUtils {

    private static final String TAG = "ImageUtils";

    private ImageUtils() {
    }

    /** 按目标宽高采样加载 Bitmap */
    public static Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options,
                                             int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfH = height / 2;
            int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * 把相册选中的图片(content:// Uri)复制到 cacheDir，返回文件路径；
     * OCR 读取需要真实文件路径。失败返回 null。
     */
    public static String copyUriToCache(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        File dest = new File(context.getCacheDir(),
                "gallery_" + System.currentTimeMillis() + ".jpg");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return dest.getAbsolutePath();
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "从相册复制图片失败", e);
            return null;
        }
    }

    /**
     * 把拍照后存放在 cacheDir 的图片复制到 filesDir/prescriptions，
     * 避免系统清理缓存导致处方照片丢失。复制失败时回退为原路径。
     */
    public static String persistPrescriptionImage(Context context, String srcPath) {
        if (srcPath == null || srcPath.isEmpty()) {
            return null;
        }
        File src = new File(srcPath);
        if (!src.exists()) {
            return null;
        }
        File dir = new File(context.getFilesDir(), "prescriptions");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建处方图片目录，回退使用缓存路径");
            return srcPath;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        File dest = new File(dir, "prescription_" + timestamp + ".jpg");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return dest.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "持久化处方图片失败", e);
            return srcPath;
        }
    }
}
