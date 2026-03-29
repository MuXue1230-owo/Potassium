package com.potassium.client.compute;

import com.potassium.client.compat.sodium.ChunkRenderListOrdering;
import com.potassium.client.compat.sodium.SectionRenderDataStorageDirtyTracker;
import com.potassium.client.compat.sodium.SectionRenderDataStorageVersioned;
import com.potassium.client.compute.SectionVisibilityCompute.RegionBatchInput;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

final class GpuResidentSectionMetadataStore {
	static final int INPUT_STRIDE_BYTES = Integer.BYTES * 24;
	static final int MAX_REGION_SECTION_COUNT =
		RenderRegion.REGION_WIDTH * RenderRegion.REGION_HEIGHT * RenderRegion.REGION_LENGTH;
	static final int FLAG_USE_LOCAL_INDEX = 1;
	private static final int CENTER_X_OFFSET = 0;
	private static final int CENTER_Y_OFFSET = 1;
	private static final int CENTER_Z_OFFSET = 2;
	private static final int BOUNDING_RADIUS_OFFSET = 3;
	private static final int SECTION_CHUNK_X_OFFSET = 4;
	private static final int SECTION_CHUNK_Y_OFFSET = 5;
	private static final int SECTION_CHUNK_Z_OFFSET = 6;
	private static final int SLICE_MASK_OFFSET = 7;
	private static final int REGION_INFO_REGION_SCENE_ID_OFFSET = 20;
	private static final int REGION_INFO_SECTION_SCENE_ID_OFFSET = 21;
	private static final int REGION_INFO_FLAGS_OFFSET = 22;
	private static final int REGION_INFO_LOCAL_SECTION_INDEX_OFFSET = 23;

	private static final int[] LOCAL_SECTION_CHUNK_X = new int[MAX_REGION_SECTION_COUNT];
	private static final int[] LOCAL_SECTION_CHUNK_Y = new int[MAX_REGION_SECTION_COUNT];
	private static final int[] LOCAL_SECTION_CHUNK_Z = new int[MAX_REGION_SECTION_COUNT];
	private static final float[] LOCAL_SECTION_CENTER_X = new float[MAX_REGION_SECTION_COUNT];
	private static final float[] LOCAL_SECTION_CENTER_Y = new float[MAX_REGION_SECTION_COUNT];
	private static final float[] LOCAL_SECTION_CENTER_Z = new float[MAX_REGION_SECTION_COUNT];
	private static final Map<SectionRenderDataStorage, CachedRegionMetadata> REGION_METADATA_CACHE = new WeakHashMap<>();
	private static final ThreadLocal<long[]> DIRTY_SECTION_WORDS =
		ThreadLocal.withInitial(() -> new long[(MAX_REGION_SECTION_COUNT + Long.SIZE - 1) / Long.SIZE]);

	private static int sectionBufferHandle;
	private static int regionSlotCapacity;
	private static int nextRegionSlot;
	private static ByteBuffer sectionUploadView;

	static {
		for (int localSectionIndex = 0; localSectionIndex < MAX_REGION_SECTION_COUNT; localSectionIndex++) {
			int chunkOffsetX = LocalSectionIndex.unpackX(localSectionIndex);
			int chunkOffsetY = LocalSectionIndex.unpackY(localSectionIndex);
			int chunkOffsetZ = LocalSectionIndex.unpackZ(localSectionIndex);
			LOCAL_SECTION_CHUNK_X[localSectionIndex] = chunkOffsetX;
			LOCAL_SECTION_CHUNK_Y[localSectionIndex] = chunkOffsetY;
			LOCAL_SECTION_CHUNK_Z[localSectionIndex] = chunkOffsetZ;
			LOCAL_SECTION_CENTER_X[localSectionIndex] = (chunkOffsetX << 4) + 8.0f;
			LOCAL_SECTION_CENTER_Y[localSectionIndex] = (chunkOffsetY << 4) + 8.0f;
			LOCAL_SECTION_CENTER_Z[localSectionIndex] = (chunkOffsetZ << 4) + 8.0f;
		}
	}

	private GpuResidentSectionMetadataStore() {
	}

