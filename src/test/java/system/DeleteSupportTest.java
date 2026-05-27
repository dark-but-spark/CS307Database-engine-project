package system;

import edu.sustech.cs307.exception.DBException;
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
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class DeleteSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void deleteRemovesOnlyRowsMatchingWhereClause() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, name char)");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (2, 'bob')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (3, 'carol')");

        List<Object[]> deleteResult = executeStatement(dbManager, "DELETE FROM users WHERE id >= 2");

        assertThat(deleteResult).hasSize(1);
        assertThat(deleteResult.get(0)).containsExactly(2);
        assertThat(selectIds(dbManager)).containsExactly(1L);
        assertThat(dbManager.isTableExists("users")).isTrue();
    }

    @Test
    void deleteWithoutWhereClearsRowsButKeepsTable() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int)");
        executeStatement(dbManager, "INSERT INTO users (id) VALUES (1)");
        executeStatement(dbManager, "INSERT INTO users (id) VALUES (2)");

        executeStatement(dbManager, "DELETE FROM users");

        assertThat(selectIds(dbManager)).isEmpty();
        assertThat(dbManager.isTableExists("users")).isTrue();
    }

    @Test
    void deleteMaintainsRuntimeIndexes() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, name char)");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (2, 'bob')");
        executeStatement(dbManager, "CREATE INDEX idx_users_id ON users (id)");

        executeStatement(dbManager, "DELETE FROM users WHERE id = 2");

        assertThat(dbManager.getIndex("users", "idx_users_id").EqualTo(new Value(2L))).isNull();
        assertThat(dbManager.getIndex("users", "idx_users_id").EqualTo(new Value(1L))).isNotNull();
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

    private List<Object[]> executeStatement(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (logicalOperator == null) {
            return List.of();
        }
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        List<Object[]> rows = new ArrayList<>();
        physicalOperator.Begin();
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
        physicalOperator.Close();
        dbManager.getBufferPool().FlushAllPages("");
        return rows;
    }

    private List<Long> selectIds(DBManager dbManager) throws DBException {
        List<Object[]> rows = executeStatement(dbManager, "SELECT * FROM users");
        List<Long> ids = new ArrayList<>();
        for (Object[] row : rows) {
            ids.add((Long) row[0]);
        }
        return ids;
    }
}
