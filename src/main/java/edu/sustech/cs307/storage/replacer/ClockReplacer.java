package edu.sustech.cs307.storage.replacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clock（时钟）页面替换器 — Task 1.2（10 分）。
 *
 * 算法原理（答辩可答）:
 *
 *
 * Clock 替换算法是 LRU 的近似实现，又称为"第二次机会"（Second-Chance）算法。
 * 使用循环缓冲区 + 时钟指针，每个 frame 维护两个标志位：
 * 
 *   <li>evictable：frame 是否可淘汰（Pin 设为 false，Unpin 设为 true）
 *   <li>referenced：最近是否被访问（Pin 设为 true，Victim 扫描时清除）
 * 
 *
 * 核心操作:
 *
 *
 * 
 *   <li><b>Victim()</b>：从 clockHand 开始顺时针扫描，找第一个可淘汰 frame。
 *       
 *         <li>遇到非 evictable frame（被 Pin 的）→ 跳过，clockHand++
 *         <li>遇到 referenced=true 的 evictable frame → 给予"第二次机会"：
 *             referenced 设为 false，clockHand++（下次扫描到它时可淘汰）
 *         <li>遇到 referenced=false 的 evictable frame → 选中为 victim，从 frames 中移除
 *       
 *       <b>为什么 maxScans = frames.size() * 2？</b>
 *       最多两圈：第一圈把所有 evictable 的 referenced 清零，第二圈就能找到 victim。
 *       如果两圈后仍找不到 → 返回 -1（所有 frame 都被 pin 住了）。
 *   <li><b>Pin(frameId)</b>：已存在的 frame → 设为不可淘汰 + 刷新 referenced=true；
 *       新 frame → 检查容量后创建 FrameState 并加入列表（保持插入顺序）
 *   <li><b>Unpin(frameId)</b>：将 pinned frame 标记为可淘汰（evictable=true）。
 *       frame 不存在或已可淘汰 → 抛异常。
 * 
 *
 * Clock vs LRU（答辩对比题）:
 *
 *
 * 
 *   <li>Clock：O(1) 近似 LRU，不需要维护链表顺序，适合操作系统页面替换
 *   <li>LRU：精确但需要维护访问顺序（链表），每次访问都要移动节点
 *   <li>Clock 的"第二次机会"机制：频繁访问的页面 referenced 被反复置 true，不易被淘汰
 * 
 *
 * 插入顺序:
 *
 *
 * frames 按 Pin 顺序追加（非 LRU 顺序），clockHand 按物理位置扫描。
 * 这意味着访问频率比访问顺序对 Clock 的影响更大。
 */
public class ClockReplacer implements PageReplacer{
    private static class FrameState {
        final int frameId;
        boolean evictable;
        boolean referenced;

        FrameState(int frameId) {
            this.frameId = frameId;
            this.evictable = false;
            this.referenced = true;
        }
    }

    private final int maxSize;
    private final List<FrameState> frames;
    private final Map<Integer, FrameState> frameMap;
    private int clockHand;

    // REVIEW(Task 1.2 Storage Management - Clock Replacer): State is held in
    // insertion order; the clock hand indexes the next frame to inspect.
    public ClockReplacer(int numPages) {
        this.maxSize = numPages;
        this.frames = new ArrayList<>();
        this.frameMap = new HashMap<>();
        this.clockHand = 0;
    }

    @Override
    // REVIEW(Task 1.2 Storage Management - Clock Replacer): Victim gives
    // referenced evictable frames one second chance and skips pinned frames.
    public int Victim() {
        if (frames.isEmpty()) {
            return -1;
        }
        int scanned = 0;
        int maxScans = frames.size() * 2;
        while (scanned < maxScans && !frames.isEmpty()) {
            if (clockHand >= frames.size()) {
                clockHand = 0;
            }
            FrameState frame = frames.get(clockHand);
            if (!frame.evictable) {
                clockHand++;
                scanned++;
                continue;
            }
            if (frame.referenced) {
                frame.referenced = false;
                clockHand++;
                scanned++;
                continue;
            }
            int victim = frame.frameId;
            frames.remove(clockHand);
            frameMap.remove(victim);
            if (clockHand >= frames.size()) {
                clockHand = 0;
            }
            return victim;
        }
        return -1;
    }

    @Override
    // REVIEW(Task 1.2 Storage Management - Clock Replacer): Pin creates or marks
    // a frame non-evictable and refreshes its reference bit.
    public void Pin(int frameId) {
        FrameState frame = frameMap.get(frameId);
        if (frame != null) {
            frame.evictable = false;
            frame.referenced = true;
            return;
        }
        if (frames.size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        frame = new FrameState(frameId);
        frames.add(frame);
        frameMap.put(frameId, frame);
    }

    @Override
    // REVIEW(Task 1.2 Storage Management - Clock Replacer): Unpin makes a
    // pinned frame evictable; unknown or already-evictable frames are invalid.
    public void Unpin(int frameId) {
        FrameState frame = frameMap.get(frameId);
        if (frame == null || frame.evictable) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        frame.evictable = true;
    }

    @Override
    public int size() {
        return frameMap.size();
    }

    @Override
    public void Reset() {
        frames.clear();
        frameMap.clear();
        clockHand = 0;
    }
}
