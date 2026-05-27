package edu.sustech.cs307.logicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.system.DBManager;

import java.util.Collections;

/**
 * 逻辑全表扫描 — 计划树的最底层叶子节点。
 *
 * 对应 SQL 中的 FROM 子句。无子节点（空 children），
 * PhysicalPlanner 将其转换为 SeqScanOperator 读取磁盘数据。
 *
 * 构造时会校验表是否存在。
 */
public class LogicalTableScanOperator extends LogicalOperator {
    private final String tableName;
    private final DBManager dbManager;

    public LogicalTableScanOperator(String tableName, DBManager dbManager) throws DBException {
        super(Collections.emptyList());
        this.tableName = tableName;
        this.dbManager = dbManager;
        if (!dbManager.isTableExists(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public String toString() {
        return "TableScanOperator(table=" + tableName + ")";
    }
}
