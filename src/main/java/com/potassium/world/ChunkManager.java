package com.potassium.world;

import com.potassium.world.data.ChunkData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collection;
import net.minecraft.world.level.ChunkPos;

public final class ChunkManager {
	private final Long2ObjectOpenHashMap<ChunkData> chunks = new Long2ObjectOpenHashMap<>();
	
	// 热区块保护：最近 N 个 tick 内访问过的区块不可驱逐（防止刚加载的区块被立即踢出）
	private static final int HOT_CHUNK_PROTECTION_TICKS = 10;

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

	/**
	 * 查找驱逐候选区块 - 使用距离加权 LRU 策略
	 * 
	 * 评分算法：evictionScore = lastTouchedTick * distanceWeight
	 * - 距离 < 32 格：权重 4x（优先保留）
	 * - 距离 32-64 格：权重 2x
	 * - 距离 64-96 格：权重 1x
	 * - 距离 > 96 格：权重 0.5x（优先驱逐）
	 * 
	 * 热区块保护：最近 HOT_CHUNK_PROTECTION_TICKS 个 tick 内访问的区块不可驱逐
	 * 
	 * @param playerChunkX 玩家当前所在区块 X 坐标
	 * @param playerChunkZ 玩家当前所在区块 Z 坐标
	 * @param currentTick 当前游戏 tick
	 * @return 驱逐评分最低的区块（最应该被驱逐的），如果没有则返回 null
	 */
	public ChunkData findEvictionCandidate(int playerChunkX, int playerChunkZ, long currentTick) {
		ChunkData candidate = null;
		long lowestScore = Long.MAX_VALUE;
		
		for (ChunkData chunkData : this.chunks.values()) {
			if (!chunkData.isResident()) {
				continue;
			}
			
			// 热区块保护：最近 N 个 tick 内访问的区块不可驱逐
			long ticksSinceAccess = currentTick - chunkData.lastTouchedTick();
			if (ticksSinceAccess <= HOT_CHUNK_PROTECTION_TICKS) {
				continue;
			}
			
			// 计算距离权重
			int distanceX = chunkData.chunkPos().x() - playerChunkX;
			int distanceZ = chunkData.chunkPos().z() - playerChunkZ;
			// 使用曼哈顿距离近似（避免开方运算）
			int manhattanDistance = Math.abs(distanceX) + Math.abs(distanceZ);
			
			// 计算距离权重（使用整数运算避免浮点）
			// 权重映射：<32→4x, 32-64→2x, 64-96→1x, >96→0.5x
			long weightedScore;
			if (manhattanDistance < 32) {
				// 距离很近，权重 4x，大幅增加 lastTouchedTick 的影响
				weightedScore = chunkData.lastTouchedTick() * 4;
			} else if (manhattanDistance < 64) {
				// 中等距离，权重 2x
				weightedScore = chunkData.lastTouchedTick() * 2;
			} else if (manhattanDistance < 96) {
				// 较远距离，权重 1x
				weightedScore = chunkData.lastTouchedTick();
			} else {
				// 很远，权重 0.5x（优先驱逐）
				// 使用除法：lastTouchedTick / 2
				weightedScore = chunkData.lastTouchedTick() >> 1;
			}
			
			// 选择评分最低的区块（最应该被驱逐）
			if (weightedScore < lowestScore) {
				lowestScore = weightedScore;
				candidate = chunkData;
			}
		}
		
		return candidate;
	}
	
	/**
	 * 查找驱逐候选区块的简化版本（不传玩家位置时使用默认策略）
	 * 兼容旧代码，但建议使用带玩家位置的重载版本
	 */
	public ChunkData findEvictionCandidate() {
		// 使用默认玩家位置 (0, 0)，退化为简单 LRU
		return this.findEvictionCandidate(0, 0, System.currentTimeMillis());
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
