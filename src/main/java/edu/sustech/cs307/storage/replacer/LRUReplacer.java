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

    // TODO(Task 1.1 Storage Management - LRU Page Replacement): Implement the full LRU replacement state machine expected by
    // LRUReplacerTest: track pinned and evictable frames, reject invalid
    // frame ids/state transitions, return -1 only when no evictable frame
    // exists, and remove the victim from all internal state.
    public int Victim() {
        return -1;
    }

    // TODO(Task 1.1 Storage Management - LRU Page Replacement): Mark the frame as pinned/non-evictable. Re-pinning an existing
    // evictable frame should remove it from the LRU queue without changing
    // the total tracked frame count.
    public void Pin(int frameId) {
    }


    // TODO(Task 1.1 Storage Management - LRU Page Replacement): Mark a pinned frame as evictable and place it at the most-recent
    // end of the LRU queue. Invalid or duplicate unpin operations should
    // follow the exception behavior asserted by LRUReplacerTest.
    public void Unpin(int frameId) {
    }


    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }
}
