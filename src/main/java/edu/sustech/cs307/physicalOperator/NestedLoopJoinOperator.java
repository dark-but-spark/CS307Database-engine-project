package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

public class NestedLoopJoinOperator implements PhysicalOperator {

    private final PhysicalOperator leftOperator;
    private final PhysicalOperator rightOperator;
    private final Collection<Expression> joinExprs;

    private Tuple leftTuple;
    private Tuple rightTuple;
    private TabCol[] combinedSchema;
    private boolean isOpen;
    private boolean ready;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> joinExprs) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.joinExprs = joinExprs;
        this.isOpen = false;
        this.ready = false;
    }

    @Override
    public boolean hasNext() {
        return isOpen && ready;
    }

    @Override
    public void Begin() throws DBException {
        isOpen = true;
        ready = false;
        leftTuple = null;
        rightTuple = null;

        buildCombinedSchema();

        leftOperator.Begin();
        rightOperator.Begin();

        if (leftOperator.hasNext()) {
            leftOperator.Next();
            leftTuple = leftOperator.Current();
            if (rightOperator.hasNext()) {
                rightOperator.Next();
                rightTuple = rightOperator.Current();
                ready = true;
            }
        }
    }

    @Override
    public void Next() throws DBException {
        if (!isOpen) {
            return;
        }
        advanceRight();
    }

    @Override
    public Tuple Current() {
        if (!isOpen || !ready) {
            return null;
        }
        return new JoinTuple(leftTuple, rightTuple, combinedSchema);
    }

    @Override
    public void Close() {
        if (leftOperator != null) {
            leftOperator.Close();
        }
        if (rightOperator != null) {
            rightOperator.Close();
        }
        isOpen = false;
        ready = false;
        leftTuple = null;
        rightTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        for (ColumnMeta col : leftOperator.outputSchema()) {
            schema.add(col);
        }
        for (ColumnMeta col : rightOperator.outputSchema()) {
            schema.add(col);
        }
        return schema;
    }

    private void advanceRight() throws DBException {
        if (rightOperator.hasNext()) {
            rightOperator.Next();
            rightTuple = rightOperator.Current();
            ready = true;
            return;
        }

        rightOperator.Close();
        if (leftOperator.hasNext()) {
            leftOperator.Next();
            leftTuple = leftOperator.Current();
            rightOperator.Begin();
            if (rightOperator.hasNext()) {
                rightOperator.Next();
                rightTuple = rightOperator.Current();
                ready = true;
            } else {
                ready = false;
            }
        } else {
            ready = false;
        }
    }

    private void buildCombinedSchema() {
        ArrayList<ColumnMeta> leftSchema = leftOperator.outputSchema();
        ArrayList<ColumnMeta> rightSchema = rightOperator.outputSchema();
        combinedSchema = new TabCol[leftSchema.size() + rightSchema.size()];
        int i = 0;
        for (ColumnMeta col : leftSchema) {
            combinedSchema[i++] = new TabCol(col.tableName, col.name);
        }
        for (ColumnMeta col : rightSchema) {
            combinedSchema[i++] = new TabCol(col.tableName, col.name);
        }
    }
}
