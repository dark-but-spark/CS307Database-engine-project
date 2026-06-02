package edu.sustech.cs307.storage;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 内存页面 — 缓冲池中缓存的基本单位。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code DEFAULT_PAGE_SIZE = 4096}（4KB）：与操作系统页大小对齐，磁盘 I/O 基本单位</li>
 *   <li>{@code PAGE_HEADER_SIZE = 8}：页头保留字节（用于 record page 的 bitmap 等元数据）</li>
 *   <li>{@code data}（ByteBuf）：Netty 字节缓冲区，存放实际页面内容</li>
 *   <li>{@code position}（PagePosition）：页在磁盘上的位置（filename + offset）</li>
 *   <li>{@code dirty}：脏标记。true 表示内存中的页已被修改，需要写回磁盘</li>
 *   <li>{@code pin_count}：引用计数。>0 表示正在被使用（不可淘汰），0 表示可淘汰</li>
 * </ul>
 *
 * <h3>dirty 标记的语义</h3>
 * dirty = true → 页面内容与磁盘不一致 → FlushPage 时必须写回。
 * unpin_page() 用 |= 设置（不清除已有 dirty），避免丢失之前的修改标记。
 * FlushPage 写回后 dirty = false。
 *
 * <h3>getPageID()</h3>
 * 页号 = position.offset / DEFAULT_PAGE_SIZE。用于 DiskManager 中定位页的序号。
 */
public class Page {
    public final static int DEFAULT_PAGE_SIZE = 4 * 1024;

    public final static int PAGE_HEADER_SIZE = 8;

    public ByteBuf data;
    public PagePosition position = new PagePosition("null", 0);
    public boolean dirty;
    public int pin_count = 0;

    public int getPageID() {
        return position.offset / DEFAULT_PAGE_SIZE;
    }

    public Page() {
        data = Unpooled.buffer(DEFAULT_PAGE_SIZE);
    }
}
