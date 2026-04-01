package com.potassium.world.data;

import net.minecraft.world.level.ChunkPos;

public final class ChunkData {
	private final ChunkPos chunkPos;
	private boolean dirty = true;
	private boolean meshDirty = true;
	private int residentSlot = -1;
	private long residentOffsetBytes = -1L;
	private long lastTouchedTick;
	private long version;

	public ChunkData(ChunkPos chunkPos) {
		this.chunkPos = chunkPos;
	}

	public ChunkPos chunkPos() {
		return this.chunkPos;
	}

	public long key() {
		return this.chunkPos.pack();
	}

	public boolean isDirty() {
		return this.dirty;
	}

	public void markDirty(long tick) {
		this.dirty = true;
		this.meshDirty = true;
		this.lastTouchedTick = tick;
		this.version++;
	}

	public void markClean() {
		this.dirty = false;
	}

	public boolean isMeshDirty() {
		return this.meshDirty;
	}

	public void markMeshDirty() {
		this.meshDirty = true;
	}

	public void markMeshClean() {
		this.meshDirty = false;
	}

	public boolean isResident() {
		return this.residentSlot >= 0;
	}

	public int residentSlot() {
		return this.residentSlot;
	}

	public long residentOffsetBytes() {
		return this.residentOffsetBytes;
	}

	public long lastTouchedTick() {
		return this.lastTouchedTick;
	}

	public long version() {
		return this.version;
	}

	public void touch(long tick) {
		this.lastTouchedTick = tick;
	}

	public void markResident(int residentSlot, long residentOffsetBytes, long tick) {
		this.residentSlot = residentSlot;
		this.residentOffsetBytes = residentOffsetBytes;
		this.lastTouchedTick = tick;
		this.dirty = false;
		this.meshDirty = true;
	}

	public void clearResident() {
		this.residentSlot = -1;
		this.residentOffsetBytes = -1L;
		this.meshDirty = false;
	}
}
