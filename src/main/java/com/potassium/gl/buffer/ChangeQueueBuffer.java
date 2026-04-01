package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class ChangeQueueBuffer implements AutoCloseable {
	private static final int HEADER_INTS = 1;
	public static final int INTS_PER_ENTRY = 4;

	private final PersistentBuffer storage;

	private ByteBuffer uploadBuffer;
	private int capacityEntries;
	private long lastUploadBytes;
	private int lastEntryCount;

	public ChangeQueueBuffer(int initialCapacityEntries, boolean persistentMappingEnabled) {
		this.capacityEntries = Math.max(initialCapacityEntries, 1);
		this.storage = new PersistentBuffer(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			toByteSize(this.capacityEntries),
			persistentMappingEnabled,
			1
		);
		this.uploadBuffer = MemoryUtil.memAlloc(toByteSize(this.capacityEntries)).order(ByteOrder.nativeOrder());
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

		this.capacityEntries = requiredEntries;
		this.storage.ensureCapacity(toByteSize(this.capacityEntries));
		this.uploadBuffer = MemoryUtil.memRealloc(this.uploadBuffer, toByteSize(this.capacityEntries)).order(ByteOrder.nativeOrder());
	}

	public void uploadChanges(List<Entry> entries) {
		int entryCount = entries.size();
		this.ensureCapacity(Math.max(entryCount, 1));
		ByteBuffer upload = this.uploadBuffer.duplicate().order(ByteOrder.nativeOrder());
		upload.clear();
		upload.putInt(entryCount);
		for (Entry entry : entries) {
			upload.putInt(entry.residentSlot());
			upload.putInt(entry.localBlockIndex());
			upload.putInt(entry.packedBlock());
			upload.putInt(entry.boundaryMask());
		}
		upload.flip();
		this.lastUploadBytes = upload.remaining();
		this.lastEntryCount = entryCount;
		this.storage.upload(upload, 0L);
	}

	public void bind(int binding) {
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public long lastUploadBytes() {
		return this.lastUploadBytes;
	}

	public int lastEntryCount() {
		return this.lastEntryCount;
	}

	public int capacityEntries() {
		return this.capacityEntries;
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.uploadBuffer);
		this.uploadBuffer = null;
		this.capacityEntries = 0;
		this.lastUploadBytes = 0L;
		this.lastEntryCount = 0;
	}

	private static int toByteSize(int entryCapacity) {
		return Integer.BYTES * (HEADER_INTS + (Math.max(entryCapacity, 1) * INTS_PER_ENTRY));
	}

	public record Entry(int residentSlot, int localBlockIndex, int packedBlock, int boundaryMask) {
	}
}
