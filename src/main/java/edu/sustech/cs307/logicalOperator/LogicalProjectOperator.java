package edu.sustech.cs307.logicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 逻辑投影算子 — 对应 SQL 的 SELECT 列列表。
 *
 * SELECT t.id, t.name FROM t
 *   → LogicalProjectOperator(selectItems=[t.id, t.name])
 *        └── LogicalTableScanOperator(table=t)
 *
 * SELECT * FROM t
 *   → LogicalProjectOperator(selectItems=[AllColumns])
 *        └── LogicalFilterOperator(condition=...)
 *              └── LogicalTableScanOperator(table=t)
 *
 * getOutputSchema() 解析 SELECT 列表：
 * - AllColumns (*) → 返回 TabCol("*", "*")，物理层展开为全部列
 * - 具名列 → TabCol("t.id", "t.id")，物理层按名称匹配
 *
 * PhysicalPlanner 将其转换为 ProjectOperator，
 * ProjectOperator 在运行时把每行投影为 ProjectTuple（只保留需要的列）。
 */
public class LogicalProjectOperator extends LogicalOperator {

    private final List<SelectItem<?>> selectItems;
    private final LogicalOperator child;

    public LogicalProjectOperator(LogicalOperator child, List<SelectItem<?>> selectItems) {
        super(Collections.singletonList(child));
        this.child = child;
        this.selectItems = selectItems;
    }

    public LogicalOperator getChild() {
        return child;
    }

    /**
     * 解析 SELECT 列表，生成输出列的 TabCol 描述。
     * SELECT * 会展开为 TabCol("*", "*")，物理层 ProjectOperator 将其展开为全部子列。
     */
    public List<TabCol> getOutputSchema() throws DBException {
        List<TabCol> outputSchema = new ArrayList<>();
        for (SelectItem<?> selectItem : selectItems) {
            if (selectItem.getExpression() instanceof AllColumns column) {
                outputSchema.add(new TabCol("*", "*"));
            } else {
                outputSchema.add(new TabCol(selectItem.getExpression().toString(), selectItem.getExpression().toString()));
            }
        }
        return outputSchema;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "ProjectOperator(selectItems=" + selectItems + ")";
        String[] childLines = child.toString().split("\\R");
        sb.append(nodeHeader);

        if (childLines.length > 0) {
            sb.append("\n└── ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }

}
