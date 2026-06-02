package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;

import java.util.ArrayList;

/**
 * 物理算子接口 — 火山模型（Iterator Model）契约。
 *
 * <h3>火山模型执行协议（答辩必答）</h3>
 * 所有物理算子遵循统一的迭代器接口：
 * <ol>
 *   <li>{@code Begin()} — 初始化算子状态，打开子算子和文件句柄</li>
 *   <li>{@code hasNext()} — 检查是否有下一条记录（不消费，只探测）</li>
 *   <li>{@code Next()} — 推进到下一条记录（消费当前，准备下一条）</li>
 *   <li>{@code Current()} — 返回当前记录（不推进，可重复调用）</li>
 *   <li>{@code Close()} — 清理资源，关闭子算子和文件句柄</li>
 * </ol>
 *
 * <h3>典型调用模式</h3>
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
 * <h3>火山模型的优点（答辩追问）</h3>
 * <ul>
 *   <li>算子可任意嵌套（Filter→Project→Join→SeqScan），接口统一</li>
 *   <li>数据按需拉取（pull-based），只需处理当前行，内存友好</li>
 *   <li>易于实现 pipelining（流水线），无需物化中间结果</li>
 * </ul>
 *
 * <h3>outputSchema()</h3>
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
