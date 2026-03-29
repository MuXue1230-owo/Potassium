package com.potassium.client.render.indirect;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.gl.PersistentBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class IndexedIndirectCommandBuffer implements AutoCloseable {
	public static final int COMMAND_STRIDE_BYTES = Integer.BYTES * 5;

	private static final int COUNT_OFFSET = 0;
	private static final int INSTANCE_COUNT_OFFSET = Integer.BYTES;
	private static final int FIRST_INDEX_OFFSET = Integer.BYTES * 2;
	private static final int BASE_VERTEX_OFFSET = Integer.BYTES * 3;
	private static final int BASE_INSTANCE_OFFSET = Integer.BYTES * 4;

	private final PersistentBuffer gpuBuffer;
	private ByteBuffer commandStream;
	private int capacityCommands;
	private int commandCount;
	private boolean dirty;
	private boolean gpuGeneratedCommandsPresent;

	public IndexedIndirectCommandBuffer(int initialCapacityCommands, boolean persistentMappingEnabled) {
		this.capacityCommands = Math.max(initialCapacityCommands, 1);
		this.commandStream = allocateCommandStream(this.capacityCommands);
		this.gpuBuffer = new PersistentBuffer(
			GL40C.GL_DRAW_INDIRECT_BUFFER,
			toByteSize(this.capacityCommands),
			persistentMappingEnabled
		);
	}

	public void beginFrame() {
		this.gpuBuffer.beginFrame();
		this.commandCount = 0;
		this.dirty = true;
		this.gpuGeneratedCommandsPresent = false;
	}

	public void endFrame() {
		this.gpuBuffer.endFrame();
	}

	public int addDrawElementsCommand(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
		validateCommand(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
		this.ensureCommandCapacity(this.commandCount + 1);

		int commandIndex = this.commandCount++;
		int commandOffset = commandIndex * COMMAND_STRIDE_BYTES;

		this.commandStream.putInt(commandOffset + COUNT_OFFSET, indexCount);
		this.commandStream.putInt(commandOffset + INSTANCE_COUNT_OFFSET, instanceCount);
		this.commandStream.putInt(commandOffset + FIRST_INDEX_OFFSET, firstIndex);
		this.commandStream.putInt(commandOffset + BASE_VERTEX_OFFSET, baseVertex);
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

	public void uploadAppendedCommands(int firstCommandIndex) {
		if (!this.dirty) {
			return;
		}

		if (firstCommandIndex < 0 || firstCommandIndex > this.commandCount) {
			throw new IllegalArgumentException("firstCommandIndex is out of range.");
		}

		if (firstCommandIndex == this.commandCount) {
			this.dirty = false;
			return;
		}

		int startByteOffset = firstCommandIndex * COMMAND_STRIDE_BYTES;
		int endByteOffset = this.usedBytes();
		ByteBuffer uploadView = this.commandStream.duplicate().order(ByteOrder.nativeOrder());
		uploadView.position(startByteOffset);
		uploadView.limit(endByteOffset);
		this.gpuBuffer.upload(uploadView.slice().order(ByteOrder.nativeOrder()), startByteOffset);
		this.dirty = false;
	}

	public int appendCommands(ByteBuffer commands, int commandCount) {
		if (commandCount < 0) {
			throw new IllegalArgumentException("commandCount must be non-negative.");
		}

		if (commandCount == 0) {
			return this.commandCount;
		}

		int byteCount = Math.multiplyExact(commandCount, COMMAND_STRIDE_BYTES);
		ByteBuffer sourceView = commands.duplicate().order(ByteOrder.nativeOrder());
		sourceView.clear();
		if (sourceView.remaining() < byteCount) {
			throw new IllegalArgumentException("Command buffer does not contain enough bytes.");
		}

		sourceView.limit(byteCount);
		this.ensureCommandCapacity(this.commandCount + commandCount);

		int firstCommandIndex = this.commandCount;
		int startByteOffset = firstCommandIndex * COMMAND_STRIDE_BYTES;
		ByteBuffer targetView = this.commandStream.duplicate().order(ByteOrder.nativeOrder());
		targetView.position(startByteOffset);
		targetView.limit(startByteOffset + byteCount);
		targetView.put(sourceView);

		this.commandCount += commandCount;
		this.dirty = true;
		return firstCommandIndex;
	}

	public void bindForDraw() {
		this.gpuBuffer.bind();
	}

	public int reserveGpuCommandRange(int maxCommandCount) {
		if (maxCommandCount < 0) {
			throw new IllegalArgumentException("maxCommandCount must be non-negative.");
		}

		this.ensureCommandCapacity(this.commandCount + maxCommandCount);
		return this.commandCount;
	}

	public void bindAsStorage(int binding) {
		this.gpuBuffer.bindRange(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			binding,
			this.gpuBuffer.activeBaseOffsetBytes(),
			toByteSize(this.capacityCommands)
		);
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

	public void commitGpuGeneratedCommands(int firstCommandIndex, int actualCommandCount) {
		if (firstCommandIndex < 0 || firstCommandIndex > this.capacityCommands) {
			throw new IllegalArgumentException("firstCommandIndex is out of range.");
		}
		if (actualCommandCount < 0 || firstCommandIndex + actualCommandCount > this.capacityCommands) {
			throw new IllegalArgumentException("actualCommandCount is out of range.");
		}

		this.commandCount = firstCommandIndex + actualCommandCount;
		this.dirty = false;
		this.gpuGeneratedCommandsPresent = true;
	}

	public void rewindToCommandCount(int commandCount) {
		if (commandCount < 0 || commandCount > this.commandCount) {
			throw new IllegalArgumentException("commandCount is out of range.");
		}

		if (commandCount == this.commandCount) {
			return;
		}

		this.commandCount = commandCount;
		this.dirty = true;
	}

	public int capacityCommands() {
		return this.capacityCommands;
	}

	public int bufferHandle() {
		return this.gpuBuffer.handle();
	}

	public ByteBuffer readCommands(int firstCommandIndex, int commandCount) {
		if (firstCommandIndex < 0 || commandCount < 0 || firstCommandIndex + commandCount > this.commandCount) {
			throw new IllegalArgumentException("Requested command range is out of bounds.");
		}

		ByteBuffer readback = MemoryUtil.memAlloc(commandCount * COMMAND_STRIDE_BYTES).order(ByteOrder.nativeOrder());
		GL45C.glGetNamedBufferSubData(this.gpuBuffer.handle(), this.drawOffsetBytes(firstCommandIndex), readback);
		return readback;
	}

	public boolean usesPersistentMapping() {
		return this.gpuBuffer.isPersistentMappingEnabled();
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

		if (this.gpuGeneratedCommandsPresent) {
			throw new IllegalStateException("Cannot resize the indirect command buffer after GPU-generated commands were written this frame.");
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

		PotassiumClientMod.LOGGER.info(
			"Indexed indirect command buffer resized from {} to {} commands ({} bytes).",
			previousCapacity,
			newCapacity,
			toByteSize(newCapacity)
		);
	}

	private static ByteBuffer allocateCommandStream(int capacityCommands) {
		int byteCapacity = Math.toIntExact(toByteSize(capacityCommands));
		return MemoryUtil.memAlloc(byteCapacity).order(ByteOrder.nativeOrder());
	}

	private static void validateCommand(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
		if (indexCount < 0) {
			throw new IllegalArgumentException("indexCount must be non-negative.");
		}
		if (instanceCount <= 0) {
			throw new IllegalArgumentException("instanceCount must be positive.");
		}
		if (firstIndex < 0) {
			throw new IllegalArgumentException("firstIndex must be non-negative.");
		}
		if (baseInstance < 0) {
			throw new IllegalArgumentException("baseInstance must be non-negative.");
		}
	}

	private static long toByteSize(int commandCapacity) {
		return (long) commandCapacity * COMMAND_STRIDE_BYTES;
	}
}
