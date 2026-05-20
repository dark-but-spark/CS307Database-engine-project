package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TabCol;

import java.util.ArrayList;
import java.util.List;

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
