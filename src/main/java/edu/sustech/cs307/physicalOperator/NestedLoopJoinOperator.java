package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

public class NestedLoopJoinOperator implements PhysicalOperator {

    private final PhysicalOperator leftOperator;
    private final PhysicalOperator rightOperator;
    // REVIEW(Task 2.2 Advanced - Join Operators): Join predicates are carried
    // for plan context, but PhysicalPlanner currently applies them through a
    // FilterOperator above this nested-loop Cartesian product.
    private final Collection<Expression> expr;
    private final ArrayList<ColumnMeta> outputSchema;
    private final TabCol[] tupleSchema;

    private List<Tuple> rightTuples;
    private Tuple currentLeftTuple;
    private Tuple currentTuple;
    private int rightIndex;
    private boolean isOpen;
    private boolean readyForNext;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
        this.outputSchema = new ArrayList<>();
        this.outputSchema.addAll(leftOperator.outputSchema());
        this.outputSchema.addAll(rightOperator.outputSchema());
        this.tupleSchema = buildTupleSchema(this.outputSchema);
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen) {
            return false;
        }
        if (!readyForNext) {
            return findNext();
        }
        return currentTuple != null;
    }

    @Override
    public void Begin() throws DBException {
        // REVIEW(Task 2.2 Advanced - Join Operators): The right input is
        // materialized in memory to avoid reopening operators for each left row.
        // Large joins should move to block nested-loop or streaming rescans.
        rightTuples = new ArrayList<>();
        rightOperator.Begin();
        try {
            while (rightOperator.hasNext()) {
                rightOperator.Next();
                Tuple tuple = rightOperator.Current();
                if (tuple != null) {
                    rightTuples.add(tuple);
                }
            }
        } finally {
            rightOperator.Close();
        }

        leftOperator.Begin();
        currentLeftTuple = null;
        currentTuple = null;
        rightIndex = 0;
        isOpen = true;
        readyForNext = false;
    }

    @Override
    public void Next() throws DBException {
        if (!isOpen) {
            return;
        }
        if (!readyForNext) {
            hasNext();
        }
        readyForNext = false;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        if (isOpen) {
            leftOperator.Close();
        }
        currentLeftTuple = null;
        currentTuple = null;
        rightTuples = null;
        rightIndex = 0;
        isOpen = false;
        readyForNext = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return new ArrayList<>(outputSchema);
    }

    private boolean findNext() throws DBException {
        currentTuple = null;
        if (rightTuples == null || rightTuples.isEmpty()) {
            return false;
        }

        while (true) {
            if (currentLeftTuple == null) {
                if (!leftOperator.hasNext()) {
                    return false;
                }
                leftOperator.Next();
                currentLeftTuple = leftOperator.Current();
                rightIndex = 0;
                if (currentLeftTuple == null) {
                    continue;
                }
            }

            if (rightIndex < rightTuples.size()) {
                Tuple rightTuple = rightTuples.get(rightIndex++);
                // Defense note: this operator produces the Cartesian candidate
                // pair. ON/WHERE predicates are evaluated by FilterOperator so
                // expression handling stays centralized in Tuple.eval_expr().
                currentTuple = new JoinTuple(currentLeftTuple, rightTuple, tupleSchema);
                readyForNext = true;
                return true;
            }

            currentLeftTuple = null;
        }
    }

    private TabCol[] buildTupleSchema(ArrayList<ColumnMeta> schema) {
        ArrayList<TabCol> result = new ArrayList<>();
        for (ColumnMeta columnMeta : schema) {
            result.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        return result.toArray(new TabCol[0]);
    }
}
