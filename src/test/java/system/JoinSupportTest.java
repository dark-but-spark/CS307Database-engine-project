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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class JoinSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void nestedLoopJoinReturnsRowsMatchingOnCondition() throws DBException {
        DBManager dbManager = buildDbManager();
        seedJoinTables(dbManager);

        List<Object[]> rows = executeStatement(dbManager,
                "SELECT * FROM users JOIN scores ON users.id = scores.user_id");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(1L, "alice", 1L, 95L);
        assertThat(rows.get(1)).containsExactly(2L, "bob", 2L, 88L);
    }

    @Test
    void joinWithoutOnReturnsCartesianProduct() throws DBException {
        DBManager dbManager = buildDbManager();
        seedJoinTables(dbManager);

        List<Object[]> rows = executeStatement(dbManager, "SELECT * FROM users JOIN scores");

        assertThat(rows).hasSize(6);
    }

    private void seedJoinTables(DBManager dbManager) throws DBException {
        executeStatement(dbManager, "CREATE TABLE users (id int, name char)");
        executeStatement(dbManager, "CREATE TABLE scores (user_id int, score int)");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (1, 'alice')");
        executeStatement(dbManager, "INSERT INTO users (id, name) VALUES (2, 'bob')");
        executeStatement(dbManager, "INSERT INTO scores (user_id, score) VALUES (1, 95)");
        executeStatement(dbManager, "INSERT INTO scores (user_id, score) VALUES (2, 88)");
        executeStatement(dbManager, "INSERT INTO scores (user_id, score) VALUES (3, 70)");
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
}
