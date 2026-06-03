package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;

import java.util.ArrayList;

/**
 * 物理算子接口 — 火山模型（Iterator Model）契约。
 *
 * 火山模型执行协议（答辩必答）:
 *
 *
 * 所有物理算子遵循统一的迭代器接口：
 * 
 *   <li>Begin() — 初始化算子状态，打开子算子和文件句柄
 *   <li>hasNext() — 检查是否有下一条记录（不消费，只探测）
 *   <li>Next() — 推进到下一条记录（消费当前，准备下一条）
 *   <li>Current() — 返回当前记录（不推进，可重复调用）
 *   <li>Close() — 清理资源，关闭子算子和文件句柄
 * 
 *
 * 典型调用模式:
 *
 *
 * <pre>
 * operator.Begin();
 * while (operator.hasNext()) {
 *     operator.Next();
 *     Tuple t = operator.Current();
 *     // 处理 t ...
 * }
 * operator.Close();
 * </pre>
 *
 * 火山模型的优点（答辩追问）:
 *
 *
 * 
 *   <li>算子可任意嵌套（Filter→Project→Join→SeqScan），接口统一
 *   <li>数据按需拉取（pull-based），只需处理当前行，内存友好
 *   <li>易于实现 pipelining（流水线），无需物化中间结果
 * 
 *
 * outputSchema():
 *
 *
 * 返回算子的输出列元数据（ColumnMeta 列表），用于上层算子解析列名和类型。
 * 例如：SeqScan 返回表的所有列，Project 返回 SELECT 指定的列子集。
 */
public interface PhysicalOperator {
    boolean hasNext() throws DBException;

    void Begin() throws DBException;

    void Next() throws DBException;

    Tuple Current();

    void Close();

    ArrayList<ColumnMeta> outputSchema();
}
