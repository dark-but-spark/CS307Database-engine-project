package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;

/**
 * COUNT 聚合算子 — Q&A 必问：解释 COUNT 的设计。
 *
 * 支持两种模式：
 * 1. COUNT(*)   → isStar=true,  columnName=null
 *    每行都计数，包括包含 NULL 值的行
 * 2. COUNT(col) → isStar=false, columnName="列名"
 *    只计该列值不为 NULL 的行
 *
 * 执行流程：
 * 1. Begin() 打开子算子（通常是 SeqScan 或 FilterOperator+SeqScan）
 * 2. 遍历所有行，根据 isStar 决定计数逻辑
 * 3. 最终输出一行结果：{count}
 *
 * WHERE 条件支持：
 * COUNT 的 WHERE 过滤不在这里处理，而是在 LogicalPlanner.buildCountPlan() 中
 * 将 WHERE 条件包装为 LogicalFilterOperator 作为子算子，这样 COUNT 只看到过滤后的行。
 */
public class CountOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final boolean isStar;       // true=COUNT(*), false=COUNT(column)
    private final String columnName;    // COUNT(column) 时的目标列名
    private final String tableName;     // 表名，用于定位列

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

    /**
     * 核心计数逻辑：
     * - isStar=true (COUNT(*))：每有一行就 count++，不管值是否 NULL
     * - isStar=false (COUNT(col))：通过 tuple.getValue(TabCol) 取该列的值，
     *   非 NULL 才 count++
     *
     * TabCol(tableName, columnName) 用于在 ProjectTuple/JoinTuple 中定位正确的列。
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
            if (isStar) {
                // COUNT(*)：所有行都计数
                count++;
            } else {
                // COUNT(column)：只计非 NULL 行
                Value colValue = tuple.getValue(new TabCol(tableName, columnName));
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
}
