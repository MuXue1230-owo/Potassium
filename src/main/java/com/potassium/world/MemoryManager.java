package com.potassium.world;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayDeque;

public final class MemoryManager {
	private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
	private final IntOpenHashSet usedSlots = new IntOpenHashSet();

	private long budgetBytes;
	private long bytesPerChunk;
	private int capacityChunks;

	public void configure(long budgetBytes, long bytesPerChunk) {
		this.reset();
		this.budgetBytes = Math.max(budgetBytes, 0L);
		this.bytesPerChunk = Math.max(bytesPerChunk, 0L);
		this.capacityChunks = this.bytesPerChunk > 0L ? (int) Math.min(Integer.MAX_VALUE, this.budgetBytes / this.bytesPerChunk) : 0;

		for (int slot = 0; slot < this.capacityChunks; slot++) {
			this.freeSlots.addLast(slot);
		}
	}

	public int tryAcquireSlot() {
		Integer slot = this.freeSlots.pollFirst();
		if (slot == null) {
			return -1;
		}

		this.usedSlots.add(slot);
		return slot;
	}

	public void releaseSlot(int slot) {
		if (slot < 0 || !this.usedSlots.remove(slot)) {
			return;
		}

		this.freeSlots.addLast(slot);
	}

	public void reset() {
		this.freeSlots.clear();
		this.usedSlots.clear();
		this.budgetBytes = 0L;
		this.bytesPerChunk = 0L;
		this.capacityChunks = 0;
	}

	public long budgetBytes() {
		return this.budgetBytes;
	}

	public long bytesPerChunk() {
		return this.bytesPerChunk;
	}

	public int capacityChunks() {
		return this.capacityChunks;
	}

	public int residentChunks() {
		return this.usedSlots.size();
	}

	public long usedBytes() {
		return this.bytesPerChunk * this.usedSlots.size();
	}
}
