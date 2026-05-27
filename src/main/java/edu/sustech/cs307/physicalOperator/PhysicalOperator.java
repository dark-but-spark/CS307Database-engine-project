package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;

import java.util.ArrayList;

/**
 * 物理算子接口 — 火山模型（Volcano / Iterator Model）。
 *
 * 所有物理算子遵循统一的迭代器协议：
 *
 *   op.Begin();
 *   while (op.hasNext()) {
 *       op.Next();
 *       Tuple row = op.Current();
 *       // 处理当前行
 *   }
 *   op.Close();
 *
 * 火山模型的核心思想：每个算子按需"拉取"下一行数据。
 * 控制流从顶层算子向下传递，数据流从底层算子向上返回。
 * 这避免了把所有中间结果加载到内存中。
 *
 * outputSchema() 返回输出列结构，供上层算子（Project/Filter）了解数据格式。
 */
public interface PhysicalOperator {
    /** 是否还有下一条记录 */
    boolean hasNext() throws DBException;

    /** 初始化算子，打开子算子和资源 */
    void Begin() throws DBException;

    /** 推进到下一条记录 */
    void Next() throws DBException;

    /** 返回当前记录 */
    Tuple Current();

    /** 关闭算子，释放资源 */
    void Close();

    /** 返回该算子输出的列元数据 */
    ArrayList<ColumnMeta> outputSchema();
}
