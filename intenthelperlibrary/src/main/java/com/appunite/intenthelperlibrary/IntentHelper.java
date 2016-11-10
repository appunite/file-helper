package com.appunite.intenthelperlibrary;


import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.v4.app.Fragment;
import android.support.v4.content.FileProvider;

import com.appunite.intenthelperlibrary.dao.ManagedFileDao;
import com.appunite.intenthelperlibrary.helpers.FileResult;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import rx.Observable;
import rx.functions.Func0;

import static com.appunite.intenthelperlibrary.dao.ManagedFileDao.*;


public class IntentHelper {

    private static final String STATE_FILE = "state_file";

    @Nonnull
    private final Context context;
    @Nonnull
    private final FilesManager filesManager;
    @Nonnull
    private final ManagedFileDao managedFileDao;
    @Nonnull
    private final String fileProvider;
    @Nullable
    private final String additionalSourceDataName;

    @Nullable
    private ManagedFile imageResultUrl;


    @Inject
    public IntentHelper(@Nonnull Context context,
                        @Nonnull FilesManager filesManager,
                        @Nonnull ManagedFileDao managedFileDao,
                        @Nonnull @Named("fileProvider") String fileProvider,
                        @Nullable @Named("additionalSourceDataName") String additionalSourceDataName) {
        this.context = context;
        this.filesManager = filesManager;
        this.managedFileDao = managedFileDao;
        this.fileProvider = fileProvider;
        this.additionalSourceDataName = additionalSourceDataName;
    }

    public void onSaveInstanceState(@Nonnull Bundle outState) {
        outState.putByteArray(STATE_FILE, imageResultUrl == null ? null : imageResultUrl.newManagedFile("IntentHelper - leaked saveState").fileId().toByteArray());
    }

    public void onRestoreInstanceState(@Nonnull Bundle savedInstanceState) {
        if (imageResultUrl != null) {
            imageResultUrl.release();
            imageResultUrl = null;
        }
        final byte[] string = savedInstanceState.getByteArray(STATE_FILE);
        if (string == null) {
            imageResultUrl = null;
        } else {
            final ByteString fileId = ByteString.copyFrom(string);
            imageResultUrl = managedFileDao.findAndAcquireManagedFileIfExists(fileId, "IntentHelper - from intent");
        }
    }

    @Nonnull
    public Observable<List<FileResult>> onActivityResultObservable(final int resultCode, @Nullable final Intent data) {
        final ManagedFile imageResultUrl = getManagedFileFromStateAndClearState();
        return Observable.defer(new Func0<Observable<List<com.appunite.intenthelperlibrary.helpers.FileResult>>>() {
            @Override
            public Observable<List<com.appunite.intenthelperlibrary.helpers.FileResult>> call() {
                try {
                    return Observable.just(onActivityResult(imageResultUrl, resultCode, data));
                } catch (IOException e) {
                    return Observable.error(e);
                }
            }
        });
    }

    @Nonnull
    public Observable<List<FileResult>> shareDataObservable(@Nonnull final Intent data) {
        final ManagedFile imageResultUrl = getManagedFileFromStateAndClearState();
        return Observable.defer(new Func0<Observable<List<com.appunite.intenthelperlibrary.helpers.FileResult>>>() {
            @Override
            public Observable<List<com.appunite.intenthelperlibrary.helpers.FileResult>> call() {
                try {
                    return Observable.just(shareData(imageResultUrl, data));
                } catch (IOException e) {
                    return Observable.error(e);
                }
            }
        });
    }

    @Nonnull
    private String guessMimeType(@Nonnull Uri uri) {
        final String mimeTypeFromName = URLConnection.guessContentTypeFromName(uri.toString());
        return !(mimeTypeFromName == null || mimeTypeFromName.length() <= 0)
                ? mimeTypeFromName
                : "application/octet-stream";
    }

    @Nullable
    private String fileNameFromUri(@Nonnull Uri uri) {
        final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);

