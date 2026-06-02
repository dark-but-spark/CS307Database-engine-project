# CS307 Database Engine 答辩流程与回答提纲

## 速查索引（关键实现代码行号）

| 模块 | 关键文件 | 行号 |
|------|---------|------|
| B+Tree split (InternalNode) | `BPlusTree.java` | :246 |
| B+Tree split (LeafNode) | `BPlusTree.java` | :397 |
| B+Tree 范围查询链表遍历 | `BPlusTree.java` | :361-383 |
| B+Tree 删除下溢处理 | `BPlusTree.java` | :185-203 |
| BPlusTreeIndex TreeSet 懒迭代 | `BPlusTreeIndex.java` | :204-235 |
| 优化器索引选择 | `PhysicalPlanner.java` | :114-152 |
| IndexScanOperator 建索引迭代器 | `IndexScanOperator.java` | :129-147 |
| IndexScanOperator RID读记录 | `IndexScanOperator.java` | :164-179 |
| BEGIN 快照设计 | `TransactionManager.java` | :81-93 |
| COMMIT 物理/逻辑操作 | `TransactionManager.java` | :106-115 |
| SAVEPOINT 创建 | `TransactionManager.java` | :150-156 |
| ROLLBACK TO SAVEPOINT | `TransactionManager.java` | :169-178 |
| restoreSnapshot 恢复逻辑 | `TransactionManager.java` | :242-252 |
| 火山模型 Begin/Next/Close | `SeqScanOperator.java`, `FilterOperator.java` 等 | — |
| LRU Victim/Pin/Unpin | `LRUReplacer.java` | — |

---

## 1. 开场说明

建议先用 1 分钟说明项目目标：

本项目实现了一个教学型关系数据库内核，核心链路是 SQL 输入、JSqlParser 解析、逻辑计划、物理计划、火山模型执行、记录存储和 BufferPool 管理。当前实现覆盖项目基础要求，并实现了部分高级功能，包括 JOIN、聚合、排序、内存 B+Tree 索引、ALTER TABLE 和基于快照的事务。

## 2. 演示流程

推荐按 `demo.SQL` 顺序演示：

1. `CREATE TABLE students ...` 和 30 条 `INSERT`
   说明元数据、记录序列化、实时写盘。

2. `SHOW TABLES`、`DESCRIBE students`
   说明 DDL 和元数据管理。

3. 基础查询：
   `SELECT *`、投影、`WHERE` 的 `AND/OR`、范围比较。
   说明 `LogicalPlanner -> PhysicalPlanner -> SeqScan/Filter/Project`。

4. 聚合与排序：
   `COUNT`、`COUNT(DISTINCT)`、`MAX/MIN`、`GROUP BY`、`ORDER BY`。
   说明这些算子都遵循 `Begin/hasNext/Next/Current/Close` 火山模型接口。

5. JOIN 与子查询：
   `JOIN ON`、`IN`、`EXISTS`。
   说明 JOIN 目前采用 nested-loop，右表物化到内存，ON 条件由上层 `FilterOperator` 判断。

6. 索引：
   `CREATE INDEX idx_students_id ON students (id)`，再跑等值、范围、反向比较、`BETWEEN`。
   说明 `PhysicalPlanner` 从单表 `WHERE` 中抽取可索引谓词，生成 `IndexScanOperator`，但仍保留 `FilterOperator` 做完整条件校验。

7. 修改与删除：
   `UPDATE`、`DELETE` 后再查询。
   说明通过 RID 定位记录，并同步维护索引。

8. 事务：
   `BEGIN`、`SAVEPOINT`、`ROLLBACK TO SAVEPOINT`、`COMMIT`。
   说明当前使用目录快照，不是 WAL，适合教学和单用户场景。

9. ALTER TABLE：
   `ADD COLUMN`、`DROP COLUMN`。
   说明非空表会重写记录以匹配新 schema。

## 3. 模块回答要点

### Storage Management

回答重点：

- `DiskManager` 负责页级文件读写。
- `BufferPool` 维护 pageId 到 frame 的缓存映射。
- `pin` 表示页面正在被使用，不能淘汰。
- `unpin` 表示页面可被替换器选择。
- dirty page 在 flush 时写回磁盘。
- LRU 维护可淘汰 frame 的访问顺序。
- Clock 使用 reference bit 给页面第二次机会。

可以这样答：

`BufferPool` 是存储层的中间层。上层请求页面时先查缓存，未命中再从磁盘读入 frame。如果 frame 不够，就调用 replacer 找 victim。被 pin 的 frame 不能淘汰，dirty victim 淘汰前必须先 flush。LRU 用链表维护最近使用顺序，Clock 用时钟指针和 referenced 标记近似 LRU。

