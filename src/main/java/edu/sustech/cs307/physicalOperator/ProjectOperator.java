package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影算子 — 对应 SELECT 列列表的运行时执行。
 *
 * 包裹一个子算子，将每行通过 ProjectTuple 裁剪到只输出需要的列。
 *
 * SELECT * 的处理：
 * 构造时如果 outputSchema 是 TabCol("*", "*")，展开为子算子的全部列。
 *
 * SELECT t.id, t.name 的处理：
 * 每行用 ProjectTuple(inputTuple, outputSchema) 包装，
 * ProjectTuple.getValue() 根据 outputSchema 从 inputTuple 中按名称匹配取值。
 *
 * outputSchema() 返回的是该算子实际输出的列元数据（从子算子 schema 中筛选匹配）。
 */
public class ProjectOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private List<TabCol> outputSchema;
    private Tuple currentTuple;

    public ProjectOperator(PhysicalOperator child, List<TabCol> outputSchema) {
        this.child = child;
        this.outputSchema = outputSchema;
        // SELECT *：展开为子算子的全部列
        if (this.outputSchema.size() == 1 && this.outputSchema.get(0).getTableName().equals("*")) {
            List<TabCol> newOutputSchema = new ArrayList<>();
            for (ColumnMeta tabCol : child.outputSchema()) {
                newOutputSchema.add(new TabCol(tabCol.tableName, tabCol.name));
            }
            this.outputSchema = newOutputSchema;
        }
    }

    @Override
    public boolean hasNext() throws DBException {
        return child.hasNext();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
    }

    @Override
    public void Next() throws DBException {
        if (hasNext()) {
            child.Next();
            Tuple inputTuple = child.Current();
            if (inputTuple != null) {
                currentTuple = new ProjectTuple(inputTuple, outputSchema);
            } else {
                currentTuple = null;
            }
        } else {
            currentTuple = null;
        }
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> childSchema = child.outputSchema();
        ArrayList<ColumnMeta> projectedSchema = new ArrayList<>();
        for (TabCol outputColumn : outputSchema) {
            for (ColumnMeta columnMeta : childSchema) {
                boolean tableMatches = outputColumn.getTableName().equals(columnMeta.tableName)
                        || outputColumn.getTableName().equals(outputColumn.getColumnName());
                if (tableMatches && outputColumn.getColumnName().equals(columnMeta.name)) {
                    projectedSchema.add(columnMeta);
                    break;
                }
            }
        }
        return projectedSchema;
    }
}
