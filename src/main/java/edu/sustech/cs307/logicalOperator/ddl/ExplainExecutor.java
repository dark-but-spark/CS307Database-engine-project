package edu.sustech.cs307.logicalOperator.ddl;


import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.exception.DBException;

import net.sf.jsqlparser.statement.ExplainStatement;
import org.pmw.tinylog.Logger;

public class ExplainExecutor implements DMLExecutor {

    private final ExplainStatement explainStatement;
    private final DBManager dbManager;

    public ExplainExecutor(ExplainStatement explainStatement, DBManager dbManager) {
        this.explainStatement = explainStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
       //REVIEW: finish this function here, and add log info
       if(explainStatement.getStatement()==null){
           throw new DBException(ExceptionTypes.UnsupportedCommand(explainStatement.toString()));
       }
       LogicalOperator logicalOperator = LogicalPlanner.handleSelect(dbManager, explainStatement.getStatement());
       Logger.info("Logical plan:");
       for (String line : logicalOperator.toString().split("\\R")){
           Logger.info(line);
       }
    }
}
