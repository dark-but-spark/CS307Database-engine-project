package edu.sustech.cs307.index;

import com.jgfanng.algo.BPlusTree;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeSet;

public class BPlusTreeIndex implements Index {
    private static final int BRANCHING_FACTOR = 64;

    private final String tableName;
    private final String indexName;
    private final String columnName;
    private final BPlusTree<ValueIndexKey, List<RID>> tree;
    private final TreeSet<ValueIndexKey> keys;

    public BPlusTreeIndex(String tableName, String indexName, String columnName) {
        this.tableName = tableName;
        this.indexName = indexName;
        this.columnName = columnName;
        this.tree = new BPlusTree<>(BRANCHING_FACTOR);
        this.keys = new TreeSet<>();
    }

    public void insert(Value value, RID rid) {
        // Task 3.1 Index Support - In-memory B+ Tree: adapt the imported
        // BPlusTree to database keys and allow duplicate key values via RID lists.
        ValueIndexKey key = new ValueIndexKey(value);
        List<RID> bucket = tree.search(key);
        if (bucket == null) {
            bucket = new ArrayList<>();
            keys.add(key);
        }
        bucket.add(new RID(rid));
        tree.insert(key, bucket);
    }

    public void delete(Value value, RID rid) {
        // Task 3.1 Index Support - Dynamic Index Maintenance: remove one RID from
        // the indexed key when a row is updated or deleted.
        ValueIndexKey key = new ValueIndexKey(value);
        List<RID> bucket = tree.search(key);
        if (bucket == null) {
            return;
        }
        bucket.removeIf(item -> item.pageNum == rid.pageNum && item.slotNum == rid.slotNum);
        if (bucket.isEmpty()) {
            tree.delete(key);
            keys.remove(key);
        } else {
            tree.insert(key, bucket);
        }
    }

    public String getTableName() {
        return tableName;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getColumnName() {
        return columnName;
    }

    @Override
    public RID EqualTo(Value value) {
        List<RID> bucket = tree.search(new ValueIndexKey(value));
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        return new RID(bucket.get(0));
    }

    public Iterator<RID> EqualToAll(Value value) {
        List<RID> bucket = tree.search(new ValueIndexKey(value));
        return copyRids(bucket == null ? List.of() : bucket).iterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        if (keys.isEmpty()) {
            return List.<Entry<Value, RID>>of().iterator();
        }
        return flatten(keys.headSet(new ValueIndexKey(value), isEqual));
    }

    @Override
    public Iterator<Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        if (keys.isEmpty()) {
            return List.<Entry<Value, RID>>of().iterator();
        }
        return flatten(keys.tailSet(new ValueIndexKey(value), isEqual));
    }

    @Override
    public Iterator<Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        if (keys.isEmpty()) {
            return List.<Entry<Value, RID>>of().iterator();
        }
        return flatten(keys.subSet(new ValueIndexKey(low), leftEqual, new ValueIndexKey(high), rightEqual));
    }

    public Iterator<Entry<Value, RID>> All() {
        if (keys.isEmpty()) {
            return List.<Entry<Value, RID>>of().iterator();
        }
        return flatten(keys);
    }

    public String printTree() {
        // REVIEW(Task 3.1 Index Support - Print B+Tree Nodes): the imported tree's
        // toString prints node key layout, but keys are adapter objects. A more
        // report-friendly printer should expose typed Value text per node.
        // TODO(Task 3.1): Add a typed B+Tree node printer that includes key
        // values, RID buckets, and tree levels for presentation/debugging.
        return "B+Tree[" + tableName + "." + indexName + " on " + columnName + "]\n" + tree;
    }

    private Iterator<Entry<Value, RID>> flatten(Iterable<ValueIndexKey> orderedKeys) {
        List<Entry<Value, RID>> result = new ArrayList<>();
        for (ValueIndexKey key : orderedKeys) {
            List<RID> bucket = tree.search(key);
            if (bucket == null) {
                continue;
            }
            for (RID rid : bucket) {
                result.add(new AbstractMap.SimpleEntry<>(key.value(), new RID(rid)));
            }
        }
        return result.iterator();
    }

    private List<RID> copyRids(List<RID> rids) {
        List<RID> result = new ArrayList<>();
        for (RID rid : rids) {
            result.add(new RID(rid));
        }
        return result;
    }
}
