/**
 * 项目任务速查注释。
 *
 * <p>搜索建议：直接搜索 "Task 1"、"Task 2"、"Task 3.1"、"Task 4"、"Task 5"
 * 可以快速定位对应实现和答辩要点。</p>
 *
 * <p>Task 1 Storage Management：LRUReplacer、ClockReplacer、BufferPool、DiskManager
 * 是存储层核心。答辩重点是 page/frame 的映射、pin/unpin 语义、dirty page 刷盘、
 * LRU 和 Clock 如何选择 victim。</p>
 *
 * <p>Task 2 Query Processing：LogicalPlanner 负责把 SQL 转成逻辑算子树，
 * PhysicalPlanner 负责转成可执行物理算子。SeqScanOperator 通过 RecordFileHandle
 * 扫描 page bitmap；FilterOperator 统一执行 WHERE；ProjectOperator 解析 SELECT 列；
 * CountOperator、MaxMinOperator、GroupByOperator、OrderByOperator 覆盖聚合和排序。</p>
 *
 * <p>Task 3.1 Index Support：BPlusTreeIndex 是运行期内存 B+Tree 索引，ValueIndexKey
 * 包装 Value 提供排序，TreeSet 维护有序 key 集合以支持范围查询。DBManager 在
 * CREATE INDEX 时扫描已有记录构建索引，并在 INSERT、UPDATE、DELETE 时动态维护索引。
 * PhysicalPlanner 会从单表 WHERE 中抽取等值和范围谓词，生成 IndexScanOperator。
 * 索引扫描只负责缩小候选 RID，外层 FilterOperator 会再次检查完整 WHERE，保证语义正确。</p>
 *
 * <p>Task 3.1 Index 查询优化现状：支持 col = literal、col &gt; literal、
 * col &gt;= literal、col &lt; literal、col &lt;= literal、literal 与 col 的反向比较、
 * BETWEEN，以及 AND 条件下同一索引列的边界合并。不支持 OR、多索引交集、成本估计、
 * join index lookup 和持久化索引。当前范围索引迭代已改为懒迭代，避免在 Begin()
 * 一次性物化大范围 RID。</p>
 *
 * <p>Task 4 Transaction：TransactionManager 使用目录快照实现事务。BEGIN 创建数据库
 * 目录和 filePages 元数据快照；SAVEPOINT 创建额外快照并按栈语义保存；ROLLBACK 或
 * ROLLBACK TO SAVEPOINT 会丢弃 BufferPool 脏页并恢复目录与 filePages；COMMIT 清理快照。
 * 该方案适合教学项目和单用户场景，不是 WAL，不能覆盖生产级并发和崩溃恢复。</p>
 *
 * <p>Task 5 Presentation：DBEntry 负责命令行输入、分号切分、多语句执行和结果显示。
 * LogicalPlanner 对 BEGIN、COMMIT、ROLLBACK、SAVEPOINT 等事务命令使用正则识别，
 * 因为这些命令不走普通 JSqlParser DML 路径。异常处理以单条 statement 为单位，
 * 一条失败不应影响后续命令继续执行。</p>
 *
 * <p>REVIEW 注释含义：代码中的 REVIEW 不是未完成错误，而是标记当前教学实现的设计取舍
 * 和后续工程化方向，例如 DDL 目前直接 Logger 输出、事务使用目录快照、索引暂不做成本估计等。</p>
 */
package edu.sustech.cs307;
