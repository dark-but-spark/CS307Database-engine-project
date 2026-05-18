package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.system.DBManager;
import net.sf.jsqlparser.statement.ShowStatement;
import org.pmw.tinylog.Logger;

public class ShowDatabaseExecutor implements DMLExecutor {

    private final ShowStatement showStatement;
    private final DBManager dbManager;

    public ShowDatabaseExecutor(ShowStatement showStatement, DBManager dbManager) {
        this.showStatement = showStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        String command = showStatement.getName();
        if (command.equalsIgnoreCase("DATABASES")) {
            Logger.info("|-----------|");
            Logger.info("| Databases |");
            Logger.info("|-----------|");
            Logger.info("|   CS307   |");
            Logger.info("|-----------|");
        } else if (command.equalsIgnoreCase("TABLES")) {
            dbManager.showTables();
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand(String.format("SHOW %s", command)));
        }
    }

}
