package com.appunite.intenthelperlibrary

import com.appunite.intenthelperlibrary.dao.ManagedFileDao
import com.appunite.keyvalue.KeyValueMemory
import rx.schedulers.Schedulers
import rx.schedulers.TestScheduler
import spock.lang.Specification

import java.util.concurrent.TimeUnit

class ManagedFileDaoSpec extends Specification {

    def LONG_TIME = 1024*1024
    def SHORT_TIME = 1
    TestScheduler testScheduler
    ManagedFileDao managedFileDao
    private ManagedFileDao.FileOperations fileOperations
    private KeyValueMemory keyValueMemory

    void setup() {
        testScheduler = Schedulers.test();
        fileOperations = Mock(ManagedFileDao.FileOperations)
        keyValueMemory = new KeyValueMemory()
        managedFileDao = createManagedDao();
    }

    private ManagedFileDao createManagedDao() {
        new ManagedFileDao(testScheduler, keyValueMemory, fileOperations)
    }

    def "assert no crash during default behavior"() {
        setup:
        def file = mockFile("file.txt")

        when:
        def managedFile = managedFileDao.manageFile(file, "file from intent");
        def restartManagedFile = managedFile.newRestartManagedFile("for task");
        managedFile.release();

        def somewhereStoreFileId;
        def managedFileIdForTask = restartManagedFile.managedFileId();

        def managedFileInTask = managedFileDao.receiveRestartManagedFile(managedFileIdForTask);
        // do upload
        somewhereStoreFileId = managedFileInTask.fileId();
        managedFileInTask.release();

        // bindViewHolder
        final ManagedFileDao.ManagedFile onBindFile = managedFileDao.findAndAcquireManagedFileIfExists(somewhereStoreFileId, "for glide");
        if (onBindFile != null) {
            // onBindFile.file() for glide
            // releaseViewHolder
            onBindFile.release();
        }

        then:
        assert true
    }

    def "when managing file, file is correct"() {
        when:
        def managedFile = managedFileDao.manageFile(mockFile("/file.txt"), "info")

        then:
        assert managedFile != null
        assert managedFile.file() != null
        assert managedFile.file().absolutePath == "/file.txt"
    }

    def "assert managed file release, delete is called"() {
        setup:
        def file = mockFile("file.txt")
        def managedFile = managedFileDao.manageFile(file, "info")

        when:
        managedFile.release()
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        managedFileDao.removeOldFiles()

        then:
        1 * fileOperations.removeFile("file.txt")
    }

    def "assert managed file release, do not delete immediately"() {
        setup:
        def file = mockFile("file.txt")
        def managedFile = managedFileDao.manageFile(file, "info")

        when:
        managedFile.release()
        testScheduler.advanceTimeBy(SHORT_TIME, TimeUnit.HOURS)
        managedFileDao.removeOldFiles()

        then:
        0 * fileOperations.removeFile("file.txt")
    }

    def "assert managed file acquired twice, delete after second call"() {
        setup:
        def file = mockFile("file.txt")
        def managedFile1 = managedFileDao.manageFile(file, "info")
        def managedFile2 = managedFile1.newManagedFile("info2")

        when:
        managedFile1.release()
        managedFile2.release()
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        managedFileDao.removeOldFiles()

        then:
        1 * fileOperations.removeFile("file.txt")
    }

    def "assert managed file acquired twice, if one1 is released, don't delete"() {
        setup:
        def file = mockFile("file.txt")
        def managedFile1 = managedFileDao.manageFile(file, "info")
        def managedFile2 = managedFile1.newManagedFile("info2")

        when:
        managedFile1.release()
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        managedFileDao.removeOldFiles()

        then:
        0 * fileOperations.removeFile("file.txt")
    }

    def "assert managed file acquired twice, if one2 is released, don't delete"() {
        setup:
        def file = mockFile("file.txt")
        def managedFile1 = managedFileDao.manageFile(file, "info")
        def managedFile2 = managedFile1.newManagedFile("info2")

        when:
        managedFile2.release()
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        managedFileDao.removeOldFiles()

        then:
        0 * fileOperations.removeFile("file.txt")
    }

    def "assert after restart, managed file is deleted1"() {
        setup:
        def file = mockFile("file.txt")
        managedFileDao.manageFile(file, "info")

        when:
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        createManagedDao().removeOldFiles()

        then:
        1 * fileOperations.removeFile("file.txt")
    }

    def "assert after restart, managed file is deleted2"() {
        setup:
        def file = mockFile("file.txt")
        managedFileDao.manageFile(file, "info")
                .newManagedFile("new info")
                .release()

        when:
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        createManagedDao().removeOldFiles()

        then:
        1 * fileOperations.removeFile("file.txt")
    }

    def "assert after restart, restart managed file is not deleted"() {
        setup:
        def file = mockFile("file.txt")
        managedFileDao.manageFile(file, "info")
                .newRestartManagedFile("new info")

        when:
        testScheduler.advanceTimeBy(LONG_TIME, TimeUnit.HOURS)
        createManagedDao().removeOldFiles()

        then:
        0 * fileOperations.removeFile("file.txt")
    }

    def "assert managed file release twice throws exception"() {
        setup:
        def managedFile = managedFileDao.manageFile(mockFile("file.txt"), "info")
        managedFile.release()

        when:
        managedFile.release()

        then:
        thrown IllegalStateException
    }

    def "when trying manage already managed file, throw exception"() {
        setup:
        managedFileDao.manageFile(mockFile("file.txt"), "info")

        when:
        managedFileDao.manageFile(mockFile("file.txt"), "info")


        then:
        def ex = thrown(IllegalStateException)
        ex.message == "File already managed"
    }

    def "when trying manage restart managed file, throw exception"() {
        setup:
        def file = managedFileDao.manageFile(mockFile("file.txt"), "info")
        file.newRestartManagedFile("restart managed")
        file.release()

        when:
        managedFileDao.manageFile(mockFile("file.txt"), "info")

        then:
        def ex = thrown(IllegalStateException)
        ex.message == "File already managed"
    }

    def "when trying to manage file without info, throw exception"() {
        when:
        managedFileDao.manageFile(mockFile("file.txt"), null)

        then:
        def ex = thrown(NullPointerException)
        ex.message == "AcquireName need to be setup"
    }

    def "when trying to manage null file, throw exception"() {
        when:
        managedFileDao.manageFile(null, "info")

        then:
        def ex = thrown(NullPointerException)
        ex.message == "File need to be setup"
    }

    private File mockFile(String path) {
        Mock(File) {
            getAbsolutePath() >> path
        }
    }

}
