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
 * 更新算子 — UPDATE 的运行时执行。
 *
 * UPDATE t SET col1 = v1, col2 = v2 WHERE cond
 *
 * Begin() 执行流程：
 * 1. 打开 SeqScan 遍历全表
 * 2. 对每行 eval_expr(whereExpr) 判断是否匹配
 * 3. 匹配的行执行以下步骤：
 *    a. 保存旧值 oldValues（用于索引更新）
 *    b. 根据 SET 子句构建新值数组 newValues（从旧值拷贝 + 覆盖 SET 指定的列）
 *    c. RecordSerializer.writeRow() 序列化新值
 *    d. fileHandle.UpdateRecord(rid, newBuf) 原地覆写磁盘记录
 *    e. dbManager.updateIndexes() 同步更新 B+Tree 索引（删旧键 + 插新键）
 * 4. 输出影响行数
 *
 * SET 子句值计算：tuple.evaluateExpression(updateSet.getValue(i))
 * 支持常量值（'apple'）和表达式引用。
 */
public class UpdateOperator implements PhysicalOperator {
    private final SeqScanOperator seqScanOperator;
    private final DBManager dbManager;
    private final String tableName;
    private final UpdateSet updateSet;
    private final Expression whereExpr;

    private int updateCount;
    private boolean isDone;

    public UpdateOperator(PhysicalOperator inputOperator, DBManager dbManager, String tableName, UpdateSet updateSet,
                          Expression whereExpr) {
        if (!(inputOperator instanceof SeqScanOperator seqScanOperator)) {
            throw new RuntimeException("The delete operator only accepts SeqScanOperator as input");
        }
        this.seqScanOperator = seqScanOperator;
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

    /**
     * 核心更新逻辑：
     * 1. SeqScan 遍历全表
     * 2. WHERE 条件匹配 → 构建新行 → 原地覆写 → 更新索引
     */
    @Override
    public void Begin() throws DBException {
        seqScanOperator.Begin();
        RecordFileHandle fileHandle = seqScanOperator.getFileHandle();

        while (seqScanOperator.hasNext()) {
            seqScanOperator.Next();
            TableTuple tuple = (TableTuple) seqScanOperator.Current();

            if (whereExpr == null || tuple.eval_expr(whereExpr)) {
                Value[] oldValues = tuple.getValues();
                List<Value> newValues = new ArrayList<>(Arrays.asList(oldValues));
                TabCol[] schema = tuple.getTupleSchema();

                // 处理 SET 子句：找到对应列位置，用新值替换
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
                // 序列化新行 → 原地覆写磁盘
                ByteBuf buffer = Unpooled.buffer();
                List<ColumnMeta> columns = new ArrayList<>();
                for (TabCol tabCol : schema) {
                    for (ColumnMeta columnMeta : seqScanOperator.outputSchema()) {
                        if (columnMeta.tableName.equals(tabCol.getTableName())
                                && columnMeta.name.equals(tabCol.getColumnName())) {
                            columns.add(columnMeta);
                            break;
                        }
                    }
                }
                RecordSerializer.writeRow(buffer, newValues, columns);

                fileHandle.UpdateRecord(tuple.getRID(), buffer);
                // 同步更新索引：删旧键 + 插新键
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
        seqScanOperator.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("update", "numberOfUpdatedRows", ValueType.INTEGER, 0, 0));
        return schema;
    }

    public void reset() { updateCount = 0; isDone = false; }
    public Tuple getNextTuple() { if (hasNext()) { Next(); return Current(); } return null; }
    public void close() { Close(); }
    public String getTableName() { return tableName; }
}
