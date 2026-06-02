# B+Tree 索引答辩要点（Task 3 — 10 分）

> 覆盖：B+Tree 数据结构 → 索引封装层 → 优化器集成 → 动态维护，共四层。

---

## 速查索引（按答辩问题快速定位代码）

| 答辩问题 | 关键文件:行号 |
|---------|-------------|
| B+Tree vs B-Tree 区别 | `BPlusTree.java:11-42`（结构定义）|
| split 分裂点为什么 InternalNode/LeafNode 不同？ | `BPlusTree.java:246`（InternalNode from）, `BPlusTree.java:397`（LeafNode from）|
| B+Tree 如何做范围查询？ | `BPlusTree.java:361-383`（LeafNode.getRange 链表遍历）|
| 溢出/下溢条件是什么？ | `BPlusTree.java:258-265`（InternalNode）, `BPlusTree.java:410-417`（LeafNode）|
| 删除时下溢后如何处理？ | `BPlusTree.java:185-203`（deleteValue + merge + split 三段式）|
| 为什么索引层用 TreeSet？ | `BPlusTreeIndex.java:22-27`（类注释）, `BPlusTreeIndex.java:204-235`（flatten 懒迭代）|
| 优化器如何选索引？ | `PhysicalPlanner.java:89-96`（handleFilter）, `PhysicalPlanner.java:114-152`（tryBuildIndexScan）|
| IndexScanOperator 执行流程 | `IndexScanOperator.java:129-147`（Begin 打开索引迭代器）, `IndexScanOperator.java:164-179`（Next 按RID读记录）|
| 索引如何动态维护（INSERT/UPDATE/DELETE）？ | `BPlusTreeIndex.java:58-67`（insert）, `BPlusTreeIndex.java:73-87`（delete）|
| CREATE INDEX 时如何构建？ | `DBManager.java:355-378`（rebuildIndex 全表扫描构建）|
| BEGIN 快照如何设计？（Task 4） | `TransactionManager.java:81-93`（begin）|
| COMMIT 底层做了什么？（Task 4） | `TransactionManager.java:106-115`（commit）|
| SAVEPOINT 和 ROLLBACK 设计（Task 4） | `TransactionManager.java:140-178`（savepoint + rollbackToSavepoint）|

---

## 一、数据结构层：B+Tree 裸实现 (`BPlusTree.java`)

### 1.1 B+Tree vs B-Tree

| 特性 | B-Tree | B+Tree |
|------|--------|--------|
| 数据存储 | 所有节点都存 key + value | 只有叶子节点存 value |
| 内部节点 | 存 key + value + 子指针 | 只存 key + 子指针（更扁） |
| 叶子节点链接 | 无 | 有 `next` 指针形成有序链表 |
| 范围查询 | 需要中序遍历整棵树 | 叶子链表顺序扫描，O(k) |
| 等值查询 | O(log n) | O(log n) |

**答辩话术**：「B+Tree 把数据全部下沉到叶子层，内部节点只存索引键。这让每一层能容纳更多 key，树更扁，磁盘 I/O 更少。叶子节点用链表串联，范围查询不需要回溯到上层，直接顺序扫就行。」

### 1.2 节点结构

```
BPlusTree<K extends Comparable, V>
├── Node (abstract)
│   ├── InternalNode  → List<K> keys    +  List<Node> children
│   └── LeafNode      → List<K> keys    +  List<V> values  +  LeafNode next
```

- **InternalNode.keys[i]**：`children[i+1]` 子树的最小键
- **LeafNode.next**：链表指针，支持范围扫描（`searchRange` 就顺着它走）
- 泛型 `<K, V>`：Key 必须可比较，Value 无约束

### 1.3 分支因子（Branching Factor）

- 默认 128，索引层设为 64
- InternalNode 最大 children 数 = branchingFactor
- LeafNode 最大 values 数 = branchingFactor - 1

**为什么叶子少一个？** 因为叶子节点分裂时，中间键被复制到父节点（不是上移），叶子保留该键。内部节点分裂时中间键是上移到父节点。所以叶子容量比内部节点少 1，保证分裂后两边都不会溢出。

### 1.4 核心操作

#### 查找 `search(key)`
从根开始：`InternalNode.getChild(key)` 用 `Collections.binarySearch` 二分定位子节点索引，递归到叶子再做二分。**全程 O(log n)**。

