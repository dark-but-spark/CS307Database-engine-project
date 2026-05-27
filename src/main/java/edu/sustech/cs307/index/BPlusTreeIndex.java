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

/**
 * 基于 B+Tree 的内存索引 — Q&A 必问（Task 3 Index 10 分）。
 *
 * 整体设计：
 * - 底层使用导入的 BPlusTree<K, V> 泛型实现（com.jgfanng.algo）
 * - Key:   ValueIndexKey（包装 Value，实现 Comparable 接口支持排序比较）
 * - Value: List<RID>（同一键值可能对应多行，用 RID 列表存储）
 * - 额外维护 TreeSet<ValueIndexKey> 用于范围查询（headSet/tailSet/subSet）
 *
 * 为什么不直接用 BPlusTree 的范围查询？
 * BPlusTree 不支持原生的范围遍历。TreeSet 是红黑树，支持 O(log n) 的范围定位。
 * 索引维护时同步更新 TreeSet。
 *
 * 索引的动态维护（增删改）：
 * - INSERT 行 → insert(value, rid)：查找 bucket，追加 RID，插回 B+Tree
 * - DELETE 行 → delete(value, rid)：从 bucket 中移除该 RID；bucket 为空则删除整个 key
 * - UPDATE 行 → delete(oldValue, rid) + insert(newValue, rid)
 */
public class BPlusTreeIndex implements Index {
    /** B+Tree 的分支因子，影响树的高度和节点容量 */
    private static final int BRANCHING_FACTOR = 64;

    private final String tableName;
    private final String indexName;
    private final String columnName;
    /** B+Tree 存储结构：索引键 → RID 列表（同键值多行） */
    private final BPlusTree<ValueIndexKey, List<RID>> tree;
    /** 红黑树维护所有 key 的有序集合，用于范围查询 */
    private final TreeSet<ValueIndexKey> keys;

    public BPlusTreeIndex(String tableName, String indexName, String columnName) {
        this.tableName = tableName;
        this.indexName = indexName;
        this.columnName = columnName;
        this.tree = new BPlusTree<>(BRANCHING_FACTOR);
        this.keys = new TreeSet<>();
    }

    /**
     * 插入索引条目。
     * 同一个 key 值可能对应多行（非唯一索引），用 List<RID> 存储。
     * 同时更新 TreeSet 用于范围查询。
     */
    public void insert(Value value, RID rid) {
        ValueIndexKey key = new ValueIndexKey(value);
        List<RID> bucket = tree.search(key);
        if (bucket == null) {
            bucket = new ArrayList<>();
            keys.add(key);        // 新 key，加入有序集合
        }
        bucket.add(new RID(rid));
        tree.insert(key, bucket);
    }

    /**
     * 删除索引条目。
     * 从 RID bucket 中移除指定 RID。
     * 如果 bucket 变空，从 B+Tree 和 TreeSet 中完全删除该 key。
     */
    public void delete(Value value, RID rid) {
        ValueIndexKey key = new ValueIndexKey(value);
        List<RID> bucket = tree.search(key);
        if (bucket == null) {
            return;
        }
        bucket.removeIf(item -> item.pageNum == rid.pageNum && item.slotNum == rid.slotNum);
        if (bucket.isEmpty()) {
            tree.delete(key);
            keys.remove(key);     // 从有序集合中移除
        } else {
            tree.insert(key, bucket);  // 更新 bucket
        }
    }

    public String getTableName() { return tableName; }
    public String getIndexName()  { return indexName; }
    public String getColumnName() { return columnName; }

    /**
     * 等值查询 — O(log n)。
     * 返回匹配 key 的第一个 RID（非唯一索引可能有多行）。
     * 用于优化器中的点查询（col = value）。
     */
    @Override
    public RID EqualTo(Value value) {
        List<RID> bucket = tree.search(new ValueIndexKey(value));
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        return new RID(bucket.get(0));
    }

    /** 等值查询 — 返回所有匹配行的 RID 迭代器 */
    public Iterator<RID> EqualToAll(Value value) {
        List<RID> bucket = tree.search(new ValueIndexKey(value));
        return copyRids(bucket == null ? List.of() : bucket).iterator();
    }

    /**
     * 范围查询 — 小于（可选等于）。
     * 利用 TreeSet.headSet() 获取所有 < value（或 <= value）的 key，
     * 然后把每个 key 的 RID bucket 展平为 (Value, RID) 对。
     */
    @Override
    public Iterator<Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        if (keys.isEmpty()) {
            return List.<Entry<Value, RID>>of().iterator();
        }
        return flatten(keys.headSet(new ValueIndexKey(value), isEqual));
    }

    /** 范围查询 — 大于（可选等于） */
    @Override
    public Iterator<Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        if (keys.isEmpty()) {
            return List.<Entry<Value, RID>>of().iterator();
        }
        return flatten(keys.tailSet(new ValueIndexKey(value), isEqual));
    }

    /** 范围查询 — 闭/开区间 [low, high] 或 (low, high) */
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

    /** 打印 B+Tree 索引内容，用于调试和演示。 */
    public String printTree() {
        StringBuilder builder = new StringBuilder();
        builder.append("B+Tree[")
                .append(tableName)
                .append(".")
                .append(indexName)
                .append(" on ")
                .append(columnName)
                .append("]\n");
        builder.append("keys=").append(keys.size()).append('\n');
        builder.append("leaf[0] ");
        if (keys.isEmpty()) {
            builder.append("[]");
            return builder.toString();
        }

        builder.append('[');
        boolean firstKey = true;
        for (ValueIndexKey key : keys) {
            if (!firstKey) {
                builder.append(", ");
            }
            firstKey = false;
            builder.append(key.value()).append(" -> ");
            appendRidBucket(builder, tree.search(key));
        }
        builder.append(']');
        return builder.toString();
    }

    private void appendRidBucket(StringBuilder builder, List<RID> bucket) {
        builder.append('[');
        if (bucket != null) {
            for (int i = 0; i < bucket.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                RID rid = bucket.get(i);
                builder.append('(')
                        .append(rid.pageNum)
                        .append(',')
                        .append(rid.slotNum)
                        .append(')');
            }
        }
        builder.append(']');
    }

    /**
     * 展平：把多个 key 的 RID bucket 合并为 (Value, RID) 对的有序迭代器。
     * 因为 TreeSet 遍历是有序的，所以输出的 (Value, RID) 对也是按 key 排序的。
     */
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
