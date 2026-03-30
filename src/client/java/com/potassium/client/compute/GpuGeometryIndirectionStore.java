package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GpuGeometryIndirectionStore {
	static final int RECORD_STRIDE_BYTES = Integer.BYTES * 21;
	private static final int GEOMETRY_SOURCE_ID_OFFSET_BYTES = 0;
	private static final int REGION_SCENE_ID_OFFSET_BYTES = Integer.BYTES;
	private static final int SECTION_SCENE_ID_OFFSET_BYTES = Integer.BYTES * 2;
	private static final int LOCAL_SECTION_INDEX_OFFSET_BYTES = Integer.BYTES * 3;
	private static final int FLAGS_OFFSET_BYTES = Integer.BYTES * 4;
	private static final int SECTION_CHUNK_X_OFFSET_BYTES = Integer.BYTES * 5;
	private static final int SECTION_CHUNK_Y_OFFSET_BYTES = Integer.BYTES * 6;
	private static final int SECTION_CHUNK_Z_OFFSET_BYTES = Integer.BYTES * 7;
	private static final int SLICE_MASK_OFFSET_BYTES = Integer.BYTES * 8;
	private static final int BASE_ELEMENT_OFFSET_BYTES = Integer.BYTES * 9;
	private static final int BASE_VERTEX_OFFSET_BYTES = Integer.BYTES * 10;
	private static final int FACING_LIST_LOW_OFFSET_BYTES = Integer.BYTES * 11;
	private static final int FACING_LIST_HIGH_OFFSET_BYTES = Integer.BYTES * 12;
	private static final int VERTEX_COUNT_BASE_OFFSET_BYTES = Integer.BYTES * 13;
	private static final int REGION_SLOT_OFFSET_BYTES = Integer.BYTES * 20;

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
		destination.geometrySourceId = cpuMirrorView.getInt(offsetBytes + GEOMETRY_SOURCE_ID_OFFSET_BYTES);
		destination.regionSceneId = cpuMirrorView.getInt(offsetBytes + REGION_SCENE_ID_OFFSET_BYTES);
		destination.sectionSceneId = cpuMirrorView.getInt(offsetBytes + SECTION_SCENE_ID_OFFSET_BYTES);
		destination.localSectionIndex = cpuMirrorView.getInt(offsetBytes + LOCAL_SECTION_INDEX_OFFSET_BYTES);
		destination.flags = cpuMirrorView.getInt(offsetBytes + FLAGS_OFFSET_BYTES);
		destination.sectionChunkX = cpuMirrorView.getInt(offsetBytes + SECTION_CHUNK_X_OFFSET_BYTES);
		destination.sectionChunkY = cpuMirrorView.getInt(offsetBytes + SECTION_CHUNK_Y_OFFSET_BYTES);
		destination.sectionChunkZ = cpuMirrorView.getInt(offsetBytes + SECTION_CHUNK_Z_OFFSET_BYTES);
		destination.sliceMask = cpuMirrorView.getInt(offsetBytes + SLICE_MASK_OFFSET_BYTES);
		destination.baseElement = cpuMirrorView.getInt(offsetBytes + BASE_ELEMENT_OFFSET_BYTES);
		destination.baseVertex = cpuMirrorView.getInt(offsetBytes + BASE_VERTEX_OFFSET_BYTES);
		long facingListLow = Integer.toUnsignedLong(cpuMirrorView.getInt(offsetBytes + FACING_LIST_LOW_OFFSET_BYTES));
		long facingListHigh = Integer.toUnsignedLong(cpuMirrorView.getInt(offsetBytes + FACING_LIST_HIGH_OFFSET_BYTES));
		destination.facingList = facingListLow | (facingListHigh << Integer.SIZE);
		for (int facing = 0; facing < destination.vertexCounts.length; facing++) {
			destination.vertexCounts[facing] = cpuMirrorView.getInt(offsetBytes + VERTEX_COUNT_BASE_OFFSET_BYTES + (facing * Integer.BYTES));
		}
		destination.regionSlot = cpuMirrorView.getInt(offsetBytes + REGION_SLOT_OFFSET_BYTES);
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
		private final int[] vertexCounts = new int[7];
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
		private long facingList;
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

		public long facingList() {
			return this.facingList;
		}

		public int vertexCount(int facing) {
			return this.vertexCounts[facing];
		}

		public boolean usesLocalIndex() {
			return (this.flags & GpuResidentSectionMetadataStore.FLAG_USE_LOCAL_INDEX) != 0;
		}

		public int regionSlot() {
			return this.regionSlot;
		}
	}
}
