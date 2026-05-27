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


/**
 * 事务管理器 — Q&A 必问（Task 4 全部 8 分）：
 * 1. BEGIN 时如何设计快照 (snapshot)
 * 2. COMMIT 时在物理和逻辑层面发生了什么
 * 3. SAVEPOINT 和 ROLLBACK 的设计
 *
 * 整体设计：基于目录快照的事务（非 WAL 日志）
 * - BEGIN: 复制整个数据库目录到临时目录 → 事务快照
 * - ROLLBACK: 删除当前数据目录 → 把快照目录复制回来
 * - COMMIT: 当前数据已经实时落盘，只需清理快照
 * - SAVEPOINT: 在事务中再创建一个快照点（支持嵌套回滚）
 *
 * 跨页快照 vs WAL：
 * 本实现使用整个目录的文件复制作为快照，适合教学场景的单用户数据库。
 * 生产级数据库使用 Write-Ahead Logging (WAL)，记录操作日志而非整页快照。
 */
public class TransactionManager {

    private final DBManager dbManager;

    /**
     * 事务快照路径 — BEGIN 时创建的数据库目录副本。
     * null 表示当前没有活跃事务。
     */
    private Path transactionSnapshot;

    /**
     * BEGIN 时的 filePages 元数据快照。
     * filePages 记录每个数据文件的页数，回滚时需要恢复到 BEGIN 时的值。
     */
    private Map<String, Integer> transactionFilePages;

    /**
     * SAVEPOINT 栈，按创建顺序存储。
     * 同名 SAVEPOINT 不覆盖旧值，而是追加到栈尾（栈语义）。
     * findLatestSavepoint 从栈尾向前搜索，实现同名覆盖效果。
     */
    private final List<SavepointSnapshot> savepoints = new ArrayList<>();


    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }


    /**
     * BEGIN — 创建事务快照。
     *
     * Q&A 要点 "snapshot design"：
     * 1. persistRuntimeState() 确保当前状态落盘
     * 2. Files.createTempDirectory() 创建临时目录
     * 3. copyDirectoryContents() 把整个数据库文件树复制到临时目录
     *    — 包括 CS307-DB/ 下所有表的数据文件、元数据 JSON
     * 4. 保存 filePages 快照（记录每个文件的当前页数）
     *
     * 如果已有活跃事务则抛出 TransactionAlreadyActive。
     */
    public void begin() throws DBException {
        if (transactionSnapshot != null) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        transactionFilePages = new HashMap<>(dbManager.getDiskManager().filePages);
        savepoints.clear();
    }


    /**
     * COMMIT — 提交事务。
     *
     * Q&A 要点 "what happens in physical and logical structure"：
     * 物理层：数据已实时落盘（每次 INSERT/UPDATE/DELETE 都直接写磁盘），
     *   所以 COMMIT 不需要再写数据，只需 persistRuntimeState() 刷新 BufferPool
     * 逻辑层：清理所有快照目录，释放事务状态，savepoints 全部清除
     *
     * 事务外 COMMIT 是空操作（不报错）。
     */
    public void commit() throws DBException {
        if (transactionSnapshot == null) {
            return;  // 事务外 COMMIT：空操作
        }
        dbManager.persistRuntimeState();
        cleanupSnapshot(transactionSnapshot);
        transactionSnapshot = null;
        transactionFilePages = null;
        cleanupSavepoints();
    }


    /**
     * ROLLBACK — 回滚整个事务。
     *
     * 执行步骤：
     * 1. DiscardAllPages() — 丢弃 BufferPool 中所有脏页（它们可能包含未提交的修改）
     * 2. deleteDirectoryContents(dbRoot) — 删除当前数据库目录内容
     * 3. copyDirectoryContents(snapshot, dbRoot) — 从快照恢复 BEGIN 时的数据
     * 4. 恢复 filePages 元数据
     * 5. 清理所有 savepoints 和快照目录
     */
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


    /**
     * SAVEPOINT — 在事务中创建命名保存点。
     *
     * Q&A 要点 "design of savepoint"：
     * 每次调用创建完整的目录快照（和 BEGIN 一样的方式）。
     * 同名 SAVEPOINT 不覆盖，追加到栈尾。
     * rollbackToSavepoint 时从栈尾搜索最近匹配。
     *
     * 必须已在事务中（有活跃快照），否则抛出 TransactionRequired。
     */
    public void savepoint(String savepointName) throws DBException {
        requireTransaction();
        savepoints.add(new SavepointSnapshot(
                savepointName,
                createSnapshot(),
                new HashMap<>(dbManager.getDiskManager().filePages)));
    }


    /**
     * ROLLBACK TO SAVEPOINT — 回滚到指定保存点。
     *
     * Q&A 要点：
     * 1. 找到最近匹配的 savepoint（从栈尾向前搜索 → 栈语义）
     * 2. 恢复该保存点的快照（目录 + filePages）
     * 3. 清理该保存点之后的所有保存点（它们也被回滚了）
     *
     * 重要：目标 savepoint 本身不清除，可以反复回滚到它。
     */
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


    /**
     * RELEASE SAVEPOINT — 释放保存点（不影响数据状态）。
     *
     * 只删除快照目录和移除保存点记录，不修改数据库。
     * 释放后不能再 rollback 到该保存点。
     */
    public void releaseSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        cleanupSnapshot(savepoints.get(index).snapshotPath);
        savepoints.remove(index);
    }

    /**
     * 创建快照：先刷盘 → 创建临时目录 → 复制整个数据库目录。
     */
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

    /**
     * 递归复制目录内容。遍历源目录的所有文件和子目录，复制到目标目录。
     */
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

    /**
     * 恢复快照：丢弃 BufferPool → 清空数据库目录 → 从快照复制回数据 → 恢复 filePages。
     */
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

    /**
     * 从栈尾向前搜索匹配的保存点（同名取最新）。
     */
    private int findLatestSavepoint(String savepointName) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name.equals(savepointName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 清理指定位置之后的所有保存点（用于 ROLLBACK TO SAVEPOINT）。
     */
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

    /**
     * 保存点数据结构：名称 + 目录快照路径 + filePages 快照。
     */
    private record SavepointSnapshot(String name, Path snapshotPath, Map<String, Integer> filePages) {
    }
}
