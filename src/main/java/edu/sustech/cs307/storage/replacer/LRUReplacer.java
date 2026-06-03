package edu.sustech.cs307.storage.replacer;

import java.util.*;

/**
 * LRU（Least Recently Used）页面替换器 — Task 1.1（10 分）。
 *
 * 数据结构设计（答辩可答）:
 *
 *
 * 
 *   <li>pinnedFrames（HashSet）：正在使用的 frame，不可淘汰。O(1) 查询/插入/删除。
 *   <li>LRUList（LinkedList）：可淘汰 frame 的 LRU 顺序。
 *       链表头 = 最久未使用（Victim 候选），链表尾 = 最近使用（刚 Unpin）
 *   <li>LRUHash（HashSet）：LRUList 中 frame 的快速成员检查。O(1) 确认 frame 是否在链表中。
 * 
 *
 * 核心操作:
 *
 *
 * 
 *   <li><b>Victim()</b>：移除并返回 LRUList 头部 frame（最久未使用）。
 *       同时从 LRUHash 中删除。O(1)。
 *   <li><b>Pin(frameId)</b>：标记 frame 为不可淘汰。
 *       
 *         <li>已在 pinnedFrames → 直接返回（重复 pin 不报错）
 *         <li>在 LRUHash 中（可淘汰）→ 从 LRUList 中移除（用 Integer.valueOf 按对象删除，非按索引），
 *             加入 pinnedFrames
 *         <li>新 frame → 检查容量（pinned + unpinned >= maxSize），加入 pinnedFrames
 *       
 *   
 *   <li><b>Unpin(frameId)</b>：标记 frame 为可淘汰，移到 LRUList 尾部（最近使用）。
 *       从 pinnedFrames 移除 → 加入 LRUHash + LRUList.addLast()。O(1)。
 * 
 *
 * 为什么用 Integer.valueOf？（答辩细节题）:
 *
 *
 * LRUList.remove(Integer.valueOf(frameId)) 按对象删除，而非按索引删除。
 * LRUList.remove(int) 会按索引删除，语义错误。
 *
 * 容量管理:
 *
 *
 * size() = LRUList.size() + pinnedFrames.size()。
 * 当 size >= maxSize 时不能再 Pin 新 frame。
 */
public class LRUReplacer implements PageReplacer {

    private final int maxSize;
    private final Set<Integer> pinnedFrames = new HashSet<>();
    private final Set<Integer> LRUHash = new HashSet<>();
    private final LinkedList<Integer> LRUList = new LinkedList<>();

    public LRUReplacer(int numPages) {
        this.maxSize = numPages;
    }

    // REVIEW(Task 1.1 Storage Management - LRU Page Replacement): Tracks both
    // pinned and evictable resident frames; Victim removes only evictable frames.
    public int Victim() {
        if (LRUList.isEmpty()) {
            return -1;
        }
        int victim = LRUList.removeFirst();
        LRUHash.remove(victim);
        return victim;
    }

    // REVIEW(Task 1.1 Storage Management - LRU Page Replacement): Pin marks a
    // frame non-evictable; re-pinning an evictable frame removes it from LRU order.
    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            return;
        }
        if (LRUHash.remove(frameId)) {
            LRUList.remove(Integer.valueOf(frameId));
            pinnedFrames.add(frameId);
            return;
        }
        if (size() >= maxSize) {
            throw new RuntimeException("REPLACER IS FULL");
        }
        pinnedFrames.add(frameId);
    }


    // REVIEW(Task 1.1 Storage Management - LRU Page Replacement): Unpin moves a
    // pinned frame to the most-recent evictable position.
    public void Unpin(int frameId) {
        if (!pinnedFrames.remove(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        if (!LRUHash.contains(frameId)) {
            LRUHash.add(frameId);
            LRUList.addLast(frameId);
        }
    }


    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }

    public void Reset() {
        pinnedFrames.clear();
        LRUHash.clear();
        LRUList.clear();
    }
}
