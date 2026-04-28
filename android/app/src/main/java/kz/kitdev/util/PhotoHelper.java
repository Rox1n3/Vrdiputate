package kz.kitdev.util;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Утилита для запуска выбора/съёмки фото.
 * Использует REQUEST_CAMERA = 201, REQUEST_GALLERY = 202.
 */
public class PhotoHelper {

    public static final int REQUEST_CAMERA         = 201;
    public static final int REQUEST_GALLERY        = 202;
    public static final int REQUEST_CAMERA_PERM    = 203;

    /** Текущий URI файла камеры (передавать между onRequestPermissionsResult / onActivityResult) */
    public Uri cameraFileUri;

    private final Activity activity;

    public PhotoHelper(Activity activity) {
        this.activity = activity;
    }

    /** Показывает диалог выбора источника фото */
    public void showPicker() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(kz.kitdev.R.string.photo_picker_title))
                .setItems(new CharSequence[]{
                        activity.getString(kz.kitdev.R.string.photo_camera),
                        activity.getString(kz.kitdev.R.string.photo_gallery)
                }, (dialog, which) -> {
                    if (which == 0) checkCameraPermAndLaunch();
                    else            launchGallery();
                })
                .show();
    }

    /** Проверяет разрешение камеры и запускает съёмку */
    public void checkCameraPermAndLaunch() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERM);
        } else {
            launchCamera();
        }
    }

    /** Запускает камеру с сохранением в кэш-файл */
    public void launchCamera() {
        try {
            File photoFile = createTempPhotoFile();
            cameraFileUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraFileUri);
            activity.startActivityForResult(intent, REQUEST_CAMERA);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Запускает галерею */
    public void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        activity.startActivityForResult(
                Intent.createChooser(intent, "Выбрать фото"), REQUEST_GALLERY);
    }

    private File createTempPhotoFile() throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        File dir = new File(activity.getCacheDir(), "photos");
        if (!dir.exists()) dir.mkdirs();
        return File.createTempFile("IMG_" + stamp + "_", ".jpg", dir);
    }
}
