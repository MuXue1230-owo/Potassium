package com.potassium.client.compute;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;

public final class GpuResidentGeometryBookkeeping {
	private static final Map<SectionRenderDataStorage, ResidentRegionRecord> RECORDS = new WeakHashMap<>();
	private static long nextGeneration = 1L;
	private static int nextSceneId = 1;
	private static int nextSectionId = 1;

	private GpuResidentGeometryBookkeeping() {
	}

	static synchronized void record(
		SectionRenderDataStorage storage,
		GpuResidentSectionMetadataStore.CachedRegionMetadata metadata
	) {
		ResidentRegionRecord existingRecord = RECORDS.get(storage);
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
		GpuResidentGeometryStore.syncRegion(metadata, fullSync);
		RECORDS.put(
			storage,
			new ResidentRegionRecord(
				sceneId,
				nextGeneration++,
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
	}

	private record ResidentRegionRecord(
		int sceneId,
		long generation,
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
