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

/**
 * 逻辑计划器 — Q&A 必问（Task 2 Advanced）。
 *
 * 职责：将 SQL 语句解析结果转换为 LogicalOperator 树（逻辑执行计划）。
 * 不涉及具体执行方式（那是 PhysicalPlanner 的工作），只描述"要做什么"。
 *
 * SQL 语句的两大类处理方式：
 *
 * 【DDL 类】直接执行，不生成算子树（返回 null）：
 *   CREATE TABLE → CreateTableExecutor
 *   DROP INDEX   → dbManager.dropIndex()
 *   ALTER TABLE  → AlterTableExecutor
 *   EXPLAIN      → ExplainExecutor（内部递归生成计划树并打印）
 *   SHOW/DESCRIBE → 直接调用 dbManager 方法
 *
 * 【DML 类】生成 LogicalOperator 树，交给 PhysicalPlanner 执行：
 *   SELECT → handleSelect() 构建 Project→Filter→Join→TableScan 树
 *   INSERT → LogicalInsertOperator
 *   UPDATE → LogicalUpdateOperator(TableScan, ...)
 *   DELETE → handleDelete() 分情况处理（有/无 WHERE）
 *
 * 【事务类】通过正则匹配识别，直接调用 TransactionManager：
 *   BEGIN / START TRANSACTION / COMMIT / ROLLBACK / SAVEPOINT / ...
 */
public class LogicalPlanner {
    /** 事务命令通过正则匹配，不走 JSqlParser。 */
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
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern SHOW_INDEX_PATTERN = Pattern.compile("(?i)^SHOW\\s+INDEX\\s+(\\S+)$");

    /**
     * 主入口：SQL → LogicalOperator 树。
     *
     * 执行顺序：
     * 1. 先检查是否是事务命令（正则匹配），是则直接处理
     * 2. 用 JSqlParser 解析为标准 SQL Statement
     * 3. 根据 Statement 类型分发到对应的 handler
     */
    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        // REVIEW(Task 5.1 Complete Command Interface): resolveAndPlan currently
        // accepts exactly one statement. DBEntry should split batches before this
        // call, or this layer should expose a batch-planning API.
        if (handleManualCommand(dbManager, sql)) {
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
            // 答辩检索-DROP_INDEX-ChenJianye：DROP INDEX 的 SQL 入口。
            // 这里只负责识别 JSqlParser 解析出的 Drop Statement，真正删除索引元数据
            // 和运行期 B+Tree 的逻辑在 DBManager.dropIndex()。
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
            // 答辩检索-EXPLAIN-chenjiyan/ChenJianye：EXPLAIN 的入口在这里，
            // 由 ExplainExecutor 重新构造 SELECT 的逻辑计划并打印计划树。
            new ExplainExecutor(explainStatement, dbManager).execute();
            return null;
        } else if (stmt instanceof DescribeStatement describeStatement) {
            // 答辩检索-DESCRIBE-chenjiyan/ChenJianye：DESCRIBE 的 SQL 入口。
            // 入口层只取表名并转发；字段名和类型的展示在 DBManager.descTable()。
            dbManager.descTable(describeStatement.getTable().getName());
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            // REVIEW(Task 2.1.1 Basic DDL - SHOW TABLES): SHOW is parsed separately
            // from normal operator planning and writes output through the executor.
            // TODO(Task 2.1.1): Model SHOW/DESCRIBE as result-producing commands
            // so CLI formatting and tests do not depend on Logger output.
            new ShowDatabaseExecutor(showStatement, dbManager).execute();
            return null;
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
    }

    /**
     * SELECT 语句的逻辑计划生成 — Q&A 重点。
     *
     * 计划树构建顺序（自底向上）：
     * 1. TableScan — 最底层，从磁盘读取表数据
     * 2. Join     — 多表连接（支持嵌套 join，按 depth 标记连接顺序）
     * 3. 聚合检测  — 优先检测 COUNT/MAX/MIN/GROUP BY，命中则短路
     * 4. Filter   — WHERE 条件过滤
     * 5. OrderBy  — 排序
     * 6. Project  — 列投影（最顶层）
     *
     * 聚合检测的优先级很重要：COUNT 和 MAX/MIN 是互斥的（都要求 SELECT 列表只有一个聚合函数），
     * 必须在 GROUP BY 之前检查。
     */
    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }

        String tableName = plainSelect.getFromItem().toString();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);

        // 构建 JOIN 链：A JOIN B JOIN C → Join(Join(Scan(A), Scan(B)), Scan(C))
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

        boolean distinct = function.isDistinct() || function.isUnique();
        if (distinct && function.isAllColumns()) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        if (function.isAllColumns()) {
            return new LogicalCountOperator(child, true, false, null, null);
        }
        ExpressionList<?> parameters = function.getParameters();
        if (parameters == null || parameters.getExpressions() == null || parameters.getExpressions().isEmpty()) {
            if (distinct) {
                throw new DBException(ExceptionTypes.UnsupportedExpression(function));
            }
            return new LogicalCountOperator(child, true, false, null, null);
        }
        if (parameters.getExpressions().size() == 1 && parameters.getExpressions().get(0) instanceof AllColumns) {
            if (distinct) {
                throw new DBException(ExceptionTypes.UnsupportedExpression(function));
            }
            return new LogicalCountOperator(child, true, false, null, null);
        }
        if (parameters.getExpressions().size() != 1 || !(parameters.getExpressions().get(0) instanceof Column column)) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(function));
        }
        return new LogicalCountOperator(child, false, distinct, column.getColumnName(), column.getTableName());
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

    /**
     * DELETE 处理 — Q&A 重点。
     *
     * 分两种情况：
     * 1. DELETE FROM t（无 WHERE）→ 直接删整张表（dropTable）
     * 2. DELETE FROM t WHERE ... → 构建 LogicalDeleteOperator 做行级删除
     */
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

    /**
     * 处理 CREATE INDEX 语句。
     * 只支持单列 BTREE 索引。调用 dbManager.createIndex()：
     * 1. 在 TableMeta 中记录索引元数据
     * 2. 扫描全表构建 B+Tree
     * 3. 保存元数据到 JSON 文件
     */
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

    /** 去掉 SQL 末尾分号和空白 */
    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    /**
     * 特殊命令识别：用正则匹配判断 SQL 是否需要绕过 JSqlParser。
     * 事务命令不走 JSqlParser（因为 SAVEPOINT 等不是标准 SQL DML 语法）。
     * SHOW TABLES 是项目 PDF 明确要求的命令；当前 JSqlParser 版本不会把它稳定分发到
     * ShowStatement，因此在这里直接调用 DBManager.showTables()。
     */
    private static boolean handleManualCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (SHOW_TABLES_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.showTables();
            return true;
        }
        Matcher showIndexMatcher = SHOW_INDEX_PATTERN.matcher(normalizedSql);
        if (showIndexMatcher.matches()) {
            dbManager.showIndex(showIndexMatcher.group(1));
            return true;
        }
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