### Query Processing

回答重点：

- `LogicalPlanner` 描述“要做什么”。
- `PhysicalPlanner` 决定“怎么执行”。
- 所有物理算子实现 `PhysicalOperator`，采用火山模型。
- `SeqScanOperator` 扫描 record page bitmap，找到有效 slot。
- `FilterOperator` 调用 `Tuple.eval_expr()` 统一判断 WHERE。
- `ProjectOperator` 根据输出 schema 取列。

可以这样答：

SQL 先被 JSqlParser 解析成 AST。`LogicalPlanner` 把 AST 转成逻辑算子树，例如 `Project(Filter(TableScan))`。`PhysicalPlanner` 再把逻辑节点转换成物理算子。执行时从根算子开始调用 `Begin()`，再循环 `hasNext()` 和 `Next()`，每个算子按需向子算子拉取数据。

### Join

回答重点：

- 当前实现是 nested-loop join。
- 右输入在 `Begin()` 中物化。
- 左输入逐行扫描，和右表每行组合成 `JoinTuple`。
- `ON` 条件在外层 `FilterOperator` 判断。

可以这样答：

我们把 JOIN 拆成两个步骤：先由 `NestedLoopJoinOperator` 枚举候选连接结果，再由 `FilterOperator` 判断 ON 条件。这样实现简单，能复用已有表达式求值逻辑。代价是大表 JOIN 会占较多内存和时间，后续可以改成 block nested-loop 或 hash join。

### Index (Task 3 — 10 points)

回答重点：

- `CREATE INDEX` 扫描已有记录构建内存 B+Tree。
- `INSERT/UPDATE/DELETE` 会维护索引。
- 支持等值、范围、反向比较、`BETWEEN`。
- 索引只缩小候选 RID，完整 WHERE 仍由过滤算子判断。
- 当前索引不持久化，重启后根据元数据重新构建。

可以这样答：

索引结构保存 `Value -> RID` 的映射。查询时 `PhysicalPlanner` 检查 WHERE 是否是可索引的单表谓词。如果命中索引列，就生成 `IndexScanOperator`，通过索引返回候选 RID，再按 RID 读取记录。为了保证语义正确，外层仍然执行完整 WHERE 过滤。

#### Task 3 Extended Q&A

**Q: 分裂时 InternalNode 和 LeafNode 的分裂点为什么不同？**

A: InternalNode 分裂后中间键**上移**到父节点（子节点不再保留），分裂点偏右（`keyNumber()/2+1`）。LeafNode 分裂后中间键**复制**到父节点（叶子保留），分裂点偏左（`(keyNumber()+1)/2`）。这是 B+Tree 标准语义——内部节点 key 是路由，叶子节点 key 是数据。

**Q: 为什么索引层额外维护 TreeSet？**

A: `BPlusTree.searchRange()` 返回物化的 `List<V>`，不适合火山模型的按需拉取。TreeSet 红黑树的 `headSet`/`tailSet`/`subSet` 是视图（不拷贝数据），`flatten()` 实现了懒迭代器——每次 `hasNext()` 才查 B+Tree 取下一个 key 的 RID bucket。当结果集很大时，避免一次性把所有 RID 装入内存。

**Q: 索引扫描后为什么还要 FilterOperator？**

A: 因为索引只吸收一部分条件。例如 `WHERE id >= 10 AND name = 'alice'` 可能只用 id 索引（第一个命中索引的列），name 条件仍需判断。保留 FilterOperator 保证语义正确，避免 residual predicate 漏判。

**Q: B+Tree 删除时下溢了如何处理？**

A: 三步走：先 merge 兄弟节点（合并 keys+values+children）→ 如果 merge 后溢出则 split 再分裂 → 从父节点 deleteChild 移除不再需要的索引项。逐层回溯到根。如果根 keys 变为 0，降树高。

### Transaction (Task 4 — 8 points, 必问 Q&A)

回答重点：

- `BEGIN` 创建数据库目录快照和 `filePages` 快照。
- `COMMIT` 刷盘并清理快照。
- `ROLLBACK` 丢弃 BufferPool 页面，恢复目录和页数元数据。
- `SAVEPOINT` 也是一个快照，按栈语义保存。
- 不是 WAL，不支持生产级崩溃恢复和并发控制。

可以这样答：

事务实现采用快照方案。开始事务前先把当前状态刷盘，然后复制数据库目录。回滚时清空当前目录并把快照复制回来，同时恢复 DiskManager 的 `filePages`。这种方式实现直接，适合课程项目展示事务语义；如果要扩展到真实数据库，应改成 undo/redo log 和 WAL。

