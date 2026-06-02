package edu.sustech.cs307.index;

import com.jgfanng.algo.BPlusTree;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/**
 * 基于 B+Tree 的内存索引 — Task 3 Index (10 分) Q&A 必问。
 *
 * <h3>整体设计</h3>
 * <ul>
 *   <li>底层：BPlusTree&lt;ValueIndexKey, List&lt;RID&gt;&gt;（com.jgfanng.algo 的泛型 B+Tree）</li>
 *   <li>Key：ValueIndexKey — 包装 Value 并实现 Comparable，支持 int/float/char 跨类型排序比较</li>
 *   <li>Value：List&lt;RID&gt; — 同键值可能对应多行（非唯一索引），用 RID 列表存储</li>
 *   <li>额外维护 TreeSet&lt;ValueIndexKey&gt; 用于范围查询（headSet/tailSet/subSet）</li>
 * </ul>
 *
 * <h3>为什么额外维护 TreeSet？（答辩高频追问）</h3>
 * <ol>
 *   <li>BPlusTree.searchRange() 返回 List&lt;V&gt;（物化结果），一次性把所有匹配值装入内存。
 *       如果范围很大会 OOM，且不符合火山模型（按需拉取）。</li>
 *   <li>索引层需要 Iterator&lt;Entry&lt;Value, RID&gt;&gt;（懒迭代器）。
 *       红黑树的 headSet/tailSet/subSet 是 SortedSet 视图，不拷贝数据。</li>
 *   <li>flatten() 遍历 TreeSet view 时按需调用 tree.search() 取 RID bucket，
 *       每个 key 只在实际被消费时才从 B+Tree 查询。</li>
 * </ol>
 *
 * <h3>RID bucket 并发语义</h3>
 * <ul>
 *   <li>insert(value, rid)：先 tree.search(key) 取 bucket → 追加 RID → tree.insert(key, bucket) 覆盖</li>
 *   <li>delete(value, rid)：从 bucket 中 removeIf → bucket 空则 tree.delete(key) + TreeSet.remove(key)</li>
 *   <li>RID 复制使用 new RID(rid) 避免引用泄漏</li>
 * </ul>
 *
 * <h3>索引动态维护</h3>
 * INSERT 行 → BPlusTreeIndex.insert(value, rid)
 * DELETE 行 → BPlusTreeIndex.delete(value, rid)
 * UPDATE 行 → delete(oldValue, rid) + insert(newValue, rid)
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
        return copyRidsLazy(bucket == null ? List.of() : bucket);
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
     * 展平：把多个 key 的 RID bucket 合并为 (Value, RID) 对的有序懒迭代器。
     * 
     * <h3>实现要点（答辩可答）</h3>
     * <ul>
     *   <li>TreeSet 遍历顺序即 key 排序顺序 → 输出 (Value, RID) 按 key 有序</li>
     *   <li>懒求值：每次 hasNext() 才推进到下一个有效 RID，不提前物化</li>
     *   <li>跨 key 切换：当前 bucket 耗尽时，从 keyIterator 取下一个 key，
     *       通过 tree.search() 查询该 key 的 RID bucket</li>
     *   <li>空 bucket 安全：bucket 为 null 时视为空列表，自动跳到下一个 key</li>
     * </ul>
     * 
     * @param orderedKeys 有序 key 迭代器（来自 TreeSet 的 view）
     * @return (Value, RID) 对的懒迭代器
     */
    private Iterator<Entry<Value, RID>> flatten(Iterable<ValueIndexKey> orderedKeys) {
        return new Iterator<>() {
            private final Iterator<ValueIndexKey> keyIterator = orderedKeys.iterator();
            private ValueIndexKey currentKey;
            private List<RID> currentBucket = List.of();
            private int bucketCursor = 0;

            @Override
            public boolean hasNext() {
                advanceToNextRid();
                return bucketCursor < currentBucket.size();
            }

            @Override
            public Entry<Value, RID> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                RID rid = currentBucket.get(bucketCursor++);
                return new AbstractMap.SimpleEntry<>(currentKey.value(), new RID(rid));
            }

            private void advanceToNextRid() {
                while (bucketCursor >= currentBucket.size() && keyIterator.hasNext()) {
                    currentKey = keyIterator.next();
                    List<RID> bucket = tree.search(currentKey);
                    currentBucket = bucket == null ? List.of() : bucket;
                    bucketCursor = 0;
                }
            }
        };
    }

    private Iterator<RID> copyRidsLazy(List<RID> rids) {
        return new Iterator<>() {
            private int cursor = 0;

            @Override
            public boolean hasNext() {
                return cursor < rids.size();
            }

            @Override
            public RID next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return new RID(rids.get(cursor++));
            }
        };
    }
}
