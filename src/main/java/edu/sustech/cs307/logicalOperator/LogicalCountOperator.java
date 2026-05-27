package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

/**
 * 逻辑 COUNT 聚合算子 — 对应 SELECT COUNT(*) / SELECT COUNT(col)。
 *
 * isStar=true  → COUNT(*),  columnName=null: 每行都计数
 * isStar=false → COUNT(col), columnName="col": 只计非 NULL 行
 *
 * PhysicalPlanner 转换为 CountOperator，遍历子算子所有行完成计数。
 * WHERE 条件在 buildCountPlan() 中已包装为 LogicalFilterOperator 作为子节点。
 */
public class LogicalCountOperator extends LogicalOperator {
    private final boolean isStar;
    private final String columnName;
    private final String tableName;

    public LogicalCountOperator(LogicalOperator child, boolean isStar, String columnName, String tableName) {
        super(Collections.singletonList(child));
        this.isStar = isStar;
        this.columnName = columnName;
        this.tableName = tableName;
    }

    public boolean isStar() { return isStar; }
    public String getColumnName() { return columnName; }
    public String getTableName() { return tableName; }

    @Override
    public String toString() {
        String target = isStar ? "*" : columnName;
        return "CountOperator(count(" + target + "))\n └── " + childern.get(0);
    }
}
