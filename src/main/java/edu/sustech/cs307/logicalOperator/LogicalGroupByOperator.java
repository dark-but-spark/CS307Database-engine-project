package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.Collections;
import java.util.List;

/**
 * 逻辑 GROUP BY 聚合算子 — 对应 SELECT ... GROUP BY col。
 *
 * 有一个子节点（通常是 LogicalTableScanOperator，如果带 WHERE 则是 LogicalFilterOperator）。
 * groupByElement 指定分组列，selectItems 包含聚合函数（COUNT/SUM/MAX 等）。
 *
 * PhysicalPlanner 转换为 GroupByOperator：
 * 1. 遍历所有行，按 groupBy 列的值分组到 HashMap<Key, List<Row>>
 * 2. 对每组行构造临时 Tuple，对 SELECT 中的聚合表达式求值
 * 3. 每组输出一行聚合结果
 */
public class LogicalGroupByOperator extends LogicalOperator {
    private final GroupByElement groupByElement;
    private final List<SelectItem<?>> selectItems;
    private final String tableName;

    public LogicalGroupByOperator(LogicalOperator child, GroupByElement groupByElement,
                                  List<SelectItem<?>> selectItems, String tableName) {
        super(Collections.singletonList(child));
        this.groupByElement = groupByElement;
        this.selectItems = selectItems;
        this.tableName = tableName;
    }

    public GroupByElement getGroupByElement() { return groupByElement; }
    public List<SelectItem<?>> getSelectItems() { return selectItems; }
    public String getTableName() { return tableName; }

    @Override
    public String toString() {
        return "GroupByOperator(groupBy=" + groupByElement + ", select=" + selectItems
                + ")\n └── " + childern.get(0);
    }
}
