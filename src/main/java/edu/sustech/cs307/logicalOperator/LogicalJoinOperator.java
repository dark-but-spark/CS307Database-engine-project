package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Arrays;
import java.util.Collection;


/**
 * 逻辑连接算子 — 对应 SQL 的 JOIN 子句。
 *
 * SELECT * FROM t1 JOIN t2 ON t1.id = t2.id
 *   → LogicalJoinOperator(left=TableScan(t1), right=TableScan(t2), onExprs=[t1.id=t2.id])
 *
 * 有两个子节点（leftInput / rightInput），depth 标记连接深度的先后顺序。
 *
 * PhysicalPlanner.handleJoin() 将其转换为：
 *   FilterOperator(
 *     NestedLoopJoinOperator(左算子, 右算子),
 *     joinExprs
 *   )
 *
 * 即先做嵌套循环笛卡尔积，再对结果行用 join 条件过滤。
 * 这种实现简单但性能不是最优（没有 hash join 或 sort-merge join）。
 */
public class LogicalJoinOperator extends LogicalOperator {
    private final Collection<Expression> onExpressions;
    private final LogicalOperator leftInput;
    private final LogicalOperator rightInput;

    public LogicalJoinOperator(LogicalOperator left, LogicalOperator right,
            Collection<Expression> onExpr,
            int depth) {
        super(Arrays.asList(left, right));
        this.leftInput = left;
        this.rightInput = right;
        this.onExpressions = onExpr;
    }

    public LogicalOperator getLeftInput() {
        return leftInput;
    }

    public LogicalOperator getRightInput() {
        return rightInput;
    }

    public Collection<Expression> getJoinExprs() {
        return onExpressions;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "LogicalJoinOperator(condition=" + onExpressions + ")";
        String[] leftLines = leftInput.toString().split("\\R");
        String[] rightLines = rightInput.toString().split("\\R");

        sb.append(nodeHeader);

        if (leftLines.length > 0) {
            sb.append("\n├── ").append(leftLines[0]);
            for (int i = 1; i < leftLines.length; i++) {
                sb.append("\n│   ").append(leftLines[i]);
            }
        }

        if (rightLines.length > 0) {
            sb.append("\n└── ").append(rightLines[0]);
            for (int i = 1; i < rightLines.length; i++) {
                sb.append("\n    ").append(rightLines[i]);
            }
        }

        return sb.toString();
    }
}
