package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

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

    public boolean isStar() {
        return isStar;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public String toString() {
        String target = isStar ? "*" : columnName;
        return "CountOperator(count(" + target + "))\n └── " + childern.get(0);
    }
}
