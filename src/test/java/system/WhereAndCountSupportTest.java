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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhereAndCountSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void whereSupportsLikeIsNotNullAndNotParentheses() throws DBException {
        DBManager dbManager = buildDbManager();
        seedUsers(dbManager);

        assertThat(selectIds(dbManager, "SELECT * FROM users WHERE name LIKE 'a%'"))
                .containsExactly(1L);
        assertThat(selectIds(dbManager, "SELECT * FROM users WHERE name IS NOT NULL"))
                .containsExactly(1L, 2L, 3L);
        assertThat(selectIds(dbManager, "SELECT * FROM users WHERE NOT (age < 20)"))
                .containsExactly(2L, 3L);
    }

    @Test
    void whereMissingColumnDoesNotMatchRows() throws DBException {
        DBManager dbManager = buildDbManager();
        seedUsers(dbManager);

        assertThat(selectIds(dbManager, "SELECT * FROM users WHERE missing = 1"))
                .isEmpty();
    }

    @Test
    void countAggregatesRowsAfterWhereFilter() throws DBException {
        DBManager dbManager = buildDbManager();
        seedUsers(dbManager);

        assertThat(singleLong(dbManager, "SELECT COUNT(*) FROM users WHERE age >= 20"))
                .isEqualTo(2L);
        assertThat(singleLong(dbManager, "SELECT COUNT(name) FROM users WHERE name LIKE '%o%'"))
                .isEqualTo(2L);
    }

    @Test
    void projectionResolvesUnqualifiedAndQualifiedColumns() throws DBException {
        DBManager dbManager = buildDbManager();
        seedUsers(dbManager);

        assertThat(executeStatement(dbManager, "SELECT id, name FROM users"))
                .extracting(row -> List.of(row[0], row[1]))
                .containsExactly(
                        List.of(1L, "alice"),
                        List.of(2L, "bob"),
                        List.of(3L, "carol"));

        assertThat(executeStatement(dbManager, "SELECT users.id FROM users"))
                .extracting(row -> row[0])
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void projectionRejectsAmbiguousUnqualifiedColumn() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE left_t (id int, name char)");
        executeStatement(dbManager, "CREATE TABLE right_t (id int, score int)");

        assertThatThrownBy(() -> executeStatement(dbManager, "SELECT id FROM left_t JOIN right_t"))
                .isInstanceOf(DBException.class)
                .hasMessageContaining("Ambiguous column reference");
    }

    private void seedUsers(DBManager dbManager) throws DBException {
        executeStatement(dbManager, "CREATE TABLE users (id int, name char, age int)");
        executeStatement(dbManager, "INSERT INTO users (id, name, age) VALUES (1, 'alice', 18)");
        executeStatement(dbManager, "INSERT INTO users (id, name, age) VALUES (2, 'bob', 20)");
        executeStatement(dbManager, "INSERT INTO users (id, name, age) VALUES (3, 'carol', 22)");
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

    private List<Long> selectIds(DBManager dbManager, String sql) throws DBException {
        List<Object[]> rows = executeStatement(dbManager, sql);
        List<Long> ids = new ArrayList<>();
        for (Object[] row : rows) {
            ids.add((Long) row[0]);
        }
        return ids;
    }

    private Long singleLong(DBManager dbManager, String sql) throws DBException {
        List<Object[]> rows = executeStatement(dbManager, sql);
        assertThat(rows).hasSize(1);
        return (Long) rows.get(0)[0];
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
