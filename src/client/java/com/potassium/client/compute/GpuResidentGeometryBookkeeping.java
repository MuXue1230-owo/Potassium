package com.potassium.client.compute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;

public final class GpuResidentGeometryBookkeeping {
	private static final Map<SectionRenderDataStorage, ResidentRegionRecord> RECORDS = new WeakHashMap<>();
	private static final Map<GpuResidentSectionMetadataStore.CachedRegionMetadata, PendingUpload> PENDING_UPLOADS =
		new IdentityHashMap<>();
	private static ResidentBatchSnapshot opaqueSnapshot = ResidentBatchSnapshot.empty();
	private static ResidentBatchSnapshot translucentSnapshot = ResidentBatchSnapshot.empty();
	private static int snapshotRecordCount = -1;
	private static boolean residentSnapshotsDirty = true;
	private static long nextSnapshotVersion = 1L;
	private static long nextGeneration = 1L;
	private static int nextSceneId = 1;
	private static int nextSectionId = 1;
	private static int nextGeometrySourceId = 1;
	private static int nextCommandBase = 0;

	private GpuResidentGeometryBookkeeping() {
	}

	static synchronized void record(
		SectionRenderDataStorage storage,
		RenderRegion region,
		boolean translucentPass,
		GpuResidentSectionMetadataStore.CachedRegionMetadata metadata
	) {
		ResidentRegionRecord existingRecord = RECORDS.get(storage);
		int geometrySourceId = existingRecord != null ? existingRecord.geometrySourceId() : nextGeometrySourceId++;
		int[] sectionSceneIdsByLocalSection = existingRecord != null
			? existingRecord.sectionSceneIdsByLocalSection()
			: new int[GpuResidentSectionMetadataStore.MAX_REGION_SECTION_COUNT];
		byte[] sectionOrderSnapshot = metadata.sectionOrderSnapshot();
		for (int sectionIndex = 0; sectionIndex < sectionOrderSnapshot.length; sectionIndex++) {
			int localSectionIndex = Byte.toUnsignedInt(sectionOrderSnapshot[sectionIndex]);
			if (sectionSceneIdsByLocalSection[localSectionIndex] == 0) {
				sectionSceneIdsByLocalSection[localSectionIndex] = nextSectionId++;
			}
		}

		int sceneId = existingRecord != null ? existingRecord.sceneId() : nextSceneId++;
		int maxCommandCount = Math.max(metadata.sectionCount() * 7, 1);
		int commandBase;
		if (existingRecord != null && existingRecord.maxCommandCount() >= maxCommandCount) {
			commandBase = existingRecord.commandBase();
		} else {
			commandBase = nextCommandBase;
			nextCommandBase = Math.addExact(nextCommandBase, maxCommandCount);
		}
		long[] sectionPresenceBits = buildSectionPresenceBits(metadata.sectionOrderSnapshot());
		boolean fullSync =
			existingRecord == null ||
			existingRecord.regionSlot() != metadata.regionSlot() ||
			!Arrays.equals(existingRecord.sectionPresenceBits(), sectionPresenceBits);
		metadata.assignSceneIds(sceneId, sectionSceneIdsByLocalSection);
		GpuResidentRegionStore.ResidentRegionDescriptor regionDescriptor = GpuResidentRegionStore.createDescriptor(
			sceneId,
			geometrySourceId,
			region,
			translucentPass,
			metadata.regionSlot(),
			metadata.sectionBaseIndex(),
			metadata.sectionCount(),
			metadata.localIndexSectionCount(),
			metadata.sharedIndexSectionCount(),
			commandBase,
			maxCommandCount
		);
		GpuResidentRegionStore.syncCpuMirror(regionDescriptor);
		GpuResidentGeometryStore.syncCpuMirror(metadata, fullSync);
		queuePendingUpload(metadata, fullSync, geometrySourceId, regionDescriptor);
		RECORDS.put(
			storage,
			new ResidentRegionRecord(
				geometrySourceId,
				sceneId,
				nextGeneration++,
				region,
				translucentPass,
				metadata.regionSlot(),
				metadata.sectionBaseIndex(),
				metadata.sectionCount(),
				metadata.localIndexSectionCount(),
				metadata.sharedIndexSectionCount(),
				commandBase,
				maxCommandCount,
				metadata.metadataBytes(),
				sectionSceneIdsByLocalSection,
				sectionPresenceBits
			)
		);
		invalidateResidentSnapshots();
	}

