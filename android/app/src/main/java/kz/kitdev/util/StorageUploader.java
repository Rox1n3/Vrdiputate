package kz.kitdev.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Сжимает фото с устройства, корректирует ориентацию по EXIF и кодирует в base64 data URL.
 * Результат хранится прямо в Firestore — Firebase Storage не нужен.
 */
public class StorageUploader {

    private static final String TAG     = "StorageUploader";
    private static final int    MAX_DIM = 1024;
    private static final int    QUALITY = 55;

    public interface UploadCallback {
        void onSuccess(String downloadUrl);   // "data:image/jpeg;base64,..."
        void onFailure(Exception e);
    }

    public static void upload(Uri imageUri, UploadCallback callback) {
        new Thread(() -> {
            try {
                Context ctx = kz.kitdev.App.get();

                // 1. Читаем EXIF-ориентацию ДО декодирования (поток открываем отдельно)
                int exifDegrees = 0;
                boolean flipH   = false;
                try (InputStream exifIs = ctx.getContentResolver().openInputStream(imageUri)) {
                    if (exifIs != null) {
                        ExifInterface exif = new ExifInterface(exifIs);
                        int orientation = exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL);
                        switch (orientation) {
                            case ExifInterface.ORIENTATION_ROTATE_90:    exifDegrees = 90;  break;
                            case ExifInterface.ORIENTATION_ROTATE_180:   exifDegrees = 180; break;
                            case ExifInterface.ORIENTATION_ROTATE_270:   exifDegrees = 270; break;
                            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flipH = true;   break;
                            case ExifInterface.ORIENTATION_TRANSPOSE:    exifDegrees = 90;  flipH = true; break;
                            case ExifInterface.ORIENTATION_FLIP_VERTICAL: exifDegrees = 180; flipH = true; break;
                            case ExifInterface.ORIENTATION_TRANSVERSE:   exifDegrees = 270; flipH = true; break;
                        }
                    }
                } catch (Exception ignored) {}

                // 2. Определяем inSampleSize (только размеры, без загрузки пикселей)
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                try (InputStream is = ctx.getContentResolver().openInputStream(imageUri)) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
                int sampleSize = 1;
                int w = opts.outWidth, h = opts.outHeight;
                while (w / sampleSize > MAX_DIM * 2 || h / sampleSize > MAX_DIM * 2) {
                    sampleSize *= 2;
                }

                // 3. Декодируем с уменьшением
                BitmapFactory.Options loadOpts = new BitmapFactory.Options();
                loadOpts.inSampleSize = sampleSize;
                Bitmap bmp;
                try (InputStream is = ctx.getContentResolver().openInputStream(imageUri)) {
                    bmp = BitmapFactory.decodeStream(is, null, loadOpts);
                }
                if (bmp == null) throw new IllegalStateException("Не удалось декодировать фото");

                // 4. Масштабируем до MAX_DIM
                int bw = bmp.getWidth(), bh = bmp.getHeight();
                if (bw > MAX_DIM || bh > MAX_DIM) {
                    float scale = Math.min((float) MAX_DIM / bw, (float) MAX_DIM / bh);
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                            Math.round(bw * scale), Math.round(bh * scale), true);
                    bmp.recycle();
                    bmp = scaled;
                }

                // 5. Применяем EXIF-ориентацию (поворот + зеркало)
                if (exifDegrees != 0 || flipH) {
                    Matrix matrix = new Matrix();
                    if (flipH) matrix.postScale(-1f, 1f);
                    if (exifDegrees != 0) matrix.postRotate(exifDegrees);
                    Bitmap rotated = Bitmap.createBitmap(
                            bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                    bmp.recycle();
                    bmp = rotated;
                }

                // 6. Сжимаем в JPEG и кодируем в base64
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, baos);
                bmp.recycle();

                byte[] bytes   = baos.toByteArray();
                String b64     = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String dataUrl = "data:image/jpeg;base64," + b64;

                Log.d(TAG, "Фото готово: " + bytes.length / 1024 + " KB, поворот=" + exifDegrees + "°");

                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> callback.onSuccess(dataUrl));

            } catch (Exception e) {
                Log.e(TAG, "Ошибка обработки фото", e);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> callback.onFailure(e));
            }
        }).start();
    }
}
