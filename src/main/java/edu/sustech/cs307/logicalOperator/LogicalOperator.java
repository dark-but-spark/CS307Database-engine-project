package edu.sustech.cs307.logicalOperator;

import java.util.List;

/**
 * 逻辑算子抽象基类 — 逻辑计划树的节点。
 *
 * 逻辑算子描述"要做什么"（而非"怎么做"），形成一棵树：
 *    Project (SELECT a, b)
 *      └── Filter (WHERE x > 10)
 *            └── TableScan (FROM t)
 *
 * children: 子节点列表。大部分算子只有一个子节点（Unary），Join 等有两个子节点（Binary）。
 * 无子节点的算子（TableScan, Insert）传入空 list。
 */
public abstract class LogicalOperator {
    protected List<LogicalOperator> childern;

    public LogicalOperator(List<LogicalOperator> children) {
        this.childern = children;
    }

    public List<LogicalOperator> getChildren() {
        return childern;
    }

    /** 获取第一个子节点。大多数算子是单输入的，这省去类型转换。 */
    public LogicalOperator getChild() {
        if (childern != null && !childern.isEmpty()) {
            return childern.get(0);
        }
        return null;
    }

    /** 用于 EXPLAIN 打印计划树。每个子类实现自己的树形文本输出。 */
    public abstract String toString();
}
