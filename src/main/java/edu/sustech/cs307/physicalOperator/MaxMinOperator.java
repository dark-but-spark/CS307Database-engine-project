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

/**
 * MAX/MIN 聚合算子 — Q&A（Task 2 Advanced）。
 *
 * 实现方式：遍历所有行，维护当前最大/最小值。
 *
 * 算法：
 * 1. 初始化 result = null
 * 2. 遍历子算子的每一行：
 *    - 取目标列的值 colValue
 *    - 如果 result == null，设为 colValue
 *    - 否则用 ValueComparer.compare(colValue, result) 比较
 *      - isMax=true 且 colValue > result → result = colValue
 *      - isMax=false 且 colValue < result → result = colValue
 * 3. 输出一行结果 {result}
 *
 * 复杂度：O(n)，单次遍历
 */
public class MaxMinOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final boolean isMax;       // true=MAX, false=MIN
    private final String columnName;   // 目标列名
    private final String tableName;    // 表名，用于 TabCol 定位

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

    /**
     * 遍历所有行，用 ValueComparer 比较维护最值。
     * TabCol(tableName, columnName) 用于从 Tuple 中正确取出目标列的值。
     */
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
                    result = colValue;    // 找到更大的
                } else if (!isMax && cmp < 0) {
                    result = colValue;    // 找到更小的
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
