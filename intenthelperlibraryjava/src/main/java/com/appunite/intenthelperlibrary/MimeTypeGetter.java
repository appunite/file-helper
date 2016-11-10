package com.appunite.intenthelperlibrary;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.MediaType;

interface MimeTypeGetter {
    /**
     * Return null if unknown
     */
    @Nullable
    String getExtensionFromMediaType(@Nonnull MediaType mediaType);
}