#### 插入 `insert(key, value)`
```
1. 根.递归到叶子 → 二分找到插入位置
2. 叶子插入（如果 key 已存在则替换 value）
3. 回溯：如果节点溢出 → split() 成两个节点
   - InternalNode 分裂时中间键上移到父节点
   - LeafNode 分裂时中间键复制到父节点（叶子保留）
4. 如果根溢出 → 创建新 InternalNode 作为新根，树高 +1
```

#### 删除 `delete(key)`
```
1. 找到叶子 → 二分定位并删除
2. 回溯：如果节点下溢 → 尝试从兄弟借键
   - 先合并（merge）左右兄弟
   - 合并后如果溢出再 split
3. 如果根被掏空（keys 变 0）→ 降高度
```

### 1.5 溢出/下溢判定

| 节点类型 | 溢出条件 | 下溢条件 |
|---------|---------|---------|
| InternalNode | `children.size() > branchingFactor` | `children.size() < (branchingFactor+1)/2` |
| LeafNode | `values.size() > branchingFactor - 1` | `values.size() < branchingFactor/2` |

### 1.6 分裂点选择（答辩重点）

```java
// InternalNode 分裂 (BPlusTree.java:245)
int from = keyNumber() / 2 + 1;   // 偏右，因为 keys[i] 对应 children[i+1]

// LeafNode 分裂 (BPlusTree.java:397)
int from = (keyNumber() + 1) / 2; // 偏左（向上取半），数据均匀分布
```

**为什么不同？** InternalNode 分裂后中间键上提到父节点（不在子节点中保留），所以分裂点偏右；LeafNode 分裂后中间键复制到父节点（叶子保留一份），分裂点偏左保证两边数据平衡。

### 1.7 范围查询 `searchRange(key1, policy1, key2, policy2)`

```
1. 从根二分找到 key1 对应的叶子节点
2. 顺着 LeafNode.next 链表顺序扫描
3. 遇 key > key2（或 key >= key2 取决于 EXCLUSIVE/INCLUSIVE）停止
4. 返回所有匹配的 value 列表
```

时间复杂度：O(log n + k)，k 为范围内的键数。

---

## 二、打印/可视化逻辑

### 2.1 树结构打印 `BPlusTree.toString()`（第 110-138 行）

BFS 逐层输出，用于调试树形态：

```
{[0, 2]}            ← root: InternalNode 的 keys
{[0], [2], [4]}     ← 第二层: children 的 keys
{[0], [1], [2, 3], [4, 5], [6]}
```

实现细节：
- 用两个队列：`queue`（当前层）、`nextQueue`（下一层）
- InternalNode 的 `children` 整组加入 nextQueue
- LeafNode 无 children，到叶子层自然停止
- 每层用 `{ }` 包裹，同层节点用 `, ` 分隔
- 每个节点的 `toString()` 只输出 `keys`（`[key1, key2, ...]` 格式），不输出 value

### 2.2 业务数据打印 `BPlusTreeIndex.printTree()`（第 152-180 行）

输出索引的实际内容：

```
B+Tree[t1.idx_age on age]
keys=3
leaf[0] [18 -> [(0,2)], 19 -> [(0,1), (1,3)], 20 -> [(0,0)]]
```

- 打印元信息（表名、索引名、列名）
- 按 TreeSet 有序遍所有 key
- 每个 key 后跟 RID bucket（页号, 槽号）
- **不展示树层级**，关注业务数据

### 2.3 如何触发打印

| 方式 | 触发点 |
|------|--------|
| `CREATE INDEX` | `DBManager.createIndex()` 自动调用 `Logger.info(index.printTree())` |
| Java 代码 | `dbManager.getIndex(table, idx).printTree()` |
| 树结构 | `bpt.toString()`（调试时手动调用） |

**当前限制**：没有 `SHOW INDEX` 命令来事后查看索引。只能在 `CREATE INDEX` 时自动输出，或在 Java 测试代码中手动调用。

---

## 三、索引封装层：`BPlusTreeIndex.java`

### 3.1 泛型实例化

```java
BPlusTree<ValueIndexKey, List<RID>> tree;
```

- **Key**：`ValueIndexKey` — 包装 `Value` 并实现 `Comparable`，支持 int/float/char 跨类型排序比较
- **Value**：`List<RID>` — 同键值多行（非唯一索引），一个 key 可对应多个 RID

### 3.2 为什么额外维护 TreeSet？

代码注释写明：**「BPlusTree 不支持原生的范围遍历」**。

