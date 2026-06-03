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
 * SUM/AVG 聚合算子。
 *
 * SELECT SUM(salary) FROM emp    → isSum=true,  输出一行: {总和}
 * SELECT AVG(salary) FROM emp    → isSum=false, 输出一行: {平均值}
 *
 * 算法：单次遍历，累加和计数。
 * SUM: 遍历每行，取列值，累加到 sum（double）
 * AVG: 同样累加 sum 和计数 count，最后 sum/count
 *      如果所有值都是 NULL（count==0），返回 0
 *
 * 设计要点：
 * SUM 和 AVG 共享同一个遍历循环，只通过 isSum 区分最终输出。
 * 所有列值统一转为 double 累加（INT 和 FLOAT 都转为 double）。
 * 跳过 NULL 值（不计入 sum 也不计入 count）。
 *
 * 复杂度：O(n)，单次遍历。
 */
public class SumAvgOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final boolean isSum;
    private final String columnName;
    private final String tableName;

    private double sum;
    private long count;
    private boolean isDone;

    public SumAvgOperator(PhysicalOperator child, boolean isSum,
                          String columnName, String tableName) {
        this.child = child;
        this.isSum = isSum;
        this.columnName = columnName;
        this.tableName = tableName;
        this.sum = 0.0;
        this.count = 0;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    /**
     * 核心聚合逻辑：
     * 遍历子算子所有行，对每行的目标列取值 → 转为 double → 累加到 sum。
     * AVG 还需要计数非 NULL 行数，最后 sum/count。
     *
     * WHERE 过滤：子算子可能已经包含 FilterOperator，这里只看到过滤后的行。
     */
    @Override
    public void Begin() throws DBException {
        child.Begin();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) continue;

            Value colValue = tuple.getValue(new TabCol(tableName, columnName));
            if (colValue == null || colValue.value == null) continue;

            double val = toDouble(colValue);
            sum += val;
            count++;
        }
    }

    private double toDouble(Value v) {
        return switch (v.type) {
            case INTEGER -> ((Long) v.value).doubleValue();
            case FLOAT   -> (Double) v.value;
            default      -> 0.0;
        };
    }

    @Override
    public void Next() { isDone = true; }

    /**
     * 返回聚合结果：SUM → {sum}，AVG → {sum/count}
     */
    @Override
    public Tuple Current() {
        if (isDone) {
            ArrayList<Value> values = new ArrayList<>();
            if (isSum) {
                values.add(new Value(sum));
            } else {
                values.add(new Value(count > 0 ? sum / count : 0.0));
            }
            return new TempTuple(values);
        }
        return null;
    }

    @Override
    public void Close() { child.Close(); sum = 0.0; count = 0; isDone = false; }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("", isSum ? "sum" : "avg", ValueType.FLOAT, 0, 0));
        return schema;
    }
}
