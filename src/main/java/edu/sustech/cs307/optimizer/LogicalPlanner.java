package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

public class LogicalPlanner {
    // TODO(Task 5.1 Complete Command Interface, Task 4 Transaction): Replace
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
        // Task 2.1 Basic SQL Statement Implementation: parse SQL statements and
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
            // REVIEW(Task 2.1.2 Logical/Physical Operators - DELETE): DELETE currently drops the whole table. Keep this marked until
            // row-level delete planning and a physical delete operator are added.
            dbManager.dropTable(deleteStmt.getTable().getName());
            return null;
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            // REVIEW(Task 2.1.1 Basic DDL - CREATE TABLE): CREATE TABLE is executed
            // directly during logical planning instead of returning a logical DDL node.
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            // REVIEW(Task 2.1.1 Basic DDL - EXPLAIN): EXPLAIN is handled as an
            // immediate side-effect command, so it cannot be composed or tested as
            // a result-producing operator yet.
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            // REVIEW(Task 2.1.1 Basic DDL - SHOW TABLES): SHOW is parsed separately
            // from normal operator planning and writes output through the executor.
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement);
            showDatabaseExecutor.execute();
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
        LogicalOperator root = new LogicalTableScanOperator(plainSelect.getFromItem().toString(), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                // REVIEW(Task 2.2 Advanced Join Operators): Joins are planned as
                // nested logical joins without optimizer-based join-order selection.
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            // Task 2.1.2 Logical/Physical Operators - WHERE: attach parsed WHERE
            // expressions to the logical filter node for later tuple evaluation.
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        // Task 2.1.2 Logical/Physical Operators - Projection: preserve SELECT
        // items in a logical project node instead of always returning SELECT *.
        root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        return root;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        // Task 2.1 Basic SQL - Data Operations: create a logical INSERT node from
        // table, column list, and VALUES expressions.
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        // Task 2.1 Basic SQL - Data Operations: plan UPDATE as table scan plus
        // update-set and optional WHERE expression.
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
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
