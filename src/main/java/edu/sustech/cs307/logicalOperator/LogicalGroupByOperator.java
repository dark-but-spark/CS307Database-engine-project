package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.Collections;
import java.util.List;

public class LogicalGroupByOperator extends LogicalOperator {
    private final GroupByElement groupByElement;
    private final List<SelectItem<?>> selectItems;
    private final String tableName;

    public LogicalGroupByOperator(LogicalOperator child, GroupByElement groupByElement,
                                  List<SelectItem<?>> selectItems, String tableName) {
        super(Collections.singletonList(child));
        this.groupByElement = groupByElement;
        this.selectItems = selectItems;
        this.tableName = tableName;
    }

    public GroupByElement getGroupByElement() {
        return groupByElement;
    }

    public List<SelectItem<?>> getSelectItems() {
        return selectItems;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public String toString() {
        return "GroupByOperator(groupBy=" + groupByElement + ", select=" + selectItems
                + ")\n └── " + childern.get(0);
    }
}
