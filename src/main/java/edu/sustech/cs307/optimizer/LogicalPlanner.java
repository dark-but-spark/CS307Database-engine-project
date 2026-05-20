package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.logicalOperator.ddl.AlterTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.system.DBManager;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;

public class LogicalPlanner {
    // REVIEW(Task 5.1 Complete Command Interface, Task 4.1 Transaction API): Replace
    // ad-hoc transaction regex parsing with a command parser path that can share
    // statement splitting, semicolon handling, and error reporting with SQL input.
    // TODO(Task 4.1/5.1): Move transaction commands into a parsed command model
    // instead of maintaining separate regex handling here.
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        // REVIEW(Task 5.1 Complete Command Interface): resolveAndPlan currently
        // accepts exactly one statement. DBEntry should split batches before this
        // call, or this layer should expose a batch-planning API.
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }

        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }

        if (stmt instanceof Select selectStmt) {
            return handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            return handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            return handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        } else if (stmt instanceof Delete deleteStmt) {
            return handleDelete(dbManager, deleteStmt);
        } else if (stmt instanceof CreateIndex createIndexStmt) {
            handleCreateIndex(dbManager, createIndexStmt);
            return null;
        } else if (stmt instanceof Drop dropStmt && "INDEX".equalsIgnoreCase(dropStmt.getType())) {
            dbManager.dropIndex(dropStmt.getName().getName());
            return null;
        } else if (stmt instanceof Alter alterStmt) {
            new AlterTableExecutor(alterStmt, dbManager).execute();
            return null;
        } else if (stmt instanceof CreateTable createTableStmt) {
            // REVIEW(Task 2.1.1 Basic DDL - CREATE TABLE): CREATE TABLE is executed
            // directly during logical planning instead of returning a logical DDL node.
            // TODO(Task 2.1.1): Introduce logical/physical DDL operators so DDL
            // commands share execution, result output, and test paths with DQL.
            new CreateTableExecutor(createTableStmt, dbManager, sql).execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            // REVIEW(Task 2.1.1 Basic DDL - EXPLAIN): EXPLAIN is handled as an
            // immediate side-effect command, so it cannot be composed or tested as
            // a result-producing operator yet.
            // TODO(Task 2.1.1): Return EXPLAIN output as a result tuple instead
            // of logging directly from the executor.
            new ExplainExecutor(explainStatement, dbManager).execute();
            return null;
        } else if (stmt instanceof DescribeStatement describeStatement) {
            dbManager.descTable(describeStatement.getTable().getName());
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            // REVIEW(Task 2.1.1 Basic DDL - SHOW TABLES): SHOW is parsed separately
            // from normal operator planning and writes output through the executor.
            // TODO(Task 2.1.1): Model SHOW/DESCRIBE as result-producing commands
            // so CLI formatting and tests do not depend on Logger output.
            new ShowDatabaseExecutor(showStatement).execute();
            return null;
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
    }

    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }

        String tableName = plainSelect.getFromItem().toString();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                // REVIEW(Task 2.2 Advanced - Join Operators and Advanced SeqScan): Joins are planned as
                // nested logical joins without optimizer-based join-order selection.
                // TODO(Task 2.2): Add join-order selection and join algorithm
                // choice once table cardinality/statistics are available.
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }

        LogicalCountOperator countOperator = tryBuildCountOperator(root, plainSelect);
        if (countOperator != null) {
            return countOperator;
        }
        if (isMaxMinQuery(plainSelect)) {
            return buildMaxMinPlan(plainSelect, root, tableName);
        }
        if (plainSelect.getGroupBy() != null) {
            return new LogicalGroupByOperator(root, plainSelect.getGroupBy(),
                    plainSelect.getSelectItems(), tableName);
        }
        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            root = new LogicalOrderByOperator(root, plainSelect.getOrderByElements());
        }

        return new LogicalProjectOperator(root, plainSelect.getSelectItems());
    }

    @SuppressWarnings("deprecation")
    private static LogicalCountOperator tryBuildCountOperator(LogicalOperator child, PlainSelect plainSelect) throws DBException {
        if (plainSelect.getSelectItems() == null || plainSelect.getSelectItems().size() != 1) {
            return null;
        }
        var selectItem = plainSelect.getSelectItems().get(0);
        if (!(selectItem.getExpression() instanceof Function function)
                || !function.getName().equalsIgnoreCase("count")) {
            return null;
        }

        if (function.isDistinct() || function.isUnique()) {
            // REVIEW(Task 2.1.3 Sequential Scan Implementation - COUNT): COUNT
            // DISTINCT is intentionally rejected until duplicate elimination is
            // implemented for aggregate inputs.
            // TODO(Task 2.1.3/2.2): Implement duplicate elimination for
            // COUNT(DISTINCT column) and grouped distinct aggregates.
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        if (function.isAllColumns()) {
            return new LogicalCountOperator(child, true, null, null);
        }
        ExpressionList<?> parameters = function.getParameters();
        if (parameters == null || parameters.getExpressions() == null || parameters.getExpressions().isEmpty()) {
            return new LogicalCountOperator(child, true, null, null);
        }
        if (parameters.getExpressions().size() == 1 && parameters.getExpressions().get(0) instanceof AllColumns) {
            return new LogicalCountOperator(child, true, null, null);
        }
        if (parameters.getExpressions().size() != 1 || !(parameters.getExpressions().get(0) instanceof Column column)) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        return new LogicalCountOperator(child, false, column.getColumnName(), column.getTableName());
    }

    private static boolean isMaxMinQuery(PlainSelect plainSelect) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems == null || selectItems.size() != 1) {
            return false;
        }
        if (selectItems.get(0).getExpression() instanceof Function function) {
            String name = function.getName().toLowerCase();
            return name.equals("max") || name.equals("min");
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static LogicalOperator buildMaxMinPlan(PlainSelect plainSelect, LogicalOperator root, String tableName) throws DBException {
        Function function = (Function) plainSelect.getSelectItems().get(0).getExpression();
        ExpressionList<?> parameters = function.getParameters();
        if (parameters == null || parameters.getExpressions() == null || parameters.getExpressions().size() != 1
                || !(parameters.getExpressions().get(0) instanceof Column column)) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        return new LogicalMaxMinOperator(root, function.getName().equalsIgnoreCase("max"),
                column.getColumnName(), column.getTableName() == null ? tableName : column.getTableName());
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }

    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        // Task 2.1.2 Logical/Physical Operators - DELETE: plan DELETE as a table
        // scan plus an optional WHERE expression, mirroring UPDATE.
        // REVIEW(Task 2.1.2 Logical/Physical Operators - DELETE): JSqlParser's
        // single-table Delete path is supported; multi-table delete dialects are
        // intentionally outside this planner path.
        String tableName = deleteStmt.getTable().getName();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);
        return new LogicalDeleteOperator(root, tableName, deleteStmt.getWhere());
    }

    private static void handleCreateIndex(DBManager dbManager, CreateIndex createIndexStmt) throws DBException {
        var index = createIndexStmt.getIndex();
        if (index == null || index.getName() == null || index.getColumnsNames() == null
                || index.getColumnsNames().size() != 1) {
            throw new DBException(ExceptionTypes.InvalidSQL(createIndexStmt.toString(),
                    "Only single-column CREATE INDEX is supported"));
        }
        String using = index.getUsing();
        if (using != null && !using.equalsIgnoreCase("BTREE") && !using.equalsIgnoreCase("B+TREE")) {
            throw new DBException(ExceptionTypes.InvalidSQL(createIndexStmt.toString(),
                    "Only BTREE indexes are supported"));
        }
        dbManager.createIndex(index.getName(), createIndexStmt.getTable().getName(), index.getColumnsNames().get(0));
    }

    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }
        if (ROLLBACK_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }
        Matcher savepointMatcher = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (savepointMatcher.matches()) {
            dbManager.getTransactionManager().savepoint(savepointMatcher.group(1));
            return true;
        }
        Matcher rollbackToSavepointMatcher = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (rollbackToSavepointMatcher.matches()) {
            dbManager.getTransactionManager().rollbackToSavepoint(rollbackToSavepointMatcher.group(1));
            return true;
        }
        Matcher releaseSavepointMatcher = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (releaseSavepointMatcher.matches()) {
            dbManager.getTransactionManager().releaseSavepoint(releaseSavepointMatcher.group(1));
            return true;
        }
        return false;
    }
}
