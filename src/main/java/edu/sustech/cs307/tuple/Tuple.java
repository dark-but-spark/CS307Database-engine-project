package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.schema.Column;

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
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): Guard leftValue before reading type and avoid
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
        return null; // Unsupported constant type
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
