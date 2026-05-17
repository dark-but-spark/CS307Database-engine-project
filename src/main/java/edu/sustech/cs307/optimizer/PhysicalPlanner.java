package edu.sustech.cs307.optimizer;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.physicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;

import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PhysicalPlanner {
    public static PhysicalOperator generateOperator(DBManager dbManager, LogicalOperator logicalOp) throws DBException {
        // Task 2.1.2 Logical/Physical Operators: lower logical plan nodes into
        // executable physical operators.
        if (logicalOp instanceof LogicalTableScanOperator tableScanOperator) {
            return handleTableScan(dbManager, tableScanOperator);
        } else if (logicalOp instanceof LogicalFilterOperator filterOperator) {
            return handleFilter(dbManager, filterOperator);
        } else if (logicalOp instanceof LogicalJoinOperator joinOperator) {
            return handleJoin(dbManager, joinOperator);
        } else if (logicalOp instanceof LogicalProjectOperator projectOperator) {
            return handleProject(dbManager, projectOperator);
        } else if (logicalOp instanceof LogicalInsertOperator insertOperator) {
            return handleInsert(dbManager, insertOperator);
        } else if (logicalOp instanceof LogicalUpdateOperator updateOperator) {
            return handleUpdate(dbManager, updateOperator);
        } else if (logicalOp instanceof LogicalDeleteOperator deleteOperator) {
            return handleDelete(dbManager, deleteOperator);
        } else if (logicalOp instanceof LogicalCountOperator countOperator) {
            return handleCount(dbManager, countOperator);
        } else if (logicalOp instanceof LogicalOrderByOperator orderByOperator) {
            return handleOrderBy(dbManager, orderByOperator);
        } else if (logicalOp instanceof LogicalMaxMinOperator maxMinOperator) {
            return handleMaxMin(dbManager, maxMinOperator);
        } else if (logicalOp instanceof LogicalGroupByOperator groupByOperator) {
            return handleGroupBy(dbManager, groupByOperator);
        }

        else {
            throw new DBException(ExceptionTypes.UnsupportedOperator(logicalOp.getClass().getSimpleName()));
        }
    }

    private static PhysicalOperator handleTableScan(DBManager dbManager, LogicalTableScanOperator logicalTableScanOp) {
        String tableName = logicalTableScanOp.getTableName();
        TableMeta tableMeta;
        try {
            tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            // Fallback to SeqScan if TableMeta cannot be retrieved
            return new SeqScanOperator(tableName, dbManager);
        }

        // Check if index exists for the table (for now, assume RBTreeIndex always
        // exists if index is defined)
        if (tableMeta.getIndexes() != null && !tableMeta.getIndexes().isEmpty()) {
            throw new RuntimeException("unimplement");
        } else {
            // Task 2.1.3 Sequential Scan Implementation: use SeqScan as the
            // default table access path when no usable index is planned.
            return new SeqScanOperator(tableName, dbManager);
        }
    }

    private static PhysicalOperator handleFilter(DBManager dbManager, LogicalFilterOperator logicalFilterOp)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalFilterOp.getChild());
        // Task 2.1.2 Logical/Physical Operators - WHERE: wrap the child operator
        // with runtime predicate filtering.
        return new FilterOperator(inputOp, logicalFilterOp.getWhereExpr());
    }

    private static PhysicalOperator handleJoin(DBManager dbManager, LogicalJoinOperator logicalJoinOp)
            throws DBException {
        PhysicalOperator leftOp = generateOperator(dbManager, logicalJoinOp.getLeftInput());
        PhysicalOperator rightOp = generateOperator(dbManager, logicalJoinOp.getRightInput());
        // Task 2.2 Advanced - Join Operators and Advanced SeqScan: build a nested-loop join pipeline for
        // join inputs before applying join predicates.
        PhysicalOperator joinOp = new NestedLoopJoinOperator(leftOp, rightOp, logicalJoinOp.getJoinExprs());

        Collection<Expression> joinFilters = logicalJoinOp.getJoinExprs();
        PhysicalOperator finalOp = new FilterOperator(joinOp, joinFilters);

        return finalOp;
    }

    private static PhysicalOperator handleProject(DBManager dbManager, LogicalProjectOperator logicalProjectOp)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalProjectOp.getChild());
        // Task 2.1.2 Logical/Physical Operators - Projection: create the physical
        // projection operator from resolved output schema.
        return new ProjectOperator(inputOp, logicalProjectOp.getOutputSchema());
    }

    /**
     * 处理将逻辑插入操作转换为物理插入运算符的过程
     * 
     * @param dbManager       提供数据库操作访问的数据库管理器实例
     * @param logicalInsertOp 需要被转换的逻辑插入运算符
     * @return 准备好执行的物理插入运算符
     * @throws DBException 如果存在列不匹配、类型不匹配或无效SQL语法时抛出
     */
    @SuppressWarnings("deprecation") // for ExpressionList<?>::getExpressions
    private static PhysicalOperator handleInsert(DBManager dbManager, LogicalInsertOperator logicalInsertOp)
            throws DBException {
        var tableMeta = dbManager.getMetaManager().getTable(logicalInsertOp.tableName);
        // Process columns
        List<String> columns = new ArrayList<>();
        if (logicalInsertOp.columns != null) {
            // the length must equal to the number of columns in the table
            if (tableMeta.columns.size() != logicalInsertOp.columns.size()) {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
            for (int i = 0; i < logicalInsertOp.columns.size(); i++) {
                String colName = logicalInsertOp.columns.get(i).getColumnName();
                if (tableMeta.getColumnMeta(colName) == null) {
                    throw new DBException(ExceptionTypes.ColumnDoesNotExist(colName));
                }
                if (!tableMeta.columns_list.get(i).name.equals(colName)) {
                    throw new DBException(ExceptionTypes.InsertColumnNameMismatch());
                }
                columns.add(colName);
            }

        } else {
            // If no columns specified, use all table columns in order
            for (ColumnMeta columnMeta : tableMeta.columns_list) {
                columns.add(columnMeta.name);
            }
        }
        if (!(logicalInsertOp.values instanceof Values)) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Values must be an expression list"));
        }
        ExpressionList<?> valuesList = ((Values) logicalInsertOp.values).getExpressions();
        if (columns.size() != valuesList.size()) {
            var element = valuesList.get(0);
            if (element instanceof ParenthesedExpressionList<?> parenthesed) {
                // check the children reexpressions
                for (Expression expr : valuesList) {
                    if (expr instanceof ParenthesedExpressionList<?> expressionList) {
                        if (expressionList.getExpressions().size() != columns.size()) {
                            throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
                        }
                    } else {
                        throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
                    }
                }
            } else {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
        }

        List<Value> values = new ArrayList<>();
        parseValue(values, valuesList, tableMeta);
        // will always be same size tuple

        // check the

        // Task 2.0.2 Data Operations - INSERT: validated VALUES are passed to the
        // physical insert operator for record serialization and storage.
        return new InsertOperator(logicalInsertOp.tableName, columns,
                values, dbManager);
    }

    @SuppressWarnings("deprecation")
    private static void parseValue(List<Value> values, ExpressionList<?> valuesList, TableMeta tableMeta)
            throws DBException {
        for (int i = 0; i < valuesList.size(); i++) {
            var expr = valuesList.getExpressions().get(i);
            if (expr instanceof StringValue string_value) {
                if (tableMeta.columns_list.get(i).type != ValueType.CHAR) {
                    throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
                }
                String value_str = string_value.getValue();
                if (value_str.length() > 64) {
                    value_str = value_str.substring(0, 64);
                }
                values.add(new Value(value_str));
            } else if (expr instanceof DoubleValue float_value) {
                if (tableMeta.columns_list.get(i).type != ValueType.FLOAT) {
                    throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
                }
                values.add(new Value(float_value.getValue()));
            } else if (expr instanceof LongValue long_value) {
                if (tableMeta.columns_list.get(i).type != ValueType.INTEGER) {
                    throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
                }
                values.add(new Value(long_value.getValue()));
            } else if (expr instanceof ParenthesedExpressionList<?> expressionList) {
                parseValue(values, expressionList, tableMeta);
            } else {
                throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Unsupported value type in VALUES clause"));
            }
        }
    }


    private static PhysicalOperator handleUpdate(DBManager dbManager, LogicalUpdateOperator logicalUpdateOp) throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalUpdateOp.getChild());
        if (logicalUpdateOp.getColumns().isEmpty()) {
            throw new DBException(ExceptionTypes.InvalidSQL("UPDATE", "Missing SET clause"));
        }
        UpdateSet mergedUpdateSet = mergeUpdateSets(logicalUpdateOp.getColumns());
        // Task 2.0.2 Data Operations - UPDATE: execute UPDATE through a sequential scan
        // and in-place record rewrite for rows satisfying the WHERE clause.
        return new UpdateOperator(scanner, logicalUpdateOp.getTableName(), mergedUpdateSet, logicalUpdateOp.getExpression());
    }

    private static PhysicalOperator handleDelete(DBManager dbManager, LogicalDeleteOperator logicalDeleteOp)
            throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalDeleteOp.getChild());
        return new DeleteOperator(scanner, logicalDeleteOp.getTableName(), logicalDeleteOp.getWhereExpr());
    }

    private static PhysicalOperator handleCount(DBManager dbManager, LogicalCountOperator logicalCountOp)
            throws DBException {
        PhysicalOperator child = generateOperator(dbManager, logicalCountOp.getChild());
        return new CountOperator(child, logicalCountOp.isStar(),
                logicalCountOp.getColumnName(), logicalCountOp.getTableName());
    }

    private static PhysicalOperator handleOrderBy(DBManager dbManager, LogicalOrderByOperator logicalOrderByOp)
            throws DBException {
        PhysicalOperator child = generateOperator(dbManager, logicalOrderByOp.getChild());
        return new OrderByOperator(child, logicalOrderByOp.getOrderByElements());
    }

    private static PhysicalOperator handleMaxMin(DBManager dbManager, LogicalMaxMinOperator logicalMaxMinOp)
            throws DBException {
        PhysicalOperator child = generateOperator(dbManager, logicalMaxMinOp.getChild());
        return new MaxMinOperator(child, logicalMaxMinOp.isMax(),
                logicalMaxMinOp.getColumnName(), logicalMaxMinOp.getTableName());
    }

    private static PhysicalOperator handleGroupBy(DBManager dbManager, LogicalGroupByOperator logicalGroupByOp)
            throws DBException {
        PhysicalOperator child = generateOperator(dbManager, logicalGroupByOp.getChild());
        return new GroupByOperator(child, logicalGroupByOp.getGroupByElement(),
                logicalGroupByOp.getSelectItems(), logicalGroupByOp.getTableName());
    }

    private static UpdateSet mergeUpdateSets(List<UpdateSet> updateSets) {
        UpdateSet mergedUpdateSet = new UpdateSet();
        for (UpdateSet updateSet : updateSets) {
            for (int i = 0; i < updateSet.getColumns().size(); i++) {
                mergedUpdateSet.add(updateSet.getColumn(i), updateSet.getValue(i));
            }
        }
        return mergedUpdateSet;
    }
}
