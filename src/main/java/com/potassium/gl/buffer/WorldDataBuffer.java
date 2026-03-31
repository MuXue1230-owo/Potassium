package com.potassium.gl.buffer;

import com.potassium.world.data.BlockData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class WorldDataBuffer implements AutoCloseable {
	private final PersistentBuffer storage;
	private final ByteBuffer scratchIntBuffer;

	private long lastUploadBytes;
	private int minSectionY;
	private int sectionsCount;
	private long bytesPerChunk;

	public WorldDataBuffer(long initialCapacityBytes, boolean persistentMappingEnabled) {
		this.storage = new PersistentBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, initialCapacityBytes, persistentMappingEnabled);
		this.scratchIntBuffer = MemoryUtil.memAlloc(Integer.BYTES).order(ByteOrder.nativeOrder());
	}

	public void beginFrame() {
		this.storage.beginFrame();
	}

	public void endFrame() {
		this.storage.endFrame();
	}

	public void upload(ByteBuffer data) {
		ByteBuffer source = data.duplicate();
		this.lastUploadBytes = source.remaining();
		if (this.lastUploadBytes == 0L) {
			return;
		}

		this.storage.upload(source, 0L);
	}

	public void bind(int binding) {
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public void configure(int minSectionY, int sectionsCount) {
		if (sectionsCount <= 0) {
			throw new IllegalArgumentException("sectionsCount must be positive.");
		}

		this.minSectionY = minSectionY;
		this.sectionsCount = sectionsCount;
		this.bytesPerChunk = (long) sectionsCount * LevelChunkSection.SECTION_SIZE * BlockData.BYTES;
	}

	public boolean isConfigured() {
		return this.bytesPerChunk > 0L;
	}

	public int minSectionY() {
		return this.minSectionY;
	}

	public int sectionsCount() {
		return this.sectionsCount;
	}

	public long bytesPerChunk() {
		return this.bytesPerChunk;
	}

	public int maxResidentChunkCapacity() {
		if (this.bytesPerChunk == 0L) {
			return 0;
		}

		return (int) Math.min(Integer.MAX_VALUE, this.storage.capacityBytes() / this.bytesPerChunk);
	}

	public long chunkOffsetBytes(int residentSlot) {
		if (residentSlot < 0) {
			throw new IllegalArgumentException("residentSlot must be non-negative.");
		}

		return this.bytesPerChunk * residentSlot;
	}

	public void uploadChunk(int residentSlot, ByteBuffer data) {
		if (!this.isConfigured()) {
			throw new IllegalStateException("World data buffer layout has not been configured.");
		}

		ByteBuffer source = data.duplicate();
		long expectedBytes = this.bytesPerChunk;
		if (source.remaining() != expectedBytes) {
			throw new IllegalArgumentException(
				"Chunk upload size mismatch. expected=" + expectedBytes + " actual=" + source.remaining()
			);
		}

		this.lastUploadBytes = source.remaining();
		this.storage.upload(source, this.chunkOffsetBytes(residentSlot));
	}

	public boolean applyBlockChange(int residentSlot, BlockPos position, BlockState newState, int flags) {
		if (!this.isConfigured()) {
			return false;
		}

		int sectionIndex = SectionPos.blockToSectionCoord(position.getY()) - this.minSectionY;
		if (sectionIndex < 0 || sectionIndex >= this.sectionsCount) {
			return false;
		}

		int localX = SectionPos.sectionRelative(position.getX());
		int localY = SectionPos.sectionRelative(position.getY());
		int localZ = SectionPos.sectionRelative(position.getZ());
		int blockIndex = (sectionIndex * LevelChunkSection.SECTION_SIZE) + (localY * 256) + (localZ * 16) + localX;
		long byteOffset = this.chunkOffsetBytes(residentSlot) + ((long) blockIndex * BlockData.BYTES);

		this.scratchIntBuffer.clear();
		this.scratchIntBuffer.putInt(BlockData.fromState(newState, flags).packed());
		this.scratchIntBuffer.flip();
		this.lastUploadBytes = BlockData.BYTES;
		this.storage.upload(this.scratchIntBuffer, byteOffset);
		return true;
	}

	public long capacityBytes() {
		return this.storage.capacityBytes();
	}

	public long lastUploadBytes() {
		return this.lastUploadBytes;
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.scratchIntBuffer);
		this.lastUploadBytes = 0L;
		this.minSectionY = 0;
		this.sectionsCount = 0;
		this.bytesPerChunk = 0L;
	}
}
