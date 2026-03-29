package com.potassium.client.render.indirect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class IndexedCommandScratchBuffer {
	private static final int COUNT_OFFSET = 0;
	private static final int INSTANCE_COUNT_OFFSET = Integer.BYTES;
	private static final int FIRST_INDEX_OFFSET = Integer.BYTES * 2;
	private static final int BASE_VERTEX_OFFSET = Integer.BYTES * 3;
	private static final int BASE_INSTANCE_OFFSET = Integer.BYTES * 4;

	private ByteBuffer commands;
	private int capacityCommands;
	private int commandCount;

	public IndexedCommandScratchBuffer(int initialCapacityCommands) {
		this.capacityCommands = Math.max(initialCapacityCommands, 1);
		this.commands = allocate(this.capacityCommands);
	}

	public int addDrawElementsCommand(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
		validateCommand(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
		this.ensureCapacity(this.commandCount + 1);

		int commandIndex = this.commandCount++;
		int commandOffset = commandIndex * IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES;

		this.commands.putInt(commandOffset + COUNT_OFFSET, indexCount);
		this.commands.putInt(commandOffset + INSTANCE_COUNT_OFFSET, instanceCount);
		this.commands.putInt(commandOffset + FIRST_INDEX_OFFSET, firstIndex);
		this.commands.putInt(commandOffset + BASE_VERTEX_OFFSET, baseVertex);
		this.commands.putInt(commandOffset + BASE_INSTANCE_OFFSET, baseInstance);
		return commandIndex;
	}

	public int commandCount() {
		return this.commandCount;
	}

	public void reset(int expectedCommandCapacity) {
		if (expectedCommandCapacity < 0) {
			throw new IllegalArgumentException("expectedCommandCapacity must be non-negative.");
		}

		this.commandCount = 0;
		this.ensureCapacity(Math.max(expectedCommandCapacity, 1));
	}

	public int usedBytes() {
		return this.commandCount * IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES;
	}

	public ByteBuffer view() {
		ByteBuffer view = this.commands.duplicate().order(ByteOrder.nativeOrder());
		view.clear();
		view.limit(this.usedBytes());
		return view;
	}

	private void ensureCapacity(int requiredCommands) {
		if (requiredCommands <= this.capacityCommands) {
			return;
		}

		int newCapacity = this.capacityCommands;
		while (newCapacity < requiredCommands) {
			newCapacity <<= 1;
		}

		ByteBuffer replacement = allocate(newCapacity);
		ByteBuffer source = this.commands.duplicate().order(ByteOrder.nativeOrder());
		source.clear();
		source.limit(this.usedBytes());
		replacement.put(source);
		replacement.clear();

		this.commands = replacement;
		this.capacityCommands = newCapacity;
	}

	private static ByteBuffer allocate(int capacityCommands) {
		return ByteBuffer.allocate(Math.multiplyExact(capacityCommands, IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES))
			.order(ByteOrder.nativeOrder());
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
}
