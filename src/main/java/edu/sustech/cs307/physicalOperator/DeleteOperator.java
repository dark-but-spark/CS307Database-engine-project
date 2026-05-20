package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.List;

public class DeleteOperator implements PhysicalOperator {
    private final PhysicalOperator inputOp;
    private final DBManager dbManager;
    private final String tableName;
    private final Expression whereExpr;

    private int deleteCount;
    private boolean isDone;
    private RecordFileHandle fileHandle;

    public DeleteOperator(PhysicalOperator inputOperator, DBManager dbManager, String tableName, Expression whereExpr) {
        // Task 3.1 Index Support - Dynamic DELETE Maintenance: DeleteOperator now
        // accepts both SeqScanOperator and IndexScanOperator as its input pipeline,
        // since both provide stable RIDs for record deletion.
        // REVIEW(Task 3.1 Index Support - Dynamic DELETE Maintenance): The
        // RecordFileHandle is extracted during Begin() because the scan operator
        // initializes it lazily. A common ScanOperator interface would eliminate
        // the instanceof dispatch here.
        if (!(inputOperator instanceof SeqScanOperator)
                && !(inputOperator instanceof IndexScanOperator)) {
            throw new RuntimeException(
                    "DeleteOperator requires SeqScanOperator or IndexScanOperator, got: "
                            + inputOperator.getClass().getSimpleName());
        }
        this.inputOp = inputOperator;
        this.dbManager = dbManager;
        this.tableName = tableName;
        this.whereExpr = whereExpr;
        this.deleteCount = 0;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    @Override
    public void Begin() throws DBException {
        inputOp.Begin();
        // Extract fileHandle after Begin() so RecordFileHandle is initialized.
        if (inputOp instanceof SeqScanOperator seqScan) {
            this.fileHandle = seqScan.getFileHandle();
        } else if (inputOp instanceof IndexScanOperator indexScan) {
            this.fileHandle = indexScan.getFileHandle();
        } else {
            throw new RuntimeException("Unexpected scan operator type: " + inputOp.getClass().getSimpleName());
        }
        List<RID> toDelete = new ArrayList<>();
        List<Value[]> deletedValues = new ArrayList<>();

        while (inputOp.hasNext()) {
            inputOp.Next();
            TableTuple tuple = (TableTuple) inputOp.Current();

            if (whereExpr == null || tuple.eval_expr(whereExpr)) {
                toDelete.add(new RID(tuple.getRID()));
                deletedValues.add(tuple.getValues());
            }
        }

        // REVIEW(Task 3.1 Index Support - Dynamic DELETE Maintenance): Index
        // entries are removed after each record slot is cleared. If persistent
        // indexes are introduced, record and index updates should become atomic.
        for (int i = 0; i < toDelete.size(); i++) {
            RID rid = toDelete.get(i);
            fileHandle.DeleteRecord(rid);
            dbManager.deleteFromIndexes(tableName, rid, deletedValues.get(i));
        }
        deleteCount = toDelete.size();
    }

    @Override
    public void Next() {
        isDone = true;
    }

    @Override
    public Tuple Current() {
        if (isDone) {
            ArrayList<Value> result = new ArrayList<>();
            result.add(new Value(deleteCount, ValueType.INTEGER));
            return new TempTuple(result);
        } else {
            throw new RuntimeException("Call Next() first");
        }
    }

    @Override
    public void Close() {
        inputOp.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("delete", "numberOfDeletedRows", ValueType.INTEGER, 0, 0));
        return schema;
    }
}