package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Collections;

/**
 * 逻辑过滤算子 — 对应 SQL 的 WHERE 子句。
 *
 * 包装一个子算子（通常是 TableScan 或 Join），
 * 保留 WHERE 条件表达式（JSqlParser Expression 树）。
 * PhysicalPlanner 将其转换为 FilterOperator，在运行时对每行执行 eval_expr()。
 *
 * 条件表达式的求值在 Tuple.evaluateCondition() 中递归处理：
 * AndExpression → 左右分别求值后 AND
 * OrExpression  → 左右分别求值后 OR
 * BinaryExpression → 提取左右值 → ValueComparer.compare() → 比较操作符
 */
public class LogicalFilterOperator extends LogicalOperator {
    private final Expression condition;
    private final LogicalOperator child;

    public LogicalFilterOperator(LogicalOperator child, Expression condition) {
        super(Collections.singletonList(child));
        this.child = child;
        this.condition = condition;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public Expression getWhereExpr() {
        return condition;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "LogicalFilterOperator(condition=" + condition + ")";
        LogicalOperator child = getChildren().get(0);

        String[] childLines = child.toString().split("\\R");
        sb.append(nodeHeader);

        if (childLines.length > 0) {
            sb.append("\n    └── ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }

        return sb.toString();
    }
}
