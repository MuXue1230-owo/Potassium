package com.potassium.world;

import com.potassium.world.data.ChunkData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collection;
import net.minecraft.world.level.ChunkPos;

public final class ChunkManager {
	private final Long2ObjectOpenHashMap<ChunkData> chunks = new Long2ObjectOpenHashMap<>();

	public ChunkData touchChunk(ChunkPos chunkPos) {
		return this.chunks.computeIfAbsent(chunkPos.pack(), ignored -> new ChunkData(chunkPos));
	}

	public ChunkData touchChunk(ChunkPos chunkPos, long tick) {
		ChunkData chunkData = this.touchChunk(chunkPos);
		chunkData.touch(tick);
		return chunkData;
	}

	public ChunkData getChunk(ChunkPos chunkPos) {
		return this.chunks.get(chunkPos.pack());
	}

	public ChunkData getChunk(long chunkKey) {
		return this.chunks.get(chunkKey);
	}

	public ChunkData removeChunk(ChunkPos chunkPos) {
		return this.chunks.remove(chunkPos.pack());
	}

	public Collection<ChunkData> chunks() {
		return this.chunks.values();
	}

	public int size() {
		return this.chunks.size();
	}

	public void clear() {
		this.chunks.clear();
	}
}
