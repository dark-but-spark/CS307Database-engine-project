package edu.sustech.cs307.index;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;

final class ValueIndexKey implements Comparable<ValueIndexKey> {
    private final Value value;

    ValueIndexKey(Value value) {
        this.value = value;
    }

    Value value() {
        return value;
    }

    @Override
    public int compareTo(ValueIndexKey other) {
        try {
            return ValueComparer.compare(value, other.value);
        } catch (DBException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
