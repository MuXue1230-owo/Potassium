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
	private static long nextGeneration = 1L;
	private static int nextSceneId = 1;
	private static int nextSectionId = 1;
	private static int nextGeometrySourceId = 1;

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
		long[] sectionPresenceBits = buildSectionPresenceBits(metadata.sectionOrderSnapshot());
		boolean fullSync =
			existingRecord == null ||
			existingRecord.regionSlot() != metadata.regionSlot() ||
			!Arrays.equals(existingRecord.sectionPresenceBits(), sectionPresenceBits);
		metadata.assignSceneIds(sceneId, sectionSceneIdsByLocalSection);
		GpuResidentGeometryStore.syncCpuMirror(metadata, fullSync);
		queuePendingUpload(metadata, fullSync, geometrySourceId);
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
				metadata.metadataBytes(),
				sectionSceneIdsByLocalSection,
				sectionPresenceBits
			)
		);
	}

	public static synchronized List<ResidentBatchInput> snapshotResidentRegions(boolean translucentPass) {
		List<ResidentBatchInput> residentBatchInputs = new ArrayList<>(RECORDS.size());
		for (Map.Entry<SectionRenderDataStorage, ResidentRegionRecord> entry : RECORDS.entrySet()) {
			ResidentRegionRecord record = entry.getValue();
			if (record.translucentPass() != translucentPass || record.region() == null) {
				continue;
			}

			residentBatchInputs.add(
				new ResidentBatchInput(
					record.region(),
					entry.getKey(),
					record.sectionCount(),
					record.sectionCount() * 7
				)
			);
		}
		return residentBatchInputs;
	}

	public static synchronized void flushPendingUploads() {
		if (PENDING_UPLOADS.isEmpty()) {
			return;
		}

		for (Map.Entry<GpuResidentSectionMetadataStore.CachedRegionMetadata, PendingUpload> entry : PENDING_UPLOADS.entrySet()) {
			GpuResidentSectionMetadataStore.CachedRegionMetadata metadata = entry.getKey();
			PendingUpload pendingUpload = entry.getValue();
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
		nextGeneration = 1L;
		nextSceneId = 1;
		nextSectionId = 1;
		nextGeometrySourceId = 1;
		PENDING_UPLOADS.clear();
	}

	private static void queuePendingUpload(
		GpuResidentSectionMetadataStore.CachedRegionMetadata metadata,
		boolean fullSync,
		int geometrySourceId
	) {
		PendingUpload existing = PENDING_UPLOADS.get(metadata);
		if (existing == null) {
			PENDING_UPLOADS.put(metadata, new PendingUpload(fullSync, geometrySourceId));
			return;
		}

		if (fullSync && !existing.fullSync()) {
			PENDING_UPLOADS.put(metadata, new PendingUpload(true, geometrySourceId));
		}
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

	private record PendingUpload(boolean fullSync, int geometrySourceId) {
	}

	public record ResidentBatchInput(
		RenderRegion region,
		SectionRenderDataStorage storage,
		int sectionCount,
		int maxCommandCount
	) {
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
