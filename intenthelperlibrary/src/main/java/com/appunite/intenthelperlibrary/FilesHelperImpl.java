package com.appunite.intenthelperlibrary;


import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.support.v4.content.FileProvider;

import java.io.File;
import java.io.IOException;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;

import okhttp3.MediaType;

class FilesHelperImpl implements FilesHelper {

    @Nonnull
    private final Context context;
    @Nonnull
    private final String subdirectoryName;
    @Nonnull
    private final String fileProvider;

    @Inject
    FilesHelperImpl(@Nonnull Context context,
                    @Nonnull @Named("subdirectoryName") String subdirectoryName,
                    @Nonnull @Named("fileProvider") String fileProvider) {
        this.context = context;
        this.subdirectoryName = subdirectoryName;
        this.fileProvider = fileProvider;
    }

    @Nonnull
    @Override
    public File getLocalPrivateDirectory() throws IOException {
        final File localPath = new File(context.getFilesDir(), "images");
        if (!localPath.isDirectory() && !localPath.mkdirs()) {
            throw new IOException("Could not create directory: " + localPath);
        }
        return localPath;
    }

    @Nonnull
    @Override
    public String createUriForLocalPrivateFile(@Nonnull File file) {
        return FileProvider.getUriForFile(context, fileProvider, file).toString();
    }

    /**
     * We need STORAGE_WRITE permissions
     */
    @Nonnull
    @Override
    public File getExternalStoragePublicDirectory(@Nonnull MediaType mediaType) throws IOException {
        final String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            throw new IOException("Media not mounted");
        }

        File file = new File(Environment.getExternalStoragePublicDirectory(getDirTypeByMediaType(mediaType)), subdirectoryName);
        if (!file.isDirectory() && !file.mkdirs()) {
            throw new IOException("Could not create directory: " + file);
        }
        return file;

    }

    @Nonnull
    @Override
    public File getInternalStorageDirectoryWithName(@Nonnull String directoryName) throws IOException {
        final File internalStorage = context.getDir(directoryName, Context.MODE_PRIVATE);
        if (!internalStorage.isDirectory() && !internalStorage.mkdirs()) {
            throw new IOException("Could not create internal storage directory " + internalStorage);
        }

        return internalStorage;
    }

    @Override
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void deleteInternalStoragePackDirectory(@Nonnull String directoryName, @Nonnull String subdirectoryName) {
        try {
            final File packDir = new File(getInternalStorageDirectoryWithName(directoryName), subdirectoryName);
            packDir.delete();
        } catch (IOException ignore) {
        }
    }

    @Override
    public void scanExternalStoragePublicFile(@Nonnull File file) {
        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                .setData(Uri.fromFile(file)));
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Nonnull
    private String getDirTypeByMediaType(@Nonnull MediaType mediaType) {
        final String type = mediaType.type();
        if (type.equals("image")) {
            return Environment.DIRECTORY_PICTURES;
        } else if (type.equals("video")) {
            return Environment.DIRECTORY_MOVIES;
        } else if (type.equals("audio")) {
            return Environment.DIRECTORY_MUSIC;
        } else {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
                return Environment.DIRECTORY_DOCUMENTS;
            } else {
                return "Documents";
            }
        }
    }

}
