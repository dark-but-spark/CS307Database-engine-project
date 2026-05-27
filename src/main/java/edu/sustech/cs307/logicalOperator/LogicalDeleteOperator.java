package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Collections;

/**
 * 逻辑删除算子 — 对应 DELETE FROM t WHERE ... 语句。
 *
 * 有一个子节点（LogicalTableScanOperator）。
 * PhysicalPlanner.handleDelete() 将其转换为 DeleteOperator：
 * 1. 打开 SeqScan 遍历全表
 * 2. 先收集所有匹配 WHERE 条件的行的 RID（避免边扫边删导致游标错乱）
 * 3. 批量对每个 RID 调用 fileHandle.DeleteRecord(rid)（bitmap 清零）
 * 4. 输出删除行数
 *
 * 注意：无 WHERE 的 DELETE 不走此算子，直接在 handleDelete() 中调用 dbManager.dropTable() 删表。
 */
public class LogicalDeleteOperator extends LogicalOperator {
    private final String tableName;
    private final Expression whereExpr;

    public LogicalDeleteOperator(LogicalOperator child, String tableName, Expression whereExpr) {
        super(Collections.singletonList(child));
        this.tableName = tableName;
        this.whereExpr = whereExpr;
    }

    public String getTableName() {
        return tableName;
    }

    public Expression getWhereExpr() {
        return whereExpr;
    }

    @Override
    public String toString() {
        return "DeleteOperator(table=" + tableName + ", where=" + whereExpr
                + ")\n ├── " + childern.get(0);
    }
}
