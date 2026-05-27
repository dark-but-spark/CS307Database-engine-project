package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

/**
 * 逻辑 MAX/MIN 聚合算子 — 对应 SELECT MAX(col) / SELECT MIN(col)。
 *
 * isMax=true → MAX(col)，遍历找最大值
 * isMax=false → MIN(col)，遍历找最小值
 *
 * PhysicalPlanner 转换为 MaxMinOperator，单次遍历 O(n)。
 * 比较使用 ValueComparer.compare()，支持 INT/FLOAT/CHAR 类型。
 * WHERE 条件在 buildMaxMinPlan() 中已包装为 LogicalFilterOperator。
 */
public class LogicalMaxMinOperator extends LogicalOperator {
    private final boolean isMax;
    private final String columnName;
    private final String tableName;

    public LogicalMaxMinOperator(LogicalOperator child, boolean isMax, String columnName, String tableName) {
        super(Collections.singletonList(child));
        this.isMax = isMax;
        this.columnName = columnName;
        this.tableName = tableName;
    }

    public boolean isMax() { return isMax; }
    public String getColumnName() { return columnName; }
    public String getTableName() { return tableName; }

    @Override
    public String toString() {
        String func = isMax ? "MAX" : "MIN";
        return func + "Operator(" + func + "(" + columnName + "))\n └── " + childern.get(0);
    }
}
