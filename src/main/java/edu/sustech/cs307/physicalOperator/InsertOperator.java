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
 * INSERT 物理算子 — Task 2.0.2 数据操作。
 *
 * 执行流程（Begin() 单次执行）:
 *
 *
 * 
 *   <li>遍历预解析的 values 列表（支持多行 INSERT：VALUES (1), (2), (3)）
 *   <li>按列类型序列化每个 Value 到 ByteBuf（int=4字节, float=8字节, char=CHAR_SIZE字节）
 *   <li>调用 RecordManager.InsertRecord() 写入磁盘 → 返回 RID（页号, 槽号）
 *   <li>调用 dbManager.insertIntoIndexes() 同步更新所有相关 B+Tree 索引
 * 
 *
 * 列值序列化:
 *
 *
 * writeColumnToBuf() 按 ValueType 选择 writer：
 * 
 *   <li>INTEGER → writeInt（4 字节，小端序）
 *   <li>FLOAT → writeDouble（8 字节）
 *   <li>CHAR → 固定 CHAR_SIZE 字节（不足用 0 填充）
 * 
 *
 * 索引同步:
 *
 *
 * InsertOperator 只负责写入数据和返回 RID。
 * 索引维护由 DBManager.insertIntoIndexes(tableName, columnValues, rid) 统一处理，
 * 遍历该表所有 BPlusTreeIndex，对每个索引列调用 index.insert(value, rid)。
 *
 * outputed 标志:
 *
 *
 * INSERT 是单输出算子：hasNext() 第一次返回 true，Current() 返回影响行数，之后 outputed=true。
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

    @Override
    public void Begin() throws DBException {
        // Task 2.0.2 Data Operations - INSERT: serialize values into record-sized buffers
        // and append them through RecordFileHandle.
        var fileHandle = dbManager.getRecordManager().OpenFile(data_file);
        try {
            var tableMeta = dbManager.getMetaManager().getTable(data_file);
            List<ColumnMeta> insertColumns = new ArrayList<>();
            for (String columnName : columnNames) {
                insertColumns.add(tableMeta.getColumnMeta(columnName));
            }
            // Serialize values to ByteBuf
            ByteBuf buffer = Unpooled.buffer();
            for (int i = 0; i < values.size(); i++) {
                RecordSerializer.writeValue(buffer, values.get(i), insertColumns.get(i % columnSize));
                if ((columnSize == 1) || ((i + 1) % columnSize == 0 && i != 0)) {
                    RID rid = fileHandle.InsertRecord(buffer);
                    Value[] rowValues = values.subList(i + 1 - columnSize, i + 1).toArray(new Value[0]);
                    // Task 3.1 Index Support - Dynamic INSERT Maintenance: use
                    // the storage RID returned by InsertRecord to update indexes.
                    dbManager.insertIntoIndexes(data_file, rid, rowValues);
                    buffer.clear();
                }
            }
            this.rowCount = values.size() / columnSize;
        } finally {
            dbManager.getRecordManager().CloseFile(fileHandle);
        }
    }

    @Override
    public void Next() {
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(rowCount, ValueType.INTEGER));
        this.outputed = true;
        return new TempTuple(values);
    }

    @Override
    public void Close() {
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> outputSchema = new ArrayList<>();
        outputSchema.add(new ColumnMeta("insert", "numberOfInsertRows", ValueType.INTEGER, 0, 0));
        return outputSchema;
    }

    public void reset() {
        // nothing to do
    }

    public Tuple getNextTuple() {
        return null;
    }
}
