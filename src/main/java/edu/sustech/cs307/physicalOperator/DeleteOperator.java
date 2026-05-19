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
    private final SeqScanOperator seqScanOperator;
    private final DBManager dbManager;
    private final String tableName;
    private final Expression whereExpr;

    private int deleteCount;
    private boolean isDone;

    public DeleteOperator(PhysicalOperator inputOperator, DBManager dbManager, String tableName, Expression whereExpr) {
        // REVIEW(Task 2.1.2 Logical/Physical Operators - DELETE): Row deletion
        // is tied to SeqScanOperator because it needs stable RIDs from table
        // storage; indexed delete should provide the same RID contract.
        if (!(inputOperator instanceof SeqScanOperator seqScanOperator)) {
            throw new RuntimeException("The delete operator only accepts SeqScanOperator as input");
        }
        this.seqScanOperator = seqScanOperator;
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
        seqScanOperator.Begin();
        RecordFileHandle fileHandle = seqScanOperator.getFileHandle();
        List<RID> toDelete = new ArrayList<>();
        List<Value[]> deletedValues = new ArrayList<>();

        while (seqScanOperator.hasNext()) {
            seqScanOperator.Next();
            TableTuple tuple = (TableTuple) seqScanOperator.Current();

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
        seqScanOperator.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("delete", "numberOfDeletedRows", ValueType.INTEGER, 0, 0));
        return schema;
    }
}
