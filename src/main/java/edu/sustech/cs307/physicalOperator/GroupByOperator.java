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

        // Build output schema
        buildOutputSchema();

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