	public static synchronized ResidentBatchSnapshot snapshotResidentRegions(boolean translucentPass) {
		int currentRecordCount = RECORDS.size();
		if (residentSnapshotsDirty || currentRecordCount != snapshotRecordCount) {
			rebuildResidentSnapshots(currentRecordCount);
		}

		return translucentPass ? translucentSnapshot : opaqueSnapshot;
	}

	public static synchronized void flushPendingUploads() {
		if (PENDING_UPLOADS.isEmpty()) {
			return;
		}

		for (Map.Entry<GpuResidentSectionMetadataStore.CachedRegionMetadata, PendingUpload> entry : PENDING_UPLOADS.entrySet()) {
			GpuResidentSectionMetadataStore.CachedRegionMetadata metadata = entry.getKey();
			PendingUpload pendingUpload = entry.getValue();
			GpuResidentRegionStore.flushPendingUpload(pendingUpload.regionDescriptor());
			GpuResidentGeometryStore.flushPendingUpload(metadata, pendingUpload.fullSync());
			GpuGeometryIndirectionStore.flushPendingUpload(metadata, pendingUpload.geometrySourceId());
			GpuSceneDataStore.flushPendingUpload(metadata);
			metadata.markUploaded();
		}

		PENDING_UPLOADS.clear();
	}

	public static synchronized Snapshot snapshot() {
		int residentRegionCount = 0;
		int residentSectionCount = 0;
		int residentLocalIndexSectionCount = 0;
		int residentSharedIndexSectionCount = 0;
		int residentMetadataBytes = 0;
		int maxSceneId = 0;
		int maxSectionId = 0;

		for (ResidentRegionRecord record : RECORDS.values()) {
			residentRegionCount++;
			residentSectionCount += record.sectionCount();
			residentLocalIndexSectionCount += record.localIndexSectionCount();
			residentSharedIndexSectionCount += record.sharedIndexSectionCount();
			residentMetadataBytes += record.metadataBytes();
			maxSceneId = Math.max(maxSceneId, record.sceneId());
			maxSectionId = Math.max(maxSectionId, record.maxSectionSceneId());
		}

		return new Snapshot(
			residentRegionCount,
			residentSectionCount,
			residentLocalIndexSectionCount,
			residentSharedIndexSectionCount,
			residentMetadataBytes,
			maxSceneId,
			maxSectionId
		);
	}

	static synchronized void reset() {
		RECORDS.clear();
		nextSnapshotVersion = 1L;
		nextGeneration = 1L;
		nextSceneId = 1;
		nextSectionId = 1;
		nextGeometrySourceId = 1;
		nextCommandBase = 0;
		PENDING_UPLOADS.clear();
		opaqueSnapshot = ResidentBatchSnapshot.empty();
		translucentSnapshot = ResidentBatchSnapshot.empty();
		snapshotRecordCount = 0;
		residentSnapshotsDirty = false;
	}

	private static void queuePendingUpload(
		GpuResidentSectionMetadataStore.CachedRegionMetadata metadata,
		boolean fullSync,
		int geometrySourceId,
		GpuResidentRegionStore.ResidentRegionDescriptor regionDescriptor
	) {
		PendingUpload existing = PENDING_UPLOADS.get(metadata);
		if (existing == null) {
			PENDING_UPLOADS.put(metadata, new PendingUpload(fullSync, geometrySourceId, regionDescriptor));
			return;
		}

		PENDING_UPLOADS.put(
			metadata,
			new PendingUpload(fullSync || existing.fullSync(), geometrySourceId, regionDescriptor)
		);
	}

	private static void invalidateResidentSnapshots() {
		residentSnapshotsDirty = true;
	}

