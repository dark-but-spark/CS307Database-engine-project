package edu.sustech.cs307.storage.replacer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
}
