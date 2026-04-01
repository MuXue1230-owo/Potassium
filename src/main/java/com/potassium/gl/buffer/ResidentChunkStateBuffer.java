package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class ResidentChunkStateBuffer implements AutoCloseable {
	public static final int INTS_PER_ENTRY = 8;
	public static final int BYTES_PER_ENTRY = Integer.BYTES * INTS_PER_ENTRY;
	public static final int ACTIVE_OFFSET = 0;
	public static final int DIRTY_OFFSET = 1;
	public static final int CHUNK_X_OFFSET = 2;
	public static final int CHUNK_Z_OFFSET = 3;
	public static final int NEG_X_SLOT_OFFSET = 4;
	public static final int POS_X_SLOT_OFFSET = 5;
	public static final int NEG_Z_SLOT_OFFSET = 6;
	public static final int POS_Z_SLOT_OFFSET = 7;

	private final PersistentBuffer storage;
	private final ByteBuffer scratchEntry;
	private final ByteBuffer scratchInt;
	private ByteBuffer zeroAll;

	private int capacityEntries;

	public ResidentChunkStateBuffer(int initialCapacityEntries, boolean persistentMappingEnabled) {
		this.capacityEntries = Math.max(initialCapacityEntries, 1);
		this.storage = new PersistentBuffer(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			toByteSize(this.capacityEntries),
			persistentMappingEnabled,
			1
		);
		this.scratchEntry = MemoryUtil.memAlloc(BYTES_PER_ENTRY).order(ByteOrder.nativeOrder());
		this.scratchInt = MemoryUtil.memAlloc(Integer.BYTES).order(ByteOrder.nativeOrder());
		this.zeroAll = allocateZeroBuffer(this.capacityEntries);
		this.clearAll();
	}

	public void beginFrame() {
		this.storage.beginFrame();
	}

	public void endFrame() {
		this.storage.endFrame();
	}

	public void ensureCapacity(int requiredEntries) {
		if (requiredEntries <= this.capacityEntries) {
			return;
		}

		int previousCapacity = this.capacityEntries;
		this.capacityEntries = requiredEntries;
		this.storage.ensureCapacity(toByteSize(this.capacityEntries));
		MemoryUtil.memFree(this.zeroAll);
		this.zeroAll = allocateZeroBuffer(this.capacityEntries);
		this.clearRange(previousCapacity, this.capacityEntries - previousCapacity);
	}

	public void bind(int binding) {
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public void writeResidentEntry(
		int entryIndex,
		int chunkX,
		int chunkZ,
		int negXSlot,
		int posXSlot,
		int negZSlot,
		int posZSlot,
		boolean dirty
	) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return;
		}

		ByteBuffer entry = this.scratchEntry.duplicate().order(ByteOrder.nativeOrder());
		entry.clear();
		entry.putInt(1);
		entry.putInt(dirty ? 1 : 0);
		entry.putInt(chunkX);
		entry.putInt(chunkZ);
		entry.putInt(negXSlot);
		entry.putInt(posXSlot);
		entry.putInt(negZSlot);
		entry.putInt(posZSlot);
		entry.flip();
		this.storage.upload(entry, (long) entryIndex * BYTES_PER_ENTRY);
	}

	public void markDirty(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return;
		}

		ByteBuffer value = this.scratchInt.duplicate().order(ByteOrder.nativeOrder());
		value.clear();
		value.putInt(1);
		value.flip();
		this.storage.upload(value, ((long) entryIndex * BYTES_PER_ENTRY) + ((long) DIRTY_OFFSET * Integer.BYTES));
	}

	public void clearSlot(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return;
		}

		ByteBuffer zeros = MemoryUtil.memCalloc(BYTES_PER_ENTRY).order(ByteOrder.nativeOrder());
		try {
			zeros.clear();
			this.storage.upload(zeros, (long) entryIndex * BYTES_PER_ENTRY);
		} finally {
			MemoryUtil.memFree(zeros);
		}
	}

	public void clearAll() {
		ByteBuffer zeros = this.zeroAll.duplicate().order(ByteOrder.nativeOrder());
		zeros.clear();
		this.storage.upload(zeros, 0L);
	}

	public int capacityEntries() {
		return this.capacityEntries;
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.scratchEntry);
		MemoryUtil.memFree(this.scratchInt);
		MemoryUtil.memFree(this.zeroAll);
		this.capacityEntries = 0;
	}

	private void clearRange(int startEntry, int entryCount) {
		if (entryCount <= 0) {
			return;
		}

		ByteBuffer zeros = allocateZeroBuffer(entryCount);
		try {
			zeros.clear();
			this.storage.upload(zeros, (long) startEntry * BYTES_PER_ENTRY);
		} finally {
			MemoryUtil.memFree(zeros);
		}
	}

	private static ByteBuffer allocateZeroBuffer(int entries) {
		return MemoryUtil.memCalloc(Math.toIntExact(toByteSize(entries))).order(ByteOrder.nativeOrder());
	}

	private static long toByteSize(int entries) {
		return (long) Math.max(entries, 1) * BYTES_PER_ENTRY;
	}
}