	private static void rebuildResidentSnapshots(int currentRecordCount) {
		ResidentBatchInput[] opaqueEntries = new ResidentBatchInput[currentRecordCount];
		ResidentBatchInput[] translucentEntries = new ResidentBatchInput[currentRecordCount];
		int[] opaqueSceneIds = new int[currentRecordCount];
		int[] translucentSceneIds = new int[currentRecordCount];
		int opaqueCount = 0;
		int translucentCount = 0;

		for (Map.Entry<SectionRenderDataStorage, ResidentRegionRecord> entry : RECORDS.entrySet()) {
			ResidentRegionRecord record = entry.getValue();
			RenderRegion region = record.region();
			if (region == null) {
				continue;
			}

			ResidentBatchInput residentBatchInput = new ResidentBatchInput(
				region,
				entry.getKey(),
				record.sceneId(),
				record.sectionCount(),
				record.commandBase(),
				record.maxCommandCount()
			);
			if (record.translucentPass()) {
				translucentEntries[translucentCount++] = residentBatchInput;
				translucentSceneIds[translucentCount - 1] = record.sceneId();
			} else {
				opaqueEntries[opaqueCount++] = residentBatchInput;
				opaqueSceneIds[opaqueCount - 1] = record.sceneId();
			}
		}

		long snapshotVersion = nextSnapshotVersion++;
		opaqueSnapshot = ResidentBatchSnapshot.of(opaqueEntries, opaqueSceneIds, opaqueCount, snapshotVersion);
		translucentSnapshot = ResidentBatchSnapshot.of(
			translucentEntries,
			translucentSceneIds,
			translucentCount,
			snapshotVersion
		);
		snapshotRecordCount = RECORDS.size();
		residentSnapshotsDirty = false;
	}

	private record ResidentRegionRecord(
		int geometrySourceId,
		int sceneId,
		long generation,
		RenderRegion region,
		boolean translucentPass,
		int regionSlot,
		int sectionBaseIndex,
		int sectionCount,
		int localIndexSectionCount,
		int sharedIndexSectionCount,
		int commandBase,
		int maxCommandCount,
		int metadataBytes,
		int[] sectionSceneIdsByLocalSection,
		long[] sectionPresenceBits
	) {
		private int maxSectionSceneId() {
			int maxSectionSceneId = 0;
			for (int sectionSceneId : this.sectionSceneIdsByLocalSection) {
				maxSectionSceneId = Math.max(maxSectionSceneId, sectionSceneId);
			}
			return maxSectionSceneId;
		}
	}

	private record PendingUpload(
		boolean fullSync,
		int geometrySourceId,
		GpuResidentRegionStore.ResidentRegionDescriptor regionDescriptor
	) {
	}

	public record ResidentBatchInput(
		RenderRegion region,
		SectionRenderDataStorage storage,
		int regionSceneId,
		int sectionCount,
		int commandBase,
		int maxCommandCount
	) {
	}

	public static final class ResidentBatchSnapshot {
		private static final ResidentBatchSnapshot EMPTY =
			new ResidentBatchSnapshot(new ResidentBatchInput[0], new int[0], 0L);

		private final ResidentBatchInput[] entries;
		private final int[] regionSceneIds;
		private final long version;

		private ResidentBatchSnapshot(ResidentBatchInput[] entries, int[] regionSceneIds, long version) {
			this.entries = entries;
			this.regionSceneIds = regionSceneIds;
			this.version = version;
		}

		private static ResidentBatchSnapshot empty() {
			return EMPTY;
		}

		private static ResidentBatchSnapshot of(
			ResidentBatchInput[] entries,
			int[] regionSceneIds,
			int count,
			long version
		) {
			if (count == 0) {
				return EMPTY;
			}

			return new ResidentBatchSnapshot(Arrays.copyOf(entries, count), Arrays.copyOf(regionSceneIds, count), version);
		}

		public int size() {
			return this.entries.length;
		}

		public ResidentBatchInput get(int index) {
			return this.entries[index];
		}

		public int regionSceneId(int index) {
			return this.regionSceneIds[index];
		}

		public long version() {
			return this.version;
		}
	}

	private static long[] buildSectionPresenceBits(byte[] sectionOrderSnapshot) {
		long[] sectionPresenceBits = new long[(GpuResidentSectionMetadataStore.MAX_REGION_SECTION_COUNT + Long.SIZE - 1) / Long.SIZE];
		for (byte sectionIndexByte : sectionOrderSnapshot) {
			int localSectionIndex = Byte.toUnsignedInt(sectionIndexByte);
			sectionPresenceBits[localSectionIndex >>> 6] |= 1L << (localSectionIndex & 63);
		}
		return sectionPresenceBits;
	}

	public record Snapshot(
		int residentRegionCount,
		int residentSectionCount,
		int residentLocalIndexSectionCount,
		int residentSharedIndexSectionCount,
		int residentMetadataBytes,
		int maxSceneId,
		int maxSectionId
	) {
	}
}
