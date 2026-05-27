package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;

import java.util.Collections;

/**
 * 逻辑插入算子 — 对应 SQL 的 INSERT INTO 语句。
 *
 * INSERT INTO t (col1, col2) VALUES (val1, val2)
 *   → LogicalInsertOperator(table=t, columns=[col1, col2], values=(val1, val2))
 *
 * 无子节点（空 children）。PhysicalPlanner.handleInsert() 将其转换为 InsertOperator。
 * InsertOperator.Begin() 做三件事：
 * 1. 校验列数与 VALUES 数匹配、列名与表 schema 匹配
 * 2. 用 RecordSerializer 把 Value 序列化为定长字节
 * 3. 调用 RecordFileHandle.InsertRecord() 写入磁盘 + 同步更新 B+Tree 索引
 */
public class LogicalInsertOperator extends LogicalOperator {
    public final String tableName;
    public final ExpressionList<Column> columns;
    public final Expression values;

    public LogicalInsertOperator(String tableName, ExpressionList<Column> columns, Expression values) {
        super(Collections.emptyList());
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
    }

    @Override
    public String toString() {
        return "InsertOperator(table=" + tableName + ", columns=" + columns + ", values=" + values + ")";
    }
}
