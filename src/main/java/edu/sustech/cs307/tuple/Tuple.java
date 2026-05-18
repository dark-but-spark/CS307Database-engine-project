package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;

import java.util.ArrayList;
import java.util.List;

public abstract class Tuple {
    public abstract Value getValue(TabCol tabCol) throws DBException;

    public abstract TabCol[] getTupleSchema();

    public abstract Value[] getValues() throws DBException;

    public boolean eval_expr(Expression expr) throws DBException {
        return evaluateCondition(this, expr);
    }

    private boolean evaluateCondition(Tuple tuple, Expression whereExpr) throws DBException {
        if (whereExpr instanceof AndExpression andExpr) {
            // Recursively evaluate left and right expressions
            return evaluateCondition(tuple, andExpr.getLeftExpression())
                    && evaluateCondition(tuple, andExpr.getRightExpression());
        } else if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression())
                    || evaluateCondition(tuple, orExpr.getRightExpression());
        } else if (whereExpr instanceof InExpression inExpr) {
            return evaluateInExpression(tuple, inExpr);
        } else if (whereExpr instanceof ExistsExpression existsExpr) {
            return evaluateExistsExpression(tuple, existsExpr);
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else {
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): Non-binary predicates such as IS NULL and LIKE are accepted
            // until their expression-specific semantics are implemented.
            return true; // For non-binary and non-AND expressions, just return true for now
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) throws DBException {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        String operator = binaryExpr.getStringExpression();
        Value leftValue = null;
        Value rightValue = null;


        if (leftExpr instanceof Column leftColumn) {
            //get table name
            String table_name = leftColumn.getTableName();
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): Only fill the table name from TableTuple
            // when SQL omits it; otherwise qualified predicates may be rewritten
            // to the current table and hide invalid table references.
            if (tuple instanceof TableTuple) {
                TableTuple tableTuple = (TableTuple) tuple;
                table_name = tableTuple.getTableName();
            }
            leftValue = tuple.getValue(new TabCol(table_name, leftColumn.getColumnName()));
            // TODO(Task 2.1.2 Logical/Physical Operators - WHERE): Guard leftValue before reading type and avoid
            // Value.toString() for CHAR until Value CHAR decoding is fixed.
            if (leftValue.type == ValueType.CHAR) {
                leftValue = new Value(leftValue.toString());
            }
        } else {
            leftValue = getConstantValue(leftExpr); // Handle constant left value
        }

        if (rightExpr instanceof Column rightColumn) {
            //get table name
            String table_name = rightColumn.getTableName();
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): Same qualified-column handling concern as
            // the left side; TableTuple should not overwrite explicit aliases.
            if (tuple instanceof TableTuple) {
                TableTuple tableTuple = (TableTuple) tuple;
                table_name = tableTuple.getTableName();
            }
            rightValue = tuple.getValue(new TabCol(table_name, rightColumn.getColumnName()));
        } else {
            rightValue = getConstantValue(rightExpr); // Handle constant right value

        }

        if (leftValue == null || rightValue == null)
            return false;

        int comparisonResult = ValueComparer.compare(leftValue, rightValue);
        return switch (operator) {
            case "=" -> comparisonResult == 0;
            case "!=", "<>" -> comparisonResult != 0;
            case ">" -> comparisonResult > 0;
            case "<" -> comparisonResult < 0;
            case ">=" -> comparisonResult >= 0;
            case "<=" -> comparisonResult <= 0;
            default -> throw new DBException(ExceptionTypes.UnsupportedExpression(binaryExpr));
        };
    }

    private Value getConstantValue(Expression expr) {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        }
        return null;
    }

    private boolean evaluateInExpression(Tuple tuple, InExpression inExpr) throws DBException {
        Value leftValue = getExprValue(tuple, inExpr.getLeftExpression());
        if (leftValue == null) {
            return inExpr.isNot();
        }

        Expression right = inExpr.getRightExpression();
        if (right instanceof ExpressionList<?> exprList) {
            for (var item : exprList.getExpressions()) {
                Value itemValue = getConstantValue((Expression) item);
                if (itemValue != null && ValueComparer.compare(leftValue, itemValue) == 0) {
                    return !inExpr.isNot();
                }
            }
            return inExpr.isNot();
        }

        if (right instanceof ParenthesedSelect) {
            List<Value> subResults = executeSubQuery(tuple, right.toString());
            for (Value v : subResults) {
                if (ValueComparer.compare(leftValue, v) == 0) {
                    return !inExpr.isNot();
                }
            }
            return inExpr.isNot();
        }

        return !inExpr.isNot();
    }

    private boolean evaluateExistsExpression(Tuple tuple, ExistsExpression existsExpr) throws DBException {
        String subSql = existsExpr.getRightExpression().toString();
        List<Value> results = executeSubQuery(tuple, subSql);
        boolean hasRows = !results.isEmpty();
        return existsExpr.isNot() ? !hasRows : hasRows;
    }

    @SuppressWarnings("deprecation")
    private List<Value> executeSubQuery(Tuple tuple, String rawSql) throws DBException {
        DBManager dbManager = DBManager.getInstance();
        String sql = substituteOuterRefs(tuple, rawSql);

        var plan = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (plan == null) {
            return new ArrayList<>();
        }
        PhysicalOperator exec = PhysicalPlanner.generateOperator(dbManager, plan);
        if (exec == null) {
            return new ArrayList<>();
        }
        List<Value> results = new ArrayList<>();
        exec.Begin();
        while (exec.hasNext()) {
            exec.Next();
            Tuple row = exec.Current();
            if (row != null) {
                Value[] vals = row.getValues();
                if (vals.length > 0 && vals[0] != null) {
                    results.add(vals[0]);
                }
            }
        }
        exec.Close();
        dbManager.getBufferPool().FlushAllPages("");
        return results;
    }

    private String substituteOuterRefs(Tuple tuple, String sql) throws DBException {
        String result = sql;
        TabCol[] schema = tuple.getTupleSchema();
        if (schema == null) {
            return result;
        }
        for (TabCol col : schema) {
            Value v = tuple.getValue(col);
            if (v == null) {
                continue;
            }
            String qualified = col.getTableName() + "." + col.getColumnName();
            String replacement = formatValueForSql(v);

            if (result.contains(qualified)) {
                result = result.replace(qualified, replacement);
            }
        }
        return result;
    }

    private String formatValueForSql(Value v) {
        return switch (v.type) {
            case INTEGER -> v.value.toString();
            case FLOAT -> v.value.toString();
            case CHAR -> "'" + v.value.toString().replace("'", "''") + "'";
            case UNKNOWN -> v.value.toString();
        };
    }

    private Value getExprValue(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof Column col) {
            String tableName = col.getTableName();
            if (tuple instanceof TableTuple tableTuple) {
                tableName = tableTuple.getTableName();
            }
            return tuple.getValue(new TabCol(tableName, col.getColumnName()));
        }
        return getConstantValue(expr);
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column) {
            Column col = (Column) expr;
            return getValue(new TabCol(col.getTableName(), col.getColumnName()));
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

}
