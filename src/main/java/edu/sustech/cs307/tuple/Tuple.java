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
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;

import java.util.regex.Pattern;

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
        } else if (whereExpr instanceof Parenthesis parenthesis) {
            return evaluateCondition(tuple, parenthesis.getExpression());
        } else if (whereExpr instanceof ParenthesedExpressionList<?> parenthesed
                && parenthesed.getExpressions().size() == 1) {
            return evaluateCondition(tuple, parenthesed.getExpressions().get(0));
        } else if (whereExpr instanceof NotExpression notExpression) {
            return !evaluateCondition(tuple, notExpression.getExpression());
        } else if (whereExpr instanceof IsNullExpression isNullExpression) {
            Value value = evaluateValue(tuple, isNullExpression.getLeftExpression());
            boolean isNull = value == null || value.value == null;
            return isNullExpression.isNot() ? !isNull : isNull;
        } else if (whereExpr instanceof LikeExpression likeExpression) {
            return evaluateLikeExpression(tuple, likeExpression);
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else {
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): IN, EXISTS,
            // BETWEEN, and arithmetic expressions still need dedicated
            // semantics before they can be accepted safely.
            throw new DBException(ExceptionTypes.UnsupportedExpression(whereExpr));
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) throws DBException {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        String operator = binaryExpr.getStringExpression();
        Value leftValue = evaluateValue(tuple, leftExpr);
        Value rightValue = evaluateValue(tuple, rightExpr);

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

    private boolean evaluateLikeExpression(Tuple tuple, LikeExpression likeExpression) throws DBException {
        Value leftValue = evaluateValue(tuple, likeExpression.getLeftExpression());
        Value rightValue = evaluateValue(tuple, likeExpression.getRightExpression());
        if (leftValue == null || rightValue == null) {
            return false;
        }
        if (leftValue.type != ValueType.CHAR || rightValue.type != ValueType.CHAR) {
            throw new DBException(ExceptionTypes.WrongComparisonError(leftValue.type, rightValue.type));
        }

        String value = leftValue.toString();
        String pattern = rightValue.toString();
        Pattern regex = Pattern.compile(likePatternToRegex(pattern),
                likeExpression.isCaseInsensitive() ? Pattern.CASE_INSENSITIVE : 0);
        boolean matched = regex.matcher(value).matches();
        return likeExpression.isNot() ? !matched : matched;
    }

    private String likePatternToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return regex.toString();
    }

    private Value evaluateValue(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof Column column) {
            return resolveColumnValue(tuple, column);
        }
        return getConstantValue(expr);
    }

    private Value resolveColumnValue(Tuple tuple, Column column) throws DBException {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        if (tableName != null && !tableName.isBlank()) {
            return tuple.getValue(new TabCol(tableName, columnName));
        }

        Value matchedValue = null;
        int matchedCount = 0;
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                matchedValue = tuple.getValue(tabCol);
                matchedCount++;
            }
        }
        if (matchedCount > 1) {
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): Ambiguous
            // unqualified columns should eventually surface a dedicated
            // ambiguity error type instead of a generic invalid SQL error.
            throw new DBException(ExceptionTypes.InvalidSQL(column.toString(), "Ambiguous column reference"));
        }
        return matchedValue;
    }

    private Value getConstantValue(Expression expr) {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof NullValue) {
            return null;
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
            return resolveColumnValue(this, col);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

}
