package com.potassium.client.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL45C;

public final class PersistentBuffer implements AutoCloseable {
	private static final int PERSISTENT_SEGMENT_COUNT = 3;
	private static final long FENCE_POLL_TIMEOUT_NANOS = 1_000_000L;

	private final int target;
	private final boolean persistentMappingEnabled;
	private final int segmentCount;
	private final long[] segmentFences;

	private int handle;
	private long capacityBytes;
	private ByteBuffer mappedView;
	private int activeSegmentIndex;
	private long activeSegmentOffsetBytes;
	private boolean frameOpen;
	private boolean frameWritten;

	public PersistentBuffer(int target, long initialCapacityBytes, boolean persistentMappingEnabled) {
		this.target = target;
		this.persistentMappingEnabled = persistentMappingEnabled;
		this.segmentCount = persistentMappingEnabled ? PERSISTENT_SEGMENT_COUNT : 1;
		this.segmentFences = new long[this.segmentCount];
		this.allocateStorage(initialCapacityBytes);
	}

	public void ensureCapacity(long requiredCapacityBytes) {
		if (requiredCapacityBytes <= this.capacityBytes) {
			return;
		}

		this.allocateStorage(nextCapacity(requiredCapacityBytes));
	}

	public void beginFrame() {
		if (!this.persistentMappingEnabled) {
			return;
		}

		if (this.frameOpen) {
			return;
		}

		this.activeSegmentIndex = (this.activeSegmentIndex + 1) % this.segmentCount;
		this.waitForSegment(this.activeSegmentIndex);
		this.activeSegmentOffsetBytes = this.capacityBytes * this.activeSegmentIndex;
		this.frameOpen = true;
		this.frameWritten = false;
	}

	public void endFrame() {
		if (!this.persistentMappingEnabled || !this.frameOpen) {
			return;
		}

		if (this.frameWritten) {
			long previousFence = this.segmentFences[this.activeSegmentIndex];
			if (previousFence != 0L) {
				GL32C.glDeleteSync(previousFence);
			}

			this.segmentFences[this.activeSegmentIndex] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		}

		this.frameOpen = false;
		this.frameWritten = false;
	}

	public void upload(ByteBuffer data, long offsetBytes) {
		ByteBuffer source = data.duplicate();
		long requiredCapacity = offsetBytes + source.remaining();
		this.ensureCapacity(requiredCapacity);

		if (this.persistentMappingEnabled) {
			if (!this.frameOpen) {
				this.beginFrame();
			}

			ByteBuffer targetView = this.mappedView.duplicate().order(ByteOrder.nativeOrder());
			long targetOffset = this.activeSegmentOffsetBytes + offsetBytes;
			targetView.position(Math.toIntExact(targetOffset));
			targetView.limit(Math.toIntExact(targetOffset + source.remaining()));
			targetView.put(source);
			this.frameWritten = true;
			return;
		}

		GL45C.glNamedBufferSubData(this.handle, offsetBytes, source);
	}

	public void bind() {
		GL15C.glBindBuffer(this.target, this.handle);
	}

	public int handle() {
		return this.handle;
	}

	public long capacityBytes() {
		return this.capacityBytes;
	}

	public boolean isPersistentMappingEnabled() {
		return this.persistentMappingEnabled;
	}

	public long activeBaseOffsetBytes() {
		return this.persistentMappingEnabled ? this.activeSegmentOffsetBytes : 0L;
	}

	@Override
	public void close() {
		if (this.handle == 0) {
			return;
		}

		for (int i = 0; i < this.segmentFences.length; i++) {
			if (this.segmentFences[i] != 0L) {
				GL32C.glDeleteSync(this.segmentFences[i]);
				this.segmentFences[i] = 0L;
			}
		}

		if (this.mappedView != null) {
			GL45C.glUnmapNamedBuffer(this.handle);
			this.mappedView = null;
		}

		GL15C.glDeleteBuffers(this.handle);
		this.handle = 0;
		this.capacityBytes = 0L;
		this.activeSegmentIndex = 0;
		this.activeSegmentOffsetBytes = 0L;
		this.frameOpen = false;
		this.frameWritten = false;
	}

	private void allocateStorage(long requestedCapacityBytes) {
		if (requestedCapacityBytes <= 0L) {
			throw new IllegalArgumentException("Buffer capacity must be positive.");
		}

		int newHandle = GL45C.glCreateBuffers();
		int storageFlags = GL45C.GL_DYNAMIC_STORAGE_BIT;

		if (this.persistentMappingEnabled) {
			storageFlags |= GL45C.GL_MAP_WRITE_BIT | GL45C.GL_MAP_PERSISTENT_BIT | GL45C.GL_MAP_COHERENT_BIT;
		}

		GL45C.glNamedBufferStorage(newHandle, requestedCapacityBytes * this.segmentCount, storageFlags);

		ByteBuffer newMappedView = null;
		if (this.persistentMappingEnabled) {
			newMappedView = GL45C.glMapNamedBufferRange(
				newHandle,
				0L,
				requestedCapacityBytes * this.segmentCount,
				GL45C.GL_MAP_WRITE_BIT | GL45C.GL_MAP_PERSISTENT_BIT | GL45C.GL_MAP_COHERENT_BIT
			).order(ByteOrder.nativeOrder());
		}

		this.close();

		this.handle = newHandle;
		this.capacityBytes = requestedCapacityBytes;
		this.mappedView = newMappedView;
	}

	private void waitForSegment(int segmentIndex) {
		long fence = this.segmentFences[segmentIndex];
		if (fence == 0L) {
			return;
		}

		while (true) {
			int waitResult = GL32C.glClientWaitSync(fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_POLL_TIMEOUT_NANOS);
			if (waitResult == GL32C.GL_ALREADY_SIGNALED || waitResult == GL32C.GL_CONDITION_SATISFIED) {
				GL32C.glDeleteSync(fence);
				this.segmentFences[segmentIndex] = 0L;
				return;
			}

			if (waitResult == GL32C.GL_WAIT_FAILED) {
				throw new IllegalStateException("Failed waiting for a persistent buffer segment fence.");
			}
		}
	}

	private static long nextCapacity(long requiredCapacityBytes) {
		long capacity = 1L;
		while (capacity < requiredCapacityBytes) {
			capacity <<= 1;
		}

		return capacity;
	}
}
