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

	public boolean expandBudget(long newBudgetBytes) {
		if (this.bytesPerChunk <= 0L || newBudgetBytes <= this.budgetBytes) {
			return false;
		}

		int newCapacityChunks = (int) Math.min(Integer.MAX_VALUE, newBudgetBytes / this.bytesPerChunk);
		if (newCapacityChunks <= this.capacityChunks) {
			// 预算没有实际增加容量
			this.budgetBytes = Math.max(this.budgetBytes, newBudgetBytes);
			return false;
		}

		// 只添加新的slot，不修改已有的
		int addedSlots = 0;
		for (int slot = this.capacityChunks; slot < newCapacityChunks; slot++) {
			if (!this.usedSlots.contains(slot)) {
				this.freeSlots.addLast(slot);
				addedSlots++;
			}
		}

		this.budgetBytes = newBudgetBytes;
		this.capacityChunks = newCapacityChunks;
		
		if (addedSlots > 0) {
			com.potassium.core.PotassiumLogger.logger().info(
				"MemoryManager expanded budget: {} -> {} chunks ({} new slots, budget={} MiB)",
				this.capacityChunks - addedSlots,
				this.capacityChunks,
				addedSlots,
				this.budgetBytes / (1024L * 1024L)
			);
		}
		
		return true;
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

	public int freeSlotsCount() {
		return this.freeSlots.size();
	}

	public long usedBytes() {
		return this.bytesPerChunk * this.usedSlots.size();
	}
}
