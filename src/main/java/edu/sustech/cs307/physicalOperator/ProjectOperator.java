package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TabCol;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影（SELECT 列选择）物理算子 — Task 2.1.2（答辩可问）。
 *
 * <h3>核心功能</h3>
 * 从子算子输出的完整行中选取指定的列子集，生成 ProjectTuple。
 *
 * <h3>SELECT * 解析（答辩细节）</h3>
 * resolveOutputSchema() 处理：
 * <ul>
 *   <li>{@code SELECT *}：展开为用户请求的所有列（TabCol(tablename, colName)）→ 映射到 child schema</li>
 *   <li>{@code SELECT col1, col2}：按列名 + 表名限定符匹配 child schema 中的列</li>
 *   <li>多表同名列不写表名限定符 → 抛出 AmbiguousColumnName（防止歧义）</li>
 * </ul>
 *
 * <h3>ProjectTuple</h3>
 * ProjectTuple 是原 Tuple 的视图（不拷贝数据），通过索引映射访问底层值：
 * {@code values[i] = originalTuple.getValue(resolvedTabCols[i])}。
 * 这样避免了数据复制，O(#projected_columns) 访问开销。
 *
 * <h3>在计划树中的位置</h3>
 * LogicalPlanner 将 Project 放在计划树最顶层：
 * <pre>
 * ProjectOperator(columns=[t.id, t.name])
 *   └── ... (Filter / Join / SeqScan)
 * </pre>
 */
public class ProjectOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private List<TabCol> outputSchema; // Use bounded wildcard
    private Tuple currentTuple;

    public ProjectOperator(PhysicalOperator child, List<TabCol> outputSchema) throws DBException { // Use bounded wildcard
        // Task 2.1.2 Logical/Physical Operators - Projection: resolve SELECT *
        // and requested columns to concrete child schema columns.
        this.child = child;
        this.outputSchema = resolveOutputSchema(outputSchema);
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

                currentTuple = new ProjectTuple(inputTuple, outputSchema); // Create ProjectTuple
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
                if (outputColumn.getTableName().equals(columnMeta.tableName)
                        && outputColumn.getColumnName().equals(columnMeta.name)) {
                    projectedSchema.add(columnMeta);
                    break;
                }
            }
        }
        return projectedSchema;
    }

    private List<TabCol> resolveOutputSchema(List<TabCol> requestedSchema) throws DBException {
        ArrayList<TabCol> resolved = new ArrayList<>();
        if (requestedSchema.size() == 1 && requestedSchema.get(0).getTableName().equals("*")) {
            for (ColumnMeta columnMeta : child.outputSchema()) {
                resolved.add(new TabCol(columnMeta.tableName, columnMeta.name));
            }
            return resolved;
        }

        for (TabCol requestedColumn : requestedSchema) {
            ColumnMeta match = null;
            int matchedCount = 0;
            for (ColumnMeta columnMeta : child.outputSchema()) {
                boolean tableMatches = requestedColumn.getTableName() == null
                        || requestedColumn.getTableName().isBlank()
                        || requestedColumn.getTableName().equalsIgnoreCase(columnMeta.tableName);
                if (tableMatches && requestedColumn.getColumnName().equalsIgnoreCase(columnMeta.name)) {
                    match = columnMeta;
                    matchedCount++;
                }
            }
            if (matchedCount == 0) {
                throw new DBException(ExceptionTypes.ColumnDoesNotExist(requestedColumn.getColumnName()));
            }
            if (matchedCount > 1) {
                throw new DBException(ExceptionTypes.InvalidSQL(requestedColumn.getColumnName(),
                        "Ambiguous column reference"));
            }
            resolved.add(new TabCol(match.tableName, match.name));
        }
        return resolved;
    }
}
