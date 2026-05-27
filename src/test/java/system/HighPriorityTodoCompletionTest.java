package system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.GroupByOperator;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HighPriorityTodoCompletionTest {
    @TempDir
    Path tempDir;

    @Test
    void insertColumnListCanBeOutOfOrderAndOmitDefaultedColumns() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, name char, gpa float)");
        executeStatement(dbManager, "INSERT INTO users (name, id) VALUES ('alice', 1)");
        executeStatement(dbManager, "INSERT INTO users (gpa, id, name) VALUES (3.5, 2, 'bob')");

        assertThat(queryRows(dbManager, "SELECT * FROM users"))
                .extracting(row -> List.of(row[0], row[1], row[2]))
                .containsExactly(
                        List.of(1L, "alice", 0.0),
                        List.of(2L, "bob", 3.5));
    }

    @Test
    void maxMinOutputSchemaUsesInputColumnType() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, name char, gpa float)");
        executeStatement(dbManager, "INSERT INTO users (id, name, gpa) VALUES (1, 'alice', 3.5)");
        LogicalOperator maxPlan = LogicalPlanner.resolveAndPlan(dbManager, "SELECT MAX(gpa) FROM users");
        PhysicalOperator maxOperator = PhysicalPlanner.generateOperator(dbManager, maxPlan);
        assertThat(maxOperator.outputSchema().get(0).type).isEqualTo(ValueType.FLOAT);

        LogicalOperator minPlan = LogicalPlanner.resolveAndPlan(dbManager, "SELECT MIN(name) FROM users");
        PhysicalOperator minOperator = PhysicalPlanner.generateOperator(dbManager, minPlan);
        assertThat(minOperator.outputSchema().get(0).type).isEqualTo(ValueType.CHAR);
    }

    @Test
    void orderByRejectsAmbiguousUnqualifiedColumn() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE left_t (id int)");
        executeStatement(dbManager, "CREATE TABLE right_t (id int)");

        assertThatThrownBy(() -> queryRows(dbManager, "SELECT * FROM left_t JOIN right_t ORDER BY id"))
                .isInstanceOf(DBException.class)
                .hasMessageContaining("Ambiguous column reference");
    }

    @Test
    void showTablesCommandMatchesProjectRequirement() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int)");

        assertThat(queryRows(dbManager, "SHOW TABLES")).isEmpty();
    }

    @Test
    void groupByOutputSchemaIsAvailableBeforeBeginForCliHeader() throws DBException {
        DBManager dbManager = buildDbManager();
        executeStatement(dbManager, "CREATE TABLE users (id int, age int)");
        executeStatement(dbManager, "INSERT INTO users (id, age) VALUES (1, 18)");
        executeStatement(dbManager, "INSERT INTO users (id, age) VALUES (2, 18)");

        LogicalOperator plan = LogicalPlanner.resolveAndPlan(dbManager,
                "SELECT age, COUNT(*) FROM users GROUP BY age");
        PhysicalOperator operator = PhysicalPlanner.generateOperator(dbManager, plan);

        assertThat(operator).isInstanceOf(GroupByOperator.class);
        assertThat(operator.outputSchema()).hasSize(2);
        assertThat(queryRows(dbManager, "SELECT age, COUNT(*) FROM users GROUP BY age"))
                .extracting(row -> List.of(row[0], row[1]))
                .containsExactly(List.of(18L, 2L));
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
}
