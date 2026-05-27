package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.util.List;

final class RecordSerializer {
    private RecordSerializer() {
    }

    static void writeValue(ByteBuf buffer, Value value, ColumnMeta columnMeta) {
        if (value.type == ValueType.CHAR) {
            byte[] bytes = ((String) value.value).getBytes();
            ByteBuffer fixedWidth = ByteBuffer.allocate(columnMeta.len);
            fixedWidth.put(bytes, 0, Math.min(bytes.length, columnMeta.len));
            buffer.writeBytes(fixedWidth.array());
            return;
        }
        buffer.writeBytes(value.ToByte());
    }

    static void writeRow(ByteBuf buffer, List<Value> values, List<ColumnMeta> columns) {
        for (int i = 0; i < values.size(); i++) {
            writeValue(buffer, values.get(i), columns.get(i));
        }
    }
}
