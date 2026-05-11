package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class TransactionManager {

    private final DBManager dbManager;
    private Path transactionSnapshot;
    private Map<String, Integer> transactionFilePages;
    private final List<SavepointSnapshot> savepoints = new ArrayList<>();


    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }


    public void begin() throws DBException {
        if (transactionSnapshot != null) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        transactionFilePages = new HashMap<>(dbManager.getDiskManager().filePages);
        savepoints.clear();
        // REVIEW: This transaction manager uses directory snapshots instead of
        // write-ahead logging, so it is suitable for this teaching engine but not
        // for concurrent or large databases.
    }


    public void commit() throws DBException {
        dbManager.persistRuntimeState();
        cleanupSnapshot(transactionSnapshot);
        transactionSnapshot = null;
        transactionFilePages = null;
        cleanupSavepoints();
    }


    public void rollback() throws DBException {
        if (transactionSnapshot == null) {
            return;
        }
        restoreSnapshot(transactionSnapshot, transactionFilePages);
        cleanupSnapshot(transactionSnapshot);
        transactionSnapshot = null;
        transactionFilePages = null;
        cleanupSavepoints();
    }


    public void savepoint(String savepointName) throws DBException {
        requireTransaction();
        savepoints.add(new SavepointSnapshot(
                savepointName,
                createSnapshot(),
                new HashMap<>(dbManager.getDiskManager().filePages)));
    }


    public void rollbackToSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        SavepointSnapshot savepoint = savepoints.get(index);
        restoreSnapshot(savepoint.snapshotPath, savepoint.filePages);
        cleanupSavepointsAfter(index);
    }


    public void releaseSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        cleanupSnapshot(savepoints.get(index).snapshotPath);
        savepoints.remove(index);
    }

    private Path createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        Path snapshotDir;
        try {
            snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        return snapshotDir;
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void restoreSnapshot(Path snapshotDir, Map<String, Integer> filePages) throws DBException {
        try {
            dbManager.getBufferPool().DiscardAllPages();
            deleteDirectoryContents(getDbRoot());
            copyDirectoryContents(snapshotDir, getDbRoot());
            dbManager.getDiskManager().filePages.clear();
            dbManager.getDiskManager().filePages.putAll(filePages);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private void requireTransaction() throws DBException {
        if (transactionSnapshot == null) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
    }

    private int findLatestSavepoint(String savepointName) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name.equals(savepointName)) {
                return i;
            }
        }
        return -1;
    }

    private void cleanupSavepointsAfter(int index) throws DBException {
        for (int i = savepoints.size() - 1; i > index; i--) {
            cleanupSnapshot(savepoints.get(i).snapshotPath);
            savepoints.remove(i);
        }
    }

    private void cleanupSavepoints() throws DBException {
        for (SavepointSnapshot savepoint : savepoints) {
            cleanupSnapshot(savepoint.snapshotPath);
        }
        savepoints.clear();
    }

    private void cleanupSnapshot(Path snapshotDir) throws DBException {
        if (snapshotDir == null) {
            return;
        }
        try {
            deleteDirectory(snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record SavepointSnapshot(String name, Path snapshotPath, Map<String, Integer> filePages) {
    }
}