#### Task 4 必问三个问题（回答错则 Task 4 全部 8 分不得分）

**Q1: Explain how to design snapshot in begin command.**

**标准回答**：
1. `persistRuntimeState()` 将 BufferPool 所有脏页写回磁盘，确保当前数据一致性。
2. `Files.createTempDirectory("cs307-txn-")` 在系统临时目录创建快照根目录。
3. `copyDirectoryContents(dbRoot, snapshotDir)` 递归复制整个 `CS307-DB/` 目录，包括所有表的数据文件、元数据 JSON、`disk_manager_meta.json`。
4. `transactionFilePages = new HashMap<>(diskManager.filePages)` 保存每个数据文件的当前页数快照（`{tablename: pageCount}`）。
5. `savepoints.clear()` 重置保存点列表。
- 为什么不是 WAL：课程项目范围，目录快照实现简单；缺点是复制大数据库慢，且不支持并发。
- 代码位置：`TransactionManager.java:81-93`

**Q2: Explain if executing commit command, what happens in physical and logical structure.**

**标准回答**：
- **物理层**：
  - 数据已实时落盘（本项目每次 INSERT/UPDATE/DELETE 都直接写磁盘），COMMIT 无需再写数据。
  - `persistRuntimeState()` 再次刷盘，保证 BufferPool 中缓存修改也持久化。
  - `cleanupSnapshot(transactionSnapshot)` 删除事务快照临时目录，释放磁盘空间。
  - `cleanupSavepoints()` 逐个删除所有保存点快照目录。
- **逻辑层**：
  - `transactionSnapshot = null` — 事务标记结束。
  - `transactionFilePages = null` — 释放页数快照引用。
  - 后续 SQL 操作不再受事务保护（不能 ROLLBACK）。
- 事务外执行 COMMIT 是 no-op（直接 return），不会报错。
- 代码位置：`TransactionManager.java:106-115`

**Q3: Explain your design of savepoint and rollback.**

**标准回答**：
- **SAVEPOINT**：`savepoint(String name)` 每次调用创建一个完整目录快照 + filePages 快照，追加到 `List<SavepointSnapshot>`。同名 SAVEPOINT 采用**栈语义**：不覆盖旧的，追加到列表末尾。`findLatestSavepoint()` 从栈尾向前搜索，因此最近同名保存点生效。
- **ROLLBACK TO SAVEPOINT**：`rollbackToSavepoint(name)` → 找到最近匹配保存点 → `restoreSnapshot()` 恢复目录和 filePages → `cleanupSavepointsAfter(index)` 清除该保存点**之后**的所有保存点。**目标保存点保留**（可多次回滚到同一保存点）。
- **ROLLBACK（全事务）**：恢复到 BEGIN 时的快照，清理所有保存点。
- **RELEASE SAVEPOINT**：只删除快照目录和记录，不影响数据。释放后不能回滚到该保存点。
- **恢复机制**：`restoreSnapshot()` = `DiscardAllPages()` 丢 BufferPool → `deleteDirectoryContents()` 清空数据目录 → `copyDirectoryContents()` 从快照复制回 → 恢复 `filePages` 元数据。
- 代码位置：`TransactionManager.java:140-195`（savepoint/rollbackTo/release），`TransactionManager.java:242-252`（restoreSnapshot）

## 4. 常见追问

Q: 为什么索引扫描后还要 Filter？

A: 因为索引只吸收一部分条件，例如 `id >= 10 AND name = 'alice'` 可能只用 `id` 索引，`name` 条件仍需判断。保留 `FilterOperator` 可以保证结果正确。

Q: 为什么事务不用日志？

A: 课程要求重点是事务语义和 rollback/savepoint 行为。目录快照实现简单、可测试，但空间和时间开销大，不适合大数据库和并发场景。工程化方向是 WAL + undo/redo log。

Q: SeqScan 如何知道哪些 slot 有记录？

A: 每个 record page 有 bitmap。`SeqScanOperator.hasNext()` 扫描 bitmap，只有 bit 被设置的 slot 才表示有效记录。

Q: BufferPool 淘汰 dirty page 怎么办？

A: dirty page 在被替换或显式 flush 时写回 `DiskManager`，确保磁盘文件和内存修改同步。

Q: 当前有哪些限制？

A: 索引不做 OR、多索引交集和成本估计；JOIN 不做 join order 优化；事务不是 WAL；DDL 部分命令通过 Logger 输出，不是统一 result set。

## 5. 验证方式

答辩前建议运行：

```powershell
mvn test
mvn -Dtest=DemoSqlIntegrationTest test
```

`DemoSqlIntegrationTest` 会读取真实 `demo.SQL` 并逐条执行，确保演示脚本和代码能力一致。
