package com.appunite.intenthelperlibrary.helpers;


import javax.annotation.Nonnull;

public class ShareResult {
    @Nonnull
    private final String uri;
    @Nonnull
    private final String mimeType;

    public ShareResult(@Nonnull String uri, @Nonnull String mimeType) {
        this.uri = uri;
        this.mimeType = mimeType;
    }

    @Nonnull
    public String uri() {
        return uri;
    }

    @Nonnull
    public String mimeType() {
        return mimeType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShareResult)) return false;

        final ShareResult that = (ShareResult) o;

        return uri.equals(that.uri) && mimeType.equals(that.mimeType);

    }

    @Override
    public int hashCode() {
        int result = uri.hashCode();
        result = 31 * result + mimeType.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ShareResult{" +
                "uri=" + uri +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }
}