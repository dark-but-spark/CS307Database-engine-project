package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

/**
 * 逻辑 SUM/AVG 聚合算子 — 对应 SELECT SUM(col) / SELECT AVG(col)。
 *
 * isSum=true  → SUM(col): 对列值求和
 * isSum=false → AVG(col): 对列值求平均 = SUM/COUNT
 *
 * PhysicalPlanner 转换为 SumAvgOperator，单次遍历完成累加和计数。
 * AVG 用 SUM/COUNT 实现，不需要两趟扫描。
 * WHERE 条件在 LogicalPlanner.buildSumAvgPlan() 中已包装为 LogicalFilterOperator。
 */
public class LogicalSumAvgOperator extends LogicalOperator {
    private final boolean isSum;
    private final String columnName;
    private final String tableName;

    public LogicalSumAvgOperator(LogicalOperator child, boolean isSum,
                                  String columnName, String tableName) {
        super(Collections.singletonList(child));
        this.isSum = isSum;
        this.columnName = columnName;
        this.tableName = tableName;
    }

    public boolean isSum() { return isSum; }
    public String getColumnName() { return columnName; }
    public String getTableName() { return tableName; }

    @Override
    public String toString() {
        String func = isSum ? "SUM" : "AVG";
        return func + "Operator(" + func + "(" + columnName + "))\n └── " + childern.get(0);
    }
}
