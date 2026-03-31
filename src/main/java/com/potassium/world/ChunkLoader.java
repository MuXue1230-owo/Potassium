package com.potassium.world;

import com.potassium.core.PotassiumLogger;
import com.potassium.render.RenderPipeline;
import com.potassium.world.data.ChunkSerializer;
import com.potassium.world.data.ChunkSnapshot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ChunkLoader {
	private static final int MAX_CHUNK_UPLOADS_PER_TICK = 8;
	private static final int MAX_CHUNK_UNLOADS_PER_TICK = 32;

	private final ChunkManager chunkManager;
	private final ArrayDeque<Long> loadQueue = new ArrayDeque<>();
	private final ArrayDeque<Long> unloadQueue = new ArrayDeque<>();
	private final LongOpenHashSet queuedLoads = new LongOpenHashSet();
	private final LongOpenHashSet queuedUnloads = new LongOpenHashSet();

	private int lastLoadedCount;
	private int lastUnloadedCount;

	public ChunkLoader(ChunkManager chunkManager) {
		this.chunkManager = chunkManager;
	}

	public void requestLoad(ChunkPos chunkPos) {
		long chunkKey = chunkPos.pack();
		this.queuedUnloads.remove(chunkKey);
		if (this.queuedLoads.add(chunkKey)) {
			this.loadQueue.addLast(chunkKey);
		}
	}

	public void requestUnload(ChunkPos chunkPos) {
		long chunkKey = chunkPos.pack();
		this.queuedLoads.remove(chunkKey);
		if (this.queuedUnloads.add(chunkKey)) {
			this.unloadQueue.addLast(chunkKey);
		}
	}

	public void requestRefresh(ChunkPos chunkPos) {
		this.requestLoad(chunkPos);
	}

	public void bootstrapLoadedChunks(ClientLevel level, BlockPos center, int radiusChunks) {
		if (level == null || center == null || radiusChunks < 0) {
			return;
		}

		ChunkPos centerChunk = ChunkPos.containing(center);
		for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				int chunkX = centerChunk.x() + dx;
				int chunkZ = centerChunk.z() + dz;
				LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (chunk != null) {
					this.requestLoad(chunk.getPos());
				}
			}
		}
	}

	public void clear() {
		this.loadQueue.clear();
		this.unloadQueue.clear();
		this.queuedLoads.clear();
		this.queuedUnloads.clear();
		this.lastLoadedCount = 0;
		this.lastUnloadedCount = 0;
	}

	public int loadQueueSize() {
		return this.loadQueue.size();
	}

	public int unloadQueueSize() {
		return this.unloadQueue.size();
	}

	public int lastLoadedCount() {
		return this.lastLoadedCount;
	}

	public int lastUnloadedCount() {
		return this.lastUnloadedCount;
	}

	public void drainQueues(ClientLevel level, RenderPipeline renderPipeline, long tickIndex) {
		this.lastLoadedCount = 0;
		this.lastUnloadedCount = 0;

		processUnloadQueue(renderPipeline);
		if (level != null) {
			processLoadQueue(level, renderPipeline, tickIndex);
		}
	}

	private void processUnloadQueue(RenderPipeline renderPipeline) {
		int processed = 0;
		while (processed < MAX_CHUNK_UNLOADS_PER_TICK && !this.unloadQueue.isEmpty()) {
			long chunkKey = this.unloadQueue.removeFirst();
			this.queuedUnloads.remove(chunkKey);

			ChunkPos chunkPos = ChunkPos.unpack(chunkKey);
			var chunkData = this.chunkManager.removeChunk(chunkPos);
			if (chunkData != null) {
				renderPipeline.unloadChunk(chunkData);
				processed++;
			}
		}

		this.lastUnloadedCount = processed;
	}

	private void processLoadQueue(ClientLevel level, RenderPipeline renderPipeline, long tickIndex) {
		int processed = 0;
		while (processed < MAX_CHUNK_UPLOADS_PER_TICK && !this.loadQueue.isEmpty()) {
			long chunkKey = this.loadQueue.removeFirst();
			this.queuedLoads.remove(chunkKey);

			int chunkX = ChunkPos.getX(chunkKey);
			int chunkZ = ChunkPos.getZ(chunkKey);
			LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (chunk == null) {
				continue;
			}

			var chunkData = this.chunkManager.touchChunk(chunk.getPos(), tickIndex);
			try (ChunkSnapshot snapshot = ChunkSerializer.serialize(chunk)) {
				if (renderPipeline.uploadChunk(chunkData, snapshot, tickIndex)) {
					processed++;
				}
			} catch (RuntimeException exception) {
				PotassiumLogger.logger().warn("Failed to upload chunk {} into the resident world buffer.", chunk.getPos(), exception);
			}
		}

		this.lastLoadedCount = processed;
	}
}
