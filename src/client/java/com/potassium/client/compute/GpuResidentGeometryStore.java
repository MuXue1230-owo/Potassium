package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

final class GpuResidentGeometryStore {
	static final int GEOMETRY_STRIDE_BYTES = GpuResidentSectionMetadataStore.INPUT_STRIDE_BYTES;

	private static int bufferHandle;
	private static int regionSlotCapacity;
	private static int boundStorageBinding = -1;
	private static ByteBuffer uploadRecordView;
	private static ByteBuffer clearRegionView;

	private GpuResidentGeometryStore() {
	}

	static void initialize() {
		if (bufferHandle == 0) {
			bufferHandle = GL45C.glCreateBuffers();
		}
	}

	static void shutdown() {
		if (bufferHandle != 0) {
			GL15C.glDeleteBuffers(bufferHandle);
			bufferHandle = 0;
		}

		regionSlotCapacity = 0;
		boundStorageBinding = -1;
		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
			uploadRecordView = null;
		}
		if (clearRegionView != null) {
			MemoryUtil.memFree(clearRegionView);
			clearRegionView = null;
		}
	}

	static void bindAsStorage(int binding) {
		initialize();
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, bufferHandle);
		boundStorageBinding = binding;
	}

	static void preallocateSlotCapacity(int requiredRegionSlots) {
		initialize();
		ensureCapacity(requiredRegionSlots);
	}

	static void unbindAsStorage(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0);
		if (boundStorageBinding == binding) {
			boundStorageBinding = -1;
		}
	}

	static void syncRegion(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata, boolean fullSync) {
		if (metadata == null) {
			return;
		}

		initialize();
		ensureCapacity(metadata.regionSlot() + 1);
		if (fullSync) {
			clearRegion(metadata.regionSlot());
			for (int sectionIndex = 0; sectionIndex < metadata.sectionCount(); sectionIndex++) {
				uploadSectionRecord(metadata, sectionIndex);
			}
			return;
		}

		if (!metadata.isDirty()) {
			return;
		}

		int start = metadata.dirtySectionStart();
		int endExclusive = metadata.dirtySectionEndExclusive();
		for (int sectionIndex = start; sectionIndex < endExclusive; sectionIndex++) {
			uploadSectionRecord(metadata, sectionIndex);
		}
	}

	static long capacityBytes() {
		return (long) regionSlotCapacity * regionStrideBytes();
	}

	private static void uploadSectionRecord(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata, int sectionIndex) {
		ensureUploadCapacity();
		ByteBuffer uploadView = uploadRecordView.duplicate().order(ByteOrder.nativeOrder());
		uploadView.clear();
		uploadView.limit(GEOMETRY_STRIDE_BYTES);
		metadata.writeGeometryRecord(uploadView, 0, sectionIndex);
		uploadView.flip();
		GL45C.glNamedBufferSubData(
			bufferHandle,
			sectionOffsetBytes(metadata.regionSlot(), metadata.localSectionIndex(sectionIndex)),
			uploadView
		);
	}

	private static void clearRegion(int regionSlot) {
		ensureClearRegionCapacity();
		ByteBuffer clearView = clearRegionView.duplicate().order(ByteOrder.nativeOrder());
		clearView.clear();
		clearView.limit(regionStrideBytes());
		GL45C.glNamedBufferSubData(bufferHandle, regionOffsetBytes(regionSlot), clearView);
	}

	private static void ensureUploadCapacity() {
		if (uploadRecordView != null && uploadRecordView.capacity() >= GEOMETRY_STRIDE_BYTES) {
			return;
		}

		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
		}
		uploadRecordView = MemoryUtil.memCalloc(GEOMETRY_STRIDE_BYTES).order(ByteOrder.nativeOrder());
	}

	private static void ensureClearRegionCapacity() {
		int requiredBytes = regionStrideBytes();
		if (clearRegionView != null && clearRegionView.capacity() >= requiredBytes) {
			return;
		}

		if (clearRegionView != null) {
			MemoryUtil.memFree(clearRegionView);
		}
		clearRegionView = MemoryUtil.memCalloc(requiredBytes).order(ByteOrder.nativeOrder());
	}

	private static void ensureCapacity(int requiredRegionSlots) {
		if (requiredRegionSlots <= regionSlotCapacity) {
			return;
		}

		int newCapacity = 1;
		while (newCapacity < requiredRegionSlots) {
			newCapacity <<= 1;
		}

		int previousHandle = bufferHandle;
		int newHandle = GL45C.glCreateBuffers();
		GL45C.glNamedBufferData(newHandle, (long) newCapacity * regionStrideBytes(), GL15C.GL_DYNAMIC_DRAW);
		if (previousHandle != 0 && regionSlotCapacity > 0) {
			GL45C.glCopyNamedBufferSubData(
				previousHandle,
				newHandle,
				0L,
				0L,
				(long) regionSlotCapacity * regionStrideBytes()
			);
		}

		bufferHandle = newHandle;
		regionSlotCapacity = newCapacity;
		if (boundStorageBinding >= 0) {
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, boundStorageBinding, bufferHandle);
		}

		if (previousHandle != 0) {
			GL15C.glDeleteBuffers(previousHandle);
		}
	}

	private static int regionStrideBytes() {
		return GpuResidentSectionMetadataStore.MAX_REGION_SECTION_COUNT * GEOMETRY_STRIDE_BYTES;
	}

	private static long regionOffsetBytes(int regionSlot) {
		return (long) regionSlot * regionStrideBytes();
	}

	private static long sectionOffsetBytes(int regionSlot, int localSectionIndex) {
		return regionOffsetBytes(regionSlot) + ((long) localSectionIndex * GEOMETRY_STRIDE_BYTES);
	}
}
