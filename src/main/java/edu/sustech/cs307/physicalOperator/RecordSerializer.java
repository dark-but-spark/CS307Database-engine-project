package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 记录序列化工具 — 将 Java Value 对象转换为定长字节写入磁盘。
 *
 * 每列在磁盘上占固定字节数（ColumnMeta.len）：
 *   INT   → 8 bytes (long)
 *   FLOAT → 8 bytes (double)
 *   CHAR  → 64 bytes (固定宽度，字符串字节不足则补 0，超出则截断)
 *
 * writeValue(buffer, value, colMeta):
 *   根据 value.type 决定序列化方式。
 *   CHAR 特殊处理：先用 ByteBuffer.allocate(64) 分配定长缓冲，
 *   写入字符串字节（Math.min(bytes.length, 64)），剩余位置自动为 0。
 *
 * writeRow(buffer, values, columns):
 *   按列顺序依次调用 writeValue，拼出完整的一行记录字节。
 *
 * 序列化后的格式就是磁盘 Record 的格式 — 列按 offset 顺序紧密排列。
 */
final class RecordSerializer {
    private RecordSerializer() {
    }

    /** 将单个列值写入定长字节缓冲区 */
    static void writeValue(ByteBuf buffer, Value value, ColumnMeta columnMeta) {
        if (value.type == ValueType.CHAR) {
            // 字符串转固定宽度字节：不足 64 字节自动补 0
            byte[] bytes = ((String) value.value).getBytes();
            ByteBuffer fixedWidth = ByteBuffer.allocate(columnMeta.len);
            fixedWidth.put(bytes, 0, Math.min(bytes.length, columnMeta.len));
            buffer.writeBytes(fixedWidth.array());
            return;
        }
        // INT/FLOAT：直接写 8 字节
        buffer.writeBytes(value.ToByte());
    }

    /** 将一整行的列值序列化到缓冲区 */
    static void writeRow(ByteBuf buffer, List<Value> values, List<ColumnMeta> columns) {
        for (int i = 0; i < values.size(); i++) {
            writeValue(buffer, values.get(i), columns.get(i));
        }
    }
}
