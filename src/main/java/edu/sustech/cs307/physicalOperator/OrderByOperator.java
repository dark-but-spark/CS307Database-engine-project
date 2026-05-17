package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
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

        Comparator<Tuple> comparator = buildComparator();
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

    private Comparator<Tuple> buildComparator() {
        ArrayList<ColumnMeta> schema = child.outputSchema();

        return (a, b) -> {
            for (OrderByElement element : orderByElements) {
                Expression expr = element.getExpression();
                boolean asc = !element.isAscDescPresent() || element.isAsc();

                Value va;
                Value vb;
                try {
                    if (expr instanceof Column col) {
                        TabCol resolved = resolveColumn(col, schema);
                        va = a.getValue(resolved);
                        vb = b.getValue(resolved);
                    } else {
                        va = a.evaluateExpression(expr);
                        vb = b.evaluateExpression(expr);
                    }

                    int cmp = ValueComparer.compare(va, vb);
                    if (cmp != 0) {
                        return asc ? cmp : -cmp;
                    }
                } catch (DBException e) {
                    throw new RuntimeException("OrderBy comparison failed: " + e.getMessage(), e);
                }
            }
            return 0;
        };
    }

    private TabCol resolveColumn(Column col, ArrayList<ColumnMeta> schema) {
        String tableName = col.getTableName();
        String columnName = col.getColumnName();

        if (tableName != null) {
            return new TabCol(tableName, columnName);
        }

        for (ColumnMeta colMeta : schema) {
            if (colMeta.name.equals(columnName)) {
                return new TabCol(colMeta.tableName, columnName);
            }
        }
        return new TabCol(tableName, columnName);
    }
}
