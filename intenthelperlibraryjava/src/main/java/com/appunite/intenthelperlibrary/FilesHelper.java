package com.appunite.intenthelperlibrary;


import java.io.File;
import java.io.IOException;

import javax.annotation.Nonnull;

import okhttp3.MediaType;

public interface FilesHelper {
    @Nonnull
    File getLocalPrivateDirectory() throws IOException;

    /**
     * @throws IllegalArgumentException When the given {@link File} is outside
     * the paths supported by the provider.
     */
    @Nonnull
    String createUriForLocalPrivateFile(@Nonnull File file) throws IllegalArgumentException;

    /**
     * We need STORAGE_WRITE permissions
     */
    @Nonnull
    File getExternalStoragePublicDirectory(@Nonnull MediaType mediaType) throws IOException;

    void scanExternalStoragePublicFile(@Nonnull File file);

    @Nonnull
    File getInternalStorageDirectoryWithName(@Nonnull String directoryName) throws IOException;

    void deleteInternalStoragePackDirectory(@Nonnull String directoryName, @Nonnull String subdirectoryName);
}
