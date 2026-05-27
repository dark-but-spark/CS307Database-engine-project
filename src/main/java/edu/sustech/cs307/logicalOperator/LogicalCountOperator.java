package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

public class LogicalCountOperator extends LogicalOperator {
    private final boolean isStar;
    private final boolean distinct;
    private final String columnName;
    private final String tableName;

    public LogicalCountOperator(LogicalOperator child, boolean isStar, String columnName, String tableName) {
        this(child, isStar, false, columnName, tableName);
    }

    public LogicalCountOperator(LogicalOperator child, boolean isStar, boolean distinct, String columnName, String tableName) {
        super(Collections.singletonList(child));
        this.isStar = isStar;
        this.distinct = distinct;
        this.columnName = columnName;
        this.tableName = tableName;
    }

    public boolean isStar() {
        return isStar;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public String toString() {
        String target = isStar ? "*" : (distinct ? "distinct " : "") + columnName;
        return "CountOperator(count(" + target + "))\n └── " + childern.get(0);
    }
}
