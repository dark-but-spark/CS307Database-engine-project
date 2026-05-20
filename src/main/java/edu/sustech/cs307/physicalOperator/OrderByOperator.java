package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OrderByOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<OrderByElement> orderByElements;

    private List<Tuple> sortedTuples;
    private int currentIndex;
    private boolean isOpen;

    public OrderByOperator(PhysicalOperator child, List<OrderByElement> orderByElements) {
        this.child = child;
        this.orderByElements = orderByElements;
        this.sortedTuples = null;
        this.currentIndex = -1;
        this.isOpen = false;
    }

    @Override
    public boolean hasNext() {
        return isOpen && sortedTuples != null && currentIndex < sortedTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        isOpen = true;
        sortedTuples = new ArrayList<>();
        currentIndex = 0;

        child.Begin();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null) {
                sortedTuples.add(tuple);
            }
        }

        // TODO(Task 2.2): Add NULL ordering semantics and an external-sort path
        // for result sets that do not fit comfortably in memory.
        List<SortKey> sortKeys = buildSortKeys();
        Comparator<Tuple> comparator = buildComparator(sortKeys);
        sortedTuples.sort(comparator);
    }

    @Override
    public void Next() {
        if (hasNext()) {
            currentIndex++;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || sortedTuples == null || currentIndex <= 0
                || currentIndex > sortedTuples.size()) {
            return null;
        }
        return sortedTuples.get(currentIndex - 1);
    }

    @Override
    public void Close() {
        child.Close();
        sortedTuples = null;
        currentIndex = -1;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }

    private Comparator<Tuple> buildComparator(List<SortKey> sortKeys) {
        return (a, b) -> {
            for (SortKey key : sortKeys) {
                Value va;
                Value vb;
                try {
                    if (key.column() != null) {
                        va = a.getValue(key.column());
                        vb = b.getValue(key.column());
                    } else {
                        va = a.evaluateExpression(key.expression());
                        vb = b.evaluateExpression(key.expression());
                    }

                    int cmp = ValueComparer.compare(va, vb);
                    if (cmp != 0) {
                        return key.asc() ? cmp : -cmp;
                    }
                } catch (DBException e) {
                    throw new RuntimeException("OrderBy comparison failed: " + e.getMessage(), e);
                }
            }
            return 0;
        };
    }

    private List<SortKey> buildSortKeys() throws DBException {
        ArrayList<ColumnMeta> schema = child.outputSchema();
        List<SortKey> sortKeys = new ArrayList<>();
        for (OrderByElement element : orderByElements) {
            Expression expr = element.getExpression();
            boolean asc = !element.isAscDescPresent() || element.isAsc();
            if (expr instanceof Column col) {
                sortKeys.add(new SortKey(resolveColumn(col, schema), null, asc));
            } else {
                sortKeys.add(new SortKey(null, expr, asc));
            }
        }
        return sortKeys;
    }

    private TabCol resolveColumn(Column col, ArrayList<ColumnMeta> schema) throws DBException {
        String tableName = col.getTableName();
        String columnName = col.getColumnName();

        if (tableName != null) {
            return new TabCol(tableName, columnName);
        }

        ColumnMeta match = null;
        int matchedCount = 0;
        for (ColumnMeta colMeta : schema) {
            if (colMeta.name.equalsIgnoreCase(columnName)) {
                match = colMeta;
                matchedCount++;
            }
        }
        if (matchedCount == 0) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
        }
        if (matchedCount > 1) {
            throw new DBException(ExceptionTypes.InvalidSQL(columnName, "Ambiguous column reference"));
        }
        return new TabCol(match.tableName, match.name);
    }

    private record SortKey(TabCol column, Expression expression, boolean asc) {
    }
}
