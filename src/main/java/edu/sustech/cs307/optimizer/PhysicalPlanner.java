package edu.sustech.cs307.optimizer;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.physicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;

import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // Task 2.1.3 Sequential Scan Implementation: use SeqScan as the
        // default table access path when no usable index is planned.
        return new SeqScanOperator(tableName, dbManager);
    }

    private static PhysicalOperator handleFilter(DBManager dbManager, LogicalFilterOperator logicalFilterOp)
            throws DBException {
        // Task 3.1 Index Support - Indexed Access Path: use B+Tree scans for
        // equality/range predicates that can be extracted from single-table WHERE.
        // Task 3.1 索引查询优化速查：
        // 1. 这里只优化 LogicalTableScanOperator 上方的单表 WHERE。
        // 2. tryBuildIndexScan 会把 AND 拆成多个条件，并识别 =、>、>=、<、<=、BETWEEN。
        // 3. 如果某个条件命中已建索引列，就把同一列上的多个边界合并为最紧 IndexBounds。
        // 4. 生成 IndexScanOperator 后仍然外包 FilterOperator，索引用于缩小候选 RID，
        //    完整 WHERE 语义由 FilterOperator 再检查，避免 residual predicate 漏判。
        // TODO(Task 3.1): Consider OR predicates, multi-index intersections, and
        // simple cost estimates instead of choosing the first usable index.
        PhysicalOperator indexScan = tryBuildIndexScan(dbManager, logicalFilterOp);
        if (indexScan != null) {
            return new FilterOperator(indexScan, logicalFilterOp.getWhereExpr());
        }
        PhysicalOperator inputOp = generateOperator(dbManager, logicalFilterOp.getChild());
        // Task 2.1.2 Logical/Physical Operators - WHERE: wrap the child operator
        // with runtime predicate filtering.
        return new FilterOperator(inputOp, logicalFilterOp.getWhereExpr());
    }

    /**
     * 尝试将过滤谓词转换为基于 B+Tree 索引的扫描。
     *
     * <h3>算法流程（答辩可逐条说明）</h3>
     * <ol>
     *   <li>检查 filter 子节点是否为 TableScanOperator（只优化单表 WHERE）</li>
     *   <li>将 WHERE 表达式拆解为 AND 条件列表（collectAndConjuncts）</li>
     *   <li>遍历 AND conjuncts，找到第一个有索引的列</li>
     *   <li>收集同一列上所有 AND 条件，合并为最紧边界：
     *       例 id >= 10 AND id < 16 AND id > 5
     *       → low=10(inclusive), high=16(exclusive)（isTighterLow/isTighterHigh 选最紧值）</li>
     *   <li>等值条件优先 → IndexScanOperator(EQUAL)；否则 → IndexScanOperator(RANGE, low, high)</li>
     * </ol>
     *
     * <h3>为什么外层仍要 FilterOperator？</h3>
     * 索引只吸收同一列上的部分条件，其他列的条件和未被索引吸收的条件
     * 由 FilterOperator 在读取完整记录后判断，保证语义正确。
     *
     * <h3>当前限制（TODO）</h3>
     * 只选第一个命中索引的列，不支持 OR、多索引交集、代价估计。
     *
     * @param dbManager       数据库管理器
     * @param logicalFilterOp 逻辑过滤算子
     * @return 可用的 IndexScanOperator，若无法使用索引则返回 null
     */
    private static PhysicalOperator tryBuildIndexScan(DBManager dbManager,
                                                        LogicalFilterOperator logicalFilterOp) throws DBException {
        LogicalOperator child = logicalFilterOp.getChild();
        if (!(child instanceof LogicalTableScanOperator tableScan)) {
            return null;
        }
        String tableName = tableScan.getTableName();
        TableMeta tableMeta = dbManager.getMetaManager().getTable(tableName);
        List<Expression> conjuncts = new ArrayList<>();
        collectAndConjuncts(logicalFilterOp.getWhereExpr(), conjuncts);
        IndexBounds selectedBounds = null;
        for (Expression conjunct : conjuncts) {
            IndexPredicate predicate = parseIndexPredicate(tableName, conjunct);
            if (predicate == null || tableMeta.findIndexOnColumn(predicate.columnName()) == null) {
                continue;
            }
            IndexBounds candidate = new IndexBounds(predicate.columnName());
            // Defense note: once a usable indexed column is found, merge all AND
            // predicates on the same column into one tight range, e.g.
            // id >= 10 AND id < 16 becomes a single IndexScanOperator range.
            for (Expression expression : conjuncts) {
                IndexPredicate peer = parseIndexPredicate(tableName, expression);
                if (peer != null && peer.columnName().equalsIgnoreCase(predicate.columnName())) {
                    candidate.add(peer);
                }
            }
            selectedBounds = candidate;
            break;
        }
        if (selectedBounds == null) {
            return null;
        }
        if (selectedBounds.equalValue != null) {
            return new IndexScanOperator(tableName, dbManager, selectedBounds.columnName, selectedBounds.equalValue);
        }
        return new IndexScanOperator(tableName, dbManager, selectedBounds.columnName,
                selectedBounds.lowValue, selectedBounds.highValue,
                selectedBounds.lowInclusive, selectedBounds.highInclusive);
    }

    private static void collectAndConjuncts(Expression expression, List<Expression> result) {
        if (expression instanceof AndExpression andExpression) {
            collectAndConjuncts(andExpression.getLeftExpression(), result);
            collectAndConjuncts(andExpression.getRightExpression(), result);
        } else {
            result.add(expression);
        }
    }

    private static IndexPredicate parseIndexPredicate(String tableName, Expression expression) {
        try {
            if (expression instanceof EqualsTo equalsTo) {
                return parseComparison(tableName, equalsTo.getLeftExpression(), equalsTo.getRightExpression(), "=");
            }
            if (expression instanceof GreaterThan greaterThan) {
                return parseComparison(tableName, greaterThan.getLeftExpression(), greaterThan.getRightExpression(), ">");
            }
            if (expression instanceof GreaterThanEquals greaterThanEquals) {
                return parseComparison(tableName, greaterThanEquals.getLeftExpression(), greaterThanEquals.getRightExpression(), ">=");
            }
            if (expression instanceof MinorThan minorThan) {
                return parseComparison(tableName, minorThan.getLeftExpression(), minorThan.getRightExpression(), "<");
            }
            if (expression instanceof MinorThanEquals minorThanEquals) {
                return parseComparison(tableName, minorThanEquals.getLeftExpression(), minorThanEquals.getRightExpression(), "<=");
            }
            if (expression instanceof Between between && !between.isNot()) {
                if (!(between.getLeftExpression() instanceof Column column)) {
                    return null;
                }
                String columnName = normalizedColumnName(tableName, column);
                if (columnName == null) {
                    return null;
                }
                Value low = parseLiteralValue(between.getBetweenExpressionStart());
                Value high = parseLiteralValue(between.getBetweenExpressionEnd());
                return low == null || high == null ? null : IndexPredicate.range(columnName, low, true, high, true);
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static IndexPredicate parseComparison(String tableName, Expression left, Expression right, String operator) {
        if (left instanceof Column column) {
            String columnName = normalizedColumnName(tableName, column);
            Value value = parseLiteralValue(right);
            return columnName == null || value == null ? null : IndexPredicate.fromOperator(columnName, operator, value);
        }
        if (right instanceof Column column) {
            String columnName = normalizedColumnName(tableName, column);
            Value value = parseLiteralValue(left);
            return columnName == null || value == null ? null : IndexPredicate.fromOperator(columnName, flipOperator(operator), value);
        }
        return null;
    }

    private static String normalizedColumnName(String tableName, Column column) {
        String qualifier = column.getTableName();
        if (qualifier != null && !qualifier.isBlank() && !qualifier.equalsIgnoreCase(tableName)) {
            return null;
        }
        return column.getColumnName();
    }

    private static String flipOperator(String operator) {
        return switch (operator) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> operator;
        };
    }

    private static Value parseLiteralValue(Expression valueExpr) {
        if (valueExpr instanceof LongValue longValue) {
            return new Value(longValue.getValue());
        } else if (valueExpr instanceof StringValue stringValue) {
            return new Value(stringValue.getValue());
        } else if (valueExpr instanceof DoubleValue doubleValue) {
            return new Value(doubleValue.getValue());
        }
        return null;
    }

    private record IndexPredicate(String columnName, Value equalValue,
                                  Value lowValue, boolean lowInclusive,
                                  Value highValue, boolean highInclusive) {
        static IndexPredicate equal(String columnName, Value value) {
            return new IndexPredicate(columnName, value, null, false, null, false);
        }

        static IndexPredicate range(String columnName, Value lowValue, boolean lowInclusive,
                                    Value highValue, boolean highInclusive) {
            return new IndexPredicate(columnName, null, lowValue, lowInclusive, highValue, highInclusive);
        }

        static IndexPredicate fromOperator(String columnName, String operator, Value value) {
            return switch (operator) {
                case "=" -> equal(columnName, value);
                case ">" -> range(columnName, value, false, null, false);
                case ">=" -> range(columnName, value, true, null, false);
                case "<" -> range(columnName, null, false, value, false);
                case "<=" -> range(columnName, null, false, value, true);
                default -> null;
            };
        }
    }

    /**
     * 索引边界收集器。将同一索引列上的多个 AND 条件合并为最紧边界区间。
     * 
     * <h3>最紧边界合并逻辑（答辩可答）</h3>
     * <ul>
     *   <li>下界选最大值：多个 col > X 条件取最大的 X → 扫描范围最小</li>
     *   <li>上界选最小值：多个 col < Y 条件取最小的 Y → 扫描范围最小</li>
     *   <li>边界相等时：inclusive 优先于 exclusive（closed 比 open 更紧）</li>
     *   <li>等值条件优先级最高，一旦出现直接覆盖所有范围条件</li>
     * </ul>
     * 
     * 例：WHERE id > 10 AND id >= 15 AND id < 20
     * → low=15(inclusive), high=20(exclusive)
     * → IndexScanOperator(RANGE, 15, true, 20, false)
     */
    private static class IndexBounds {
        private final String columnName;
        private Value equalValue;
        private Value lowValue;
        private Value highValue;
        private boolean lowInclusive;
        private boolean highInclusive;

        private IndexBounds(String columnName) {
            this.columnName = columnName;
        }

        private void add(IndexPredicate predicate) throws DBException {
            if (predicate.equalValue() != null) {
                this.equalValue = predicate.equalValue();
            }
            // Choose the tightest lower and upper bounds so the index scan reads
            // the smallest candidate RID range while FilterOperator preserves
            // full SQL correctness above it.
            if (predicate.lowValue() != null && isTighterLow(predicate)) {
                this.lowValue = predicate.lowValue();
                this.lowInclusive = predicate.lowInclusive();
            }
            if (predicate.highValue() != null && isTighterHigh(predicate)) {
                this.highValue = predicate.highValue();
                this.highInclusive = predicate.highInclusive();
            }
        }

        private boolean isTighterLow(IndexPredicate predicate) throws DBException {
            if (lowValue == null) {
                return true;
            }
            int comparison = ValueComparer.compare(predicate.lowValue(), lowValue);
            return comparison > 0 || (comparison == 0 && lowInclusive && !predicate.lowInclusive());
        }

        private boolean isTighterHigh(IndexPredicate predicate) throws DBException {
            if (highValue == null) {
                return true;
            }
            int comparison = ValueComparer.compare(predicate.highValue(), highValue);
            return comparison < 0 || (comparison == 0 && highInclusive && !predicate.highInclusive());
        }
    }

    private static PhysicalOperator handleJoin(DBManager dbManager, LogicalJoinOperator logicalJoinOp)
            throws DBException {
        PhysicalOperator leftOp = generateOperator(dbManager, logicalJoinOp.getLeftInput());
        PhysicalOperator rightOp = generateOperator(dbManager, logicalJoinOp.getRightInput());
        // Task 2.2 Advanced - Join Operators and Advanced SeqScan: build a nested-loop join pipeline for
        // join inputs before applying join predicates.
        PhysicalOperator joinOp = new NestedLoopJoinOperator(leftOp, rightOp, logicalJoinOp.getJoinExprs());

        Collection<Expression> joinFilters = logicalJoinOp.getJoinExprs();
        if (joinFilters == null || joinFilters.isEmpty()) {
            return joinOp;
        }
        return new FilterOperator(joinOp, joinFilters);
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
        List<String> targetColumns = new ArrayList<>();
        if (logicalInsertOp.columns != null) {
            for (Column column : logicalInsertOp.columns) {
                String colName = column.getColumnName();
                if (tableMeta.getColumnMeta(colName) == null) {
                    throw new DBException(ExceptionTypes.ColumnDoesNotExist(colName));
                }
                if (targetColumns.stream().anyMatch(existing -> existing.equalsIgnoreCase(colName))) {
                    throw new DBException(ExceptionTypes.InsertColumnNameMismatch());
                }
                targetColumns.add(colName);
            }

        } else {
            for (ColumnMeta columnMeta : tableMeta.columns_list) {
                targetColumns.add(columnMeta.name);
            }
        }
        if (!(logicalInsertOp.values instanceof Values)) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Values must be an expression list"));
        }
        ExpressionList<?> valuesList = ((Values) logicalInsertOp.values).getExpressions();
        List<List<Expression>> rows = parseInsertRows(valuesList, targetColumns.size());
        List<String> storageColumns = tableMeta.columns_list.stream().map(column -> column.name).toList();
        List<Value> values = buildStorageOrderedRows(rows, targetColumns, tableMeta);

        // Task 2.0.2 Data Operations - INSERT: validated VALUES are passed to the
        // physical insert operator for record serialization and storage.
        return new InsertOperator(logicalInsertOp.tableName, storageColumns,
                values, dbManager);
    }

    @SuppressWarnings("deprecation")
    private static List<List<Expression>> parseInsertRows(ExpressionList<?> valuesList, int columnCount)
            throws DBException {
        List<List<Expression>> rows = new ArrayList<>();
        if (valuesList.size() == columnCount
                && valuesList.getExpressions().stream().noneMatch(ParenthesedExpressionList.class::isInstance)) {
            rows.add(new ArrayList<>(valuesList.getExpressions()));
            return rows;
        }
        for (Expression expr : valuesList.getExpressions()) {
            if (!(expr instanceof ParenthesedExpressionList<?> rowExpr)) {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
            if (rowExpr.getExpressions().size() != columnCount) {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
            rows.add(new ArrayList<>(rowExpr.getExpressions()));
        }
        return rows;
    }

    private static List<Value> buildStorageOrderedRows(List<List<Expression>> rows, List<String> targetColumns,
                                                       TableMeta tableMeta) throws DBException {
        List<Value> values = new ArrayList<>();
        for (List<Expression> row : rows) {
            Map<String, Value> rowValues = new HashMap<>();
            for (ColumnMeta columnMeta : tableMeta.columns_list) {
                rowValues.put(columnMeta.name, defaultInsertValue(columnMeta.type));
            }
            for (int i = 0; i < targetColumns.size(); i++) {
                ColumnMeta columnMeta = tableMeta.getColumnMeta(targetColumns.get(i));
                rowValues.put(columnMeta.name, parseInsertValue(row.get(i), columnMeta));
            }
            for (ColumnMeta columnMeta : tableMeta.columns_list) {
                values.add(rowValues.get(columnMeta.name));
            }
        }
        return values;
    }

    private static Value parseInsertValue(Expression expr, ColumnMeta columnMeta) throws DBException {
        if (expr instanceof StringValue stringValue) {
            if (columnMeta.type != ValueType.CHAR) {
                throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
            }
            String value = stringValue.getValue();
            return new Value(value.length() > Value.CHAR_SIZE ? value.substring(0, Value.CHAR_SIZE) : value);
        } else if (expr instanceof DoubleValue doubleValue) {
            if (columnMeta.type != ValueType.FLOAT) {
                throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
            }
            return new Value(doubleValue.getValue());
        } else if (expr instanceof LongValue longValue) {
            if (columnMeta.type != ValueType.INTEGER) {
                throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
            }
            return new Value(longValue.getValue());
        }
        throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Unsupported value type in VALUES clause"));
    }

    private static Value defaultInsertValue(ValueType valueType) throws DBException {
        return switch (valueType) {
            case CHAR -> new Value("");
            case INTEGER -> new Value(0L);
            case FLOAT -> new Value(0.0);
            default -> throw new DBException(ExceptionTypes.UnsupportedValueType(valueType));
        };
    }


    private static PhysicalOperator handleUpdate(DBManager dbManager, LogicalUpdateOperator logicalUpdateOp) throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalUpdateOp.getChild());
        if (logicalUpdateOp.getColumns().isEmpty()) {
            throw new DBException(ExceptionTypes.InvalidSQL("UPDATE", "Missing SET clause"));
        }
        UpdateSet mergedUpdateSet = mergeUpdateSets(logicalUpdateOp.getColumns());
        // Task 2.0.2 Data Operations - UPDATE: execute UPDATE through a sequential scan
        // and in-place record rewrite for rows satisfying the WHERE clause.
        return new UpdateOperator(scanner, dbManager, logicalUpdateOp.getTableName(), mergedUpdateSet, logicalUpdateOp.getExpression());
    }

    private static PhysicalOperator handleDelete(DBManager dbManager, LogicalDeleteOperator logicalDeleteOp) throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalDeleteOp.getChild());
        // Task 2.1.2 Logical/Physical Operators - DELETE: execute row-level
        // deletion through the planned scan and WHERE predicate evaluation.
        return new DeleteOperator(scanner, dbManager, logicalDeleteOp.getTableName(), logicalDeleteOp.getWhereExpr());
    }

    private static PhysicalOperator handleCount(DBManager dbManager, LogicalCountOperator logicalCountOp) throws DBException {
        PhysicalOperator child = generateOperator(dbManager, logicalCountOp.getChild());
        // Task 2.1.3 Sequential Scan Implementation - COUNT: count rows from the
        // planned child pipeline, including any WHERE filters already attached.
        return new CountOperator(child, logicalCountOp.isStar(), logicalCountOp.isDistinct(), logicalCountOp.getColumnName(),
                logicalCountOp.getTableName());
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
