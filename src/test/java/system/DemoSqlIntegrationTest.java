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
import edu.sustech.cs307.system.TransactionManager;
import edu.sustech.cs307.tuple.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class DemoSqlIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void demoSqlRunsEndToEndAndLeavesExpectedState() throws DBException, IOException {
        DBManager dbManager = buildDbManager();

        // Defense guardrail: execute the exact script used in presentation so
        // demo.SQL cannot drift away from the engine's supported SQL surface.
        for (String statement : splitSqlBatch(Files.readString(Path.of("demo.SQL")))) {
            executeStatement(dbManager, statement);
        }

        // These assertions pin the visible effects of the demo: one row is
        // deleted, one row is updated, savepoint rollback keeps only id=1's
        // name change, and the auxiliary demo tables still exist.
        assertThat(queryRows(dbManager, "SELECT COUNT(*) FROM students").get(0)[0])
                .isEqualTo(29L);
        assertThat(queryRows(dbManager, "SELECT id, name, gpa FROM students WHERE id = 8").get(0))
                .containsExactly(8L, "heidi", 4.0);
        assertThat(queryRows(dbManager, "SELECT id, name FROM students WHERE id <= 2"))
                .extracting(row -> List.of(row[0], row[1]))
                .containsExactly(
                        List.of(1L, "rollback"),
                        List.of(2L, "bob"));
        assertThat(dbManager.isTableExists("scratch")).isTrue();
        assertThat(dbManager.isTableExists("scores")).isTrue();
    }

    private DBManager buildDbManager() throws DBException {
        HashMap<String, Integer> fileOffsets = new HashMap<>();
        DiskManager diskManager = new DiskManager(tempDir.toString(), fileOffsets);
        IntFunction<PageReplacer> replacerFactory = ClockReplacer::new;
        BufferPool bufferPool = new BufferPool(16, diskManager, replacerFactory.apply(16));
        RecordManager recordManager = new RecordManager(diskManager, bufferPool);
        MetaManager metaManager = new MetaManager(tempDir.resolve("meta").toString());
        DBManager dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager, null,
                replacerFactory);
        dbManager.setTransactionManager(new TransactionManager(dbManager));
        return dbManager;
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

    private List<String> splitSqlBatch(String sql) {
        ArrayList<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        // Mirrors DBEntry.splitSqlBatch(): semicolons inside string literals
        // must not split statements, otherwise demo data like 'a;b' would fail.
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                current.append(ch);
                if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append(sql.charAt(++i));
                } else {
                    inSingleQuote = !inSingleQuote;
                }
                continue;
            }
            if (ch == ';' && !inSingleQuote) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        return statements;
    }
}
