package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class IndexSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void createIndexBuildsBPlusTreeAndMaintainsInsertedAndUpdatedRows() throws DBException {
        // Task 3.2 Validation: cover CREATE INDEX, initial tree build from stored
        // rows, INSERT maintenance, and UPDATE maintenance.
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, name char)");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (2, 'bob')");
        executeStatement(dbManager, "CREATE INDEX idx_users_id ON users (id)");

        BPlusTreeIndex index = dbManager.getIndex("users", "idx_users_id");
        assertThat(dbManager.getMetaManager().getTable("users").getIndexColumn("idx_users_id")).isEqualTo("id");
        assertThat(index.EqualTo(new Value(2L))).isNotNull();
        assertThat(index.printTree()).contains("idx_users_id").contains("1").contains("2");

        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (3, 'carol')");
        assertThat(dbManager.getIndex("users", "idx_users_id").EqualTo(new Value(3L))).isNotNull();

        executeStatement(dbManager, "UPDATE users SET id = 4 WHERE id = 3");
        assertThat(dbManager.getIndex("users", "idx_users_id").EqualTo(new Value(3L))).isNull();
        assertThat(dbManager.getIndex("users", "idx_users_id").EqualTo(new Value(4L))).isNotNull();
    }

    @Test
    void dropIndexRemovesMetadataAndRuntimeTree() throws DBException {
        // Task 3.2 Validation: cover DROP INDEX metadata removal and ensure a
        // table with removed index still remains queryable through SeqScan.
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int)");
        executeStatement(dbManager, "INSERT INTO users (id) VALUES (1)");
        executeStatement(dbManager, "CREATE INDEX idx_users_id ON users (id)");
        executeStatement(dbManager, "DROP INDEX idx_users_id");

        assertThat(dbManager.getMetaManager().getTable("users").getIndexes()).isEmpty();
        executeStatement(dbManager, "SELECT * FROM users WHERE id = 1");
    }

    private DBManager buildDbManager() throws DBException {
        HashMap<String, Integer> fileOffsets = new HashMap<>();
        DiskManager diskManager = new DiskManager(tempDir.toString(), fileOffsets);
        IntFunction<PageReplacer> replacerFactory = ClockReplacer::new;
        BufferPool bufferPool = new BufferPool(16, diskManager, replacerFactory.apply(16));
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        return new DBManager(diskManager, bufferPool, recordManager, metaManager, null, replacerFactory);
    }

    private void executeStatement(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return;
        }
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        physicalOperator.Begin();
        while (physicalOperator.hasNext()) {
            physicalOperator.Next();
            physicalOperator.Current();
        }
        physicalOperator.Close();
        dbManager.getBufferPool().FlushAllPages("");
    }
}
