package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GpuSceneDataStore {
	static final int SCENE_STRIDE_BYTES = Float.BYTES * 4;
	private static final int VISIBILITY_OFFSET_BYTES = 0;
	private static final int LOD_SCALE_OFFSET_BYTES = VISIBILITY_OFFSET_BYTES + Float.BYTES;
	private static final int BUFFER_COUNT = configuredBufferCount();

	private static final int[] bufferHandles = new int[BUFFER_COUNT];
	private static int sceneCapacity;
	private static int activeBufferIndex = -1;
	private static int boundStorageBinding = -1;
	private static ByteBuffer uploadRecordView;
	private static ByteBuffer uploadVisibilityView;
	private static ByteBuffer cpuMirrorView;

	private GpuSceneDataStore() {
	}

	public static void initialize() {
		for (int bufferIndex = 0; bufferIndex < bufferHandles.length; bufferIndex++) {
			if (bufferHandles[bufferIndex] == 0) {
				bufferHandles[bufferIndex] = GL45C.glCreateBuffers();
			}
		}
	}

	public static void shutdown() {
		for (int bufferIndex = 0; bufferIndex < bufferHandles.length; bufferIndex++) {
			if (bufferHandles[bufferIndex] != 0) {
				GL15C.glDeleteBuffers(bufferHandles[bufferIndex]);
				bufferHandles[bufferIndex] = 0;
			}
		}

		sceneCapacity = 0;
		activeBufferIndex = -1;
		boundStorageBinding = -1;
		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
			uploadRecordView = null;
		}
		if (uploadVisibilityView != null) {
			MemoryUtil.memFree(uploadVisibilityView);
			uploadVisibilityView = null;
		}
		if (cpuMirrorView != null) {
			MemoryUtil.memFree(cpuMirrorView);
			cpuMirrorView = null;
		}
	}

	public static void bindAsStorage(int binding) {
		initialize();
		activeBufferIndex = (activeBufferIndex + 1) % bufferHandles.length;
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, activeBufferHandle());
		boundStorageBinding = binding;
	}

	public static void unbindAsStorage(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0);
		if (boundStorageBinding == binding) {
			boundStorageBinding = -1;
		}
	}

	static void flushPendingUpload(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata) {
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

			ensureSceneCapacity(sectionSceneId + 1);
			ByteBuffer uploadView = uploadRecordView.duplicate().order(ByteOrder.nativeOrder());
			uploadView.clear();
			uploadView.limit(SCENE_STRIDE_BYTES);
			metadata.writeSceneRecord(uploadView, 0, sectionIndex);
			uploadView.flip();
			updateCpuMirrorRecord(sectionSceneId, uploadView);
			for (int bufferHandle : bufferHandles) {
				GL45C.glNamedBufferSubData(bufferHandle, (long) sectionSceneId * SCENE_STRIDE_BYTES, uploadView.rewind());
			}
		}
	}

	public static void updateSectionVisibility(
		SectionVisibilityCompute.SectionSceneIds sceneIds,
		RenderRegion region,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		boolean translucentPass
	) {
		if (sceneIds == null || region == null || renderList == null) {
			return;
		}

		ByteIterator iterator = renderList.sectionsWithGeometryIterator(translucentPass);
		if (iterator == null) {
			return;
		}

		initialize();
		ensureVisibilityUploadCapacity();
		ByteBuffer visibilityView = uploadVisibilityView.duplicate().order(ByteOrder.nativeOrder());
		GpuResidentGeometryStore.SectionRecord sectionRecord = GpuResidentGeometryStore.createScratchRecord();

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			if (!GpuResidentGeometryStore.loadSection(sceneIds.regionSlot(), localSectionIndex, sectionRecord)) {
				continue;
			}

			RenderSection section = region.getSection(localSectionIndex);
			if (section == null) {
				continue;
			}

			int sceneId = sectionRecord.sectionSceneId();
			ensureSceneCapacity(sceneId + 1);
			visibilityView.clear();
			visibilityView.putFloat(section.getCurrentVisibility());
			visibilityView.putFloat(GpuResidentGeometryStore.computeLodScale(sectionRecord, cameraTransform, translucentPass));
			visibilityView.flip();
			GL45C.glNamedBufferSubData(
				activeBufferHandle(),
				((long) sceneId * SCENE_STRIDE_BYTES) + VISIBILITY_OFFSET_BYTES,
				visibilityView
			);
		}
	}

	private static void ensureUploadCapacity() {
		if (uploadRecordView != null && uploadRecordView.capacity() >= SCENE_STRIDE_BYTES) {
			return;
		}

		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
		}
		uploadRecordView = MemoryUtil.memAlloc(SCENE_STRIDE_BYTES).order(ByteOrder.nativeOrder());
	}

	private static void ensureVisibilityUploadCapacity() {
		if (uploadVisibilityView != null && uploadVisibilityView.capacity() >= (Float.BYTES * 2)) {
			return;
		}

		if (uploadVisibilityView != null) {
			MemoryUtil.memFree(uploadVisibilityView);
		}
		uploadVisibilityView = MemoryUtil.memAlloc(Float.BYTES * 2).order(ByteOrder.nativeOrder());
	}

	private static void ensureSceneCapacity(int requiredCapacity) {
		if (requiredCapacity <= sceneCapacity) {
			return;
		}
		if (requiredCapacity <= 0) {
			throw new IllegalStateException("Scene data store received a non-positive capacity request.");
		}

		int newCapacity = 1;
		while (newCapacity < requiredCapacity) {
			if (newCapacity > (Integer.MAX_VALUE >>> 1)) {
				throw new IllegalStateException("Scene data store capacity request is unreasonably large: " + requiredCapacity);
			}
			newCapacity <<= 1;
		}

		int[] previousHandles = bufferHandles.clone();
		for (int bufferIndex = 0; bufferIndex < bufferHandles.length; bufferIndex++) {
			int newHandle = GL45C.glCreateBuffers();
			GL45C.glNamedBufferData(newHandle, (long) newCapacity * SCENE_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
			if (previousHandles[bufferIndex] != 0 && sceneCapacity > 0) {
				GL45C.glCopyNamedBufferSubData(
					previousHandles[bufferIndex],
					newHandle,
					0L,
					0L,
					(long) sceneCapacity * SCENE_STRIDE_BYTES
				);
			}
			bufferHandles[bufferIndex] = newHandle;
		}

		ByteBuffer previousCpuMirror = cpuMirrorView;
		ByteBuffer newCpuMirror = MemoryUtil.memCalloc(Math.toIntExact((long) newCapacity * SCENE_STRIDE_BYTES)).order(ByteOrder.nativeOrder());
		if (previousCpuMirror != null && sceneCapacity > 0) {
			ByteBuffer sourceView = previousCpuMirror.duplicate().order(ByteOrder.nativeOrder());
			sourceView.clear();
			sourceView.limit(Math.toIntExact((long) sceneCapacity * SCENE_STRIDE_BYTES));
			newCpuMirror.put(sourceView);
			newCpuMirror.clear();
		}
		cpuMirrorView = newCpuMirror;
		sceneCapacity = newCapacity;
		if (boundStorageBinding >= 0) {
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, boundStorageBinding, activeBufferHandle());
		}

		for (int previousHandle : previousHandles) {
			if (previousHandle != 0) {
				GL15C.glDeleteBuffers(previousHandle);
			}
		}
		if (previousCpuMirror != null) {
			MemoryUtil.memFree(previousCpuMirror);
		}
	}

	private static int activeBufferHandle() {
		if (activeBufferIndex < 0 || activeBufferIndex >= bufferHandles.length) {
			activeBufferIndex = 0;
		}
		return bufferHandles[activeBufferIndex];
	}

	private static void updateCpuMirrorRecord(int sceneId, ByteBuffer uploadView) {
		if (cpuMirrorView == null) {
			return;
		}

		ByteBuffer sourceView = uploadView.duplicate().order(ByteOrder.nativeOrder());
		sourceView.position(0);
		sourceView.limit(SCENE_STRIDE_BYTES);
		ByteBuffer mirrorView = cpuMirrorView.duplicate().order(ByteOrder.nativeOrder());
		int offsetBytes = sceneId * SCENE_STRIDE_BYTES;
		mirrorView.position(offsetBytes);
		mirrorView.limit(offsetBytes + SCENE_STRIDE_BYTES);
		mirrorView.put(sourceView);
	}

	private static int configuredBufferCount() {
		int configuredCount = Integer.getInteger("potassium.graphicsSceneBuffers", 3);
		return Math.max(2, Math.min(configuredCount, 3));
	}
}
