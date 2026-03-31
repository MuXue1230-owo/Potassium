package com.potassium.gl.buffer;

import com.potassium.core.PotassiumLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.system.MemoryUtil;

public final class IndirectCommandBuffer implements AutoCloseable {
	public static final int COMMAND_STRIDE_BYTES = Integer.BYTES * 4;

	private static final int COUNT_OFFSET = 0;
	private static final int INSTANCE_COUNT_OFFSET = Integer.BYTES;
	private static final int FIRST_VERTEX_OFFSET = Integer.BYTES * 2;
	private static final int BASE_INSTANCE_OFFSET = Integer.BYTES * 3;

	private final PersistentBuffer gpuBuffer;
	private ByteBuffer commandStream;
	private int capacityCommands;
	private int commandCount;
	private boolean dirty;

	public IndirectCommandBuffer(int initialCapacityCommands, boolean persistentMappingEnabled) {
		this(initialCapacityCommands, persistentMappingEnabled, 3);
	}

	public IndirectCommandBuffer(int initialCapacityCommands, boolean persistentMappingEnabled, int persistentSegmentCount) {
		this.capacityCommands = Math.max(initialCapacityCommands, 1);
		this.commandStream = allocateCommandStream(this.capacityCommands);
		this.gpuBuffer = new PersistentBuffer(
			GL40C.GL_DRAW_INDIRECT_BUFFER,
			toByteSize(this.capacityCommands),
			persistentMappingEnabled,
			persistentSegmentCount
		);
	}

	public void beginFrame() {
		this.gpuBuffer.beginFrame();
		this.commandCount = 0;
		this.dirty = true;
	}

	public void endFrame() {
		this.gpuBuffer.endFrame();
	}

	public int addDrawArraysCommand(int vertexCount, int instanceCount, int firstVertex, int baseInstance) {
		validateCommand(vertexCount, instanceCount, firstVertex, baseInstance);
		this.ensureCommandCapacity(this.commandCount + 1);

		int commandIndex = this.commandCount++;
		int commandOffset = commandIndex * COMMAND_STRIDE_BYTES;

		this.commandStream.putInt(commandOffset + COUNT_OFFSET, vertexCount);
		this.commandStream.putInt(commandOffset + INSTANCE_COUNT_OFFSET, instanceCount);
		this.commandStream.putInt(commandOffset + FIRST_VERTEX_OFFSET, firstVertex);
		this.commandStream.putInt(commandOffset + BASE_INSTANCE_OFFSET, baseInstance);
		this.dirty = true;

		return commandIndex;
	}

	public void upload() {
		if (!this.dirty) {
			return;
		}

		int usedBytes = this.usedBytes();
		if (usedBytes == 0) {
			this.dirty = false;
			return;
		}

		ByteBuffer uploadView = this.commandStream.duplicate().order(ByteOrder.nativeOrder());
		uploadView.clear();
		uploadView.limit(usedBytes);
		this.gpuBuffer.upload(uploadView, 0L);
		this.dirty = false;
	}

	public void bindForDraw() {
		this.gpuBuffer.bind();
	}

	public long drawOffsetBytes(int firstCommandIndex) {
		if (firstCommandIndex < 0 || firstCommandIndex > this.commandCount) {
			throw new IllegalArgumentException("firstCommandIndex is out of range.");
		}

		return this.gpuBuffer.activeBaseOffsetBytes() + ((long) firstCommandIndex * COMMAND_STRIDE_BYTES);
	}

	public int commandCount() {
		return this.commandCount;
	}

	public int capacityCommands() {
		return this.capacityCommands;
	}

	public boolean usesPersistentMapping() {
		return this.gpuBuffer.isPersistentMappingEnabled();
	}

	public int segmentCount() {
		return this.gpuBuffer.segmentCount();
	}

	public int usedBytes() {
		return this.commandCount * COMMAND_STRIDE_BYTES;
	}

	@Override
	public void close() {
		this.gpuBuffer.close();
		if (this.commandStream != null) {
			MemoryUtil.memFree(this.commandStream);
			this.commandStream = null;
		}

		this.capacityCommands = 0;
		this.commandCount = 0;
		this.dirty = false;
	}

	private void ensureCommandCapacity(int requiredCommands) {
		if (requiredCommands <= this.capacityCommands) {
			return;
		}

		int previousCapacity = this.capacityCommands;
		int newCapacity = previousCapacity;
		while (newCapacity < requiredCommands) {
			newCapacity <<= 1;
		}

		ByteBuffer newCommandStream = allocateCommandStream(newCapacity);
		ByteBuffer sourceView = this.commandStream.duplicate().order(ByteOrder.nativeOrder());
		sourceView.clear();
		sourceView.limit(this.usedBytes());
		newCommandStream.put(sourceView);
		newCommandStream.clear();

		MemoryUtil.memFree(this.commandStream);
		this.commandStream = newCommandStream;
		this.capacityCommands = newCapacity;
		this.gpuBuffer.ensureCapacity(toByteSize(newCapacity));
		this.dirty = true;

		PotassiumLogger.logger().info(
			"Indirect command buffer resized from {} to {} commands ({} bytes).",
			previousCapacity,
			newCapacity,
			toByteSize(newCapacity)
		);
	}

	private static ByteBuffer allocateCommandStream(int capacityCommands) {
		int byteCapacity = Math.toIntExact(toByteSize(capacityCommands));
		return MemoryUtil.memAlloc(byteCapacity).order(ByteOrder.nativeOrder());
	}

	private static void validateCommand(int vertexCount, int instanceCount, int firstVertex, int baseInstance) {
		if (vertexCount < 0) {
			throw new IllegalArgumentException("vertexCount must be non-negative.");
		}
		if (instanceCount <= 0) {
			throw new IllegalArgumentException("instanceCount must be positive.");
		}
		if (firstVertex < 0) {
			throw new IllegalArgumentException("firstVertex must be non-negative.");
		}
		if (baseInstance < 0) {
			throw new IllegalArgumentException("baseInstance must be non-negative.");
		}
	}

	private static long toByteSize(int commandCapacity) {
		return (long) commandCapacity * COMMAND_STRIDE_BYTES;
	}
}
