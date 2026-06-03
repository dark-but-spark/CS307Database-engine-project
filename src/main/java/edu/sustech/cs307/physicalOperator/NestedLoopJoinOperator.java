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

/**
 * 嵌套循环连接（Nested-Loop Join）物理算子 — Task 2.2 Advanced（必问 Q&A，答错 = 0 分）。
 *
 * 算法原理（答辩可逐条说明）:
 *
 *
 * 
 *   <li>左输入（outer）：逐行扫描，每行与右表所有行组合
 *   <li>右输入（inner）：Begin() 中完全物化到内存（List&lt;Tuple&gt;）
 *       — 避免每行左输入都重新打开右算子（N+1 次 Begin/Close）
 *   <li>对每对 (leftTuple, rightTuple) 创建 JoinTuple（Cartesian 候选对）
 *   <li>ON 条件由外层 FilterOperator 判断，本算子只负责生成候选对
 * 
 *
 * 为什么 ON 条件不在这里判断？（答辩高频追问）:
 *
 *
 * PhysicalPlanner.handleJoin() 将 FilterOperator 包在 NestedLoopJoinOperator 之上：
 * <pre>
 * FilterOperator(NestedLoopJoinOperator(left, right), joinExprs)
 * </pre>
 * 这样：
 * 
 *   <li>表达式求值统一走 Tuple.eval_expr()，避免分散在多个算子中实现
 *   <li>JOIN 的 ON 条件与 WHERE 条件复用同一套 FilterOperator 逻辑
 * 
 *
 * 性能特性:
 *
 *
 * 
 *   <li>时间复杂度：O(m × n)，m=左表行数，n=右表行数
 *   <li>空间复杂度：O(n)，右表完全物化在内存
 *   <li>优化方向：block nested-loop（分块处理右表）、hash join（右表建哈希表）
 * 
 *
 * 火山模型实现:
 *
 *
 * findNext() 维护状态机：
 * 
 *   <li>currentLeftTuple == null → 从 leftOperator 取下一行
 *   <li>rightIndex < rightTuples.size() → 取下一个右表元组做笛卡尔积
 *   <li>rightIndex 耗尽 → currentLeftTuple = null，回到外层循环
 * 
 *
 * JoinTuple 合并:
 *
 *
 * 输出 schema = leftOperator.outputSchema() + rightOperator.outputSchema()。
 * 列名前缀来自原始表名，上层算子通过 TabCol(tableName, columnName) 解析。
 */
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
