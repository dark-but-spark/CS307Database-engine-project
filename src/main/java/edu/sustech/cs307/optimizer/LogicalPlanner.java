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
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.logicalOperator.ddl.AlterTableExecutor;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

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

    /** 事务命令通过正则匹配，不走 JSqlParser（因为不是标准 SQL DML） */
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");

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

        // === DML 类：生成算子树 ===
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        else if (stmt instanceof Delete deleteStmt) {
            operator = handleDelete(dbManager, deleteStmt);
        }
        // === DDL 类：直接执行 ===
        else if (stmt instanceof CreateIndex createIndexStmt) {
            handleCreateIndex(dbManager, createIndexStmt);
            return null;
        } else if (stmt instanceof Drop dropStmt && "INDEX".equalsIgnoreCase(dropStmt.getType())) {
            dbManager.dropIndex(dropStmt.getName().getName());
            return null;
        } else if (stmt instanceof Alter alterStmt) {
            AlterTableExecutor alterTableExecutor = new AlterTableExecutor(alterStmt, dbManager);
            alterTableExecutor.execute();
            return null;
        }
        else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
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
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 聚合检测：优先短路，不再走 Filter→Project 路径
        if (isCountQuery(plainSelect)) {
            return buildCountPlan(dbManager, plainSelect, root, tableName);
        }
        if (isMaxMinQuery(plainSelect)) {
            return buildMaxMinPlan(dbManager, plainSelect, root, tableName);
        }
        if (plainSelect.getGroupBy() != null) {
            return buildGroupByPlan(dbManager, plainSelect, root, tableName);
        }

        // 普通查询：Filter → OrderBy → Project
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }

        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            root = new LogicalOrderByOperator(root, plainSelect.getOrderByElements());
        }

        root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        return root;
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
        if (deleteStmt.getWhere() == null) {
            dbManager.dropTable(deleteStmt.getTable().getName());
            return null;
        }
        LogicalOperator root = new LogicalTableScanOperator(deleteStmt.getTable().getName(), dbManager);
        return new LogicalDeleteOperator(root, deleteStmt.getTable().getName(), deleteStmt.getWhere());
    }

    /**
     * 检测是否是 COUNT 查询：SELECT 列表只有一个 item，且是 COUNT(...) 函数。
     */
    private static boolean isCountQuery(PlainSelect plainSelect) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        if (selectItems == null || selectItems.size() != 1) {
            return false;
        }
        return selectItems.get(0).getExpression() instanceof Function f
                && f.getName().equalsIgnoreCase("count");
    }

    /**
     * 构建 COUNT 计划。
     * 解析 COUNT(*) vs COUNT(column)：
     * - 无参数或参数是 * → isStar=true
     * - 参数是列名 → isStar=false, 记录列名用于后续判断 NULL
     * WHERE 条件通过 FilterOperator 附加。
     */
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

    /**
     * 检测是否是 MAX/MIN 查询：SELECT 列表只有一个函数，名称是 max 或 min。
     */
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

    /**
     * 构建 MAX/MIN 计划。
     * 提取函数名和参数列名，保留 WHERE 过滤。
     */
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

    /**
     * 构建 GROUP BY 计划。
     * WHERE 条件先过滤 → 然后按 groupByElement 列分组 → 对每组做 SELECT 聚合求值。
     */
    private static LogicalOperator buildGroupByPlan(DBManager dbManager, PlainSelect plainSelect,
                                                    LogicalOperator root, String tableName) throws DBException {
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        return new LogicalGroupByOperator(root, plainSelect.getGroupBy(),
                plainSelect.getSelectItems(), tableName);
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
     * 事务命令识别：用正则匹配判断 SQL 是否是事务控制语句。
     * 事务命令不走 JSqlParser（因为 SAVEPOINT 等不是标准 SQL DML 语法）。
     */
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
