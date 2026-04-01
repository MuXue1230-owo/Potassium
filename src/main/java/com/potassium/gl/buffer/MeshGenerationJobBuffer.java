package com.potassium.gl.buffer;

import com.potassium.core.PotassiumLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class MeshGenerationJobBuffer implements AutoCloseable {
	public static final int HEADER_BYTES = Integer.BYTES * 4;
	public static final int JOB_STRIDE_BYTES = Integer.BYTES * 4;

	private static final int HEADER_JOB_COUNT_OFFSET = 0;
	private static final int JOB_RESIDENT_SLOT_OFFSET = 0;
	private static final int JOB_CHUNK_X_OFFSET = Integer.BYTES;
	private static final int JOB_CHUNK_Z_OFFSET = Integer.BYTES * 2;
	private static final int JOB_VERSION_OFFSET = Integer.BYTES * 3;

	private final PersistentBuffer gpuBuffer;
	private ByteBuffer jobStream;
	private int capacityJobs;
	private int jobCount;
	private boolean dirty;

	public MeshGenerationJobBuffer(int initialCapacityJobs, boolean persistentMappingEnabled) {
		this.capacityJobs = Math.max(initialCapacityJobs, 1);
		this.jobStream = allocateJobStream(this.capacityJobs);
		this.gpuBuffer = new PersistentBuffer(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			toByteSize(this.capacityJobs),
			persistentMappingEnabled,
			1
		);
	}

	public void beginFrame() {
		this.gpuBuffer.beginFrame();
		this.jobCount = 0;
		this.jobStream.putInt(HEADER_JOB_COUNT_OFFSET, 0);
		this.dirty = true;
	}

	public void endFrame() {
		this.gpuBuffer.endFrame();
	}

	public int addJob(int residentSlot, ChunkPos chunkPos, long version) {
		if (residentSlot < 0) {
			throw new IllegalArgumentException("residentSlot must be non-negative.");
		}

		this.ensureCapacity(this.jobCount + 1);
		int jobIndex = this.jobCount++;
		int jobOffset = HEADER_BYTES + (jobIndex * JOB_STRIDE_BYTES);
		this.jobStream.putInt(jobOffset + JOB_RESIDENT_SLOT_OFFSET, residentSlot);
		this.jobStream.putInt(jobOffset + JOB_CHUNK_X_OFFSET, chunkPos.x());
		this.jobStream.putInt(jobOffset + JOB_CHUNK_Z_OFFSET, chunkPos.z());
		this.jobStream.putInt(jobOffset + JOB_VERSION_OFFSET, (int) Math.min(version, Integer.MAX_VALUE));
		this.dirty = true;
		return jobIndex;
	}

	public void upload() {
		if (!this.dirty) {
			return;
		}

		this.jobStream.putInt(HEADER_JOB_COUNT_OFFSET, this.jobCount);
		int usedBytes = this.usedBytes();
		ByteBuffer uploadView = this.jobStream.duplicate().order(ByteOrder.nativeOrder());
		uploadView.clear();
		uploadView.limit(usedBytes);
		this.gpuBuffer.upload(uploadView, 0L);
		this.dirty = false;
	}

	public void bind(int binding) {
		this.gpuBuffer.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public int jobCount() {
		return this.jobCount;
	}

	public int usedBytes() {
		return HEADER_BYTES + (this.jobCount * JOB_STRIDE_BYTES);
	}

	@Override
	public void close() {
		this.gpuBuffer.close();
		if (this.jobStream != null) {
			MemoryUtil.memFree(this.jobStream);
			this.jobStream = null;
		}

		this.capacityJobs = 0;
		this.jobCount = 0;
		this.dirty = false;
	}

	private void ensureCapacity(int requiredJobs) {
		if (requiredJobs <= this.capacityJobs) {
			return;
		}

		int previousCapacity = this.capacityJobs;
		int newCapacity = previousCapacity;
		while (newCapacity < requiredJobs) {
			newCapacity <<= 1;
		}

		ByteBuffer newStream = allocateJobStream(newCapacity);
		ByteBuffer previousData = this.jobStream.duplicate().order(ByteOrder.nativeOrder());
		previousData.clear();
		previousData.limit(this.usedBytes());
		newStream.put(previousData);
		newStream.clear();

		MemoryUtil.memFree(this.jobStream);
		this.jobStream = newStream;
		this.capacityJobs = newCapacity;
		this.gpuBuffer.ensureCapacity(toByteSize(newCapacity));
		this.dirty = true;

		PotassiumLogger.logger().info(
			"Mesh generation job buffer resized from {} to {} jobs ({} bytes).",
			previousCapacity,
			newCapacity,
			toByteSize(newCapacity)
		);
	}

	private static ByteBuffer allocateJobStream(int capacityJobs) {
		return MemoryUtil.memAlloc(Math.toIntExact(toByteSize(capacityJobs))).order(ByteOrder.nativeOrder());
	}

	private static long toByteSize(int capacityJobs) {
		return HEADER_BYTES + ((long) capacityJobs * JOB_STRIDE_BYTES);
	}
}
