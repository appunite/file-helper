package com.appunite.intenthelperlibrary.dao;


import com.appunite.intenthelperlibrary.snappy.files.Message;
import com.appunite.keyvalue.IdGenerator;
import com.appunite.keyvalue.KeyGenerator;
import com.appunite.keyvalue.KeyValue;
import com.appunite.keyvalue.NotFoundException;
import com.appunite.rx.dagger.NetworkScheduler;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import rx.Scheduler;

@Singleton
public class ManagedFileDao {

    private static final long EXPIRATION_DURATION_IN_MILLIS = 1000L * 60L * 60L * 2L; // mills, seconds, minutes, 2 = 2 hours

    public static class FileOperationsImpl implements FileOperations {

        @Inject
        FileOperationsImpl() {
        }

        @Override
        public void removeFile(@Nonnull String path) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }
    }

    public interface FileOperations {
        void removeFile(@Nonnull String path);
    }

    private static class FileDatabase {

        @Nonnull
        private final KeyValue keyValue;
        @Nonnull
        private final FileOperations fileOperations;
        @Nonnull
        private final Map<ByteString, Map<ByteString, Message.AcquireMessage>> volotailAcqurisions = new HashMap<>(); // fileId -> (acquireId -> acquire name)
        @Nonnull
        private final IdGenerator idGenerator = new IdGenerator();

        FileDatabase(@Nonnull KeyValue keyValue, @Nonnull FileOperations fileOperations) {
            this.keyValue = keyValue;
            this.fileOperations = fileOperations;
        }

        class FileEntry {

            @Nonnull
            private final ByteString fileId;
            @Nonnull
            private final String path;

            FileEntry(@Nonnull ByteString fileId, @Nonnull String path) {
                this.fileId = fileId;
                this.path = path;
            }

            @Nonnull
            ByteString fileId() {
                return fileId;
            }

            @Nonnull
            String path() {
                return path;
            }

            @Nonnull
            ByteString acquireRestart(@Nonnull String acquireName) {
                synchronized (FileDatabase.this) {
                    final Message.AcquireMessage acquireMessage = newAcquire(acquireName);
                    final ByteString acquireKey = getAcquireKey(acquireMessage.getAcquireId());
                    keyValue.put(acquireKey, acquireMessage.toByteString());
                    keyValue.put(getAcquirePathIndex(acquireMessage), acquireKey);
                    return acquireMessage.getAcquireId();
                }
            }

            void releaseRestart(@Nonnull ByteString acquireId) {
                synchronized (FileDatabase.this) {
                    try {
                        final Message.AcquireMessage acquireMessage = Message.AcquireMessage.parseFrom(keyValue.getBytes(getAcquireKey(acquireId)));
                        keyValue.del(getAcquirePathIndex(acquireMessage));
                        keyValue.del(getAcquireKey(acquireMessage.getAcquireId()));
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException("Wrong database state");
                    } catch (NotFoundException e) {
                        throw new IllegalStateException("Acquire does not exist");
                    }
                }
            }

            @Nonnull
            ByteString acquireVolatile(@Nonnull String acquireName) {
                synchronized (FileDatabase.this) {
                    final ByteString acquire = idGenerator.newId();
                    final Map<ByteString, Message.AcquireMessage> volotailAcquirisions = getVolatileAcqusitions();
                    volotailAcquirisions.put(acquire, newAcquire(acquireName));
                    return acquire;
                }
            }

            void releaseVolatile(@Nonnull ByteString acquireId) {
                synchronized (FileDatabase.this) {
                    final Map<ByteString, Message.AcquireMessage> volotailAcquirisions = getVolatileAcqusitions();
                    final Message.AcquireMessage remove = volotailAcquirisions.remove(acquireId);
                    if (remove == null) {
                        throw new IllegalStateException("Already released");
                    }
                }
            }

            @Nonnull
            private Message.AcquireMessage newAcquire(@Nonnull String acquireName) {
                return Message.AcquireMessage.newBuilder()
                        .setFileId(fileId)
                        .setAcquireId(idGenerator.newId())
                        .setAcquireName(acquireName)
                        .build();
            }

            public void removeIfNonAcquired(long nowInMillis) {
                removeIfNoneAcquired(fileId, nowInMillis);
            }

            @Nonnull
            Map<ByteString, Message.AcquireMessage> getVolatileAcqusitions() {
                return FileDatabase.this.getVolatileAcquisitions(fileId);
            }

            @Nonnull
            Map<ByteString, Message.AcquireMessage> getRestartAcquisitions() {
                return FileDatabase.this.getRestartAcquisitions(fileId);
            }

        }

        private synchronized void removeIfNoneAcquired(@Nonnull ByteString fileId, long nowInMillis) {
            final Map<ByteString, Message.AcquireMessage> volotailAcquirisions = getVolatileAcquisitions(fileId);
            if (!volotailAcquirisions.isEmpty()) {
                return;
            }
            final Map<ByteString, Message.AcquireMessage> restartAcquirisions = getRestartAcquisitions(fileId);
            if (!restartAcquirisions.isEmpty()) {
                return;
            }
            try {
                final Message.FileEntryMessage message = Message.FileEntryMessage.parseFrom(keyValue.getBytes(getManagedFileKey(fileId)));
                if (message.getExpirationTimeInMillis() != 0 && nowInMillis < message.getExpirationTimeInMillis()) {
                    return;
                }

                fileOperations.removeFile(message.getPath());
                keyValue.del(getManagedFileKey(message.getFileId()));
                keyValue.del(getManagedFilePathIndex(message));
                keyValue.del(getManagedFileAllIndex(message));

            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException("database issue");
            } catch (NotFoundException ignore) {
                // Already removed
            }
        }

        @Nonnull
        private synchronized Map<ByteString, Message.AcquireMessage> getRestartAcquisitions(ByteString fileId) {
            final ByteString prefix = getAcquirePathPrefix(fileId);
            ByteString nextTokenOrNull = null;
            final Map<ByteString, Message.AcquireMessage> objects = new HashMap<>();
            for (; ; ) {

                final KeyValue.Iterator iterator = keyValue.getKeys(prefix, nextTokenOrNull, 100);
                final List<ByteString> keys = iterator.keys();
                for (ByteString key : keys) {
                    try {
                        final Message.AcquireMessage message = Message.AcquireMessage.parseFrom(keyValue.getBytes(key));
                        objects.put(message.getAcquireId(), message);
                    } catch (InvalidProtocolBufferException | NotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
                nextTokenOrNull = iterator.nextToken();
                if (nextTokenOrNull == null) {
                    break;
                }
            }
            return objects;
        }

        @Nonnull
        public synchronized ArrayList<FileEntry> getFileEntries() {
            final ByteString prefix = getManagedFileAllPrefx();
            ByteString nextTokenOrNull = null;
            final ArrayList<FileEntry> objects = new ArrayList<>();
            for (; ; ) {

                final KeyValue.Iterator iterator = keyValue.getKeys(prefix, nextTokenOrNull, 100);
                final List<ByteString> keys = iterator.keys();
                for (ByteString key : keys) {
                    try {
                        final Message.FileEntryMessage message = Message.FileEntryMessage.parseFrom(keyValue.getBytes(key));
                        objects.add(new FileEntry(message.getFileId(), message.getPath()));
                    } catch (InvalidProtocolBufferException | NotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
                nextTokenOrNull = iterator.nextToken();
                if (nextTokenOrNull == null) {
                    break;
                }
            }
            return objects;
        }

        @Nonnull
        public synchronized FileEntry fileEntryByManagedAcquireId(@Nonnull ByteString acquireId) throws IllegalStateException {
            try {
                final Message.AcquireMessage acquireMessage;
                try {
                    acquireMessage = Message.AcquireMessage.parseFrom(keyValue.getBytes(getAcquireKey(acquireId)));
                } catch (NotFoundException e) {
                    throw new IllegalStateException("Acquire does not exist");
                }
                try {
                    final Message.FileEntryMessage fileEntryMessage = Message.FileEntryMessage.parseFrom(keyValue.getBytes(getManagedFileKey(acquireMessage.getFileId())));
                    return new FileEntry(fileEntryMessage.getFileId(), fileEntryMessage.getPath());
                } catch (NotFoundException e) {
                    throw new RuntimeException("Can not read from database");
                }
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException("Can not read from database");
            }
        }

        @Nonnull
        public synchronized FileEntry create(@Nonnull String path, long expirationTimeInMillis) {
            try {
                keyValue.getBytes(getManagedFilePathPrefix(path));
                throw new IllegalStateException("File already managed");
            } catch (NotFoundException ignore) {
            }
            final ByteString fileId = idGenerator.newId();
            final Message.FileEntryMessage message = Message.FileEntryMessage.newBuilder()
                    .setFileId(fileId)
                    .setPath(path)
                    .setExpirationTimeInMillis(expirationTimeInMillis)
                    .build();
            final ByteString keyId = getManagedFileKey(fileId);
            keyValue.put(keyId, message.toByteString());
            keyValue.put(getManagedFilePathIndex(message), keyId);
            keyValue.put(getManagedFileAllIndex(message), keyId);
            return new FileEntry(fileId, path);
        }

        @Nullable
        public synchronized FileEntry fileEntryByFileId(@Nonnull ByteString fileId) {
            try {
                Message.FileEntryMessage message = Message.FileEntryMessage.parseFrom(keyValue.getBytes(getManagedFileKey(fileId)));
                return new FileEntry(message.getFileId(), message.getPath());
            } catch (NotFoundException e) {
                return null;
            } catch (InvalidProtocolBufferException e) {
                return null;
            }
        }


        @Nonnull
        private synchronized Map<ByteString, Message.AcquireMessage> getVolatileAcquisitions(@Nonnull ByteString fileId) {
            final Map<ByteString, Message.AcquireMessage> elements = volotailAcqurisions.get(fileId);
            if (elements != null) {
                return elements;
            }
            final HashMap<ByteString, Message.AcquireMessage> newElements = new HashMap<>();
            volotailAcqurisions.put(fileId, newElements);
            return newElements;
        }


        private static final byte[] ACQUIRE = "managed_file_acquire".getBytes();
        private static final byte[] ACQUIRE_ORDER_FILE = "managed_file_acquire_order_file".getBytes();
        private static final byte[] ACQUIRE_ORDER_FILE_INDEX_FILE_ID = "file_id".getBytes();
        private static final byte[] MANAGED_FILE = "managed_file".getBytes();
        private static final byte[] MANAGED_FILE_PATH = "managed_file_path".getBytes();
        private static final byte[] MANAGED_FILE_ALL = "manged_file_all".getBytes();

        @Nonnull
        private final KeyGenerator keyGenerator = new KeyGenerator();

        @Nonnull
        private synchronized ByteString getAcquireKey(@Nonnull ByteString acquireId) {
            return keyGenerator.value(ACQUIRE, acquireId);
        }

        @Nonnull
        private synchronized ByteString getAcquirePathPrefix(@Nonnull ByteString fileId) {
            return keyGenerator.startIndex(ACQUIRE_ORDER_FILE)
                    .addField(ACQUIRE_ORDER_FILE_INDEX_FILE_ID, fileId)
                    .buildQuery();
        }

        @Nonnull
        private synchronized ByteString getAcquirePathIndex(@Nonnull Message.AcquireMessage acquireMessage) {
            return keyGenerator.startIndex(ACQUIRE_ORDER_FILE)
                    .addField(ACQUIRE_ORDER_FILE_INDEX_FILE_ID, acquireMessage.getFileId())
                    .buildIndex(acquireMessage.getAcquireId());
        }

        @Nonnull
        private synchronized ByteString getManagedFileKey(@Nonnull ByteString fileId) {
            return keyGenerator.value(MANAGED_FILE, fileId);
        }

        @Nonnull
        private synchronized ByteString getManagedFilePathPrefix(@Nonnull String path) {
            return keyGenerator.value(MANAGED_FILE_PATH, ByteString.copyFromUtf8(path));
        }

        @Nonnull
        private synchronized ByteString getManagedFilePathIndex(@Nonnull Message.FileEntryMessage message) {
            return keyGenerator.value(MANAGED_FILE_PATH, ByteString.copyFromUtf8(message.getPath()));
        }

        @Nonnull
        private synchronized ByteString getManagedFileAllIndex(@Nonnull Message.FileEntryMessage message) {
            return keyGenerator.startIndex(MANAGED_FILE_ALL)
                    .buildIndex(message.getFileId());
        }

        @Nonnull
        private synchronized ByteString getManagedFileAllPrefx() {
            return keyGenerator.startIndex(MANAGED_FILE_ALL)
                    .buildQuery();
        }
    }

    public interface RestartManagedFile extends ManagedFile {

        /**
         * Id od managed id for restoring after restart
         *
         * @return managed file id
         */
        @Nonnull
        ByteString managedFileId();
    }

    public static class FakeManagedFile implements RestartManagedFile {

        @Nonnull
        private final String fileName;
        @Nonnull
        private final String acquireName;
        @Nonnull
        private final AtomicInteger counter;
        private boolean released = false;

        private FakeManagedFile(@Nonnull String fileName, @Nonnull String acquireName, @Nonnull AtomicInteger counter) {
            this.fileName = fileName;
            this.acquireName = acquireName;
            this.counter = counter;
        }

        public FakeManagedFile(@Nonnull String fileName, @Nonnull String acquireName) {
            this(fileName, acquireName, new AtomicInteger(1));
        }

        public FakeManagedFile(@Nonnull String fileName) {
            this(fileName, "test");
        }

        @Nonnull
        @Override
        public ByteString managedFileId() {
            checkIfNotReleased();
            return ByteString.copyFromUtf8(fileName + ":" + acquireName);
        }

        @Nonnull
        @Override
        public ByteString fileId() {
            checkIfNotReleased();
            return ByteString.copyFromUtf8(fileName);
        }

        @Nonnull
        @Override
        public File file() throws IllegalStateException {
            checkIfNotReleased();
            return new File(fileName);
        }

        @Override
        public void release() throws IllegalStateException {
            checkIfNotReleased();
            released = true;
            if (counter.decrementAndGet() < 0) {
                throw new IllegalStateException("Under counted");
            }
        }

        private void checkIfNotReleased() throws IllegalStateException {
            if (released) {
                throw new IllegalStateException("Already released");
            }
            if (counter.get() <= 0) {
                throw new IllegalStateException("Already released all");
            }
        }

        @Nonnull
        @Override
        public RestartManagedFile newRestartManagedFile(@Nonnull String acquireName) throws IllegalStateException {
            checkIfNotReleased();
            counter.incrementAndGet();
            return new FakeManagedFile(fileName, acquireName, counter);
        }

        @Nonnull
        @Override
        public ManagedFile newManagedFile(@Nonnull String acquireName) throws IllegalStateException {
            checkIfNotReleased();
            counter.incrementAndGet();
            return new FakeManagedFile(fileName, acquireName, counter);
        }
    }

    abstract class BaseAdvancedManagedFile implements ManagedFile {

        @Nonnull
        protected final FileDatabase.FileEntry fileEntry;
        private boolean released = false;

        public BaseAdvancedManagedFile(@Nonnull FileDatabase.FileEntry fileEntry) {
            this.fileEntry = fileEntry;
        }

        @Nonnull
        @Override
        public ByteString fileId() {
            return fileEntry.fileId();
        }

        @Nonnull
        @Override
        public File file() throws IllegalStateException {
            checkIfReleased();
            return new File(fileEntry.path());
        }

        private void checkIfReleased() {
            if (released) {
                throw new IllegalStateException("Already released");
            }
        }

        @Override
        public void release() throws IllegalStateException {
            checkIfReleased();
            released = true;
            releaseWithoutCheck();
        }

        protected abstract void releaseWithoutCheck();

        @Nonnull
        @Override
        public RestartManagedFile newRestartManagedFile(@Nonnull String acquireName) throws IllegalStateException {
            return new AdvancedRestartManagedFile(fileEntry, fileEntry.acquireRestart(acquireName));
        }

        @Nonnull
        @Override
        public ManagedFile newManagedFile(@Nonnull String acquireName) throws IllegalStateException {
            return new AdvancedVolatileManagedFile(fileEntry, fileEntry.acquireVolatile(acquireName));
        }
    }

    class AdvancedRestartManagedFile extends BaseAdvancedManagedFile implements RestartManagedFile {

        @Nonnull
        private final ByteString acquireId;

        AdvancedRestartManagedFile(@Nonnull FileDatabase.FileEntry fileEntry, @Nonnull ByteString acquire) {
            super(fileEntry);
            acquireId = acquire;
        }

        @Nonnull
        @Override
        public ByteString managedFileId() {
            return acquireId;
        }

        @Override
        protected void releaseWithoutCheck() {
            fileEntry.releaseRestart(acquireId);
        }
    }

    class AdvancedVolatileManagedFile extends BaseAdvancedManagedFile {

        @Nonnull
        private final ByteString acquireId;

        AdvancedVolatileManagedFile(@Nonnull FileDatabase.FileEntry fileEntry, @Nonnull ByteString acquire) {
            super(fileEntry);
            acquireId = acquire;
        }

        @Override
        protected void releaseWithoutCheck() {
            fileEntry.releaseVolatile(acquireId);
        }
    }

    public interface ManagedFile {

        /**
         * Id of file
         *
         * @return id of file
         */
        @Nonnull
        ByteString fileId();

        /**
         * Get file
         *
         * @return file for managed file
         * @throws IllegalStateException if called after release()
         */
        @Nonnull
        File file() throws IllegalStateException;

        /**
         * Release ManagedFile
         * <p>
         * After calling this method you can not receive file
         *
         * @throws IllegalStateException if already release
         */
        void release() throws IllegalStateException;

        /**
         * Acquire managed file that will be kept after restarts
         *
         * @param acquireName name of acquire - for debugging
         * @return restart managed file
         * @throws IllegalStateException if called after release()
         */
        @Nonnull
        RestartManagedFile newRestartManagedFile(@Nonnull String acquireName) throws IllegalStateException;

        /**
         * Acquire managed file
         *
         * @param acquireName name of acquire - for debugging
         * @return restart managed file
         * @throws IllegalStateException if called after release()
         */
        @Nonnull
        ManagedFile newManagedFile(@Nonnull String acquireName) throws IllegalStateException;
    }

    @Nonnull
    private final Scheduler networkScheduler;
    @Nonnull
    private final FileDatabase fileDatabase;

    @Inject
    public ManagedFileDao(@Nonnull @NetworkScheduler Scheduler networkScheduler,
                          @Nonnull KeyValue keyValue,
                          @Nonnull FileOperations fileOperations) {
        this.networkScheduler = networkScheduler;
        this.fileDatabase = new FileDatabase(keyValue, fileOperations);
    }

    /**
     * Restore `ManagedFile` after crash
     *
     * @param managedFileId id of previously received `ManagedFile`
     * @return restored managed file
     * @throws IllegalStateException if does not exists
     */
    @Nonnull
    public RestartManagedFile receiveRestartManagedFile(@Nonnull ByteString managedFileId) throws IllegalStateException {
        //noinspection ConstantConditions
        if (managedFileId == null) {
            throw new NullPointerException("ManagedFileId need to be setup");
        }
        final FileDatabase.FileEntry fileEntry = fileDatabase.fileEntryByManagedAcquireId(managedFileId);
        return new AdvancedRestartManagedFile(fileEntry, managedFileId);
    }


    /**
     * Try to find file and acquire it
     *
     * @param fileId      fileId of file
     * @param acquireName acquire name - for debugging
     * @return managed file or null if file does not exists (was removed from cache)
     */
    @Nullable
    public ManagedFile findAndAcquireManagedFileIfExists(@Nonnull ByteString fileId, @Nonnull String acquireName) {
        //noinspection ConstantConditions
        if (fileId == null) {
            throw new NullPointerException("FileId need to be setup");
        }
        //noinspection ConstantConditions
        if (fileId == null) {
            throw new NullPointerException("AcquireName need to be setup");
        }
        final FileDatabase.FileEntry fileEntry = fileDatabase.fileEntryByFileId(fileId);
        if (fileEntry == null) {
            return null;
        }
        return new AdvancedVolatileManagedFile(fileEntry, fileEntry.acquireVolatile(acquireName));
    }

    /**
     * Start managing this file
     * <p>
     * After calling this method you should immediately stop using this file directly.
     * Be sure that file is safely stored
     *
     * @param file to manage
     * @return managed file
     */
    @Nonnull
    public ManagedFile manageFile(@Nonnull File file, @Nonnull String acquireName) {
        return manageFile(file, acquireName, networkScheduler.now() + EXPIRATION_DURATION_IN_MILLIS);
    }

    /**
     * Start managing this file
     * <p>
     * After calling this method you should immediately stop using this file directly.
     * Be sure that file is safely stored
     *
     * @param file to manage
     * @return managed file
     */
    @Nonnull
    public ManagedFile manageFile(@Nonnull File file, @Nonnull String acquireName, long expirationTimeInMillis) {
        //noinspection ConstantConditions
        if (file == null) {
            throw new NullPointerException(("File need to be setup"));
        }
        //noinspection ConstantConditions
        if (acquireName == null) {
            throw new NullPointerException("AcquireName need to be setup");
        }
        final FileDatabase.FileEntry fileEntry = fileDatabase.create(file.getAbsolutePath(), expirationTimeInMillis);
        return new AdvancedVolatileManagedFile(fileEntry, fileEntry.acquireVolatile(acquireName));
    }

    /**
     * Remove old and not persistent files from cache
     */
    public void removeOldFiles() {
        final long nowInMillis = networkScheduler.now();
        final ArrayList<FileDatabase.FileEntry> fileEntries = fileDatabase.getFileEntries();
        for (FileDatabase.FileEntry fileEntry : fileEntries) {
            fileEntry.removeIfNonAcquired(nowInMillis);
        }
    }

    @Nonnull
    public String debugPurposeListAllFiles() {
        final StringBuilder sb = new StringBuilder();
        final ArrayList<FileDatabase.FileEntry> fileEntries = fileDatabase.getFileEntries();
        for (FileDatabase.FileEntry fileEntry : fileEntries) {
            sb.append("File: ").append(fileEntry.path()).append("\n");
            if (!fileEntry.getRestartAcquisitions().isEmpty()) {
                sb.append("Acquisition (restart):\n");
                for (Message.AcquireMessage acquire : fileEntry.getRestartAcquisitions().values()) {
                    sb.append(" - ");
                    sb.append(acquire.getAcquireName());
                    sb.append("\n");
                }
            }
            if (!fileEntry.getVolatileAcqusitions().isEmpty()) {
                sb.append("Acquisition (Volatile):");
                for (Message.AcquireMessage acquire : fileEntry.getVolatileAcqusitions().values()) {
                    sb.append(" - ");
                    sb.append(acquire.getAcquireName());
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ManagedFileDao{" +
                debugPurposeListAllFiles() +
                '}';
    }
}