        if (cursor == null) return null;

        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
            } else {
                return null;
            }
        } finally {
            cursor.close();
        }
    }

    @Nonnull
    private FileResult fileResultFromUri(@Nonnull Uri uri) throws IOException {
        final String type = context.getContentResolver().getType(uri);

        if (type != null) {
            final ManagedFile file = createFileFromUri(uri);
            return fix(new FileResult(file, type, fileNameFromUri(uri)));
        } else { // Check if file exists on sd card. We can get the file without permission for STORAGE
            final File sdCardFile = new File(uri.getPath());

            if (!sdCardFile.exists()) {
                throw new IOException("File does not exists in content resolver nor on sd card  " + uri);
            }

            return fix(new FileResult(createFileFromFile(sdCardFile), guessMimeType(uri), fileNameFromUri(uri)));
        }
    }

    @Nonnull
    private ManagedFile rotateBitmap(@Nonnull ManagedFile managedFile,
                                                    @Nonnull Matrix rotationMatrix) throws IOException {
        try {
            final Bitmap bitmap = BitmapFactory.decodeFile(managedFile.file().getAbsolutePath());
            try {
                final Bitmap newBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotationMatrix, true);
                try {
                    final ManagedFile compressedFile = createTemporaryPrivateFile();
                    try {
                        final FileOutputStream output = new FileOutputStream(compressedFile.file());
                        try {
                            newBitmap.compress(Bitmap.CompressFormat.JPEG, 70, output);
                        } finally {
                            output.close();
                        }
                        return compressedFile.newManagedFile("after decompression");
                    } finally {
                        compressedFile.release();
                    }
                } finally {
                    newBitmap.recycle();
                }
            } catch (OutOfMemoryError e) {
                throw new IOException("Out of memory");
            } finally {
                bitmap.recycle();
            }
        } finally {
            managedFile.release();
        }
    }

    @Nonnull
    private FileResult fileResultFromFile(@Nonnull ManagedFile managedFile) throws IOException {
        return fix(new FileResult(managedFile, "image/jpeg", "image.jpg"));
    }

    @Nonnull
    private FileResult fix(@Nonnull FileResult fileResult) throws IOException {
        if (fileResult.mimeType().startsWith("image/jpeg")) {
            final Matrix rotationMatrix = getRotationMatrix(fileResult.managedFile().file().getPath());
            if (rotationMatrix == null) {
                return fileResult;
            }

            return new FileResult(rotateBitmap(fileResult.managedFile(), rotationMatrix), "image/jpeg", fileResult.fileName());
        } else {
            return fileResult;
        }
    }

    @Nonnull
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private List<FileResult> getFilesFromClipData(@Nonnull Intent data) throws IOException {
        final List<FileResult> fileResult = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            final ClipData clipData = data.getClipData();
            final Uri dataUri = data.getData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); ++i) {
                    final Uri uri = clipData.getItemAt(i).getUri();
                    fileResult.add(fileResultFromUri(uri));
                }
            } else if (dataUri != null) {
                fileResult.add(fileResultFromUri(dataUri));
            }
        } else {
            final ArrayList<String> dataPreJellyBean = data.getStringArrayListExtra(additionalSourceDataName);
            if (dataPreJellyBean != null && !dataPreJellyBean.isEmpty()) {
                for (int i = 0; i < dataPreJellyBean.size(); ++i) {
                    fileResult.add(fileResultFromUri(Uri.parse(dataPreJellyBean.get(i))));
                }
            }
        }
        return fileResult;
    }

    @Nonnull
    private List<FileResult> shareData(@Nullable ManagedFile imageResultUrl, @Nonnull Intent data) throws IOException {
        final List<FileResult> fileResult = new ArrayList<>();
        fileResult.addAll(getFilesFromClipData(data));
        if (imageResultUrl != null) {
            try {
                if (imageResultUrl.file().length() > 0) {
                    fileResult.add(fileResultFromFile(imageResultUrl.newManagedFile("for share intent")));
                }
            } finally {
                imageResultUrl.release();
            }
        }
        return fileResult;
    }

    @Nonnull
    private List<FileResult> onActivityResult(@Nullable ManagedFile imageResultUrl,
                                              int resultCode,
                                              @Nullable Intent data) throws IOException {
        final List<FileResult> fileResult = new ArrayList<>();
        if (resultCode != Activity.RESULT_OK) {
            return fileResult;
        }
        if (data != null) {
            fileResult.addAll(getFilesFromClipData(data));
        }
        if (imageResultUrl != null) {
            final File file = imageResultUrl.file();
            try {
                if (file.length() > 0) {
                    fileResult.add(fileResultFromFile(imageResultUrl.newManagedFile("intent result")));
                }
            } finally {
                imageResultUrl.release();
            }
        }
        return fileResult;
    }

    @Nullable
    private ManagedFile getManagedFileFromStateAndClearState() {
        if (this.imageResultUrl != null) {
            final ManagedFile imageResultUrl = this.imageResultUrl.newManagedFile("for intent resolution");
            this.imageResultUrl.release();
            this.imageResultUrl = null;
            return imageResultUrl;
        } else {
            return null;
        }
    }

    @Nonnull
    private ManagedFile createTemporaryPrivateFile() throws IOException {
        return filesManager.createTemporaryFile(null, "for intent capture");
    }

    @Nonnull
    private ManagedFile createFileFromUri(@Nonnull Uri uri) throws IOException {
        final InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("Could not open uri");
        }
        try {
            return writeToTemporaryFile(inputStream);
        } finally {
            inputStream.close();
        }
    }

    @Nonnull
    private ManagedFile createFileFromFile(@Nonnull File file) throws IOException {
        final InputStream inputStream = new FileInputStream(file);
        try {
            return writeToTemporaryFile(inputStream);
        } finally {
            inputStream.close();
        }
    }

    @Nonnull
    private ManagedFile writeToTemporaryFile(@Nonnull InputStream inputStream) throws IOException {
        final ManagedFile temporaryPrivateFile = createTemporaryPrivateFile();
        try {
            final FileOutputStream file = new FileOutputStream(temporaryPrivateFile.file());
            try {
                final byte[] buffer = new byte[1000];
                int read;
                while ((read = inputStream.read(buffer, 0, buffer.length)) != -1) {
                    file.write(buffer, 0, read);
                }
            } finally {
                file.close();
            }
            return temporaryPrivateFile.newManagedFile("file written temporary");
        } finally {
            temporaryPrivateFile.release();
        }
    }

    @Nullable
    private Matrix getRotationMatrix(@Nonnull String currentPhotoPath) throws IOException {
        final ExifInterface exifInterface = new ExifInterface(currentPhotoPath);
        final int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return null;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: {
                final Matrix matrix = new Matrix();
                matrix.setScale(-1, 1);
                return matrix;
            }
            case ExifInterface.ORIENTATION_ROTATE_180: {
                final Matrix matrix = new Matrix();
                matrix.setRotate(180);
                return matrix;
            }
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: {
                final Matrix matrix = new Matrix();
                matrix.setRotate(180);
                matrix.postScale(-1, 1);
                return matrix;
            }
            case ExifInterface.ORIENTATION_TRANSPOSE: {
                final Matrix matrix = new Matrix();
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                return matrix;
            }
            case ExifInterface.ORIENTATION_ROTATE_90: {
                final Matrix matrix = new Matrix();
                matrix.setRotate(90);
                return matrix;
            }
            case ExifInterface.ORIENTATION_TRANSVERSE: {
                final Matrix matrix = new Matrix();
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                return matrix;
            }
            case ExifInterface.ORIENTATION_ROTATE_270: {
                final Matrix matrix = new Matrix();
                matrix.setRotate(-90);
                return matrix;
            }
            default:
                return null;
        }
    }

    private void grantUriPermissionForOldSamsung(@Nonnull Uri uriForFile, @Nonnull Intent intent) {
        final List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo resolveInfo : resInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            context.grantUriPermission(packageName, uriForFile, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Nonnull
    private Intent createCaptureImageIntent() throws IOException {
        if (imageResultUrl != null) {
            imageResultUrl.release();
            imageResultUrl = null;
        }
        imageResultUrl = createTemporaryPrivateFile();
        final Uri uriForFile = FileProvider.getUriForFile(context, fileProvider, imageResultUrl.file());
        final Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(MediaStore.EXTRA_OUTPUT, uriForFile);
        grantUriPermissionForOldSamsung(uriForFile, intent);
        return intent;
    }

    @Nonnull
    private Intent createCaptureVideoIntent() {
        return new Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Nonnull
    private Intent createPickFileIntent(@Nonnull String type, boolean mutliple) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return createPickFileIntentWithoutMultiple(type)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mutliple);
        } else {
            return createPickFileIntentWithoutMultiple(type);
        }
    }

    @Nonnull
    private Intent createPickFileIntentWithoutMultiple(@Nonnull String type) {
        return new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type);
    }

    @SuppressWarnings("unused")
    public void requestCaptureImage(@Nonnull Activity activity, int requestCode) throws IOException {
        activity.startActivityForResult(createCaptureImageIntent(), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestCaptureImage(@Nonnull Fragment fragment, int requestCode) throws IOException {
        fragment.startActivityForResult(createCaptureImageIntent(), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestCaptureImage(@Nonnull android.app.Fragment fragment, int requestCode) throws IOException {
        fragment.startActivityForResult(createCaptureImageIntent(), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestCaptureVideo(@Nonnull Activity activity, int requestCode) {
        activity.startActivityForResult(createCaptureVideoIntent(), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestCaptureVideo(@Nonnull Fragment fragment, int requestCode) {
        fragment.startActivityForResult(createCaptureVideoIntent(), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestCaptureVideo(@Nonnull android.app.Fragment fragment, int requestCode) {
        fragment.startActivityForResult(createCaptureVideoIntent(), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestFiles(@Nonnull Fragment fragment, @Nonnull String type, boolean multiple, int requestCode) {
        fragment.startActivityForResult(createPickFileIntent(type, multiple), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestFiles(@Nonnull android.app.Fragment fragment, @Nonnull String type, boolean multiple, int requestCode) {
        fragment.startActivityForResult(createPickFileIntent(type, multiple), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestFiles(@Nonnull Activity activity, String type, boolean multiple, int requestCode) {
        activity.startActivityForResult(createPickFileIntent(type, multiple), requestCode);
    }

    @SuppressWarnings("unused")
    public void requestGalleryWithIntent(@Nonnull Fragment fragment, @Nonnull Intent intent, int requestCode) {
        fragment.startActivityForResult(intent, requestCode);
    }

}
