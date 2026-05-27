package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.meta.ColumnMeta;

import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordPageHandle;
import edu.sustech.cs307.record.BitMap;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;

import java.util.ArrayList;

/**
 * 全表扫描算子 — Q&A 必问：要求能详细解释实现和执行逻辑。
 *
 * 磁盘数据文件结构：
 * ┌─────────────────────────────────────┐
 * │ Page 0: RecordFileHeader            │ ← 文件头：记录大小、每页记录数、总页数
 * ├─────────────────────────────────────┤
 * │ Page 1: RecordPageHeader + Data     │ ← 数据页：bitmap + 记录槽位数组
 * │ Page 2: ...                         │
 * │ Page N: ...                         │
 * └─────────────────────────────────────┘
 *
 * 执行流程：
 * 1. Begin() 打开 RecordFileHandle，计算数据页起始位置 (page 0 是文件头，数据从 page 0 开始)
 * 2. hasNext() 在当前页用 bitmap 找到下一个有效记录的槽位
 * 3. Next() 读取该槽位的 Record，推进槽位指针
 * 4. Current() 将 Record + RID 包装为 TableTuple 返回
 */
public class SeqScanOperator implements PhysicalOperator {
    private String tableName;
    private DBManager dbManager;
    private TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private Record currentRecord;
    private RID currentRid;

    // 扫描游标状态
    private int currentPageNum;   // 当前页号
    private int currentSlotNum;   // 当前页内槽位号
    private int totalPages;       // 总数据页数
    private int recordsPerPage;   // 每页最多记录数
    private boolean isOpen = false;

    public SeqScanOperator(String tableName, DBManager dbManager) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        try {
            this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            e.printStackTrace();
        }
    }

    /**
     * 判断是否还有下一条记录。
     * 核心逻辑：从当前位置开始，用 bitmap 扫描找到下一个被标记为"已占用"的槽位。
     * BitMap.isSet(bitmap, slotNum) 检查该槽位是否存有有效记录。
     */
    @Override
    public boolean hasNext() {
        if (!isOpen)
            return false;
        try {
            if (currentPageNum <= totalPages) {
                while (currentPageNum <= totalPages) {
                    RecordPageHandle pageHandle = fileHandle.FetchPageHandle(currentPageNum);
                    // 在当前页中扫描槽位
                    while (currentSlotNum < recordsPerPage) {
                        if (BitMap.isSet(pageHandle.bitmap, currentSlotNum)) {
                            return true; // 找到下一个有效记录
                        }
                        currentSlotNum++;
                    }
                    // 当前页扫描完毕，翻到下一页
                    currentPageNum++;
                    currentSlotNum = 0;
                }
            }
        } catch (DBException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 初始化扫描状态。
     * 打开 RecordFileHandle，计算数据页范围。
     * totalPages 从文件头中读取，减去 2 是因为：
     * - Page 0 是 RecordFileHeader（文件元数据页）
     * - 最后一页可能是未满页
     */
    @Override
    public void Begin() throws DBException {
        try {
            fileHandle = dbManager.getRecordManager().OpenFile(tableName);
            totalPages = fileHandle.getFileHeader().getNumberOfPages() - 2;
            recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();
            currentPageNum = 0;  // 从第一页数据页开始
            currentSlotNum = 0;
            isOpen = true;
        } catch (DBException e) {
            e.printStackTrace();
            isOpen = false;
        }
    }

    /**
     * 推进到下一条记录。
     * hasNext() 已经定位了下一个有效槽位，这里直接:
     * 1. 用 currentPageNum + currentSlotNum 构造 RID
     * 2. 调用 fileHandle.GetRecord(rid) 读取该位置的 Record
     * 3. 推进槽位（可能跨页）
     */
    @Override
    public void Next() {
        if (!isOpen)
            return;
        try {
            if (hasNext()) {
                RID rid = new RID(currentPageNum, currentSlotNum);
                currentRecord = fileHandle.GetRecord(rid);
                currentRid = new RID(rid);
                currentSlotNum++;
                if (currentSlotNum >= recordsPerPage) {
                    currentPageNum++;     // 翻页
                    currentSlotNum = 0;   // 槽位归零
                }
                fileHandle.UnpinPageHandle(rid.pageNum, false);
            } else {
                currentRecord = null;
                currentRid = null;
            }
        } catch (DBException e) {
            e.printStackTrace();
            currentRecord = null;
            currentRid = null;
        }
    }

    /**
     * 返回当前记录，包装为 TableTuple。
     * TableTuple 包含：
     * - 记录的值数组 Value[]（按列 offset 从 Record.data 反序列化）
     * - RID（页号 + 槽位号，用于 DELETE/UPDATE 定位记录）
     * - TableMeta（列名、类型等元数据）
     */
    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null || currentRid == null) {
            return null;
        }
        return new TableTuple(tableName, tableMeta, currentRecord, new RID(currentRid));
    }

    @Override
    public void Close() {
        if (!isOpen)
            return;
        try {
            dbManager.getRecordManager().CloseFile(fileHandle);
        } catch (DBException e) {
            e.printStackTrace();
        }
        fileHandle = null;
        currentRecord = null;
        currentRid = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }

    public RecordFileHandle getFileHandle() {
        return fileHandle;
    }
}
