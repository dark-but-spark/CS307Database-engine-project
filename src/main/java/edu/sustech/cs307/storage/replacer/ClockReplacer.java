package edu.sustech.cs307.storage.replacer;

import java.util.List;

public class ClockReplacer implements PageReplacer{
    private List<Integer> frames;

    // TODO: Initialize all clock replacement state: frame entries, reference
    // bits, pinned/evictable flags, capacity, and the clock hand.
    public ClockReplacer(int numPages) {
    }

    @Override
    // TODO: Implement second-chance victim selection. Skip pinned frames,
    // clear reference bits on first pass, remove the chosen victim completely,
    // and return -1 when no frame is evictable.
    public int Victim() {
        return 0;
    }

    @Override
    // TODO: Mark a frame pinned/non-evictable and refresh its reference state
    // according to ClockReplacerTest expectations.
    public void Pin(int frameId) {

    }

    @Override
    // TODO: Mark a pinned frame evictable. Unknown frames and invalid repeated
    // unpin operations should match the exception behavior in ClockReplacerTest.
    public void Unpin(int frameId) {

    }

    @Override
    public int size() {
        return frames.size();
    }
}
