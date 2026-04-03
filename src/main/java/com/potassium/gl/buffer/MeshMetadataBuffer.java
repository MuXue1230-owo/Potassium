package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class MeshMetadataBuffer implements AutoCloseable {
	public static final int INTS_PER_ENTRY = 8;
	public static final int BYTES_PER_ENTRY = Integer.BYTES * INTS_PER_ENTRY;

	private final PersistentBuffer storage;
	private final ByteBuffer zeroEntry;
	private ByteBuffer zeroAll;
	private ByteBuffer readbackBuffer;
	private IntBuffer readbackInts;

	private int capacityEntries;

	public MeshMetadataBuffer(int initialCapacityEntries) {
		this.capacityEntries = Math.max(initialCapacityEntries, 1);
		this.storage = new PersistentBuffer(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			toByteSize(this.capacityEntries),
			false,
			1
		);
		this.zeroEntry = MemoryUtil.memCalloc(BYTES_PER_ENTRY).order(ByteOrder.nativeOrder());
		this.zeroAll = allocateZeroBuffer(this.capacityEntries);
		this.readbackBuffer = allocateZeroBuffer(this.capacityEntries);
		this.readbackInts = this.readbackBuffer.asIntBuffer();
		this.clearAll();
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
		MemoryUtil.memFree(this.readbackBuffer);
		this.readbackBuffer = allocateZeroBuffer(this.capacityEntries);
		this.readbackInts = this.readbackBuffer.asIntBuffer();
		this.clearRange(previousCapacity, this.capacityEntries - previousCapacity);
	}

	public void bind(int binding) {
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public void clearSlot(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return;
		}

		ByteBuffer zeros = this.zeroEntry.duplicate().order(ByteOrder.nativeOrder());
		zeros.clear();
		this.storage.upload(zeros, (long) entryIndex * BYTES_PER_ENTRY);
	}

	public void clearAll() {
		ByteBuffer zeros = this.zeroAll.duplicate().order(ByteOrder.nativeOrder());
		zeros.clear();
		this.storage.upload(zeros, 0L);
	}

	public IntBuffer readEntries() {
		ByteBuffer readback = this.readbackBuffer.duplicate().order(ByteOrder.nativeOrder());
		readback.clear();
		GL45C.glGetNamedBufferSubData(this.storage.handle(), 0L, readback);
		IntBuffer ints = this.readbackInts.duplicate();
		ints.clear();
		return ints;
	}

	public int[] readEntry(int entryIndex) {
		if (entryIndex < 0 || entryIndex >= this.capacityEntries) {
			return new int[INTS_PER_ENTRY];
		}

		ByteBuffer readback = MemoryUtil.memAlloc(BYTES_PER_ENTRY).order(ByteOrder.nativeOrder());
		try {
			GL45C.glGetNamedBufferSubData(
				this.storage.handle(),
				(long) entryIndex * BYTES_PER_ENTRY,
				readback
			);
			IntBuffer ints = readback.asIntBuffer();
			int[] entry = new int[INTS_PER_ENTRY];
			ints.get(entry);
			return entry;
		} finally {
			MemoryUtil.memFree(readback);
		}
	}

	public int capacityEntries() {
		return this.capacityEntries;
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.zeroEntry);
		MemoryUtil.memFree(this.zeroAll);
		MemoryUtil.memFree(this.readbackBuffer);
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
