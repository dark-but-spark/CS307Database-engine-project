package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.Collections;
import java.util.List;

/**
 * 逻辑排序算子 — 对应 SQL 的 ORDER BY 子句。
 *
 * SELECT * FROM t ORDER BY col1 ASC, col2 DESC
 *   → LogicalOrderByOperator(TableScan, orderBy=[col1 ASC, col2 DESC])
 *
 * PhysicalPlanner 转换为 OrderByOperator：
 * 1. 把子算子的所有行读入内存 List<Tuple>
 * 2. 构建多列 Comparator：按 orderByElements 顺序依次比较
 *    ASC → cmp, DESC → -cmp
 * 3. 排序后逐行输出
 *
 * 实现是物化排序（全部加载到内存），大数据集可能 OOM。
 */
public class LogicalOrderByOperator extends LogicalOperator {
    private final List<OrderByElement> orderByElements;

    public LogicalOrderByOperator(LogicalOperator child, List<OrderByElement> orderByElements) {
        super(Collections.singletonList(child));
        this.orderByElements = orderByElements;
    }

    public List<OrderByElement> getOrderByElements() { return orderByElements; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OrderByOperator(orderBy=").append(orderByElements).append(")");
        String[] childLines = childern.get(0).toString().split("\\R");
        sb.append("\n└── ").append(childLines[0]);
        for (int i = 1; i < childLines.length; i++) {
            sb.append("\n    ").append(childLines[i]);
        }
        return sb.toString();
    }
}
