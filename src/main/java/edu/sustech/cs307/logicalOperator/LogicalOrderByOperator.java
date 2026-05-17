package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.Collections;
import java.util.List;

public class LogicalOrderByOperator extends LogicalOperator {
    private final List<OrderByElement> orderByElements;

    public LogicalOrderByOperator(LogicalOperator child, List<OrderByElement> orderByElements) {
        super(Collections.singletonList(child));
        this.orderByElements = orderByElements;
    }

    public List<OrderByElement> getOrderByElements() {
        return orderByElements;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OrderByOperator(orderBy=").append(orderByElements).append(")");
        String[] childLines = childern.get(0).toString().split("\\R");
        sb.append("\n└── ").append(childLines[0]);
        for (int i = 1; i < childLines.length; i++) {
            sb.append("\n    ").append(childLines[i]);
        }
        return sb.toString();
    }
}
