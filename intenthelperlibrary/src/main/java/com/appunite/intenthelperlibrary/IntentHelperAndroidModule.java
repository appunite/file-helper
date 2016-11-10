package com.appunite.intenthelperlibrary;


import android.webkit.MimeTypeMap;

import com.appunite.intenthelperlibrary.dao.ManagedFileDao;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import dagger.Module;
import dagger.Provides;
import okhttp3.MediaType;

@Module
class IntentHelperAndroidModule {

    @Nonnull
    @Provides
    MimeTypeGetter mimeTypeGetter() {
        return new MimeTypeGetter() {
            @Nullable
            @Override
            public String getExtensionFromMediaType(@Nonnull MediaType mediaType) {
                return MimeTypeMap.getSingleton().getExtensionFromMimeType(mediaType.type() + "/" + mediaType.subtype());
            }
        };
    }

    @Nonnull
    @Provides
    com.appunite.intenthelperlibrary.FilesHelper filesHelper(@Nonnull FilesHelperImpl impl) {
        return impl;
    }

    @Nonnull
    @Provides
    com.appunite.intenthelperlibrary.dao.ManagedFileDao.FileOperations fileOperations(@Nonnull ManagedFileDao.FileOperationsImpl impl) {
        return impl;
    }

}
