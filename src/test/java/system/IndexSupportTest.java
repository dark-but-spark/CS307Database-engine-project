package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.IndexScanOperator;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        assertThat(index.printTree())
                .contains("idx_users_id")
                .contains("leaf[0]")
                .contains("1 -> [(")
                .contains("2 -> [(");

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

    @Test
    void indexedRangeScanSupportsAndBoundsAndResidualFilters() throws DBException {
        DBManager dbManager = buildDbManager();
        seedUsersWithIndex(dbManager);

        List<Object[]> rows = queryRows(dbManager,
                "SELECT id FROM users WHERE id >= 2 AND id < 4 AND name <> 'bob'");

        assertThat(rows).extracting(row -> row[0]).containsExactly(3L);
    }

    @Test
    void indexedRangeScanSupportsReversedComparisonAndBetween() throws DBException {
        DBManager dbManager = buildDbManager();
        seedUsersWithIndex(dbManager);

        assertThat(queryRows(dbManager, "SELECT id FROM users WHERE 4 > id AND id > 1"))
                .extracting(row -> row[0])
                .containsExactly(2L, 3L);

        assertThat(queryRows(dbManager, "SELECT id FROM users WHERE id BETWEEN 2 AND 3"))
                .extracting(row -> row[0])
                .containsExactly(2L, 3L);
    }

    @Test
    void largeIndexedTableUsesIndexScanAndMaintainsMutations() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, name char)");
        for (int i = 1; i <= 150; i++) {
            executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (" + i + ", 'user" + i + "')");
        }
        executeStatement(dbManager, "CREATE INDEX idx_users_id ON users (id)");

        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager,
                "SELECT id FROM users WHERE id >= 40 AND id < 50");
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        assertThat(containsOperator(physicalOperator, IndexScanOperator.class)).isTrue();

        assertThat(queryRows(dbManager, "SELECT id FROM users WHERE id >= 40 AND id < 50"))
                .extracting(row -> row[0])
                .containsExactly(40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L);

        executeStatement(dbManager, "UPDATE users SET id = 1000 WHERE id = 45");
        executeStatement(dbManager, "DELETE FROM users WHERE id = 46");

        BPlusTreeIndex index = dbManager.getIndex("users", "idx_users_id");
        assertThat(index.EqualTo(new Value(45L))).isNull();
        assertThat(index.EqualTo(new Value(46L))).isNull();
        assertThat(index.EqualTo(new Value(1000L))).isNotNull();

        assertThat(queryRows(dbManager, "SELECT id FROM users WHERE id >= 44 AND id <= 47"))
                .extracting(row -> row[0])
                .containsExactly(44L, 47L);
        assertThat(queryRows(dbManager, "SELECT id FROM users WHERE id = 1000"))
                .extracting(row -> row[0])
                .containsExactly(1000L);
    }

    private void seedUsersWithIndex(DBManager dbManager) throws DBException {
        executeStatement(dbManager, "CREATE TABLE users (id int, name char)");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (2, 'bob')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (3, 'carol')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (4, 'dave')");
        executeStatement(dbManager, "CREATE INDEX idx_users_id ON users (id)");
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
        queryRows(dbManager, sql);
    }

    private List<Object[]> queryRows(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return List.of();
        }
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        List<Object[]> rows = new ArrayList<>();
        physicalOperator.Begin();
        try {
            while (physicalOperator.hasNext()) {
                physicalOperator.Next();
                Tuple tuple = physicalOperator.Current();
                if (tuple != null) {
                    var values = tuple.getValues();
                    Object[] row = new Object[values.length];
                    for (int i = 0; i < values.length; i++) {
                        row[i] = values[i].value;
                    }
                    rows.add(row);
                }
            }
        } finally {
            physicalOperator.Close();
            dbManager.getBufferPool().FlushAllPages("");
        }
        return rows;
    }

    private boolean containsOperator(Object candidate, Class<?> operatorClass) {
        if (candidate == null) {
            return false;
        }
        if (operatorClass.isInstance(candidate)) {
            return true;
        }
        Class<?> type = candidate.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!PhysicalOperator.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (containsOperator(field.get(candidate), operatorClass)) {
                        return true;
                    }
                } catch (IllegalAccessException ignored) {
                    return false;
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
