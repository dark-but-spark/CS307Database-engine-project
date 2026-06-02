package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.*;

/**
 * GROUP BY 物理算子 — Task 2.2 Advanced（必问 Q&A，答错 = 0 分）。
 *
 * <h3>SQL → 执行流程（答辩可答）</h3>
 * <ol>
 *   <li>LogicalPlanner.handleSelect() 检测到 {@code plainSelect.getGroupBy() != null}
 *       → 创建 LogicalGroupByOperator（在 COUNT/MAX/MIN 短路检测之后）</li>
 *   <li>PhysicalPlanner.handleGroupBy() → 生成 GroupByOperator（子算子 + groupByElement + selectItems）</li>
 *   <li>Begin() 物化所有子算子行到内存 → 按 GROUP BY 列分组 → 计算聚合函数</li>
 * </ol>
 *
 * <h3>核心实现（Begin() 方法）</h3>
 * <ol>
 *   <li>调用 child.Begin() 拉取所有行</li>
 *   <li>{@code LinkedHashMap<String, List<Tuple>>} 按分组键分组（保持插入顺序）</li>
 *   <li>分组键：GROUP BY 列值以 {@code |} 分隔的字符串拼接</li>
 *   <li>逐组调用 {@code computeAggregate()} 计算 count/max/min</li>
 *   <li>{@code buildResultTuple()} 按 SELECT 项顺序组装输出行</li>
 * </ol>
 *
 * <h3>支持的聚合函数</h3>
 * <ul>
 *   <li>COUNT(*) — 统计组内行数</li>
 *   <li>COUNT(col) — 统计组内非空列值</li>
 *   <li>MAX(col) — 组内最大值（O(n) 单遍扫描）</li>
 *   <li>MIN(col) — 组内最小值（O(n) 单遍扫描）</li>
 * </ul>
 *
 * <h3>SELECT 列表处理</h3>
 * SELECT 项可以是：
 * <ul>
 *   <li>聚合函数（Function）→ 输出聚合结果值</li>
 *   <li>GROUP BY 列（Column）→ 输出该组的分组键值</li>
 *   <li>{@code *}（AllColumns）→ 输出所有分组键值</li>
 * </ul>
 *
 * <h3>当前限制（TODO）</h3>
 * <ul>
 *   <li>仅支持单个聚合函数（parseAggregate 取第一个 Function 即 return）</li>
 *   <li>不支持 HAVING 子句过滤</li>
 *   <li>Begin() 中物化所有子算子行到内存（非流式/哈希聚合）</li>
 *   <li>分组键使用字符串拼接（非类型化复合键）</li>
 * </ul>
 *
 * @see LogicalPlanner#handleSelect 逻辑计划中的 GROUP BY 检测
 * @see PhysicalPlanner#handleGroupBy 物理计划生成
 */
