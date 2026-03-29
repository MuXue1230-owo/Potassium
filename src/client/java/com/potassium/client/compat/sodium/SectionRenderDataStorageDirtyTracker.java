package com.potassium.client.compat.sodium;

public interface SectionRenderDataStorageDirtyTracker {
	boolean potassium$isFullMetadataDirty();

	boolean potassium$hasDirtySections();

	void potassium$copyDirtySectionBits(long[] destination);

	void potassium$clearMetadataDirty();
}
