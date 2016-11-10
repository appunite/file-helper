package com.appunite.intenthelperlibrary;


import com.appunite.intenthelperlibrary.dao.ManagedFileDao;
import com.appunite.intenthelperlibrary.helpers.ShareResult;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Callable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import rx.Observable;
import rx.functions.Func0;
import rx.functions.Func1;

public class FilesManager {
    @Nonnull
    private final FilesHelper filesHelper;
    @Nonnull
    private final MimeTypeGetter mimeTypeGetter;
    @Nonnull
    private final OkHttpClient okHttpClient;
    @Nonnull
    private final ManagedFileDao managedFileDao;

    @Inject
    FilesManager(@Nonnull FilesHelper filesHelper,
                 @Nonnull MimeTypeGetter mimeTypeGetter,
                 @Nonnull OkHttpClient okHttpClient,
                 @Nonnull ManagedFileDao managedFileDao) {
        this.filesHelper = filesHelper;
        this.mimeTypeGetter = mimeTypeGetter;
        this.okHttpClient = okHttpClient;
        this.managedFileDao = managedFileDao;
    }

    @Nonnull
    public Observable<ShareResult> downloadFileAndGetShareUrl(@Nonnull final String url) {
        return Observable.defer(new Func0<Observable<ShareResult>>() {
            @Override
            public Observable<ShareResult> call() {
                try {
                    return Observable.just(downloadFileAndGetShareUrl(url));
                } catch (IOException e) {
                    return Observable.error(e);
                }
            }

            @Nonnull
            ShareResult downloadFileAndGetShareUrl(@Nonnull final String url) throws IOException {
                final ResponseBody body = downloadBodyFromUrl(url);
                try {
                    final MediaType mediaType = body.contentType();
                    final String mimeType = mediaType.type() + "/" + mediaType.subtype();
                    final String extension = mimeTypeGetter.getExtensionFromMediaType(mediaType);
                    final ManagedFileDao.ManagedFile managedFile = writeToFile(extension, body);
                    try {
                        final File leakedFile = managedFile.newRestartManagedFile("leaked share url - " + url).file().getAbsoluteFile();
                        final String uri = filesHelper.createUriForLocalPrivateFile(leakedFile);
                        return new ShareResult(uri, mimeType);
                    } finally {
                        managedFile.release();
                    }
                } finally {
                    body.close();
                }
            }
        });
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public String downloadAndStore(@Nonnull String id, @Nonnull String url) throws IOException {
        final ResponseBody responseBody = downloadBodyFromUrl(url);
        final ManagedFileDao.ManagedFile managedFile = writeToFile(null, responseBody);
        try {
            return managedFile.newRestartManagedFile("leaked file - " + id + ", " + url).file().getAbsolutePath();
        } finally {
            managedFile.release();
        }
    }

    @Nonnull
    public Observable<ManagedFileDao.ManagedFile> createTemporaryFileObservable(@Nullable final String extension, final @Nonnull String acquireName) {
        return Observable.defer(new Func0<Observable<ManagedFileDao.ManagedFile>>() {
            @Override
            public Observable<ManagedFileDao.ManagedFile> call() {
                try {
                    return Observable.just(createTemporaryFile(extension, acquireName));
                } catch (IOException e) {
                    return Observable.error(e);
                }
            }
        });
    }

    @Nonnull
    public ManagedFileDao.ManagedFile createTemporaryFile(@Nullable String extension, @Nonnull String acquireName) throws IOException {
        final File localPrivateDirectory = filesHelper.getLocalPrivateDirectory();
        final File file = File.createTempFile("managed", extension == null ? null : ("." + extension), localPrivateDirectory);
        return managedFileDao.manageFile(file, acquireName);
    }

    @Nonnull
    public String localOrRemotePath(@Nonnull String directoryName, @Nullable String id, @Nonnull String remoteUrl) {
        if (id == null || id.length() <= 0) return remoteUrl;

        try {
            final File stickerFile = new File(filesHelper.getInternalStorageDirectoryWithName(directoryName), id);
            return stickerFile.exists()
                    ? stickerFile.getPath()
                    : remoteUrl;
        } catch (IOException e) {
            return remoteUrl;
        }
    }

    @Nonnull
    private ResponseBody downloadBodyFromUrl(@Nonnull String url) throws IOException {
        final Request request = new Request.Builder()
                .url(url)
                .build();
        final Response response = okHttpClient.newCall(request).execute();

        final boolean successful = response.isSuccessful();
        if (!successful) {
            throw new IOException("Could not download file");
        }
        return response.body();
    }

    @Nonnull
    private ManagedFileDao.ManagedFile writeToFile(@Nullable String extension, @Nonnull ResponseBody body) throws IOException {
        final ManagedFileDao.ManagedFile managedFile = createTemporaryFile(extension, "acquire for download share");
        try {
            final BufferedSink sink = Okio.buffer(Okio.sink(managedFile.file()));
            try {
                sink.writeAll(body.source());
                sink.flush();
                return managedFile.newManagedFile("temporary file - written");
            } finally {
                sink.close();
            }
        } finally {
            managedFile.release();
        }
    }

    @Nonnull
    public Observable<Object> downloadFileAndAddToGallery(@Nonnull final String url) {
        return downloadAndReturnFile(url)
                .flatMap(new Func1<File, Observable<Object>>() {
                    @Override
                    public Observable<Object> call(File file) {
                        return addToGallery(file);
                    }

                    @Nonnull
                    public Observable<Object> addToGallery(@Nonnull final File file) {
                        return Observable.fromCallable(new Callable<Object>() {
                            @Override
                            public Object call() throws Exception {
                                filesHelper.scanExternalStoragePublicFile(file);
                                return null;
                            }
                        });
                    }
                });
    }

    @Nonnull
    public Observable<File> downloadAndReturnFile(@Nonnull final String url) {
        return Observable.fromCallable(new Callable<File>() {
            @Override
            public File call() throws Exception {
                return downloadFileAndReturnIt(url);
            }

            @Nonnull
            File downloadFileAndReturnIt(@Nonnull String url) throws IOException {
                final ResponseBody body = downloadBodyFromUrl(url);
                try {
                    final MediaType mediaType = body.contentType();
                    final String extension = mimeTypeGetter.getExtensionFromMediaType(mediaType);
                    final File file = createTemporaryFile(filesHelper.getExternalStoragePublicDirectory(mediaType), extension);
                    writeToFileOrDeleteAndThrow(file, body);
                    return file;
                } finally {
                    body.close();
                }
            }
        });
    }


    private void writeToFileOrDeleteAndThrow(@Nonnull File file, @Nonnull ResponseBody body) throws IOException {
        try {
            final BufferedSink sink = Okio.buffer(Okio.sink(file));
            try {
                sink.writeAll(body.source());
            } finally {
                sink.close();
            }
        } catch (IOException e) {
            file.delete();
            throw e;
        }
    }


    @Nonnull
    private File createTemporaryFile(@Nonnull File directory, @Nullable String extension) throws IOException {
        return File.createTempFile(
                createImageFileName(),
                extension == null ? null : ("." + extension),
                directory);
    }

    @Nonnull
    private String createImageFileName() {
        final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        final String timeStamp = timestampFormat.format(new Date());
        return "TMP_" + timeStamp + "_";
    }
}
