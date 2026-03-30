package com.potassium.client.compute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class GpuResidentRegionStore {
	static final int RECORD_STRIDE_BYTES = (Integer.BYTES * 12) + (Float.BYTES * 4);
	private static final int FLAG_TRANSLUCENT_PASS = 1;
	private static final float REGION_HALF_WIDTH_BLOCKS = RenderRegion.REGION_WIDTH * 8.0f;
	private static final float REGION_HALF_HEIGHT_BLOCKS = RenderRegion.REGION_HEIGHT * 8.0f;
	private static final float REGION_HALF_LENGTH_BLOCKS = RenderRegion.REGION_LENGTH * 8.0f;
	private static final float REGION_BOUNDING_RADIUS = (float) Math.sqrt(
		(REGION_HALF_WIDTH_BLOCKS * REGION_HALF_WIDTH_BLOCKS) +
		(REGION_HALF_HEIGHT_BLOCKS * REGION_HALF_HEIGHT_BLOCKS) +
		(REGION_HALF_LENGTH_BLOCKS * REGION_HALF_LENGTH_BLOCKS)
	);

	private static int bufferHandle;
	private static int recordCapacity;
	private static int boundStorageBinding = -1;
	private static ByteBuffer uploadRecordView;
	private static ByteBuffer cpuMirrorView;

	private GpuResidentRegionStore() {
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

	static ResidentRegionDescriptor createDescriptor(
		int regionSceneId,
		int geometrySourceId,
		RenderRegion region,
		boolean translucentPass,
		int regionSlot,
		int sectionBaseIndex,
		int sectionCount,
		int localIndexSectionCount,
		int sharedIndexSectionCount,
		int commandBase,
		int maxCommandCount
	) {
		float centerX = (region.getChunkX() << 4) + REGION_HALF_WIDTH_BLOCKS;
		float centerY = (region.getChunkY() << 4) + REGION_HALF_HEIGHT_BLOCKS;
		float centerZ = (region.getChunkZ() << 4) + REGION_HALF_LENGTH_BLOCKS;
		int flags = translucentPass ? FLAG_TRANSLUCENT_PASS : 0;
		return new ResidentRegionDescriptor(
			regionSceneId,
			geometrySourceId,
			regionSlot,
			sectionBaseIndex,
			sectionCount,
			localIndexSectionCount,
			sharedIndexSectionCount,
			flags,
			commandBase,
			maxCommandCount,
			0,
			0,
			centerX,
			centerY,
			centerZ,
			REGION_BOUNDING_RADIUS
		);
	}

	static void syncCpuMirror(ResidentRegionDescriptor descriptor) {
		if (descriptor == null || descriptor.regionSceneId() <= 0) {
			return;
		}

		ensureCapacity(descriptor.regionSceneId() + 1);
		ByteBuffer mirrorView = cpuMirrorView.duplicate().order(ByteOrder.nativeOrder());
		writeDescriptor(mirrorView, descriptor.regionSceneId() * RECORD_STRIDE_BYTES, descriptor);
	}

	static void flushPendingUpload(ResidentRegionDescriptor descriptor) {
		if (descriptor == null || descriptor.regionSceneId() <= 0) {
			return;
		}

		initialize();
		ensureCapacity(descriptor.regionSceneId() + 1);
		ensureUploadCapacity();
		ByteBuffer uploadView = uploadRecordView.duplicate().order(ByteOrder.nativeOrder());
		uploadView.clear();
		uploadView.limit(RECORD_STRIDE_BYTES);
		writeDescriptor(uploadView, 0, descriptor);
		uploadView.flip();
		GL45C.glNamedBufferSubData(bufferHandle, (long) descriptor.regionSceneId() * RECORD_STRIDE_BYTES, uploadView);
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
		ByteBuffer newCpuMirror =
			MemoryUtil.memCalloc(Math.toIntExact((long) newCapacity * RECORD_STRIDE_BYTES)).order(ByteOrder.nativeOrder());
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

	private static void writeDescriptor(ByteBuffer destination, int destinationOffsetBytes, ResidentRegionDescriptor descriptor) {
		destination.position(destinationOffsetBytes);
		destination.putInt(descriptor.regionSceneId());
		destination.putInt(descriptor.geometrySourceId());
		destination.putInt(descriptor.regionSlot());
		destination.putInt(descriptor.sectionBaseIndex());
		destination.putInt(descriptor.sectionCount());
		destination.putInt(descriptor.localIndexSectionCount());
		destination.putInt(descriptor.sharedIndexSectionCount());
		destination.putInt(descriptor.flags());
		destination.putInt(descriptor.commandBase());
		destination.putInt(descriptor.maxCommandCount());
		destination.putInt(descriptor.padding0());
		destination.putInt(descriptor.padding1());
		destination.putFloat(descriptor.centerX());
		destination.putFloat(descriptor.centerY());
		destination.putFloat(descriptor.centerZ());
		destination.putFloat(descriptor.boundingRadius());
	}

	record ResidentRegionDescriptor(
		int regionSceneId,
		int geometrySourceId,
		int regionSlot,
		int sectionBaseIndex,
		int sectionCount,
		int localIndexSectionCount,
		int sharedIndexSectionCount,
		int flags,
		int commandBase,
		int maxCommandCount,
		int padding0,
		int padding1,
		float centerX,
		float centerY,
		float centerZ,
		float boundingRadius
	) {
	}
}
