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
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;

import java.util.ArrayList;
import java.util.List;
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
        } else if (whereExpr instanceof InExpression inExpression) {
            return evaluateInExpression(tuple, inExpression);
        } else if (whereExpr instanceof ExistsExpression existsExpression) {
            return evaluateExistsExpression(tuple, existsExpression);
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else {
            // REVIEW(Task 2.1.2 Logical/Physical Operators - WHERE): BETWEEN
            // and arithmetic expressions still need dedicated semantics before
            // they can be accepted safely.
            throw new DBException(ExceptionTypes.UnsupportedExpression(whereExpr));
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) throws DBException {
        Value leftValue = evaluateValue(tuple, binaryExpr.getLeftExpression());
        Value rightValue = evaluateValue(tuple, binaryExpr.getRightExpression());
        if (leftValue == null || rightValue == null) {
            return false;
        }

        int comparisonResult = ValueComparer.compare(leftValue, rightValue);
        return switch (binaryExpr.getStringExpression()) {
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

        Pattern regex = Pattern.compile(likePatternToRegex(rightValue.toString()),
                likeExpression.isCaseInsensitive() ? Pattern.CASE_INSENSITIVE : 0);
        boolean matched = regex.matcher(leftValue.toString()).matches();
        return likeExpression.isNot() ? !matched : matched;
    }

    private boolean evaluateInExpression(Tuple tuple, InExpression inExpression) throws DBException {
        Value leftValue = evaluateValue(tuple, inExpression.getLeftExpression());
        if (leftValue == null) {
            return inExpression.isNot();
        }

        Expression right = inExpression.getRightExpression();
        if (right instanceof ExpressionList<?> expressionList) {
            for (Expression item : expressionList.getExpressions()) {
                Value itemValue = evaluateValue(tuple, item);
                if (itemValue != null && ValueComparer.compare(leftValue, itemValue) == 0) {
                    return !inExpression.isNot();
                }
            }
            return inExpression.isNot();
        }

        if (right instanceof ParenthesedSelect) {
            for (Value value : executeSubQuery(tuple, right.toString())) {
                if (value != null && ValueComparer.compare(leftValue, value) == 0) {
                    return !inExpression.isNot();
                }
            }
            return inExpression.isNot();
        }

        throw new DBException(ExceptionTypes.UnsupportedExpression(inExpression));
    }

    private boolean evaluateExistsExpression(Tuple tuple, ExistsExpression existsExpression) throws DBException {
        List<Value> results = executeSubQuery(tuple, existsExpression.getRightExpression().toString());
        boolean hasRows = !results.isEmpty();
        return existsExpression.isNot() ? !hasRows : hasRows;
    }

    private List<Value> executeSubQuery(Tuple tuple, String rawSql) throws DBException {
        DBManager dbManager = DBManager.getInstance();
        if (dbManager == null) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(new StringValue(rawSql)));
        }
        String sql = substituteOuterRefs(tuple, rawSql);
        var plan = LogicalPlanner.resolveAndPlan(dbManager, sql);
        if (plan == null) {
            return new ArrayList<>();
        }
        PhysicalOperator exec = PhysicalPlanner.generateOperator(dbManager, plan);
        List<Value> results = new ArrayList<>();
        exec.Begin();
        try {
            while (exec.hasNext()) {
                exec.Next();
                Tuple row = exec.Current();
                if (row != null) {
                    Value[] values = row.getValues();
                    if (values.length > 0) {
                        results.add(values[0]);
                    }
                }
            }
        } finally {
            exec.Close();
        }
        return results;
    }

    private String substituteOuterRefs(Tuple tuple, String sql) throws DBException {
        String result = sql;
        for (TabCol column : tuple.getTupleSchema()) {
            Value value = tuple.getValue(column);
            if (value == null) {
                continue;
            }
            String qualified = column.getTableName() + "." + column.getColumnName();
            result = result.replace(qualified, formatValueForSql(value));
        }
        return result;
    }

    private String formatValueForSql(Value value) {
        return switch (value.type) {
            case INTEGER, FLOAT -> value.value.toString();
            case CHAR -> "'" + value.value.toString().replace("'", "''") + "'";
            case UNKNOWN -> value.value.toString();
        };
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
        if (expr instanceof StringValue stringValue) {
            return new Value(stringValue.getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue doubleValue) {
            return new Value(doubleValue.getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue longValue) {
            return new Value(longValue.getValue(), ValueType.INTEGER);
        } else if (expr instanceof NullValue) {
            return null;
        }
        return null;
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        if (expr instanceof StringValue stringValue) {
            return new Value(stringValue.getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue doubleValue) {
            return new Value(doubleValue.getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue longValue) {
            return new Value(longValue.getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column column) {
            return resolveColumnValue(this, column);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }
}
