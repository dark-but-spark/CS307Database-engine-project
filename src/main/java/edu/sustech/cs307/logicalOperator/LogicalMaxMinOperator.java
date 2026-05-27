package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

public class LogicalMaxMinOperator extends LogicalOperator {
    private final boolean isMax;       // true for MAX, false for MIN
    private final String columnName;
    private final String tableName;

    public LogicalMaxMinOperator(LogicalOperator child, boolean isMax, String columnName, String tableName) {
        super(Collections.singletonList(child));
        this.isMax = isMax;
        this.columnName = columnName;
        this.tableName = tableName;
    }

    public boolean isMax() {
        return isMax;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public String toString() {
        String func = isMax ? "MAX" : "MIN";
        return func + "Operator(" + func + "(" + columnName + "))\n └── " + childern.get(0);
    }
}
