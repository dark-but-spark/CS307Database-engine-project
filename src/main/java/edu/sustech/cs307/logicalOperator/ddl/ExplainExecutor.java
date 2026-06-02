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
       // Task 2.1.1 Basic DDL - EXPLAIN: print the logical plan generated for a
       // SELECT statement.
       // 答辩检索-EXPLAIN-chenjiyan：ExplainExecutor 是 EXPLAIN 的核心执行器。
       // 讲解顺序：检查 EXPLAIN 后是否带 SELECT -> 调用 LogicalPlanner.handleSelect()
       // 生成逻辑计划树 -> 按行 Logger 输出树形结构。
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
