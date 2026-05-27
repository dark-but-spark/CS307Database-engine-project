package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级删除算子 — Q&A 必问：DELETE 的实现设计。
 *
 * 执行流程：
 * 1. Begin() 打开 SeqScan 遍历全表
 * 2. 先收集所有匹配 WHERE 条件的行的 RID（先扫描，后删除，避免边扫边删导致游标错乱）
 * 3. 对每个 RID 调用 fileHandle.DeleteRecord(rid)
 *    - DeleteRecord 不是物理删除数据，而是把 bitmap 对应位清零
 *    - 被删除的记录数据仍然在磁盘上，但槽位标记为"空闲"可供后续 INSERT 复用
 * 4. 输出：删除的行数
 *
 * 设计要点：
 * - "先收集后删除"策略：扫描时不能立即删除，因为 SeqScan 依赖 bitmap 定位下一条记录，
 *   删除会破坏 bitmap 状态导致漏扫
 * - bitmap 标记删除：不回缩文件，空间可复用
 */
public class DeleteOperator implements PhysicalOperator {
    private final SeqScanOperator seqScanOperator;
    private final String tableName;
    private final Expression whereExpr;

    private int deleteCount;
    private boolean isDone;

    public DeleteOperator(PhysicalOperator inputOperator, String tableName, Expression whereExpr) {
        if (!(inputOperator instanceof SeqScanOperator seqScanOperator)) {
            throw new RuntimeException("The delete operator only accepts SeqScanOperator as input");
        }
        this.seqScanOperator = seqScanOperator;
        this.tableName = tableName;
        this.whereExpr = whereExpr;
        this.deleteCount = 0;
        this.isDone = false;
    }

    @Override
    public boolean hasNext() {
        return !isDone;
    }

    /**
     * 核心删除逻辑：
     * 第一阶段：全表扫描，收集所有匹配 WHERE 条件的行的 RID
     * 第二阶段：对收集到的 RID 逐个执行 DeleteRecord（bitmap 清零）
     *
     * 为什么分两阶段？
     * SeqScan.hasNext() 依赖 bitmap 定位下一条记录。如果在扫描过程中直接删除，
     * bitmap 变化可能导致游标错乱，漏掉某些本应被删除的行。
     */
    @Override
    public void Begin() throws DBException {
        seqScanOperator.Begin();
        RecordFileHandle fileHandle = seqScanOperator.getFileHandle();
        List<RID> toDelete = new ArrayList<>();

        // 第一阶段：扫描收集
        while (seqScanOperator.hasNext()) {
            seqScanOperator.Next();
            TableTuple tuple = (TableTuple) seqScanOperator.Current();

            if (whereExpr == null || tuple.eval_expr(whereExpr)) {
                toDelete.add(new RID(tuple.getRID()));
            }
        }

        // 第二阶段：批量删除
        for (RID rid : toDelete) {
            fileHandle.DeleteRecord(rid);
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
