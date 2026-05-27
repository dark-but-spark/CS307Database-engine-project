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
import edu.sustech.cs307.value.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class AlterTableSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void addAndDropColumnUpdateMetadataForEmptyTables() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int)");
        executeStatement(dbManager, "ALTER TABLE users ADD name char");

        var tableMeta = dbManager.getMetaManager().getTable("users");
        assertThat(tableMeta.getColumnMeta("name").type).isEqualTo(ValueType.CHAR);
        assertThat(tableMeta.columns_list).extracting(column -> column.name)
                .containsExactly("id", "name");

        executeStatement(dbManager, "ALTER TABLE users DROP COLUMN name");

        assertThat(dbManager.getMetaManager().getTable("users").getColumnMeta("name")).isNull();
        assertThat(dbManager.getMetaManager().getTable("users").columns_list)
                .extracting(column -> column.name)
                .containsExactly("id");
    }

    @Test
    void renameTableUpdatesMetadataAndStorageName() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int)");
        executeStatement(dbManager, "ALTER TABLE users RENAME TO people");

        assertThat(dbManager.isTableExists("users")).isFalse();
        assertThat(dbManager.isTableExists("people")).isTrue();
        assertThat(dbManager.getDiskManager().filePages).containsKey("people/data");
    }

    @Test
    void addAndDropColumnRewriteNonEmptyTables() throws DBException {
        DBManager dbManager = buildDbManager();

        executeStatement(dbManager, "CREATE TABLE users (id int, gpa float)");
        executeStatement(dbManager, "INSERT INTO users (id, gpa) VALUES (1, 3.5)");
        executeStatement(dbManager, "INSERT INTO users (id, gpa) VALUES (2, 4.0)");
        executeStatement(dbManager, "ALTER TABLE users ADD name char");

        List<Object[]> rowsAfterAdd = selectRows(dbManager, "SELECT * FROM users");
        assertThat(rowsAfterAdd).hasSize(2);
        assertThat(rowsAfterAdd.get(0)).containsExactly(1L, 3.5, "");
        assertThat(rowsAfterAdd.get(1)).containsExactly(2L, 4.0, "");

        executeStatement(dbManager, "ALTER TABLE users DROP COLUMN gpa");

        List<Object[]> rowsAfterDrop = selectRows(dbManager, "SELECT * FROM users");
        assertThat(dbManager.getMetaManager().getTable("users").columns_list)
                .extracting(column -> column.name)
                .containsExactly("id", "name");
        assertThat(rowsAfterDrop).hasSize(2);
        assertThat(rowsAfterDrop.get(0)).containsExactly(1L, "");
        assertThat(rowsAfterDrop.get(1)).containsExactly(2L, "");
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

    private List<Object[]> selectRows(DBManager dbManager, String sql) throws DBException {
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, sql);
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
        return rows;
    }
}
