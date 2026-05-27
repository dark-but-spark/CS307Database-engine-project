package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.Collections;
import java.util.List;

/**
 * 逻辑更新算子 — 对应 SQL 的 UPDATE 语句。
 *
 * UPDATE t SET col1 = val1, col2 = val2 WHERE cond
 *   → LogicalUpdateOperator(TableScan, tableName=t, columns=[SET子句], expressions=WHERE)
 *
 * 有一个子节点（通常是 LogicalTableScanOperator）。
 * PhysicalPlanner.handleUpdate() 将其转换为 UpdateOperator：
 * 1. 打开 SeqScan 遍历全表
 * 2. 对每行 eval_expr(whereExpr) 检查匹配
 * 3. 匹配的行：读取旧值 → 计算新值 → fileHandle.UpdateRecord(rid, newBuf) 原地覆写
 * 4. 同步更新 B+Tree 索引（先删旧键，再插新键）
 * 5. 输出影响行数
 */
public class LogicalUpdateOperator extends LogicalOperator {
    private final String tableName;
    private final List<UpdateSet> columns;
    private final Expression expressions;

    public LogicalUpdateOperator(LogicalOperator child, String tableName, List<UpdateSet> columns,
                                 Expression expressions) {
        super(Collections.singletonList(child));
        this.tableName = tableName;
        this.columns = columns;
        this.expressions = expressions;
    }

    public String getTableName() {
        return tableName;
    }

    public List<UpdateSet> getColumns() {
        return columns;
    }

    public Expression getExpression() {
        return expressions;
    }

    @Override
    public String toString() {
        return "UpdateOperator(table=" + tableName + ", columns=" + columns + ", expressions=" + expressions
                + ")\n ├── " + childern.get(0);
    }
}
