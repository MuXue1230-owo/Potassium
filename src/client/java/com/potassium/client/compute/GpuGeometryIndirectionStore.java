package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GpuGeometryIndirectionStore {
	static final int RECORD_STRIDE_BYTES = Integer.BYTES * 12;

	private static int bufferHandle;
	private static int recordCapacity;
	private static int boundStorageBinding = -1;
	private static ByteBuffer uploadRecordView;
	private static ByteBuffer cpuMirrorView;

	private GpuGeometryIndirectionStore() {
	}

	public static void initialize() {
		if (bufferHandle == 0) {
			bufferHandle = GL45C.glCreateBuffers();
		}
	}

	public static void shutdown() {
		if (bufferHandle != 0) {
			GL15C.glDeleteBuffers(bufferHandle);
			bufferHandle = 0;
		}

		recordCapacity = 0;
		boundStorageBinding = -1;
		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
			uploadRecordView = null;
		}
		if (cpuMirrorView != null) {
			MemoryUtil.memFree(cpuMirrorView);
			cpuMirrorView = null;
		}
	}

	public static void bindAsStorage(int binding) {
		initialize();
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, bufferHandle);
		boundStorageBinding = binding;
	}

	public static void unbindAsStorage(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0);
		if (boundStorageBinding == binding) {
			boundStorageBinding = -1;
		}
	}

	static void flushPendingUpload(
		GpuResidentSectionMetadataStore.CachedRegionMetadata metadata,
		int geometrySourceId
	) {
		int dirtySectionStart = metadata.dirtySectionStart();
		int dirtySectionEndExclusive = metadata.dirtySectionEndExclusive();
		if (dirtySectionEndExclusive <= dirtySectionStart) {
			return;
		}

		initialize();
		ensureUploadCapacity();

		for (int sectionIndex = dirtySectionStart; sectionIndex < dirtySectionEndExclusive; sectionIndex++) {
			int sectionSceneId = metadata.sectionSceneId(sectionIndex);
			if (sectionSceneId <= 0) {
				continue;
			}

			ensureCapacity(sectionSceneId + 1);
			ByteBuffer uploadView = uploadRecordView.duplicate().order(ByteOrder.nativeOrder());
			uploadView.clear();
			uploadView.limit(RECORD_STRIDE_BYTES);
			metadata.writeGeometryIndirectionRecord(uploadView, 0, sectionIndex, geometrySourceId);
			uploadView.flip();
			updateCpuMirror(sectionSceneId, uploadView);
			GL45C.glNamedBufferSubData(bufferHandle, (long) sectionSceneId * RECORD_STRIDE_BYTES, uploadView);
		}
	}

	public static GeometryRecord createScratchRecord() {
		return new GeometryRecord();
	}

	public static boolean loadRecord(int sectionSceneId, GeometryRecord destination) {
		if (destination == null || cpuMirrorView == null || sectionSceneId <= 0 || sectionSceneId >= recordCapacity) {
			return false;
		}

		int offsetBytes = sectionSceneId * RECORD_STRIDE_BYTES;
		destination.geometrySourceId = cpuMirrorView.getInt(offsetBytes);
		destination.regionSceneId = cpuMirrorView.getInt(offsetBytes + Integer.BYTES);
		destination.sectionSceneId = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 2));
		destination.localSectionIndex = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 3));
		destination.flags = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 4));
		destination.sectionChunkX = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 5));
		destination.sectionChunkY = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 6));
		destination.sectionChunkZ = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 7));
		destination.sliceMask = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 8));
		destination.baseElement = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 9));
		destination.baseVertex = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 10));
		destination.regionSlot = cpuMirrorView.getInt(offsetBytes + (Integer.BYTES * 11));
		return destination.sectionSceneId != 0;
	}

	private static void ensureUploadCapacity() {
		if (uploadRecordView != null && uploadRecordView.capacity() >= RECORD_STRIDE_BYTES) {
			return;
		}

		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
		}
		uploadRecordView = MemoryUtil.memAlloc(RECORD_STRIDE_BYTES).order(ByteOrder.nativeOrder());
	}

	private static void ensureCapacity(int requiredCapacity) {
		if (requiredCapacity <= recordCapacity) {
			return;
		}

		int newCapacity = 1;
		while (newCapacity < requiredCapacity) {
			newCapacity <<= 1;
		}

		int previousHandle = bufferHandle;
		int newHandle = GL45C.glCreateBuffers();
		GL45C.glNamedBufferData(newHandle, (long) newCapacity * RECORD_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
		if (previousHandle != 0 && recordCapacity > 0) {
			GL45C.glCopyNamedBufferSubData(
				previousHandle,
				newHandle,
				0L,
				0L,
				(long) recordCapacity * RECORD_STRIDE_BYTES
			);
		}

		ByteBuffer previousCpuMirror = cpuMirrorView;
		ByteBuffer newCpuMirror = MemoryUtil.memCalloc(Math.toIntExact((long) newCapacity * RECORD_STRIDE_BYTES)).order(ByteOrder.nativeOrder());
		if (previousCpuMirror != null && recordCapacity > 0) {
			ByteBuffer sourceView = previousCpuMirror.duplicate().order(ByteOrder.nativeOrder());
			sourceView.clear();
			sourceView.limit(recordCapacity * RECORD_STRIDE_BYTES);
			newCpuMirror.put(sourceView);
			newCpuMirror.clear();
		}
		cpuMirrorView = newCpuMirror;
		recordCapacity = newCapacity;

		bufferHandle = newHandle;
		if (boundStorageBinding >= 0) {
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, boundStorageBinding, bufferHandle);
		}

		if (previousHandle != 0) {
			GL15C.glDeleteBuffers(previousHandle);
		}
		if (previousCpuMirror != null) {
			MemoryUtil.memFree(previousCpuMirror);
		}
	}

	private static void updateCpuMirror(int sectionSceneId, ByteBuffer uploadView) {
		if (cpuMirrorView == null) {
			return;
		}

		ByteBuffer sourceView = uploadView.duplicate().order(ByteOrder.nativeOrder());
		sourceView.position(0);
		sourceView.limit(RECORD_STRIDE_BYTES);
		ByteBuffer mirrorView = cpuMirrorView.duplicate().order(ByteOrder.nativeOrder());
		int offsetBytes = sectionSceneId * RECORD_STRIDE_BYTES;
		mirrorView.position(offsetBytes);
		mirrorView.limit(offsetBytes + RECORD_STRIDE_BYTES);
		mirrorView.put(sourceView);
	}

	public static final class GeometryRecord {
		private int geometrySourceId;
		private int regionSceneId;
		private int sectionSceneId;
		private int localSectionIndex;
		private int flags;
		private int sectionChunkX;
		private int sectionChunkY;
		private int sectionChunkZ;
		private int sliceMask;
		private int baseElement;
		private int baseVertex;
		private int regionSlot;

		public int geometrySourceId() {
			return this.geometrySourceId;
		}

		public int regionSceneId() {
			return this.regionSceneId;
		}

		public int sectionSceneId() {
			return this.sectionSceneId;
		}

		public int localSectionIndex() {
			return this.localSectionIndex;
		}

		public int flags() {
			return this.flags;
		}

		public int sectionChunkX() {
			return this.sectionChunkX;
		}

		public int sectionChunkY() {
			return this.sectionChunkY;
		}

		public int sectionChunkZ() {
			return this.sectionChunkZ;
		}

		public int sliceMask() {
			return this.sliceMask;
		}

		public int baseElement() {
			return this.baseElement;
		}

		public int baseVertex() {
			return this.baseVertex;
		}

		public int regionSlot() {
			return this.regionSlot;
		}
	}
}