虽然底层 `BPlusTree.searchRange()` 存在且有 LeafNode 链表，但索引层选择用 `TreeSet<ValueIndexKey>`（红黑树）做范围查询。原因：
- `headSet`/`tailSet`/`subSet` 直接返回有序 key 集合
- 更易于展平为 `(Value, RID)` 迭代器
- TreeSet 的 O(log n) 范围定位 + 顺序遍历

### 3.3 范围查询实现

```java
// 小于 value: keys.headSet(key, isEqual)  → flatten → (Value, RID) 迭代器
// 大于 value: keys.tailSet(key, isEqual)  → flatten → (Value, RID) 迭代器
// 区间查询: keys.subSet(low, leftEq, high, rightEq) → flatten
```

`flatten()` 方法把多个 key 的 RID bucket 合并为懒迭代器（不提前物化，按需产出）。

### 3.4 RID bucket 的并发语义

- `insert(value, rid)`：先 `tree.search(key)` 取 bucket，追加 RID，再 `tree.insert(key, bucket)` 覆盖
- `delete(value, rid)`：从 bucket 中 `removeIf`，bucket 空则 `tree.delete(key)` 并从 TreeSet 移除
- 复制 RID 时使用 `new RID(rid)` 避免引用泄漏

---

## 四、优化器集成

### 4.1 物理计划生成 `PhysicalPlanner.handleFilter()`（第 85-105 行）

```
LogicalFilterOperator
    └── LogicalTableScanOperator（单表扫描）
```

当 filter 下面是一个 table scan 时，`tryBuildIndexScan()` 尝试用索引替代全表扫描：

```
if (可以用索引):
    return FilterOperator(IndexScanOperator, residual_predicate)
else:
    return FilterOperator(SeqScanOperator, full_predicate)
```

**索引不是替代 WHERE**，而是缩小候选 RID 范围。上层 FilterOperator 仍然保留完整 WHERE 语义，防止 residual predicate 漏判。

### 4.2 索引选择策略 `tryBuildIndexScan()`（第 114-152 行）

```
1. 检查 filter 下面是否是 LogicalTableScanOperator（单表）
2. 拆解 AND 条件为 conjuncts
3. 找到第一个有索引的列
4. 收集同一列上所有 AND 条件，合并为最紧的边界
   例：id >= 10 AND id < 16 → low=10(inclusive), high=16(exclusive)
5. 等值条件优先 → IndexScanOperator(EQUAL)
6. 范围条件   → IndexScanOperator(RANGE, low, high, ...)
```

**TODO 标注**：当前只选「第一个有索引的列」。后续可考虑 OR 谓词、多索引交集、代价估计。

### 4.3 IndexScanOperator 执行流程（第 129-147 行）

```
Begin():
    1. 获取 TableMeta + RecordFileHandle
    2. dbManager.getIndexOnColumn(tableName, columnName) 获取 BPlusTreeIndex
    3. 根据 ScanMode 创建索引迭代器:
       - EQUAL → index.EqualToAll(equalValue) → 包装为 (Value,RID) 迭代器
       - RANGE → rangeIterator(index) → 根据 low/high 选择 LessThan/MoreThan/Range
    4. 懒迭代: 只在 Next() 时才从索引取下一个 RID

Next():
    entry = indexIterator.next()       // 从 B+Tree 拿到 RID
    record = fileHandle.GetRecord(rid)  // 按 RID 随机读取记录
    currentTuple = new TableTuple(record)
```

**已知瓶颈**（注释中标明）：大量结果集时产生大量随机 I/O。后续可引入批量预取（batch RID → page lookup）。

---

## 五、索引动态维护

三种 DML 操作都会同步更新运行时索引：

| 操作 | 索引维护 | 代码位置 |
|------|---------|---------|
| INSERT | `insertIntoIndexes()` | `DBManager.java:320` |
| UPDATE | `updateIndexes()` — 先删旧值，再插新值 | `DBManager.java:330` |
| DELETE | `deleteFromIndexes()` — 从所有索引中移除该行 RID | `DBManager.java:342` |

### 5.1 索引重建 `rebuildIndex()`（第 355-378 行）

索引是纯内存结构。当进程重启或首次使用索引时，通过全表扫描重建：

```
1. 创建空 BPlusTreeIndex
2. SeqScanOperator 扫描全表
3. 逐行 index.insert(columnValue, tupleRID)
4. 存入 runtimeIndexes Map
```

