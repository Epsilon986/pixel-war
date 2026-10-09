package org.pixelwar.pixelwar.domain;

import java.util.Arrays;
import java.util.random.RandomGenerator;

/** Primitive dense set with paged reverse slots (zero means absent). Board monitor protects it. */
final class FrontierIndex {
    private static final int PAGE_SHIFT = 12, PAGE_SIZE = 1 << PAGE_SHIFT;
    private final int[][] slots;
    private final int capacity;
    private int[] ids = new int[16];
    private int size;

    FrontierIndex(int capacity) {
        this.capacity = capacity;
        slots = new int[(int) (((long) capacity + PAGE_SIZE - 1) / PAGE_SIZE)][];
    }

    int size() { return size; }
    boolean contains(int id) {
        var page = slots[id >>> PAGE_SHIFT];
        return page != null && page[id & (PAGE_SIZE - 1)] != 0;
    }
    void update(int id, boolean present) {
        if (present == contains(id)) return;
        int pageId = id >>> PAGE_SHIFT, offset = id & (PAGE_SIZE - 1);
        if (present) {
            if (size == ids.length) ids = Arrays.copyOf(ids, (int) Math.min(capacity, (long) ids.length * 2));
            if (slots[pageId] == null) slots[pageId] = new int[Math.min(PAGE_SIZE, capacity - pageId * PAGE_SIZE)];
            ids[size] = id;
            slots[pageId][offset] = ++size;
        } else {
            int slot = slots[pageId][offset] - 1;
            int last = ids[--size];
            ids[slot] = last;
            slots[last >>> PAGE_SHIFT][last & (PAGE_SIZE - 1)] = slot + 1;
            slots[pageId][offset] = 0;
        }
    }
    int randomId(RandomGenerator random) { return ids[random.nextInt(size)]; }
    void reset() {
        Arrays.fill(slots, null);
        ids = new int[16];
        size = 0;
    }
    long storageBytes() {
        long bytes = (long) ids.length * Integer.BYTES + (long) slots.length * Long.BYTES;
        for (var page : slots) if (page != null) bytes += (long) page.length * Integer.BYTES;
        return bytes;
    }
}
