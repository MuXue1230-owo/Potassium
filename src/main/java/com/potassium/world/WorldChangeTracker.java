package com.potassium.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldChangeTracker {
	private final Long2ObjectOpenHashMap<BlockChange> pendingChanges = new Long2ObjectOpenHashMap<>();
	private long tickIndex;
	private int lastDrainSize;

	public void record(BlockPos position, BlockState oldState, BlockState newState, int flags) {
		this.pendingChanges.put(
			position.asLong(),
			new BlockChange(position.immutable(), oldState, newState, flags, this.tickIndex)
		);
	}

	public List<BlockChange> drainChanges() {
		List<BlockChange> drained = new ArrayList<>(this.pendingChanges.values());
		this.pendingChanges.clear();
		this.lastDrainSize = drained.size();
		return drained;
	}

	public int pendingChangeCount() {
		return this.pendingChanges.size();
	}

	public int lastDrainSize() {
		return this.lastDrainSize;
	}

	public long tickIndex() {
		return this.tickIndex;
	}

	public void advanceTick() {
		this.tickIndex++;
	}

	public void clear() {
		this.pendingChanges.clear();
		this.lastDrainSize = 0;
		this.tickIndex = 0L;
	}

	public record BlockChange(BlockPos position, BlockState oldState, BlockState newState, int flags, long tick) {
	}
}