**为什么用 lazy rebuild？** 索引不持久化。进程重启后 `runtimeIndexes` 为空，第一次查询自动重建。简单、正确、符合项目要求。

---

## 六、答辩可能追问 — Task 3 Index（必问，回答错则 Index 部分不得分）

### Q1: B+Tree 为什么比 B-Tree 更适合数据库索引？请从 I/O 和范围查询两个角度回答。

**标准回答**：
1. **更少的 I/O**：B+Tree 内部节点只存 key 不存 value，单个节点的出度更大 → 同样数据量下树更矮（高度更低）→ 从根到叶子的随机 I/O 次数更少。例如 branchingFactor=128 时，4 层 B+Tree 可以索引 128³≈200 万条记录。
2. **范围查询高效**：B+Tree 叶子节点用 `next` 指针串成有序链表，定位到起始 key 后顺序扫描即可（O(log n + k)）。B-Tree 范围查询需要中序遍历整棵树，I/O 不连续、缓存不友好。
3. **扫描稳定性**：任何查询都要走到叶子层，路径长度固定，性能可预测。

**代码证据**：`BPlusTree.java:364-381` — `LeafNode.getRange()` 顺着 `node.next` 链表逐个叶子扫描。

### Q2: 分裂时 InternalNode 和 LeafNode 的分裂点为什么不同？具体举例说明。

**标准回答**：
- **InternalNode 分裂（line 246）**：`from = keyNumber() / 2 + 1`，偏右。
  - 原因：内部节点分裂时中间键**上移**到父节点，子节点不再保留它。分裂后两边各分大约一半 children。
  - 例：7 个 key [k1,k2,k3,k4,k5,k6,k7] 对应 8 个 children [c1..c8]。
    - from=7/2+1=4（zero-index），即从 k4 开始分给 sibling。
    - 分裂后原节点保留 k1,k2,k3 + c1..c4，sibling 得到 k5,k6,k7 + c5..c8。
    - k4 上移到父节点，父节点新增一条索引指向 sibling。
  
- **LeafNode 分裂（line 397）**：`from = (keyNumber() + 1) / 2`，偏左（向上取半）。
  - 原因：叶子分裂时中间键**复制**到父节点，叶子自己保留一份。分裂点偏左保证两边数据量均衡。
  - 例：5 个 key [k1,k2,k3,k4,k5]。
    - from=(5+1)/2=3（zero-index），即从 k3 开始分给 sibling。
    - 原节点保留 k1,k2，sibling 得到 k3,k4,k5。
    - k3 复制到父节点作为 sibling 的索引键。

### Q3: 为什么索引层用 TreeSet 而不是直接用 BPlusTree.searchRange？（高频追问）

**标准回答**：
- `BPlusTree.searchRange()` 返回 `List<V>`，是一份**物化**的全量结果（提前把所有匹配 value 装入内存）。如果范围很大，会 OOM。
- 索引层需要 `Iterator<Entry<Value, RID>>`，希望按需拉取（火山模型），避免提前物化。
- `TreeSet`（红黑树）的 `headSet`/`tailSet`/`subSet` 是**视图**（SortedSet view），不复制数据。遍历时才按需调用 `tree.search()` 取 bucket。
- `flatten()`（`BPlusTreeIndex.java:204`）实现了懒迭代器：每次 `hasNext()` 才去 B+Tree 查下一个 key 的 RID bucket。

### Q4: 删除时下溢了怎么处理？逐层回溯流程是什么？

**标准回答**（代码见 `BPlusTree.java:185-203`）：
```
1. 叶子删除 key/value → 检查是否 isUnderflow()
2. 如果下溢：获取左右兄弟 → merge(兄弟)
   - InternalNode merge：合并 keys + children，用兄弟的 firstLeafKey 做分隔键
   - LeafNode merge：合并 keys + values，连接 next 指针
3. merge 之后检查左节点（left）是否 isOverflow()
   - 如果溢出 → split() 再分裂，新节点插入父节点
4. 从父节点 deleteChild(被合并节点的 firstLeafKey) — 移除不再需要的索引项
5. 如果父节点也因此下溢 → 递归回到步骤 2
6. 根节点 keys 变为 0 → root = left（降高度）
```
**关键点**：下溢 → merge（先合并）→ 可能溢出 → split（再分裂），这种"先合再分"保证不会产生新的持续下溢。

