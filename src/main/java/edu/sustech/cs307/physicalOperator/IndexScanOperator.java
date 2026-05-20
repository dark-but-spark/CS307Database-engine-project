package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

/**
 * 基于 B+Tree 索引的物理扫描算子。
 *
 * <p>该算子利用 BPlusTreeIndex 提供的索引查找能力，根据查询条件（等值、范围）
 * 从索引中获取匹配的 RID，再通过 RecordFileHandle 按 RID 读取记录，返回 TableTuple。</p>
 *
 * <p>当前支持两种扫描模式：</p>
 * <ul>
 *   <li>{@link ScanMode#EQUAL} — 等值查找（WHERE col = value）</li>
 *   <li>{@link ScanMode#RANGE} — 范围查找（WHERE col BETWEEN low AND high 或 col > value 等）</li>
 * </ul>
 *
 * <p>REVIEW(Task 3.1 Index Support - B+Tree Index Scan): IndexScanOperator 每次 Begin()
 * 会从 FileHandle 逐条按 RID 读取记录。当结果集很大时，这会产生大量随机 I/O。
 * 可以引入批量预取（batch RID → page lookup）来减少 pin/unpin 开销。</p>
 */
public class IndexScanOperator implements PhysicalOperator {

    /**
     * 索引扫描模式。
     */
    public enum ScanMode {
        /** 等值：col = value */
        EQUAL,
        /** 双边界范围：col BETWEEN low AND high，或 col > low AND col < high */
        RANGE
    }

    private final String tableName;
    private final DBManager dbManager;
    private final String indexColumnName;
    private final ScanMode scanMode;

    // 等值参数
    private final Value equalValue;

    // 范围参数
    private final Value lowValue;
    private final Value highValue;
    private final boolean lowInclusive;
    private final boolean highInclusive;

    // 运行时状态
    private TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private boolean isOpen;

    // 索引结果迭代
    private List<Entry<Value, RID>> indexResults;
    private int resultCursor;
    private TableTuple currentTuple;

    // ==================== 构造函数 ====================

    /**
     * 等值扫描构造函数。
     *
     * @param tableName       表名
     * @param dbManager       数据库管理器
     * @param indexColumnName 被索引的列名
     * @param equalValue      等值条件值
     */
    public IndexScanOperator(String tableName, DBManager dbManager,
                             String indexColumnName, Value equalValue) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        this.indexColumnName = indexColumnName;
        this.scanMode = ScanMode.EQUAL;
        this.equalValue = equalValue;
        this.lowValue = null;
        this.highValue = null;
        this.lowInclusive = false;
        this.highInclusive = false;
    }

    /**
     * 范围扫描构造函数。
     *
     * <p>REVIEW(Task 3.1 Index Support - Index Range Scan): Range scan currently
     * materializes all matching RIDs from BPlusTreeIndex before iteration. For
     * large ranges, a streaming iterator would reduce memory pressure.</p>
     *
     * @param tableName       表名
     * @param dbManager       数据库管理器
     * @param indexColumnName 被索引的列名
     * @param lowValue        下界值（null 表示无下界）
     * @param highValue       上界值（null 表示无上界）
     * @param lowInclusive    下界是否包含
     * @param highInclusive   上界是否包含
     */
    public IndexScanOperator(String tableName, DBManager dbManager,
                             String indexColumnName,
                             Value lowValue, Value highValue,
                             boolean lowInclusive, boolean highInclusive) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        this.indexColumnName = indexColumnName;
        this.scanMode = ScanMode.RANGE;
        this.equalValue = null;
        this.lowValue = lowValue;
        this.highValue = highValue;
        this.lowInclusive = lowInclusive;
        this.highInclusive = highInclusive;
    }

    // ==================== PhysicalOperator 接口 ====================

    @Override
    public void Begin() throws DBException {
        // Task 3.1 Index Support - B+Tree Index Scan: open the record file,
        // retrieve index entries, and materialize matching RIDs.
        tableMeta = dbManager.getMetaManager().getTable(tableName);
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);

        BPlusTreeIndex index = dbManager.getIndexOnColumn(tableName, indexColumnName);
        if (index == null) {
            throw new DBException(ExceptionTypes.InvalidSQL("INDEX SCAN",
                    "No index found on column " + indexColumnName + " for table " + tableName));
        }

        indexResults = new ArrayList<>();

        switch (scanMode) {
            case EQUAL -> {
                Iterator<RID> rids = index.EqualToAll(equalValue);
                while (rids.hasNext()) {
                    RID rid = rids.next();
                    indexResults.add(new java.util.AbstractMap.SimpleEntry<>(equalValue, rid));
                }
            }
            case RANGE -> {
                Iterator<Entry<Value, RID>> rangeIter;
                if (lowValue != null && highValue != null) {
                    // 双边界范围
                    rangeIter = index.Range(lowValue, highValue, lowInclusive, highInclusive);
                } else if (lowValue != null) {
                    // 只有下界：>= low 或 > low
                    rangeIter = index.MoreThan(lowValue, lowInclusive);
                } else if (highValue != null) {
                    // 只有上界：<= high 或 < high
                    rangeIter = index.LessThan(highValue, highInclusive);
                } else {
                    // REVIEW(Task 3.1 Index Support - Index Range Scan): 无边界的范围扫描
                    // 应等价于全表扫描; 当前返回空结果以避免意外全量读取。
                    rangeIter = List.<Entry<Value, RID>>of().iterator();
                }
                while (rangeIter.hasNext()) {
                    indexResults.add(rangeIter.next());
                }
            }
        }

        resultCursor = 0;
        isOpen = true;
    }

    @Override
    public boolean hasNext() {
        if (!isOpen) {
            return false;
        }
        return resultCursor < indexResults.size();
    }

    @Override
    public void Next() {
        if (!isOpen || resultCursor >= indexResults.size()) {
            currentTuple = null;
            return;
        }
        Entry<Value, RID> entry = indexResults.get(resultCursor);
        RID rid = entry.getValue();
        try {
            Record record = fileHandle.GetRecord(rid);
            currentTuple = new TableTuple(tableName, tableMeta, record, new RID(rid));
            // 读取完成后释放页面 pin
            fileHandle.UnpinPageHandle(rid.pageNum, false);
        } catch (DBException e) {
            currentTuple = null;
        }
        resultCursor++;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        if (!isOpen) {
            return;
        }
        try {
            dbManager.getRecordManager().CloseFile(fileHandle);
        } catch (DBException e) {
            // 关闭失败时记录但不抛出，避免阻塞后续清理
        }
        fileHandle = null;
        tableMeta = null;
        indexResults = null;
        currentTuple = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta != null ? tableMeta.columns_list : new ArrayList<>();
    }

    // ==================== 辅助方法 ====================

    public String getTableName() {
        return tableName;
    }

    public String getIndexColumnName() {
        return indexColumnName;
    }

    public ScanMode getScanMode() {
        return scanMode;
    }

    /**
     * REVIEW(Task 3.1 Index Support - B+Tree Index Scan): 为 UpdateOperator/DeleteOperator
     * 提供 getFileHandle()，使其能复用索引 RID 做原地更新/删除。
     */
    public RecordFileHandle getFileHandle() {
        return fileHandle;
    }
}
