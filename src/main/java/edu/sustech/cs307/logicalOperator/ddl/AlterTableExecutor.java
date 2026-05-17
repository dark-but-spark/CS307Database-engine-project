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
                    dbManager.addColumn(tableName, colName, dataType);
                    Logger.info("Added column {} {} to table {}", colName, dataType, tableName);
                }
            } else if (op == AlterOperation.DROP) {
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
            } else {
                throw new DBException(ExceptionTypes.UnsupportedCommand(
                        "ALTER TABLE " + op + " (not supported)"));
            }
        }
    }
}