### Q5: 为什么是内存索引而不是持久化索引？重启后索引还在吗？

**标准回答**：
- 当前索引是纯内存结构（`BPlusTree` + `TreeSet` 都在 JVM 堆中），不写磁盘。
- 重启后 `runtimeIndexes` Map 为空，索引丢失。
- 恢复机制：首次查询某表或 `CREATE INDEX` 时，`DBManager.rebuildIndex()`（line 355-378）通过全表 SeqScan 重建索引。
- 元数据（哪个表有哪些索引、索引在哪些列上）持久化在 JSON 文件中，重启后可读取。
- **为什么不持久化？** 课程项目范围。生产级（MySQL InnoDB）将 B+Tree 页直接映射到磁盘页（聚簇索引），配合 buffer pool 管理。

### Q6: 如果有多个索引列，优化器怎么选？

**标准回答**：
- 当前策略：`tryBuildIndexScan()`（`PhysicalPlanner.java:114-152`）遍历 WHERE 子句的 AND conjuncts，选择**第一个**命中已建索引的列。
- 选中后，把同一列上的所有 AND 条件合并为最紧边界（`IndexBounds.add()` 的 `isTighterLow`/`isTighterHigh`）。
- 例：`WHERE id >= 10 AND id < 16 AND name = 'alice'`，如果 id 和 name 都有索引，优化器只选 id（第一个命中的），生成 `IndexScanOperator(RANGE, 10, 16)`，外层 `FilterOperator` 再检查 `name = 'alice'`。
- **局限**（代码 TODO）：不支持 OR、多索引交集、代价估计。

### Q7: 范围查询时，上界和下界的开闭区间怎么处理？

**标准回答**：
- `BPlusTreeIndex.LessThan(value, isEqual)` → `TreeSet.headSet(key, isEqual)`
- `BPlusTreeIndex.MoreThan(value, isEqual)` → `TreeSet.tailSet(key, isEqual)`
- `BPlusTreeIndex.Range(low, high, leftEq, rightEq)` → `TreeSet.subSet(keyLow, leftEq, keyHigh, rightEq)`
- `IndexScanOperator` 构造时传入 `lowInclusive`/`highInclusive` 布尔标志，`rangeIterator()`（line 252-263）根据 low/high 是否为 null 决定调用 LessThan/MoreThan/Range。
- 例：`WHERE col > 10 AND col <= 20` → low=10(exclusive), high=20(inclusive) → `MoreThan(10, false)` 不行（因为范围扫描用 Range），实际合并为 `Range(10, 20, false, true)`。

### Q8: IndexScanOperator 执行时，每次 Next() 都要读磁盘吗？性能如何？

**标准回答**：
- 是的。`Next()`（`IndexScanOperator.java:164`）调用 `fileHandle.GetRecord(rid)` 按 RID 随机读取记录。
- 索引只提供候选 RID 集合，实际记录仍从磁盘读取。
- **随机 I/O 代价**：如果结果集很大（如 1000 行），会产生 1000 次 `GetRecord` → 1000 次 `BufferPool.Pin/Unpin`。
- **优化方向**（代码 REVIEW 标注）：批量预取（收集一批 RID → 按 page 分组 → 一次 pin 一页读取多个 slot）。
- **为什么仍保留外层 FilterOperator？** 索引可能只吸收部分条件（如只用 id 索引），其他条件（如 name = 'alice'）需要 FilterOperator 在读取完整记录后判断，保证语义正确。

### Q9: CREATE INDEX 时如果有现有数据，如何处理？

**标准回答**：
- `CREATE INDEX` 执行流程：
  1. `MetaManager` 更新元数据 JSON（记录索引名、表名、列名）
  2. `DBManager.createIndex()` 调用 `rebuildIndex()` 
  3. `rebuildIndex()` 创建空的 `BPlusTreeIndex` → 用 `SeqScanOperator` 全表扫描 → 逐行 `index.insert(columnValue, rid)`
  4. 存入选中的 `runtimeIndexes` Map
- 后续 INSERT/UPDATE/DELETE 自动维护索引（参见第五节动态维护表）。

### Q10: IndexScanOperator 和 SeqScanOperator 的区别？什么情况下索引扫描反而不如全表扫描？

