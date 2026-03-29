package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GpuResidentGeometryStore {
	static final int GEOMETRY_STRIDE_BYTES = GpuResidentSectionMetadataStore.INPUT_STRIDE_BYTES;
	private static final int CENTER_X_OFFSET_BYTES = 0;
	private static final int CENTER_Y_OFFSET_BYTES = Float.BYTES;
	private static final int CENTER_Z_OFFSET_BYTES = Float.BYTES * 2;
	private static final int BOUNDING_RADIUS_OFFSET_BYTES = Float.BYTES * 3;
	private static final int SECTION_CHUNK_X_OFFSET_BYTES = Float.BYTES * 4;
	private static final int SECTION_CHUNK_Y_OFFSET_BYTES = (Float.BYTES * 4) + Integer.BYTES;
	private static final int SECTION_CHUNK_Z_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 2);
	private static final int SLICE_MASK_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 3);
	private static final int BASE_ELEMENT_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 4);
	private static final int BASE_VERTEX_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 5);
	private static final int FACING_LIST_LOW_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 6);
	private static final int FACING_LIST_HIGH_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 7);
	private static final int VERTEX_COUNT_BASE_OFFSET_BYTES = (Float.BYTES * 4) + (Integer.BYTES * 8);
	private static final int REGION_SCENE_ID_OFFSET_BYTES = Integer.BYTES * 20;
	private static final int SECTION_SCENE_ID_OFFSET_BYTES = Integer.BYTES * 21;
	private static final int FLAGS_OFFSET_BYTES = Integer.BYTES * 22;
	private static final int LOCAL_SECTION_INDEX_OFFSET_BYTES = Integer.BYTES * 23;
	private static final int LOD_NEAR_CHUNKS = 12;
	private static final int LOD_FAR_CHUNKS = 40;
	private static final float LOD_MIN_SCALE = 0.35f;

	private static int bufferHandle;
	private static int cpuRegionSlotCapacity;
	private static int gpuRegionSlotCapacity;
	private static int boundStorageBinding = -1;
	private static ByteBuffer uploadRecordView;
	private static ByteBuffer clearRegionView;
	private static ByteBuffer cpuMirrorView;

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

		cpuRegionSlotCapacity = 0;
		gpuRegionSlotCapacity = 0;
		boundStorageBinding = -1;
		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
			uploadRecordView = null;
		}
		if (clearRegionView != null) {
			MemoryUtil.memFree(clearRegionView);
			clearRegionView = null;
		}
		if (cpuMirrorView != null) {
			MemoryUtil.memFree(cpuMirrorView);
			cpuMirrorView = null;
		}
	}

	static void bindAsStorage(int binding) {
		initialize();
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, bufferHandle);
		boundStorageBinding = binding;
	}

	static void preallocateSlotCapacity(int requiredRegionSlots) {
		ensureCpuCapacity(requiredRegionSlots);
		initialize();
		ensureGpuCapacity(requiredRegionSlots);
	}

	static void unbindAsStorage(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0);
		if (boundStorageBinding == binding) {
			boundStorageBinding = -1;
		}
	}

	static void syncCpuMirror(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata, boolean fullSync) {
		if (metadata == null) {
			return;
		}

		ensureCpuCapacity(metadata.regionSlot() + 1);
		if (fullSync) {
			clearRegionMirror(metadata.regionSlot());
			for (int sectionIndex = 0; sectionIndex < metadata.sectionCount(); sectionIndex++) {
				writeSectionRecordToMirror(metadata, sectionIndex);
			}
			return;
		}

		if (!metadata.isDirty()) {
			return;
		}

		int start = metadata.dirtySectionStart();
		int endExclusive = metadata.dirtySectionEndExclusive();
		for (int sectionIndex = start; sectionIndex < endExclusive; sectionIndex++) {
			writeSectionRecordToMirror(metadata, sectionIndex);
		}
	}

	static void flushPendingUpload(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata, boolean fullSync) {
		if (metadata == null) {
			return;
		}

		initialize();
		ensureGpuCapacity(metadata.regionSlot() + 1);
		if (fullSync) {
			clearRegionGpu(metadata.regionSlot());
			for (int sectionIndex = 0; sectionIndex < metadata.sectionCount(); sectionIndex++) {
				uploadSectionRecordToGpu(metadata, sectionIndex);
			}
			return;
		}

		if (!metadata.isDirty()) {
			return;
		}

		int start = metadata.dirtySectionStart();
		int endExclusive = metadata.dirtySectionEndExclusive();
		for (int sectionIndex = start; sectionIndex < endExclusive; sectionIndex++) {
			uploadSectionRecordToGpu(metadata, sectionIndex);
		}
	}

	static long capacityBytes() {
		return (long) gpuRegionSlotCapacity * regionStrideBytes();
	}

	public static SectionRecord createScratchRecord() {
		return new SectionRecord();
	}

	public static boolean loadSection(int regionSlot, int localSectionIndex, SectionRecord destination) {
		if (destination == null || cpuMirrorView == null) {
			return false;
		}
		if (regionSlot < 0 || regionSlot >= cpuRegionSlotCapacity) {
			return false;
		}
		if (localSectionIndex < 0 || localSectionIndex >= GpuResidentSectionMetadataStore.MAX_REGION_SECTION_COUNT) {
			return false;
		}

		int offsetBytes = Math.toIntExact(sectionOffsetBytes(regionSlot, localSectionIndex));
		destination.centerX = cpuMirrorView.getFloat(offsetBytes + CENTER_X_OFFSET_BYTES);
		destination.centerY = cpuMirrorView.getFloat(offsetBytes + CENTER_Y_OFFSET_BYTES);
		destination.centerZ = cpuMirrorView.getFloat(offsetBytes + CENTER_Z_OFFSET_BYTES);
		destination.boundingRadius = cpuMirrorView.getFloat(offsetBytes + BOUNDING_RADIUS_OFFSET_BYTES);
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
		destination.regionSceneId = cpuMirrorView.getInt(offsetBytes + REGION_SCENE_ID_OFFSET_BYTES);
		destination.sectionSceneId = cpuMirrorView.getInt(offsetBytes + SECTION_SCENE_ID_OFFSET_BYTES);
		destination.flags = cpuMirrorView.getInt(offsetBytes + FLAGS_OFFSET_BYTES);
		destination.localSectionIndex = cpuMirrorView.getInt(offsetBytes + LOCAL_SECTION_INDEX_OFFSET_BYTES);
		return destination.sectionSceneId != 0;
	}

	public static float computeLodScale(SectionRecord record, CameraTransform cameraTransform, boolean translucentPass) {
		if (record == null || cameraTransform == null || translucentPass) {
			return 1.0f;
		}

		float cameraX = cameraTransform.intX + cameraTransform.fracX;
		float cameraY = cameraTransform.intY + cameraTransform.fracY;
		float cameraZ = cameraTransform.intZ + cameraTransform.fracZ;
		float dx = (record.centerX - cameraX) / 16.0f;
		float dy = (record.centerY - cameraY) / 16.0f;
		float dz = (record.centerZ - cameraZ) / 16.0f;
		float distanceChunks = (float) Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
		if (distanceChunks <= LOD_NEAR_CHUNKS) {
			return 1.0f;
		}
		if (distanceChunks >= LOD_FAR_CHUNKS) {
			return LOD_MIN_SCALE;
		}

		float t = (distanceChunks - LOD_NEAR_CHUNKS) / (LOD_FAR_CHUNKS - LOD_NEAR_CHUNKS);
		return 1.0f - ((1.0f - LOD_MIN_SCALE) * t);
	}

	private static void writeSectionRecordToMirror(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata, int sectionIndex) {
		if (cpuMirrorView == null) {
			return;
		}

		ByteBuffer mirrorView = cpuMirrorView.duplicate().order(ByteOrder.nativeOrder());
		metadata.writeGeometryRecord(
			mirrorView,
			Math.toIntExact(sectionOffsetBytes(metadata.regionSlot(), metadata.localSectionIndex(sectionIndex))),
			sectionIndex
		);
	}

	private static void uploadSectionRecordToGpu(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata, int sectionIndex) {
		ensureUploadCapacity();
		ByteBuffer uploadView = uploadRecordView.duplicate().order(ByteOrder.nativeOrder());
		uploadView.clear();
		uploadView.limit(GEOMETRY_STRIDE_BYTES);
		metadata.writeGeometryRecord(uploadView, 0, sectionIndex);
		uploadView.flip();
		long offsetBytes = sectionOffsetBytes(metadata.regionSlot(), metadata.localSectionIndex(sectionIndex));
		GL45C.glNamedBufferSubData(bufferHandle, offsetBytes, uploadView);
	}

	private static void clearRegionMirror(int regionSlot) {
		if (cpuMirrorView == null) {
			return;
		}

		ByteBuffer mirrorView = cpuMirrorView.duplicate().order(ByteOrder.nativeOrder());
		int offsetBytes = Math.toIntExact(regionOffsetBytes(regionSlot));
		mirrorView.position(offsetBytes);
		mirrorView.limit(offsetBytes + regionStrideBytes());
		while (mirrorView.hasRemaining()) {
			mirrorView.put((byte) 0);
		}
	}

	private static void clearRegionGpu(int regionSlot) {
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

	private static void ensureCpuCapacity(int requiredRegionSlots) {
		if (requiredRegionSlots <= cpuRegionSlotCapacity) {
			return;
		}

		int newCapacity = 1;
		while (newCapacity < requiredRegionSlots) {
			newCapacity <<= 1;
		}

		ByteBuffer previousCpuMirror = cpuMirrorView;
		ByteBuffer newCpuMirror = MemoryUtil.memCalloc(Math.toIntExact((long) newCapacity * regionStrideBytes())).order(ByteOrder.nativeOrder());
		if (previousCpuMirror != null && cpuRegionSlotCapacity > 0) {
			ByteBuffer sourceView = previousCpuMirror.duplicate().order(ByteOrder.nativeOrder());
			sourceView.clear();
			sourceView.limit(Math.toIntExact((long) cpuRegionSlotCapacity * regionStrideBytes()));
			newCpuMirror.put(sourceView);
			newCpuMirror.clear();
		}
		cpuMirrorView = newCpuMirror;
		cpuRegionSlotCapacity = newCapacity;
		if (previousCpuMirror != null) {
			MemoryUtil.memFree(previousCpuMirror);
		}
	}

	private static void ensureGpuCapacity(int requiredRegionSlots) {
		if (requiredRegionSlots <= gpuRegionSlotCapacity) {
			return;
		}

		int newCapacity = 1;
		while (newCapacity < requiredRegionSlots) {
			newCapacity <<= 1;
		}

		int previousHandle = bufferHandle;
		int newHandle = GL45C.glCreateBuffers();
		GL45C.glNamedBufferData(newHandle, (long) newCapacity * regionStrideBytes(), GL15C.GL_DYNAMIC_DRAW);
		if (previousHandle != 0 && gpuRegionSlotCapacity > 0) {
			GL45C.glCopyNamedBufferSubData(
				previousHandle,
				newHandle,
				0L,
				0L,
				(long) gpuRegionSlotCapacity * regionStrideBytes()
			);
		}

		bufferHandle = newHandle;
		gpuRegionSlotCapacity = newCapacity;
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

	public static final class SectionRecord {
		private final int[] vertexCounts = new int[7];
		private float centerX;
		private float centerY;
		private float centerZ;
		private float boundingRadius;
		private int sectionChunkX;
		private int sectionChunkY;
		private int sectionChunkZ;
		private int sliceMask;
		private int baseElement;
		private int baseVertex;
		private long facingList;
		private int regionSceneId;
		private int sectionSceneId;
		private int flags;
		private int localSectionIndex;

		public boolean exists() {
			return this.sectionSceneId != 0;
		}

		public float centerX() {
			return this.centerX;
		}

		public float centerY() {
			return this.centerY;
		}

		public float centerZ() {
			return this.centerZ;
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

		public int sectionSceneId() {
			return this.sectionSceneId;
		}

		public boolean usesLocalIndex() {
			return (this.flags & GpuResidentSectionMetadataStore.FLAG_USE_LOCAL_INDEX) != 0;
		}

		public int faceOrder(int facing) {
			return (int) ((this.facingList >>> (facing * 8)) & 0xFFL);
		}
	}
}
