package edu.sustech.cs307.storage.replacer;

import java.util.*;

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
}