**标准回答**：
- **SeqScanOperator**：扫描 record page bitmap，一次 page pin 可以顺序读多个有效 slot → 对磁盘顺序 I/O 友好。
- **IndexScanOperator**：先查索引定位 RID → 再逐 RID 随机读记录 → 随机 I/O。
- **索引不一定更快的情况**：
  - 结果集占比很大（如 >30% 行满足条件）→ 随机 I/O 代价超过顺序扫描。
  - 表很小（几十行）→ B+Tree 查找的 overhead 超过直接扫描。
  - 数据物理上按索引列有序存储（聚簇）→ 索引扫描的随机读可能退化为顺序读。
- 当前实现**无条件使用索引**（如果可用），生产级系统会基于选择性估算（cardinality estimation）来决定。

---

## 七、Task 4 Transaction 答辩必问（8 分，回答错则 Transaction 部分不得分）

### T1: BEGIN 时如何设计快照（snapshot）？

**标准回答**（代码见 `TransactionManager.java:81-93`）：
1. **检查活跃事务**：如果已有活跃事务（`transactionSnapshot != null`），抛出 `TransactionAlreadyActive`。
2. **刷盘当前状态**：`dbManager.persistRuntimeState()` 将 BufferPool 中所有脏页写入磁盘，确保快照捕获一致状态。
3. **创建临时目录**：`Files.createTempDirectory("cs307-txn-")` 在系统临时目录创建快照目录。
4. **全量复制数据文件**：`copyDirectoryContents(dbRoot, snapshotDir)` 递归复制整个 `CS307-DB/` 目录（包括所有表的数据文件、元数据 JSON）。
5. **保存元数据快照**：`transactionFilePages = new HashMap<>(diskManager.filePages)` 记录每个文件的当前页数，回滚时恢复。
6. **清空保存点列表**：`savepoints.clear()`。

**为什么用目录复制而非 WAL？**
- 教学项目：目录复制实现简单、直观、可验证。
- 局限性：大数据库复制开销大、不支持并发事务、不支持崩溃恢复。
- 生产级替代：Write-Ahead Logging（WAL）+ undo/redo log。

### T2: COMMIT 时在物理和逻辑层面发生了什么？

**标准回答**（代码见 `TransactionManager.java:106-115`）：
- **物理层**：
  - 数据已实时落盘（本项目 INSERT/UPDATE/DELETE 每次都直接写磁盘 + BufferPool），COMMIT **不需要再写数据**。
  - `persistRuntimeState()` 再次刷盘，确保 BufferPool 中的修改持久化。
  - 删除事务快照目录（`cleanupSnapshot`），释放磁盘空间。
- **逻辑层**：
  - `transactionSnapshot = null` — 标记事务结束，后续操作不再受事务保护。
  - `transactionFilePages = null` — 释放页数快照。
  - `cleanupSavepoints()` — 清理所有保存点快照目录和记录。
- **事务外 COMMIT**：直接 return（no-op），不报错。

### T3: SAVEPOINT 和 ROLLBACK 的设计是什么？

**标准回答**（代码见 `TransactionManager.java:140-195`）：

**SAVEPOINT 设计**：
- 每次 `savepoint(name)` 创建完整的目录快照 + filePages 快照（和 BEGIN 一样的方式）。
- 快照存入 `List<SavepointSnapshot>`（按创建顺序追加）。
- **同名 SAVEPOINT 栈语义**：同名保存点不覆盖旧值，追加到栈尾。`findLatestSavepoint()` 从栈尾向前搜索 → 最近同名保存点生效（类似后进先出）。

**ROLLBACK TO SAVEPOINT 设计**：
1. `findLatestSavepoint(name)` 找到最近匹配的保存点索引。
2. `restoreSnapshot(savepoint.snapshotPath, savepoint.filePages)`：丢弃 BufferPool → 清空数据库目录 → 从快照复制回数据 → 恢复 filePages。
3. `cleanupSavepointsAfter(index)`：清理目标保存点**之后**的所有保存点（因为它们也被回滚了）。
4. **关键：目标保存点本身不清除**，可以反复回滚到同一个点。

**ROLLBACK（全事务）设计**：
- 恢复到 BEGIN 时的事务快照状态。
- 清理所有保存点。

**RELEASE SAVEPOINT 设计**：
- 只删除快照目录和移除保存点记录，**不修改数据库数据**。
- 释放后不能再 rollback 到该保存点。

**错误处理**：
- 事务外 SAVEPOINT/ROLLBACK TO/RELEASE → 抛出 `TransactionRequired`
- ROLLBACK TO 不存在的保存点 → 抛出 `SavepointDoesNotExist`
