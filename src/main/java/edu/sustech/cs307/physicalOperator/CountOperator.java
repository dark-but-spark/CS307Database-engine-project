package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;

public class CountOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final boolean isStar;
    private final String columnName;
    private final String tableName;

    private int count;
    private boolean isDone;

    public CountOperator(PhysicalOperator child, boolean isStar, String columnName, String tableName) {
        this.child = child;
        this.isStar = isStar;
        this.columnName = columnName;
        this.tableName = tableName;
        this.count = 0;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    @Override
    public void Begin() throws DBException {
        count = 0;
        isDone = false;
        child.Begin();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            if (isStar) {
                count++;
            } else {
                Value colValue = getColumnValue(tuple);
                if (colValue != null) {
                    count++;
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
            ArrayList<Value> result = new ArrayList<>();
            result.add(new Value((long) count, ValueType.INTEGER));
            return new TempTuple(result);
        }
        return null;
    }

    @Override
    public void Close() {
        child.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("", "count", ValueType.INTEGER, 0, 0));
        return schema;
    }

    private Value getColumnValue(Tuple tuple) throws DBException {
        if (tableName != null && !tableName.isBlank()) {
            return tuple.getValue(new TabCol(tableName, columnName));
        }

        Value matchedValue = null;
        int matchedCount = 0;
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                matchedValue = tuple.getValue(tabCol);
                matchedCount++;
            }
        }
        if (matchedCount > 1) {
            // REVIEW(Task 2.1.3 Sequential Scan Implementation - COUNT):
            // Ambiguous unqualified COUNT(column) should use a dedicated
            // ambiguity exception once the exception hierarchy grows one.
            throw new DBException(ExceptionTypes.InvalidSQL("COUNT(" + columnName + ")",
                    "Ambiguous column reference"));
        }
        return matchedValue;
    }
}