	static void initialize() {
		if (sectionBufferHandle == 0) {
			sectionBufferHandle = GL45C.glCreateBuffers();
		}
		GpuResidentGeometryStore.initialize();
		GpuSceneDataStore.initialize();
	}

	static void shutdown() {
		if (sectionBufferHandle != 0) {
			GL15C.glDeleteBuffers(sectionBufferHandle);
			sectionBufferHandle = 0;
		}

		REGION_METADATA_CACHE.clear();
		GpuResidentGeometryBookkeeping.reset();
		GpuResidentGeometryStore.shutdown();
		GpuSceneDataStore.shutdown();
		nextRegionSlot = 0;
		regionSlotCapacity = 0;

		if (sectionUploadView != null) {
			MemoryUtil.memFree(sectionUploadView);
			sectionUploadView = null;
		}
	}

	static void bindAsStorage(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, sectionBufferHandle);
	}

	static void preallocateSlotCapacity(int requiredRegionSlots) {
		ensureResidentCapacity(requiredRegionSlots);
	}

	static CachedRegionMetadata resolveMetadata(
		RegionBatchInput regionInput,
		boolean preferLocalIndices,
		boolean translucentPass
	) {
		ChunkRenderList renderList = regionInput.renderList();
		SectionRenderDataStorage storage = regionInput.storage();
		if (!(renderList instanceof ChunkRenderListOrdering ordering)) {
			throw new IllegalStateException("Potassium chunk render list accessor mixin is not applied.");
		}
		if (!(storage instanceof SectionRenderDataStorageVersioned versioned)) {
			throw new IllegalStateException("Potassium section render storage version mixin is not applied.");
		}
		if (!(storage instanceof SectionRenderDataStorageDirtyTracker dirtyTracker)) {
			throw new IllegalStateException("Potassium section render dirty-tracker mixin is not applied.");
		}

		byte[] sectionsWithGeometry = ordering.potassium$getSectionsWithGeometry();
		long[] sectionsWithGeometryMap = ordering.potassium$getSectionsWithGeometryMap();
		int sectionCount = renderList.getSectionsWithGeometryCount();
		int storageVersion = versioned.potassium$getStorageVersion();
		boolean preserveSectionOrder = translucentPass;
		CachedRegionMetadata cachedMetadata = REGION_METADATA_CACHE.get(storage);
		if (cachedMetadata != null) {
			if (
				cachedMetadata.matchesLayout(
					preferLocalIndices,
					preserveSectionOrder,
					sectionsWithGeometry,
					sectionsWithGeometryMap,
					sectionCount
				)
			) {
				if (cachedMetadata.storageVersion() == storageVersion) {
					return cachedMetadata;
				}

				if (!dirtyTracker.potassium$isFullMetadataDirty()) {
					long[] dirtySectionWords = DIRTY_SECTION_WORDS.get();
					dirtyTracker.potassium$copyDirtySectionBits(dirtySectionWords);
					if (cachedMetadata.refreshDirtySections(regionInput, storageVersion, preferLocalIndices, dirtySectionWords)) {
						dirtyTracker.potassium$clearMetadataDirty();
						GpuResidentGeometryBookkeeping.record(storage, cachedMetadata);
						return cachedMetadata;
					}
				}
			}
		}

		int regionSlot = cachedMetadata != null ? cachedMetadata.regionSlot() : nextRegionSlot++;
		CachedRegionMetadata rebuiltMetadata = buildCachedMetadata(
			regionInput,
			storageVersion,
			preferLocalIndices,
			preserveSectionOrder,
			sectionsWithGeometry,
			sectionsWithGeometryMap,
			sectionCount,
			regionSlot
		);
		REGION_METADATA_CACHE.put(storage, rebuiltMetadata);
		dirtyTracker.potassium$clearMetadataDirty();
		GpuResidentGeometryBookkeeping.record(storage, rebuiltMetadata);
		return rebuiltMetadata;
	}

	static void uploadIfDirty(CachedRegionMetadata cachedMetadata) {
		if (!cachedMetadata.isDirty()) {
			return;
		}

		ensureResidentCapacity(cachedMetadata.regionSlot() + 1);
		int uploadOffsetBytes = cachedMetadata.dirtyUploadOffsetBytes();
		int uploadLengthBytes = cachedMetadata.dirtyUploadLengthBytes();
		if (uploadLengthBytes <= 0) {
			cachedMetadata.markUploaded();
			return;
		}

		ensureSectionUploadCapacity(uploadLengthBytes);
		ByteBuffer uploadView = sectionUploadView.duplicate().order(ByteOrder.nativeOrder());
		uploadView.clear();
		uploadView.limit(uploadLengthBytes);
		uploadView.put(cachedMetadata.templateBytes(), uploadOffsetBytes, uploadLengthBytes);
		uploadView.flip();
		GL45C.glNamedBufferSubData(
			sectionBufferHandle,
			sectionBaseOffsetBytes(cachedMetadata.regionSlot()) + uploadOffsetBytes,
			uploadView
		);
		cachedMetadata.markUploaded();
	}

	private static CachedRegionMetadata buildCachedMetadata(
		RegionBatchInput regionInput,
		int storageVersion,
		boolean preferLocalIndices,
		boolean preserveSectionOrder,
		byte[] sectionsWithGeometry,
		long[] sectionsWithGeometryMap,
		int sectionCount,
		int regionSlot
	) {
		RenderRegion region = regionInput.region();
		SectionRenderDataStorage storage = regionInput.storage();
		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		byte[] sectionOrderSnapshot = preserveSectionOrder
			? snapshotSectionOrder(sectionsWithGeometry, sectionCount)
			: buildStableSectionOrder(sectionsWithGeometryMap, sectionCount);
		long[] geometryMapSnapshot = preserveSectionOrder ? null : sectionsWithGeometryMap.clone();
		short[] packedIndexByLocalSection = buildPackedIndexLookup(sectionOrderSnapshot);
		byte[] templateBytes = new byte[sectionCount * INPUT_STRIDE_BYTES];
		boolean[] localIndexSections = new boolean[sectionCount];
		ByteBuffer metadataView = ByteBuffer.wrap(templateBytes).order(ByteOrder.nativeOrder());
		int localIndexSectionCount = 0;

		for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
			int localSectionIndex = Byte.toUnsignedInt(sectionOrderSnapshot[sectionIndex]);
			boolean useLocalIndex = writeSectionMetadata(
				metadataView,
				sectionIndex,
				localSectionIndex,
				storage,
				regionChunkX,
				regionChunkY,
				regionChunkZ,
				preferLocalIndices
			);
			localIndexSections[sectionIndex] = useLocalIndex;
			if (useLocalIndex) {
				localIndexSectionCount++;
			}
		}

		return new CachedRegionMetadata(
			storageVersion,
			preferLocalIndices,
			preserveSectionOrder,
			sectionOrderSnapshot,
			geometryMapSnapshot,
			packedIndexByLocalSection,
			templateBytes,
			localIndexSections,
			localIndexSectionCount,
			regionSlot,
			true
		);
	}

	private static boolean writeSectionMetadata(
		ByteBuffer metadataView,
		int sectionIndex,
		int localSectionIndex,
		SectionRenderDataStorage storage,
		int regionChunkX,
		int regionChunkY,
		int regionChunkZ,
		boolean preferLocalIndices
	) {
		long dataPointer = storage.getDataPointer(localSectionIndex);
		int sectionChunkX = regionChunkX + LOCAL_SECTION_CHUNK_X[localSectionIndex];
		int sectionChunkY = regionChunkY + LOCAL_SECTION_CHUNK_Y[localSectionIndex];
		int sectionChunkZ = regionChunkZ + LOCAL_SECTION_CHUNK_Z[localSectionIndex];
		long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
		boolean useLocalIndex = preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer);
		int flags = useLocalIndex ? FLAG_USE_LOCAL_INDEX : 0;
		int offset = sectionIndex * INPUT_STRIDE_BYTES;
		metadataView.position(offset);
		metadataView.putFloat((regionChunkX << 4) + LOCAL_SECTION_CENTER_X[localSectionIndex]);
		metadataView.putFloat((regionChunkY << 4) + LOCAL_SECTION_CENTER_Y[localSectionIndex]);
		metadataView.putFloat((regionChunkZ << 4) + LOCAL_SECTION_CENTER_Z[localSectionIndex]);
		metadataView.putFloat(14.0f);
		metadataView.putInt(sectionChunkX);
		metadataView.putInt(sectionChunkY);
		metadataView.putInt(sectionChunkZ);
		metadataView.putInt(SectionRenderDataUnsafe.getSliceMask(dataPointer));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getBaseElement(dataPointer), "baseElement"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getBaseVertex(dataPointer), "baseVertex"));
		metadataView.putInt((int) facingList);
		metadataView.putInt((int) (facingList >>> Integer.SIZE));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 0), "vertexCount[0]"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 1), "vertexCount[1]"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 2), "vertexCount[2]"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 3), "vertexCount[3]"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 4), "vertexCount[4]"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 5), "vertexCount[5]"));
		metadataView.putInt(checkedInt(SectionRenderDataUnsafe.getVertexCount(dataPointer, 6), "vertexCount[6]"));
		metadataView.putInt(0);
		metadataView.putInt(0);
		metadataView.putInt(0);
		metadataView.putInt(flags);
		metadataView.putInt(localSectionIndex);
		return useLocalIndex;
	}

	private static byte[] snapshotSectionOrder(byte[] sectionsWithGeometry, int sectionCount) {
		byte[] snapshot = new byte[sectionCount];
		System.arraycopy(sectionsWithGeometry, 0, snapshot, 0, sectionCount);
		return snapshot;
	}

	private static byte[] buildStableSectionOrder(long[] sectionsWithGeometryMap, int sectionCount) {
		byte[] stableOrder = new byte[sectionCount];
		int writeIndex = 0;

		for (int wordIndex = 0; wordIndex < sectionsWithGeometryMap.length; wordIndex++) {
			long sectionBits = sectionsWithGeometryMap[wordIndex];
			while (sectionBits != 0L) {
				int bitIndex = Long.numberOfTrailingZeros(sectionBits);
				stableOrder[writeIndex++] = (byte) ((wordIndex << 6) + bitIndex);
				sectionBits &= sectionBits - 1L;
			}
		}

		if (writeIndex != sectionCount) {
			throw new IllegalStateException("Stable section order size does not match the geometry section count.");
		}

		return stableOrder;
	}

	private static short[] buildPackedIndexLookup(byte[] sectionOrderSnapshot) {
		short[] packedIndexByLocalSection = new short[MAX_REGION_SECTION_COUNT];
		Arrays.fill(packedIndexByLocalSection, (short) -1);

		for (int sectionIndex = 0; sectionIndex < sectionOrderSnapshot.length; sectionIndex++) {
			int localSectionIndex = Byte.toUnsignedInt(sectionOrderSnapshot[sectionIndex]);
			packedIndexByLocalSection[localSectionIndex] = (short) sectionIndex;
		}

		return packedIndexByLocalSection;
	}

	private static void ensureResidentCapacity(int requiredRegionSlots) {
		if (requiredRegionSlots <= regionSlotCapacity) {
			return;
		}

		int newCapacity = nextCapacity(requiredRegionSlots);
		GL45C.glNamedBufferData(sectionBufferHandle, sectionBufferBytes(newCapacity), GL15C.GL_DYNAMIC_DRAW);
		regionSlotCapacity = newCapacity;
		for (CachedRegionMetadata cachedMetadata : REGION_METADATA_CACHE.values()) {
			cachedMetadata.markDirty();
		}
	}

	private static void ensureSectionUploadCapacity(int requiredBytes) {
		if (sectionUploadView != null && sectionUploadView.capacity() >= requiredBytes) {
			return;
		}

		int newCapacity = nextCapacity(requiredBytes);
		ByteBuffer newUploadView = MemoryUtil.memAlloc(newCapacity).order(ByteOrder.nativeOrder());
		if (sectionUploadView != null) {
			MemoryUtil.memFree(sectionUploadView);
		}
		sectionUploadView = newUploadView;
	}

	private static long sectionBufferBytes(int regionSlots) {
		return (long) regionSlots * MAX_REGION_SECTION_COUNT * INPUT_STRIDE_BYTES;
	}

	private static long sectionBaseOffsetBytes(int regionSlot) {
		return (long) regionSlot * MAX_REGION_SECTION_COUNT * INPUT_STRIDE_BYTES;
	}

	private static int nextCapacity(int requiredCapacity) {
		int capacity = 1;
		while (capacity < requiredCapacity) {
			capacity <<= 1;
		}

		return capacity;
	}

	private static int checkedInt(long value, String label) {
		try {
			return Math.toIntExact(value);
		} catch (ArithmeticException exception) {
			throw new IllegalStateException("Section metadata field is out of 32-bit range: " + label, exception);
		}
	}

	static final class CachedRegionMetadata {
		private int storageVersion;
		private final boolean preferLocalIndices;
		private final boolean preserveSectionOrder;
		private final byte[] sectionOrderSnapshot;
		private final long[] geometryMapSnapshot;
		private final short[] packedIndexByLocalSection;
		private final byte[] templateBytes;
		private final boolean[] localIndexSections;
		private final ByteBuffer templateView;
		private int localIndexSectionCount;
		private final int regionSlot;
		private int regionSceneId = -1;
		private int[] packedSectionSceneIds = new int[0];
		private boolean dirty;
		private int dirtySectionStart;
		private int dirtySectionEndExclusive;

		private CachedRegionMetadata(
			int storageVersion,
			boolean preferLocalIndices,
			boolean preserveSectionOrder,
			byte[] sectionOrderSnapshot,
			long[] geometryMapSnapshot,
			short[] packedIndexByLocalSection,
			byte[] templateBytes,
			boolean[] localIndexSections,
			int localIndexSectionCount,
			int regionSlot,
			boolean dirty
		) {
			this.storageVersion = storageVersion;
			this.preferLocalIndices = preferLocalIndices;
			this.preserveSectionOrder = preserveSectionOrder;
			this.sectionOrderSnapshot = sectionOrderSnapshot;
			this.geometryMapSnapshot = geometryMapSnapshot;
			this.packedIndexByLocalSection = packedIndexByLocalSection;
			this.templateBytes = templateBytes;
			this.localIndexSections = localIndexSections;
			this.templateView = ByteBuffer.wrap(templateBytes).order(ByteOrder.nativeOrder());
			this.localIndexSectionCount = localIndexSectionCount;
			this.regionSlot = regionSlot;
			this.dirty = dirty;
			this.dirtySectionStart = dirty ? 0 : -1;
			this.dirtySectionEndExclusive = dirty ? sectionOrderSnapshot.length : -1;
		}

		int storageVersion() {
			return this.storageVersion;
		}

		int sectionCount() {
			return this.sectionOrderSnapshot.length;
		}

		byte[] sectionOrderSnapshot() {
			return this.sectionOrderSnapshot;
		}

		int sectionBaseIndex() {
			return this.regionSlot * MAX_REGION_SECTION_COUNT;
		}

		int regionSlot() {
			return this.regionSlot;
		}

		int regionSceneId() {
			return this.regionSceneId;
		}

		short[] packedIndexByLocalSection() {
			return this.packedIndexByLocalSection;
		}

		int[] packedSectionSceneIds() {
			return this.packedSectionSceneIds;
		}

		int sectionSceneId(int sectionIndex) {
			return this.packedSectionSceneIds[sectionIndex];
		}

		int localSectionIndex(int sectionIndex) {
			return Byte.toUnsignedInt(this.sectionOrderSnapshot[sectionIndex]);
		}

		int localIndexSectionCount() {
			return this.localIndexSectionCount;
		}

		int sharedIndexSectionCount() {
			return this.sectionOrderSnapshot.length - this.localIndexSectionCount;
		}

		int metadataBytes() {
			return this.templateBytes.length;
		}

		byte[] templateBytes() {
			return this.templateBytes;
		}

		void assignSceneIds(int sceneId, int[] sectionSceneIdsByLocalSection) {
			this.regionSceneId = sceneId;
			if (this.packedSectionSceneIds.length != this.sectionOrderSnapshot.length) {
				this.packedSectionSceneIds = new int[this.sectionOrderSnapshot.length];
			}

			for (int sectionIndex = 0; sectionIndex < this.sectionOrderSnapshot.length; sectionIndex++) {
				int localSectionIndex = Byte.toUnsignedInt(this.sectionOrderSnapshot[sectionIndex]);
				int sectionSceneId = sectionSceneIdsByLocalSection[localSectionIndex];
				this.packedSectionSceneIds[sectionIndex] = sectionSceneId;
				int baseOffset = sectionIndex * INPUT_STRIDE_BYTES;
				this.templateView.putInt(
					baseOffset + (REGION_INFO_REGION_SCENE_ID_OFFSET * Integer.BYTES),
					sceneId
				);
				this.templateView.putInt(
					baseOffset + (REGION_INFO_SECTION_SCENE_ID_OFFSET * Integer.BYTES),
					sectionSceneId
				);
				this.templateView.putInt(
					baseOffset + (REGION_INFO_LOCAL_SECTION_INDEX_OFFSET * Integer.BYTES),
					localSectionIndex
				);
			}

			GpuSceneDataStore.uploadDirtySections(this);
		}

		int dirtySectionStart() {
			return Math.max(this.dirtySectionStart, 0);
		}

		int dirtySectionEndExclusive() {
			return this.dirty ? this.dirtySectionEndExclusive : 0;
		}

		void writeSceneRecord(ByteBuffer destination, int destinationOffsetBytes, int sectionIndex) {
			int metadataOffsetBytes = sectionIndex * INPUT_STRIDE_BYTES;
			destination.position(destinationOffsetBytes);
			destination.putInt(this.regionSceneId);
			destination.putInt(this.packedSectionSceneIds[sectionIndex]);
			destination.putInt(this.templateView.getInt(metadataOffsetBytes + (REGION_INFO_LOCAL_SECTION_INDEX_OFFSET * Integer.BYTES)));
			destination.putInt(this.templateView.getInt(metadataOffsetBytes + (REGION_INFO_FLAGS_OFFSET * Integer.BYTES)));
			destination.putInt(this.templateView.getInt(metadataOffsetBytes + (SLICE_MASK_OFFSET * Integer.BYTES)));
			destination.putInt(this.templateView.getInt(metadataOffsetBytes + (SECTION_CHUNK_X_OFFSET * Integer.BYTES)));
			destination.putInt(this.templateView.getInt(metadataOffsetBytes + (SECTION_CHUNK_Y_OFFSET * Integer.BYTES)));
			destination.putInt(this.templateView.getInt(metadataOffsetBytes + (SECTION_CHUNK_Z_OFFSET * Integer.BYTES)));
			destination.putFloat(this.templateView.getFloat(metadataOffsetBytes + (CENTER_X_OFFSET * Float.BYTES)));
			destination.putFloat(this.templateView.getFloat(metadataOffsetBytes + (CENTER_Y_OFFSET * Float.BYTES)));
			destination.putFloat(this.templateView.getFloat(metadataOffsetBytes + (CENTER_Z_OFFSET * Float.BYTES)));
			destination.putFloat(this.templateView.getFloat(metadataOffsetBytes + (BOUNDING_RADIUS_OFFSET * Float.BYTES)));
			destination.putFloat(1.0f);
			destination.putFloat(0.0f);
			destination.putFloat(0.0f);
			destination.putFloat(0.0f);
		}

		void writeGeometryRecord(ByteBuffer destination, int destinationOffsetBytes, int sectionIndex) {
			int metadataOffsetBytes = sectionIndex * INPUT_STRIDE_BYTES;
			destination.position(destinationOffsetBytes);
			for (int wordIndex = 0; wordIndex < INPUT_STRIDE_BYTES / Integer.BYTES; wordIndex++) {
				destination.putInt(this.templateView.getInt(metadataOffsetBytes + (wordIndex * Integer.BYTES)));
			}
		}

		boolean isDirty() {
			return this.dirty;
		}

		void markDirty() {
			this.dirty = true;
			this.dirtySectionStart = 0;
			this.dirtySectionEndExclusive = this.sectionOrderSnapshot.length;
		}

		void markUploaded() {
			this.dirty = false;
			this.dirtySectionStart = -1;
			this.dirtySectionEndExclusive = -1;
		}

		int dirtyUploadOffsetBytes() {
			return Math.max(this.dirtySectionStart, 0) * INPUT_STRIDE_BYTES;
		}

		int dirtyUploadLengthBytes() {
			if (!this.dirty || this.dirtySectionStart < 0 || this.dirtySectionEndExclusive <= this.dirtySectionStart) {
				return 0;
			}

			return (this.dirtySectionEndExclusive - this.dirtySectionStart) * INPUT_STRIDE_BYTES;
		}

		void markDirtyRange(int firstSectionIndex, int endExclusiveSectionIndex) {
			if (firstSectionIndex < 0 || endExclusiveSectionIndex <= firstSectionIndex) {
				return;
			}

			if (!this.dirty) {
				this.dirty = true;
				this.dirtySectionStart = firstSectionIndex;
				this.dirtySectionEndExclusive = endExclusiveSectionIndex;
				return;
			}

			this.dirtySectionStart = Math.min(this.dirtySectionStart, firstSectionIndex);
			this.dirtySectionEndExclusive = Math.max(this.dirtySectionEndExclusive, endExclusiveSectionIndex);
		}

		boolean matchesLayout(
			boolean currentPreferLocalIndices,
			boolean currentPreserveSectionOrder,
			byte[] currentSectionOrder,
			long[] currentGeometryMap,
			int currentSectionCount
		) {
			if (
				this.preferLocalIndices != currentPreferLocalIndices ||
				this.preserveSectionOrder != currentPreserveSectionOrder ||
				this.sectionOrderSnapshot.length != currentSectionCount
			) {
				return false;
			}

			if (!this.preserveSectionOrder) {
				if (this.geometryMapSnapshot == null || this.geometryMapSnapshot.length != currentGeometryMap.length) {
					return false;
				}

				for (int wordIndex = 0; wordIndex < currentGeometryMap.length; wordIndex++) {
					if (this.geometryMapSnapshot[wordIndex] != currentGeometryMap[wordIndex]) {
						return false;
					}
				}

				return true;
			}

			for (int sectionIndex = 0; sectionIndex < currentSectionCount; sectionIndex++) {
				if (this.sectionOrderSnapshot[sectionIndex] != currentSectionOrder[sectionIndex]) {
					return false;
				}
			}

			return true;
		}

		boolean refreshDirtySections(
			RegionBatchInput regionInput,
			int currentStorageVersion,
			boolean currentPreferLocalIndices,
			long[] dirtySectionWords
		) {
			RenderRegion region = regionInput.region();
			SectionRenderDataStorage storage = regionInput.storage();
			int regionChunkX = region.getChunkX();
			int regionChunkY = region.getChunkY();
			int regionChunkZ = region.getChunkZ();
			ByteBuffer metadataView = this.templateView.duplicate().order(ByteOrder.nativeOrder());
			boolean updated = false;
			int firstDirtySectionIndex = Integer.MAX_VALUE;
			int lastDirtySectionIndex = -1;

			for (int wordIndex = 0; wordIndex < dirtySectionWords.length; wordIndex++) {
				long dirtyWord = dirtySectionWords[wordIndex];
				while (dirtyWord != 0L) {
					int bitIndex = Long.numberOfTrailingZeros(dirtyWord);
					int localSectionIndex = (wordIndex << 6) + bitIndex;
					dirtyWord &= dirtyWord - 1L;
					if (localSectionIndex >= this.packedIndexByLocalSection.length) {
						continue;
					}

					int sectionIndex = this.packedIndexByLocalSection[localSectionIndex];
					if (sectionIndex < 0) {
						continue;
					}

					boolean useLocalIndex = writeSectionMetadata(
						metadataView,
						sectionIndex,
						localSectionIndex,
						storage,
						regionChunkX,
						regionChunkY,
						regionChunkZ,
						currentPreferLocalIndices
					);
					if (this.localIndexSections[sectionIndex] != useLocalIndex) {
						this.localIndexSections[sectionIndex] = useLocalIndex;
						this.localIndexSectionCount += useLocalIndex ? 1 : -1;
					}
					updated = true;
					firstDirtySectionIndex = Math.min(firstDirtySectionIndex, sectionIndex);
					lastDirtySectionIndex = Math.max(lastDirtySectionIndex, sectionIndex);
				}
			}

			this.storageVersion = currentStorageVersion;
			if (updated) {
				this.markDirtyRange(firstDirtySectionIndex, lastDirtySectionIndex + 1);
			}
			return true;
		}
	}
}
