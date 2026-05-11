package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.ColumnMeta;

import java.util.ArrayList;

public class InMemoryIndexScanOperator implements PhysicalOperator {

    private final InMemoryOrderedIndex index;
    private boolean isOpen;
    private Tuple currentTuple;

    public InMemoryIndexScanOperator(InMemoryOrderedIndex index) {
        this.index = index;
    }

    @Override
    public boolean hasNext() {
        // REVIEW: The operator needs table metadata, a RecordFileHandle, and scan bounds
        // before it can turn index RIDs into tuples. Until the planner provides that
        // context, this is a well-formed empty scan instead of an unfinished stub.
        return false;
    }

    @Override
    public void Begin() throws DBException {
        if (index == null) {
            throw new DBException(ExceptionTypes.UnsupportedOperator("InMemoryIndexScanOperator(null)"));
        }
        isOpen = true;
        currentTuple = null;
    }

    @Override
    public void Next() {
        currentTuple = null;
    }

    @Override
    public Tuple Current() { // Return Tuple
        return currentTuple;
    }

    @Override
    public void Close() {
        isOpen = false;
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return new ArrayList<>();
    }
}
