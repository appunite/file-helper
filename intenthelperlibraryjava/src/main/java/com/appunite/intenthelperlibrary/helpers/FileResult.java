package com.appunite.intenthelperlibrary.helpers;


import com.appunite.intenthelperlibrary.dao.ManagedFileDao;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FileResult {
    @Nonnull
    private final ManagedFileDao.ManagedFile managedFile;
    @Nonnull
    private final String mimeType;
    @Nullable
    private final String fileName;

    public FileResult(@Nonnull ManagedFileDao.ManagedFile file,
               @Nonnull String mimeType,
               @Nullable String fileName) {
        this.managedFile = file;
        this.mimeType = mimeType;
        this.fileName = fileName;
    }

    @Nonnull
    public ManagedFileDao.ManagedFile managedFile() {
        return managedFile;
    }

    @Nonnull
    public String mimeType() {
        return mimeType;
    }

    @Nullable
    public String fileName() {
        return fileName;
    }

    @Nonnull
    public FileResult newRestartManagedFile(@Nonnull String acquireName) {
        return new FileResult(managedFile.newRestartManagedFile(acquireName), mimeType, fileName);
    }

    @Nonnull
    public FileResult newManagedFile(@Nonnull String acquireName) {
        return new FileResult(managedFile.newManagedFile(acquireName), mimeType, fileName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileResult)) return false;

        final FileResult that = (FileResult) o;

        return managedFile.equals(that.managedFile) && mimeType.equals(that.mimeType) && (fileName != null ? fileName.equals(that.fileName) : that.fileName == null);

    }

    @Override
    public int hashCode() {
        int result = managedFile.hashCode();
        result = 31 * result + mimeType.hashCode();
        result = 31 * result + (fileName != null ? fileName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("FileResult{");
        sb.append("managedFile=").append(managedFile);
        sb.append(", mimeType='").append(mimeType).append('\'');
        sb.append(", fileName='").append(fileName).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
