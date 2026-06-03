package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * UPDATE 物理算子 — Task 2.0.2 数据操作。
 *
 * 执行流程（两阶段：扫描 + 原地更新）:
 *
 *
 * 
 *   <li>Begin() 从子算子（SeqScan 或 IndexScan）扫描所有匹配行，收集待更新 RID
 *   <li>遍历收集的 RID，对每个执行更新
 * 
 *
 * 为什么先收集后更新？（答辩可问）:
 *
 *
 * 如果边扫描边更新，更新后的记录可能被重复扫描（若表扫描按页遍历）。
 * "collect-then-update" 确保只处理一次。
 *
 * 原地更新（In-place Update）:
 *
 *
 * 
 *   <li>UpdateRecord() 在相同 RID 位置写回新记录（不重新分配 slot）
 *   <li>先读旧记录 → 提取 oldValues → 构造新 ByteBuf → 写回同位置
 * 
 *
 * 索引同步（关键步骤）:
 *
 *
 * UPDATE 对索引列的影响 = DELETE(旧值) + INSERT(新值)：
 * 
 *   <li>遍历表的所有索引 → 找到被更新的索引列 → index.delete(oldValue, rid)
 *   <li>再 index.insert(newValue, rid) 同步新值到 B+Tree
 * 
 *
 * 接受 IndexScanOperator 作为输入:
 *
 *
 * 如果 WHERE 条件命中索引，PhysicalPlanner 会传入 IndexScanOperator。
 * UpdateOperator 通过子算子获取 RecordFileHandle 做原地更新。
 */
public class UpdateOperator implements PhysicalOperator {
    private final PhysicalOperator inputOp;
    private final DBManager dbManager;
    private final String tableName;
    private final UpdateSet updateSet;
    private final Expression whereExpr;

    private int updateCount;
    private boolean isDone;
    private RecordFileHandle fileHandle;

    public UpdateOperator(PhysicalOperator inputOperator, DBManager dbManager, String tableName, UpdateSet updateSet,
                          Expression whereExpr) {
        // Task 3.1 Index Support - Dynamic UPDATE Maintenance: UpdateOperator now
        // accepts both SeqScanOperator (full table scan) and IndexScanOperator
        // (targeted index lookup) as its input pipeline.
        // REVIEW(Task 3.1 Index Support - Dynamic UPDATE Maintenance): The
        // fileHandle of the input scan operator is extracted during Begin()
        // because SeqScanOperator and IndexScanOperator initialize their
        // RecordFileHandle lazily. If this causes lifecycle coupling issues
        // for multi-use operators, switch to a common ScanOperator interface.
        if (!(inputOperator instanceof SeqScanOperator)
                && !(inputOperator instanceof IndexScanOperator)) {
            throw new RuntimeException(
                    "UpdateOperator requires SeqScanOperator or IndexScanOperator, got: "
                            + inputOperator.getClass().getSimpleName());
        }
        this.inputOp = inputOperator;
        this.dbManager = dbManager;
        this.tableName = tableName;
        this.updateSet = updateSet;
        this.whereExpr = whereExpr;
        this.updateCount = 0;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    @Override
    public void Begin() throws DBException {
        // Task 2.0.2 Data Operations - UPDATE: scan rows, evaluate WHERE, rewrite matched
        // records, and count affected rows.
        inputOp.Begin();
        // Extract fileHandle after Begin() so RecordFileHandle is initialized.
        if (inputOp instanceof SeqScanOperator seqScan) {
            this.fileHandle = seqScan.getFileHandle();
        } else if (inputOp instanceof IndexScanOperator indexScan) {
            this.fileHandle = indexScan.getFileHandle();
        } else {
            throw new RuntimeException("Unexpected scan operator type: " + inputOp.getClass().getSimpleName());
        }

        while (inputOp.hasNext()) {
            inputOp.Next();
            TableTuple tuple = (TableTuple) inputOp.Current();

            if (whereExpr == null || tuple.eval_expr(whereExpr)) {
                Value[] oldValues = tuple.getValues();
                List<Value> newValues = new ArrayList<>(Arrays.asList(oldValues));
                TabCol[] schema = tuple.getTupleSchema();

                for (int i = 0; i < this.updateSet.getColumns().size(); i++) {
                    String targetTable = updateSet.getColumn(i).getTableName();
                    if (targetTable == null) targetTable = tuple.getTableName();
                    String targetColumn = updateSet.getColumn(i).getColumnName();
                    int index = -1;
                    for (int j = 0; j < schema.length; j++) {
                        if (schema[j].getColumnName().equalsIgnoreCase(targetColumn)
                                && schema[j].getTableName().equalsIgnoreCase(targetTable)) {
                            index = j;
                            break;
                        }
                    }
                    if (index == -1) {
                        throw new DBException(ExceptionTypes.ColumnDoesNotExist(targetColumn));
                    }
                    Value newValue = tuple.evaluateExpression(updateSet.getValue(i));
                    newValues.set(index, newValue);
                }
                ByteBuf buffer = Unpooled.buffer();
                List<ColumnMeta> columns = new ArrayList<>();
                for (TabCol tabCol : schema) {
                    for (ColumnMeta columnMeta : inputOp.outputSchema()) {
                        if (columnMeta.tableName.equals(tabCol.getTableName())
                                && columnMeta.name.equals(tabCol.getColumnName())) {
                            columns.add(columnMeta);
                            break;
                        }
                    }
                }
                RecordSerializer.writeRow(buffer, newValues, columns);

                fileHandle.UpdateRecord(tuple.getRID(), buffer);
                // Task 3.1 Index Support - Dynamic UPDATE Maintenance: keep
                // indexed keys synchronized after in-place record rewrites.
                dbManager.updateIndexes(tableName, tuple.getRID(), oldValues, newValues.toArray(new Value[0]));
                updateCount++;
            }
        }
    }

    @Override
    public void Next() {
        isDone = true;
    }

    @Override
    public Tuple Current() {
        if (isDone) {
            ArrayList<Value> result = new ArrayList<>();
            result.add(new Value(updateCount, ValueType.INTEGER));
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
        schema.add(new ColumnMeta("update", "numberOfUpdatedRows", ValueType.INTEGER, 0, 0));
        return schema;
    }

    public void reset() {
        updateCount = 0;
        isDone = false;
    }

    public Tuple getNextTuple() {
        if (hasNext()) {
            Next();
            return Current();
        }
        return null;
    }

    public void close() {
        Close();
    }

    public String getTableName() {
        return tableName;
    }
}