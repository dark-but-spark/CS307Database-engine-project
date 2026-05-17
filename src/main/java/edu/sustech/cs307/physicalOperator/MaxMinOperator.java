package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;

public class MaxMinOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final boolean isMax;
    private final String columnName;
    private final String tableName;

    private Value result;
    private boolean isDone;

    public MaxMinOperator(PhysicalOperator child, boolean isMax, String columnName, String tableName) {
        this.child = child;
        this.isMax = isMax;
        this.columnName = columnName;
        this.tableName = tableName;
        this.result = null;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            Value colValue = tuple.getValue(new TabCol(tableName, columnName));
            if (colValue == null) {
                continue;
            }
            if (result == null) {
                result = colValue;
            } else {
                int cmp = ValueComparer.compare(colValue, result);
                if (isMax && cmp > 0) {
                    result = colValue;
                } else if (!isMax && cmp < 0) {
                    result = colValue;
                }
            }
        }
    }

    @Override
    public void Next() {
        isDone = true;
    }

    @Override
    public Tuple Current() {
        if (isDone) {
            ArrayList<Value> values = new ArrayList<>();
            if (result != null) {
                values.add(result);
            } else {
                values.add(new Value((long) 0, ValueType.INTEGER));
            }
            return new TempTuple(values);
        }
        return null;
    }

    @Override
    public void Close() {
        child.Close();
        result = null;
        isDone = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        String colName = isMax ? "max" : "min";
        schema.add(new ColumnMeta("", colName, ValueType.INTEGER, 0, 0));
        return schema;
    }
}
