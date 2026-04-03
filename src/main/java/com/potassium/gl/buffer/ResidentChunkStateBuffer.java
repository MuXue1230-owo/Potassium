package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class ResidentChunkStateBuffer implements AutoCloseable {
	public static final int INTS_PER_ENTRY = 9;
	public static final int BYTES_PER_ENTRY = Integer.BYTES * INTS_PER_ENTRY;
	public static final int ACTIVE_OFFSET = 0;
	public static final int DIRTY_OFFSET = 1;
	public static final int CHUNK_X_OFFSET = 2;
	public static final int CHUNK_Z_OFFSET = 3;
	public static final int NEG_X_SLOT_OFFSET = 4;
	public static final int POS_X_SLOT_OFFSET = 5;
	public static final int NEG_Z_SLOT_OFFSET = 6;
	public static final int POS_Z_SLOT_OFFSET = 7;
	public static final int MESH_REVISION_OFFSET = 8;

	private final PersistentBuffer storage;
	private final ByteBuffer scratchEntry;
	private final ByteBuffer scratchInt;
	private ByteBuffer zeroAll;
	private int[] meshRevisions;

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
		this.meshRevisions = new int[this.capacityEntries];
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
		int[] expandedRevisions = new int[this.capacityEntries];
		System.arraycopy(this.meshRevisions, 0, expandedRevisions, 0, previousCapacity);
		this.meshRevisions = expandedRevisions;
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

		if (dirty) {
			this.meshRevisions[entryIndex]++;
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
		entry.putInt(this.meshRevisions[entryIndex]);
		entry.flip();
		this.storage.upload(entry, (long) entryIndex * BYTES_PER_ENTRY);
	}

	public void markDirty(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return;
		}

		this.meshRevisions[entryIndex]++;

		ByteBuffer value = this.scratchInt.duplicate().order(ByteOrder.nativeOrder());
		value.clear();
		value.putInt(1);
		value.flip();
		this.storage.upload(value, ((long) entryIndex * BYTES_PER_ENTRY) + ((long) DIRTY_OFFSET * Integer.BYTES));

		value.clear();
		value.putInt(this.meshRevisions[entryIndex]);
		value.flip();
		this.storage.upload(value, ((long) entryIndex * BYTES_PER_ENTRY) + ((long) MESH_REVISION_OFFSET * Integer.BYTES));
	}

	public void clearSlot(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return;
		}

		this.meshRevisions[entryIndex] = 0;

		ByteBuffer zeros = MemoryUtil.memCalloc(BYTES_PER_ENTRY).order(ByteOrder.nativeOrder());
		try {
			zeros.clear();
			this.storage.upload(zeros, (long) entryIndex * BYTES_PER_ENTRY);
		} finally {
			MemoryUtil.memFree(zeros);
		}
	}

	public void clearAll() {
		java.util.Arrays.fill(this.meshRevisions, 0);
		ByteBuffer zeros = this.zeroAll.duplicate().order(ByteOrder.nativeOrder());
		zeros.clear();
		this.storage.upload(zeros, 0L);
	}

	public int capacityEntries() {
		return this.capacityEntries;
	}

	public int meshRevision(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return 0;
		}

		return this.meshRevisions[entryIndex];
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.scratchEntry);
		MemoryUtil.memFree(this.scratchInt);
		MemoryUtil.memFree(this.zeroAll);
		this.meshRevisions = new int[0];
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
