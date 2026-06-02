package edu.sustech.cs307.physicalOperator;

import net.sf.jsqlparser.expression.Expression;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;
import java.util.ArrayList;
import java.util.Collection;

import edu.sustech.cs307.exception.DBException;
import org.pmw.tinylog.Logger;

/**
 * 过滤（WHERE）物理算子 — Task 2.1.2（答辩可问）。
 *
 * <h3>火山模型执行流程</h3>
 * 遵循典型的火山模型 readyForNext 模式：
 * <ol>
 *   <li>hasNext() → 调用 findNext() 搜索下一个满足 WHERE 条件的元组</li>
 *   <li>findNext() → 循环从 child 取元组，调用 tuple.eval_expr(whereExpr) 判断</li>
 *   <li>Next() → 消费已准备好的元组，置 readyForNext=false</li>
 * </ol>
 *
 * <h3>表达式求值（eval_expr）</h3>
 * whereExpr 是 JSqlParser 表达式树（支持 AND/OR/= /&gt;/&lt;/&gt;=/&lt;= 等）。
 * Tuple.eval_expr() 递归遍历表达式树求值，返回 Boolean。
 * 例如：{@code WHERE age > 18 AND name = 'alice'} 被解析为
 * AndExpression(GreaterThan(age, 18), EqualsTo(name, 'alice'))。
 *
 * <h3>为什么索引扫描后仍保留 FilterOperator？（答辩高频追问）</h3>
 * PhysicalPlanner 生成 {@code FilterOperator(IndexScanOperator, whereExpr)} 而非
 * 直接用 IndexScanOperator。因为：
 * <ul>
 *   <li>索引可能只吸收部分 WHERE 条件（如只用 id 索引，name 条件未吸收）</li>
 *   <li>FilterOperator 保证完整 WHERE 语义，避免 residual predicate 漏判</li>
 *   <li>即使索引已完全覆盖所有条件，FilterOperator 也只多一次 eval 调用，无副作用</li>
 * </ul>
 */
public class FilterOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private Expression whereExpr;
    private Tuple currentTuple;
    private boolean isOpen = false;
    // 标记是否已经准备好下一个元组
    private boolean readyForNext = false;

    public FilterOperator(PhysicalOperator child, Expression whereExpr) {
        this.child = child;
        this.whereExpr = whereExpr;
    }

    public FilterOperator(PhysicalOperator child, Collection<Expression> whereExpr) {
        this.child = child;
        // 只使用第一个表达式，简化逻辑
        this.whereExpr = whereExpr.iterator().next();
    }

    @Override
    public void Begin() throws DBException {
        Logger.debug("FilterOperator.Begin() 被调用");
        child.Begin();
        isOpen = true;
        currentTuple = null;
        readyForNext = false;

        // 在Begin后我们不主动查找第一个元组，而是等待hasNext()调用
    }

    @Override
    public boolean hasNext() throws DBException {
        Logger.debug("FilterOperator.hasNext() 被调用");
        if (!isOpen) {
            return false;
        }

        // 如果我们还没有准备好下一个元组，就尝试找一个
        if (!readyForNext) {
            return findNext();
        }

        // 如果已经准备好，且currentTuple不为null，则说明有下一个
        return currentTuple != null;
    }

    @Override
    public void Next() throws DBException {
        Logger.debug("FilterOperator.Next() 被调用");
        if (!isOpen) {
            return;
        }

        // 如果没有准备好，先准备
        if (!readyForNext) {
            hasNext(); // 这会调用findNext()来准备下一个元组
        }

        // 清除已准备状态，表示需要准备下一个元组
        readyForNext = false;
    }

    /**
     * 查找下一个符合条件的元组，并准备好它
     * 
     * @return 如果找到则返回true，否则返回false
     */
    private boolean findNext() throws DBException {
        // Task 2.1.2 Logical/Physical Operators - WHERE: evaluate predicates on
        // each tuple and return only matching rows.
        // 标记没有找到合适的元组
        currentTuple = null;

        // 循环直到找到匹配的元组或没有更多元组
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();

            // 如果元组不为空且满足条件，则设置为当前元组并标记为已准备好
            if (tuple != null && tuple.eval_expr(whereExpr)) {
                Logger.debug("FilterOperator找到匹配的元组: " + tuple);
                currentTuple = tuple;
                readyForNext = true;
                return true;
            }
        }

        // 没有找到匹配的元组
        Logger.debug("FilterOperator没有找到更多匹配的元组");
        return false;
    }

    @Override
    public Tuple Current() {
        Logger.debug("FilterOperator.Current() 被调用，返回: " + currentTuple);
        return currentTuple;
    }

    @Override
    public void Close() {
        if (child != null) {
            child.Close();
        }
        isOpen = false;
        currentTuple = null;
        readyForNext = false;
        Logger.debug("FilterOperator.Close() 被调用");
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }
}
