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
