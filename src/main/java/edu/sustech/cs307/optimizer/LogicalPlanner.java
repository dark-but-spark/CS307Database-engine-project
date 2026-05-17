package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.AlterTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

public class LogicalPlanner {
    // TODO(Task 5.1 Complete Command Interface, Task 4.1 Transaction API): Replace
    // ad-hoc transaction regex parsing with a command parser path that can share
    // statement splitting, semicolon handling, and error reporting with SQL input.
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
        // TODO(Task 5.1 Complete Command Interface): resolveAndPlan currently
        // accepts exactly one statement. DBEntry should split batches before this
        // call, or this layer should expose a batch-planning API.
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Task 2.0 Basic SQL Statement Implementation: parse SQL statements and
        // construct logical operators or execute immediate DDL/transaction commands.
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        else if (stmt instanceof Delete deleteStmt) {
            operator = handleDelete(dbManager, deleteStmt);
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            // REVIEW(Task 2.1.1 Basic DDL - CREATE TABLE): CREATE TABLE is executed
            // directly during logical planning instead of returning a logical DDL node.
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof Alter alterStmt) {
            AlterTableExecutor alterExecutor = new AlterTableExecutor(alterStmt, dbManager);
            alterExecutor.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            // REVIEW(Task 2.1.1 Basic DDL - EXPLAIN): EXPLAIN is handled as an
            // immediate side-effect command, so it cannot be composed or tested as
            // a result-producing operator yet.
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement, dbManager);
            showDatabaseExecutor.execute();
            return null;
        } else if (stmt instanceof ShowTablesStatement showTablesStatement) {
            dbManager.showTables();
            return null;
        } else if (stmt instanceof DescribeStatement describeStatement) {
            dbManager.descTable(describeStatement.getTable().getName());
            return null;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }
        String tableName = plainSelect.getFromItem().toString();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);

        // Check for COUNT aggregation
        if (isCountQuery(plainSelect)) {
            return buildCountPlan(dbManager, plainSelect, root, tableName);
        }
        // Check for MAX/MIN aggregation
        if (isMaxMinQuery(plainSelect)) {
            return buildMaxMinPlan(dbManager, plainSelect, root, tableName);
        }
        // Check for GROUP BY
        if (plainSelect.getGroupBy() != null) {
            return buildGroupByPlan(dbManager, plainSelect, root, tableName);
        }

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                // REVIEW(Task 2.2 Advanced - Join Operators and Advanced SeqScan): Joins are planned as
                // nested logical joins without optimizer-based join-order selection.
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
        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            root = new LogicalOrderByOperator(root, plainSelect.getOrderByElements());
        }
        root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        return root;
    }

    private static boolean isCountQuery(PlainSelect plainSelect) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems == null || selectItems.size() != 1) {
            return false;
        }
        return selectItems.get(0).getExpression() instanceof Function f
                && f.getName().equalsIgnoreCase("count");
    }

    @SuppressWarnings("deprecation")
    private static LogicalOperator buildCountPlan(DBManager dbManager, PlainSelect plainSelect,
                                                  LogicalOperator root, String tableName) throws DBException {
        SelectItem<?> selectItem = plainSelect.getSelectItems().get(0);
        Function func = (Function) selectItem.getExpression();
        ExpressionList<?> params = func.getParameters();

        boolean isStar;
        String columnName = null;

        if (params == null || params.getExpressions() == null || params.getExpressions().isEmpty()) {
            isStar = true;
        } else {
            var firstParam = params.getExpressions().get(0);
            if (firstParam instanceof net.sf.jsqlparser.statement.select.AllColumns) {
                isStar = true;
            } else if (firstParam instanceof Column col) {
                isStar = false;
                columnName = col.getColumnName();
            } else {
                isStar = true;
            }
        }

        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }

        return new LogicalCountOperator(root, isStar, columnName, tableName);
    }

    private static boolean isMaxMinQuery(PlainSelect plainSelect) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems == null || selectItems.size() != 1) {
            return false;
        }
        if (selectItems.get(0).getExpression() instanceof Function f) {
            String name = f.getName().toLowerCase();
            return name.equals("max") || name.equals("min");
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static LogicalOperator buildMaxMinPlan(DBManager dbManager, PlainSelect plainSelect,
                                                   LogicalOperator root, String tableName) throws DBException {
        SelectItem<?> selectItem = plainSelect.getSelectItems().get(0);
        Function func = (Function) selectItem.getExpression();
        boolean isMax = func.getName().equalsIgnoreCase("max");
        ExpressionList<?> params = func.getParameters();

        String columnName;
        if (params != null && params.getExpressions() != null && !params.getExpressions().isEmpty()
                && params.getExpressions().get(0) instanceof Column col) {
            columnName = col.getColumnName();
        } else {
            columnName = params.getExpressions().get(0).toString();
        }

        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }

        return new LogicalMaxMinOperator(root, isMax, columnName, tableName);
    }

    private static LogicalOperator buildGroupByPlan(DBManager dbManager, PlainSelect plainSelect,
                                                    LogicalOperator root, String tableName) throws DBException {
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        return new LogicalGroupByOperator(root, plainSelect.getGroupBy(),
                plainSelect.getSelectItems(), tableName);
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        // Task 2.0.2 Data Operations: create a logical INSERT node from
        // table, column list, and VALUES expressions.
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        // Task 2.0.2 Data Operations: plan UPDATE as table scan plus
        // update-set and optional WHERE expression.
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }
    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        if (deleteStmt.getWhere() == null) {
            dbManager.dropTable(deleteStmt.getTable().getName());
            return null;
        }
        LogicalOperator root = new LogicalTableScanOperator(deleteStmt.getTable().getName(), dbManager);
        return new LogicalDeleteOperator(root, deleteStmt.getTable().getName(), deleteStmt.getWhere());
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
