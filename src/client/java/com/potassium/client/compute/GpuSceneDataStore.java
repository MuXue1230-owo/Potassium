package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GpuSceneDataStore {
	static final int SCENE_STRIDE_BYTES = Integer.BYTES * 8 + Float.BYTES * 8;
	private static final int VISIBILITY_OFFSET_BYTES = (Integer.BYTES * 8) + (Float.BYTES * 4);

	private static int bufferHandle;
	private static int sceneCapacity;
	private static ByteBuffer uploadRecordView;
	private static ByteBuffer uploadVisibilityView;

	private GpuSceneDataStore() {
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

		sceneCapacity = 0;
		if (uploadRecordView != null) {
			MemoryUtil.memFree(uploadRecordView);
			uploadRecordView = null;
		}
		if (uploadVisibilityView != null) {
			MemoryUtil.memFree(uploadVisibilityView);
			uploadVisibilityView = null;
		}
	}

	public static void bindAsStorage(int binding) {
		initialize();
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, bufferHandle);
	}

	static void uploadDirtySections(GpuResidentSectionMetadataStore.CachedRegionMetadata metadata) {
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
			GL45C.glNamedBufferSubData(bufferHandle, (long) sectionSceneId * SCENE_STRIDE_BYTES, uploadView);
		}
	}

	public static void updateSectionVisibility(
		SectionVisibilityCompute.SectionSceneIds sceneIds,
		RenderRegion region,
		ChunkRenderList renderList,
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

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			int sceneId = sceneIds.sectionSceneId(localSectionIndex);
			if (sceneId <= 0) {
				continue;
			}

			RenderSection section = region.getSection(localSectionIndex);
			if (section == null) {
				continue;
			}

			ensureSceneCapacity(sceneId + 1);
			visibilityView.clear();
			visibilityView.putFloat(section.getCurrentVisibility());
			visibilityView.flip();
			GL45C.glNamedBufferSubData(
				bufferHandle,
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
		if (uploadVisibilityView != null && uploadVisibilityView.capacity() >= Float.BYTES) {
			return;
		}

		if (uploadVisibilityView != null) {
			MemoryUtil.memFree(uploadVisibilityView);
		}
		uploadVisibilityView = MemoryUtil.memAlloc(Float.BYTES).order(ByteOrder.nativeOrder());
	}

	private static void ensureSceneCapacity(int requiredCapacity) {
		if (requiredCapacity <= sceneCapacity) {
			return;
		}

		int newCapacity = 1;
		while (newCapacity < requiredCapacity) {
			newCapacity <<= 1;
		}

		GL45C.glNamedBufferData(bufferHandle, (long) newCapacity * SCENE_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
		sceneCapacity = newCapacity;
	}
}
