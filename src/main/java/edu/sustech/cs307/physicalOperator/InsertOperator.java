package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.List;
import java.util.ArrayList;

/**
 * 插入算子 — INSERT INTO 的运行时执行。
 *
 * INSERT INTO t (col1, col2) VALUES (v1, v2), (v3, v4)
 *
 * Begin() 执行流程：
 * 1. OpenFile 打开数据文件
 * 2. 从 TableMeta 获取各列元数据（offset, len）
 * 3. 逐行序列化：
 *    a. RecordSerializer.writeValue() 把每个 Value 转为定长字节
 *    b. 凑够一行（i % columnSize == 行末），调用 fileHandle.InsertRecord(buffer)
 *    c. InsertRecord 在 bitmap 中找空闲槽位，写入数据，返回 RID
 *    d. dbManager.insertIntoIndexes() 同步写入 B+Tree 索引
 * 4. 输出插入行数
 *
 * 设计要点：
 * - 支持批量插入（多组 VALUES），每个值按列顺序排列在 values list 中
 * - 每行插入后立即更新索引，保证索引与数据一致
 * - 输出 TempTuple(插入行数)，供上层报告结果
 */
public class InsertOperator implements PhysicalOperator {
    private final String data_file;
    private final List<Value> values;
    private final List<String> columnNames;
    private final DBManager dbManager;
    private final int columnSize;
    private int rowCount;
    private boolean outputed;

    public InsertOperator(String data_file, List<String> columnNames, List<Value> values, DBManager dbManager) {
        this.data_file = data_file;
        this.columnNames = columnNames;
        this.values = values;
        this.dbManager = dbManager;
        this.columnSize = columnNames.size();
        this.rowCount = 0;
        this.outputed = false;
    }

    @Override
    public boolean hasNext() {
        return !this.outputed;
    }

    /**
     * 核心插入逻辑：
     * 遍历 values 列表，每凑满一行就调用 InsertRecord 写入磁盘并同步索引。
     */
    @Override
    public void Begin() throws DBException {
        try {
            var fileHandle = dbManager.getRecordManager().OpenFile(data_file);
            var tableMeta = dbManager.getMetaManager().getTable(data_file);
            List<ColumnMeta> insertColumns = new ArrayList<>();
            for (String columnName : columnNames) {
                insertColumns.add(tableMeta.getColumnMeta(columnName));
            }
            ByteBuf buffer = Unpooled.buffer();
            for (int i = 0; i < values.size(); i++) {
                // 按列顺序序列化每个值
                RecordSerializer.writeValue(buffer, values.get(i), insertColumns.get(i % columnSize));
                // 一行凑满（或多个单列值各自为一行）
                if ((columnSize == 1) || ((i + 1) % columnSize == 0 && i != 0)) {
                    RID rid = fileHandle.InsertRecord(buffer);
                    Value[] rowValues = values.subList(i + 1 - columnSize, i + 1).toArray(new Value[0]);
                    dbManager.insertIntoIndexes(data_file, rid, rowValues);
                    buffer.clear();
                }
            }
            this.rowCount = values.size() / columnSize;
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert record: " + e.getMessage() + "\n");
        }
    }

    @Override
    public void Next() { }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(rowCount, ValueType.INTEGER));
        this.outputed = true;
        return new TempTuple(values);
    }

    @Override
    public void Close() { }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> outputSchema = new ArrayList<>();
        outputSchema.add(new ColumnMeta("insert", "numberOfInsertRows", ValueType.INTEGER, 0, 0));
        return outputSchema;
    }

    public void reset() { }

    public Tuple getNextTuple() { return null; }
}