public class GroupByOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<TabCol> groupByCols;
    private final String tableName;

    // Aggregate info
    private String aggFuncName;       // "count", "max", "min"
    private String aggColumnName;     // null for COUNT(*)
    private boolean aggIsStar;

    // Output schema info
    private final List<SelectItem<?>> selectItems;
    private ArrayList<ColumnMeta> outputSchema;

    // Results
    private List<Tuple> resultTuples;
    private int currentIndex;
    private boolean isOpen;

    @SuppressWarnings("deprecation")
    public GroupByOperator(PhysicalOperator child, GroupByElement groupByElement,
                           List<SelectItem<?>> selectItems, String tableName) {
        this.child = child;
        this.selectItems = selectItems;
        this.tableName = tableName;

        this.groupByCols = new ArrayList<>();
        if (groupByElement.getGroupByExpressions() != null) {
            for (var expr : groupByElement.getGroupByExpressions().getExpressions()) {
                if (expr instanceof Column col) {
                    String colName = col.getColumnName();
                    this.groupByCols.add(new TabCol(col.getTableName() != null ? col.getTableName() : tableName, colName));
                }
            }
        }

        parseAggregate();
        buildOutputSchema();
        this.resultTuples = null;
        this.currentIndex = -1;
        this.isOpen = false;
    }

    @SuppressWarnings("deprecation")
    private void parseAggregate() {
        // TODO(Task 2.2): Support multiple aggregate expressions, HAVING, and
        // non-aggregate projected columns according to SQL GROUP BY rules.
        for (SelectItem<?> item : selectItems) {
            if (item.getExpression() instanceof Function f) {
                String name = f.getName().toLowerCase();
                if (name.equals("count") || name.equals("max") || name.equals("min")) {
                    this.aggFuncName = name;
                    var params = f.getParameters();
                    if (params == null || params.getExpressions() == null || params.getExpressions().isEmpty()) {
                        this.aggIsStar = true;
                    } else {
                        var first = params.getExpressions().get(0);
                        if (first instanceof AllColumns) {
                            this.aggIsStar = true;
                        } else if (first instanceof Column col) {
                            this.aggIsStar = false;
                            this.aggColumnName = col.getColumnName();
                        } else {
                            this.aggIsStar = true;
                        }
                    }
                    return;
                }
            }
        }
    }

    @Override
    public boolean hasNext() {
        return isOpen && resultTuples != null && currentIndex < resultTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        isOpen = true;
        resultTuples = new ArrayList<>();
        currentIndex = 0;

        child.Begin();

        // groupKey(pipe-separated) → list of tuples
        // TODO(Task 2.2): Use a typed composite group key and streaming/hash
        // aggregation to avoid materializing every tuple per group.
        Map<String, List<Tuple>> groups = new LinkedHashMap<>();
        Map<String, List<Value>> groupKeyValueMap = new LinkedHashMap<>();

        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) continue;

            String key = buildGroupKey(tuple);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tuple);
            if (!groupKeyValueMap.containsKey(key)) {
                groupKeyValueMap.put(key, extractGroupKeyValues(tuple));
            }
        }

        // Compute aggregate for each group
        for (var entry : groups.entrySet()) {
            String key = entry.getKey();
            List<Tuple> groupTuples = entry.getValue();
            List<Value> groupKeyVals = groupKeyValueMap.get(key);
            Value aggResult = computeAggregate(groupTuples);
            resultTuples.add(buildResultTuple(groupKeyVals, aggResult));
        }
    }

    @Override
    public void Next() {
        if (hasNext()) {
            currentIndex++;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || resultTuples == null || currentIndex <= 0
                || currentIndex > resultTuples.size()) {
            return null;
        }
        return resultTuples.get(currentIndex - 1);
    }

    @Override
    public void Close() {
        child.Close();
        resultTuples = null;
        currentIndex = -1;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        if (outputSchema == null) {
            buildOutputSchema();
        }
        return outputSchema;
    }

    private String buildGroupKey(Tuple tuple) throws DBException {
        StringBuilder sb = new StringBuilder();
        for (TabCol col : groupByCols) {
            Value v = tuple.getValue(col);
            sb.append(v != null ? v.value.toString() : "NULL");
            sb.append("|");
        }
        return sb.toString();
    }

    private List<Value> extractGroupKeyValues(Tuple tuple) throws DBException {
        List<Value> values = new ArrayList<>();
        for (TabCol col : groupByCols) {
            values.add(tuple.getValue(col));
        }
        return values;
    }

    private Value computeAggregate(List<Tuple> groupTuples) throws DBException {
        return switch (aggFuncName) {
            case "count" -> {
                if (aggIsStar) {
                    yield new Value((long) groupTuples.size(), ValueType.INTEGER);
                } else {
                    long cnt = 0;
                    for (Tuple t : groupTuples) {
                        Value v = t.getValue(new TabCol(tableName, aggColumnName));
                        if (v != null) cnt++;
                    }
                    yield new Value(cnt, ValueType.INTEGER);
                }
            }
            case "max" -> {
                Value best = null;
                for (Tuple t : groupTuples) {
                    Value v = t.getValue(new TabCol(tableName, aggColumnName));
                    if (v == null) continue;
                    if (best == null || ValueComparer.compare(v, best) > 0) {
                        best = v;
                    }
                }
                yield best != null ? best : new Value((long) 0, ValueType.INTEGER);
            }
            case "min" -> {
                Value best = null;
                for (Tuple t : groupTuples) {
                    Value v = t.getValue(new TabCol(tableName, aggColumnName));
                    if (v == null) continue;
                    if (best == null || ValueComparer.compare(v, best) < 0) {
                        best = v;
                    }
                }
                yield best != null ? best : new Value((long) 0, ValueType.INTEGER);
            }
            default -> new Value((long) groupTuples.size(), ValueType.INTEGER);
        };
    }

    @SuppressWarnings("deprecation")
    private Tuple buildResultTuple(List<Value> groupKeyVals, Value aggResult) {
        List<Value> row = new ArrayList<>();
        // Build row in SELECT item order
        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof Function) {
                row.add(aggResult);
            } else if (expr instanceof Column col) {
                String colName = col.getColumnName();
                // Find matching group-by column
                boolean found = false;
                for (int i = 0; i < groupByCols.size(); i++) {
                    if (groupByCols.get(i).getColumnName().equals(colName)) {
                        row.add(groupKeyVals.get(i));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // Try table-qualified match
                    for (int i = 0; i < groupByCols.size(); i++) {
                        String qualified = groupByCols.get(i).getTableName() + "." + groupByCols.get(i).getColumnName();
                        if (qualified.equals(colName) || groupByCols.get(i).getColumnName().equals(colName)) {
                            row.add(groupKeyVals.get(i));
                            found = true;
                            break;
                        }
                    }
                }
            } else if (expr instanceof AllColumns) {
                row.addAll(groupKeyVals);
            } else {
                row.add(new Value((long) 0, ValueType.INTEGER));
            }
        }
        return new TempTuple(row);
    }

    @SuppressWarnings("deprecation")
    private void buildOutputSchema() {
        outputSchema = new ArrayList<>();
        ArrayList<ColumnMeta> childSchema = child.outputSchema();

        for (SelectItem<?> item : selectItems) {
            Expression expr = item.getExpression();
            if (expr instanceof Function f) {
                String name = f.getName().toLowerCase();
                ValueType outType = ValueType.INTEGER;
                if ((name.equals("max") || name.equals("min")) && aggColumnName != null) {
                    // Look up the column type from child schema
                    for (ColumnMeta cm : childSchema) {
                        if (cm.name.equals(aggColumnName) || cm.tableName.equals(tableName)) {
                            outType = cm.type;
                            break;
                        }
                    }
                }
                outputSchema.add(new ColumnMeta("", name, outType, 0, 0));
            } else if (expr instanceof Column col) {
                String colName = col.getColumnName();
                boolean found = false;
                for (ColumnMeta cm : childSchema) {
                    if (cm.name.equals(colName)) {
                        outputSchema.add(cm);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    outputSchema.add(new ColumnMeta("", colName, ValueType.INTEGER, 0, 0));
                }
            } else if (expr instanceof AllColumns) {
                for (TabCol gc : groupByCols) {
                    outputSchema.add(new ColumnMeta("", gc.getColumnName(), ValueType.INTEGER, 0, 0));
                }
            }
        }
    }
}
