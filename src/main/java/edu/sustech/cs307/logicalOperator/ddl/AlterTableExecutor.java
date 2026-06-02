package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.system.DBManager;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import org.pmw.tinylog.Logger;

public class AlterTableExecutor implements DMLExecutor {

    private final Alter alterStmt;
    private final DBManager dbManager;

    public AlterTableExecutor(Alter alterStmt, DBManager dbManager) {
        this.alterStmt = alterStmt;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        String tableName = alterStmt.getTable().getName();

        for (AlterExpression expr : alterStmt.getAlterExpressions()) {
            AlterOperation op = expr.getOperation();

            if (op == AlterOperation.ADD && expr.getColDataTypeList() != null) {
                for (var colDataType : expr.getColDataTypeList()) {
                    String colName = colDataType.getColumnName();
                    String dataType = colDataType.getColDataType().getDataType();
                    // DONE: ADD COLUMN accepts caller-provided DEFAULT values via
                    // addColumn(table, col, type, Value). Constraints/position
                    // clauses remain pending metadata support.
                    dbManager.addColumn(tableName, colName, dataType);
                    Logger.info("Added column {} {} to table {}", colName, dataType, tableName);
                }
            } else if (op == AlterOperation.DROP) {
                // 答辩检索-DROP_COLUMN-ChenJianye：ALTER TABLE DROP COLUMN 入口。
                // 讲解点：解析出列名后交给 DBManager.dropColumn()，由 DBManager
                // 负责重写记录文件和更新 TableMeta，保证非空表 schema 也一致。
                String colName = expr.getColumnName();
                if (colName != null) {
                    dbManager.dropColumn(tableName, colName);
                    Logger.info("Dropped column {} from table {}", colName, tableName);
                }
            } else if (op == AlterOperation.RENAME_TABLE) {
                String newName = expr.getNewTableName();
                if (newName != null) {
                    dbManager.renameTable(tableName, newName);
                    Logger.info("Renamed table {} to {}", tableName, newName);
                }
            } else if (op == AlterOperation.MODIFY) {
                String colName = expr.getColumnName();
                if (colName != null && expr.getColDataTypeList() != null && !expr.getColDataTypeList().isEmpty()) {
                    String dataType = expr.getColDataTypeList().get(0)
                            .getColDataType().getDataType();
                    dbManager.modifyColumn(tableName, colName, dataType);
                    Logger.info("Modified column {} to type {} in table {}", colName, dataType, tableName);
                }
            } else {
                // DONE: ALTER TABLE MODIFY COLUMN supported via modifyColumn.
                // RENAME COLUMN pending AlterOperation.RENAME_COLUMN in JSqlParser.
                throw new DBException(ExceptionTypes.UnsupportedCommand(
                        "ALTER TABLE " + op + " (not supported)"));
            }
        }
    }
}
