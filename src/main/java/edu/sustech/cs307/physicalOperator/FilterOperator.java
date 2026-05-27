package edu.sustech.cs307.physicalOperator;

import net.sf.jsqlparser.expression.Expression;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;
import java.util.ArrayList;
import java.util.Collection;

import edu.sustech.cs307.exception.DBException;
import org.pmw.tinylog.Logger;

/**
 * 过滤算子 — 对应 WHERE 子句的运行时执行。
 *
 * 包裹一个子算子（通常是 SeqScan 或 NestedLoopJoin），
 * 用 whereExpr（JSqlParser Expression 树）对每行做条件检查。
 *
 * 执行流程：
 * 1. Begin() 打开子算子
 * 2. hasNext() 调用 findNext() 从子算子拉取行，直到找到满足条件的
 * 3. findNext() 的核心：while(child.hasNext()) { child.Next(); tuple.eval_expr(whereExpr); }
 * 4. 满足条件的行缓存在 currentTuple，上层通过 Next()/Current() 获取
 *
 * 条件求值在 Tuple.evaluateCondition() 中递归处理：
 * AndExpression → 递归左右
 * OrExpression → 递归左右
 * BinaryExpression → 提取左右值 → ValueComparer.compare() → 比较操作符
 *
 * 第二个构造函数（接受 Collection<Expression>）用于 JOIN 场景：
 * NestedLoopJoin 的结果需要同时满足多个 join 条件，只取第一个表达式。
 */
public class FilterOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private Expression whereExpr;
    private Tuple currentTuple;
    private boolean isOpen = false;
    private boolean readyForNext = false;

    public FilterOperator(PhysicalOperator child, Expression whereExpr) {
        this.child = child;
        this.whereExpr = whereExpr;
    }

    public FilterOperator(PhysicalOperator child, Collection<Expression> whereExpr) {
        this.child = child;
        this.whereExpr = whereExpr.iterator().next();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        isOpen = true;
        currentTuple = null;
        readyForNext = false;
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen) return false;
        if (!readyForNext) return findNext();
        return currentTuple != null;
    }

    @Override
    public void Next() throws DBException {
        if (!isOpen) return;
        if (!readyForNext) hasNext();
        readyForNext = false;
    }

    /**
     * 核心过滤逻辑：从子算子拉取行，找到第一个满足 whereExpr 的行。
     * 满足 → 缓存到 currentTuple，返回 true
     * 没有更多行 → 返回 false
     */
    private boolean findNext() throws DBException {
        currentTuple = null;
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null && tuple.eval_expr(whereExpr)) {
                currentTuple = tuple;
                readyForNext = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        if (child != null) child.Close();
        isOpen = false;
        currentTuple = null;
        readyForNext = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }
}
