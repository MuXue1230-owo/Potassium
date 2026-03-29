package com.potassium.client.compute;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.compat.sodium.ChunkRenderListOrdering;
import com.potassium.client.compat.sodium.SectionRenderDataStorageDirtyTracker;
import com.potassium.client.compat.sodium.SectionRenderDataStorageVersioned;
import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.gl.GpuMemoryBudget;
import com.potassium.client.render.indirect.IndexedIndirectCommandBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

public final class SectionVisibilityCompute {
	private static final String SHADER_RESOURCE = "assets/potassium/shaders/compute/section_visibility.comp";
	private static final int PLANE_COUNT = 6;
	private static final int LOCAL_SIZE_X = 64;
	private static final int INPUT_STRIDE_BYTES = Integer.BYTES * 24;
	private static final int REGION_DESCRIPTOR_STRIDE_BYTES = Integer.BYTES * 4;
	private static final int MAX_REGION_SECTION_COUNT =
		RenderRegion.REGION_WIDTH * RenderRegion.REGION_HEIGHT * RenderRegion.REGION_LENGTH;
	private static final int REGION_DISPATCH_GROUP_COUNT_Y =
		(MAX_REGION_SECTION_COUNT + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X;
	private static final int COUNTERS_PER_REGION = 2;
	private static final int REGION_COMMAND_COUNT_OFFSET = 0;
	private static final int REGION_VISIBLE_SECTION_COUNT_OFFSET = 1;
	private static final int FLAG_USE_LOCAL_INDEX = 1;
	private static final int FACING_COUNT = 7;
	private static final int ALL_FACES_MASK = (1 << FACING_COUNT) - 1;
	private static final float SECTION_BOUNDING_RADIUS = 14.0f;
	private static final float SECTION_PADDED_HALF_EXTENT = 9.125f;
	private static final boolean ENABLE_REGION_METADATA_CACHE = true;
	private static final Map<SectionRenderDataStorage, CachedRegionMetadata> REGION_METADATA_CACHE = new WeakHashMap<>();
	private static final ThreadLocal<long[]> DIRTY_SECTION_WORDS =
		ThreadLocal.withInitial(() -> new long[(MAX_REGION_SECTION_COUNT + Long.SIZE - 1) / Long.SIZE]);
	private static final ThreadLocal<ComputePassScratch> COMPUTE_PASS_SCRATCH =
		ThreadLocal.withInitial(ComputePassScratch::new);

	private static boolean enabled;
	private static String disableReason = "not initialized";
	private static int programHandle;
	private static int sectionBufferHandle;
	private static int regionDescriptorBufferHandle;
	private static int counterBufferHandle;
	private static int frustumPlanesLocation = -1;
	private static int cameraPositionLocation = -1;
	private static int cameraBlockPositionLocation = -1;
	private static int useBlockFaceCullingLocation = -1;
	private static int regionSlotCapacity;
	private static int regionDescriptorCapacity;
	private static int nextRegionSlot;
	private static ByteBuffer regionDescriptorView;
	private static ByteBuffer counterBufferView;
	private static ByteBuffer sectionUploadView;
	private static IntBuffer counterClearValue;

	private SectionVisibilityCompute() {
	}

	public static void initialize() {
		if (enabled || programHandle != 0) {
			return;
		}

		if (!GLCapabilities.hasComputeShader() || !GLCapabilities.hasSSBO()) {
			disableReason = "missing compute shader or SSBO support";
			return;
		}

		try {
			programHandle = createProgram(loadShaderSource());
			sectionBufferHandle = GL45C.glCreateBuffers();
			regionDescriptorBufferHandle = GL45C.glCreateBuffers();
			counterBufferHandle = GL45C.glCreateBuffers();
			frustumPlanesLocation = GL20C.glGetUniformLocation(programHandle, "uFrustumPlanes");
			cameraPositionLocation = GL20C.glGetUniformLocation(programHandle, "uCameraPosition");
			cameraBlockPositionLocation = GL20C.glGetUniformLocation(programHandle, "uCameraBlockPosition");
			useBlockFaceCullingLocation = GL20C.glGetUniformLocation(programHandle, "uUseBlockFaceCulling");
			counterClearValue = MemoryUtil.memCallocInt(1);
			preallocateWorkingSet();
			enabled = true;
			disableReason = "none";
			PotassiumClientMod.LOGGER.info(
				"Section visibility compute shader ready (preallocated regionSlots={}, regionDescriptors={}, sectionBuffer={} MiB)",
				regionSlotCapacity,
				regionDescriptorCapacity,
				sectionBufferBytes(regionSlotCapacity) / (1024L * 1024L)
			);
		} catch (RuntimeException exception) {
			disable("initialization failed", exception);
		}
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static String disableReason() {
		return disableReason;
	}

	public static void captureFrustumPlanes(ChunkRenderMatrices matrices, float[] destination) {
		if (destination.length < PLANE_COUNT * 4) {
			throw new IllegalArgumentException("Destination array is too small for frustum planes.");
		}

		Matrix4f combined = new Matrix4f(matrices.projection()).mul(matrices.modelView());
		Vector4f plane = new Vector4f();
		int[] planeIndices = {
			FrustumIntersection.PLANE_NX,
			FrustumIntersection.PLANE_PX,
			FrustumIntersection.PLANE_NY,
			FrustumIntersection.PLANE_PY,
			FrustumIntersection.PLANE_NZ,
			FrustumIntersection.PLANE_PZ
		};

		for (int planeIndex = 0; planeIndex < planeIndices.length; planeIndex++) {
			combined.frustumPlane(planeIndices[planeIndex], plane);
			int offset = planeIndex * 4;
			destination[offset] = plane.x;
			destination[offset + 1] = plane.y;
			destination[offset + 2] = plane.z;
			destination[offset + 3] =
				-(
					plane.w +
					(plane.x < 0.0f ? -SECTION_PADDED_HALF_EXTENT : SECTION_PADDED_HALF_EXTENT) * plane.x +
					(plane.y < 0.0f ? -SECTION_PADDED_HALF_EXTENT : SECTION_PADDED_HALF_EXTENT) * plane.y +
					(plane.z < 0.0f ? -SECTION_PADDED_HALF_EXTENT : SECTION_PADDED_HALF_EXTENT) * plane.z
				);
		}
	}

	public static ComputePassResult generateIndexedCommands(
		IndexedIndirectCommandBuffer commandBuffer,
		List<RegionBatchInput> regionInputs,
		int regionCount,
		boolean translucentPass,
		CameraTransform cameraTransform,
		float[] frustumPlanes,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices,
		boolean readBackCounters
	) {
		if (!enabled || regionCount <= 0) {
			return ComputePassResult.empty();
		}

		ensureDynamicCapacity(regionCount);
		ComputePassScratch scratch = COMPUTE_PASS_SCRATCH.get();
		scratch.ensureCapacity(regionCount);
		CachedRegionMetadata[] cachedMetadatas = scratch.cachedMetadatas;
		int[] testedSectionCounts = scratch.testedSectionCounts;
		int requiredRegionSlots = 0;

		for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
			RegionBatchInput regionInput = regionInputs.get(regionIndex);
			CachedRegionMetadata cachedMetadata = getOrBuildCachedMetadata(regionInput, preferLocalIndices, translucentPass);
			cachedMetadatas[regionIndex] = cachedMetadata;
			testedSectionCounts[regionIndex] = cachedMetadata.sectionCount();
			requiredRegionSlots = Math.max(requiredRegionSlots, cachedMetadata.regionSlot() + 1);
		}

		ensureStaticBufferCapacity(requiredRegionSlots);

		ByteBuffer descriptorView = thisFrameRegionDescriptorView(regionCount);
		for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
			CachedRegionMetadata cachedMetadata = cachedMetadatas[regionIndex];
			uploadCachedMetadataIfDirty(cachedMetadata);
			descriptorView.putInt(cachedMetadata.sectionBaseIndex());
			descriptorView.putInt(cachedMetadata.sectionCount());
			descriptorView.putInt(regionInputs.get(regionIndex).firstCommandIndex());
			descriptorView.putInt(0);
		}

		uploadRegionDescriptors(descriptorView);
		resetCounters(regionCount);

		int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, sectionBufferHandle);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, regionDescriptorBufferHandle);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, counterBufferHandle);
		commandBuffer.bindAsStorage(3);
		try {
			GL20C.glUseProgram(programHandle);
			GL20C.glUniform4fv(frustumPlanesLocation, frustumPlanes);
			GL20C.glUniform3f(
				cameraPositionLocation,
				cameraTransform.intX + cameraTransform.fracX,
				cameraTransform.intY + cameraTransform.fracY,
				cameraTransform.intZ + cameraTransform.fracZ
			);
			GL20C.glUniform3i(
				cameraBlockPositionLocation,
				cameraTransform.intX,
				cameraTransform.intY,
				cameraTransform.intZ
			);
			GL30C.glUniform1ui(useBlockFaceCullingLocation, useBlockFaceCulling ? 1 : 0);
			GL43C.glDispatchCompute(regionInputs.size(), REGION_DISPATCH_GROUP_COUNT_Y, 1);
			GL42C.glMemoryBarrier(
				GL43C.GL_SHADER_STORAGE_BARRIER_BIT |
				GL43C.GL_COMMAND_BARRIER_BIT |
				GL43C.GL_BUFFER_UPDATE_BARRIER_BIT
			);
		} finally {
			GL20C.glUseProgram(previousProgram);
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, 0);
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, 0);
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, 0);
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, 0);
		}

		if (!readBackCounters) {
			return new ComputePassResult(
				true,
				false,
				testedSectionCounts,
				scratch.commandCounts,
				scratch.visibleSectionCounts
			);
		}

		int[] commandCounts = scratch.commandCounts;
		int[] visibleSectionCounts = scratch.visibleSectionCounts;
		readCounters(regionInputs, regionCount, commandCounts, visibleSectionCounts);
		return new ComputePassResult(true, true, testedSectionCounts, commandCounts, visibleSectionCounts);
	}

	public static void bindCountersAsParameterBuffer() {
		GL15C.glBindBuffer(parameterBufferTarget(), counterBufferHandle);
	}

	public static long commandCountOffsetBytes(int regionIndex) {
		return (long) ((regionIndex * COUNTERS_PER_REGION) + REGION_COMMAND_COUNT_OFFSET) * Integer.BYTES;
	}

	public static PackedRegionAnalysis debugAnalyzePackedRegion(
		RegionBatchInput regionInput,
		CameraTransform cameraTransform,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices
	) {
		CachedRegionMetadata cachedMetadata = getOrBuildCachedMetadata(regionInput, preferLocalIndices, false);
		ByteBuffer metadataView = ByteBuffer.wrap(cachedMetadata.templateBytes()).order(ByteOrder.nativeOrder());
		int sectionCount = cachedMetadata.sectionCount();
		int sliceMaskedSectionCount = 0;
		int visibleSectionCount = 0;
		int emittedCommandCount = 0;
		int localSectionCount = 0;
		int sharedSectionCount = 0;
		int firstVisibleFacesMask = 0;
		int firstSliceMask = 0;
		boolean capturedFirstVisibleSection = false;

		for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
			int sectionOffsetBytes = sectionIndex * INPUT_STRIDE_BYTES;
			int sectionChunkX = metadataView.getInt(sectionOffsetBytes + (Integer.BYTES * 4));
			int sectionChunkY = metadataView.getInt(sectionOffsetBytes + (Integer.BYTES * 5));
			int sectionChunkZ = metadataView.getInt(sectionOffsetBytes + (Integer.BYTES * 6));
			int sliceMask = metadataView.getInt(sectionOffsetBytes + (Integer.BYTES * 7));
			if (sliceMask != 0) {
				sliceMaskedSectionCount++;
			}

			int visibleFaces = useBlockFaceCulling
				? DefaultChunkRenderer.getVisibleFaces(
					cameraTransform.intX,
					cameraTransform.intY,
					cameraTransform.intZ,
					sectionChunkX,
					sectionChunkY,
					sectionChunkZ
				)
				: ALL_FACES_MASK;
			visibleFaces &= sliceMask;
			if (visibleFaces == 0) {
				continue;
			}

			visibleSectionCount++;
			if (!capturedFirstVisibleSection) {
				firstVisibleFacesMask = visibleFaces;
				firstSliceMask = sliceMask;
				capturedFirstVisibleSection = true;
			}

			int flags = metadataView.getInt(sectionOffsetBytes + (Integer.BYTES * 22));
			boolean useLocalIndex = preferLocalIndices && (flags & FLAG_USE_LOCAL_INDEX) != 0;
			if (useLocalIndex) {
				localSectionCount++;
				emittedCommandCount += Integer.bitCount(visibleFaces & ALL_FACES_MASK);
			} else {
				sharedSectionCount++;
				emittedCommandCount += countSharedCommands(metadataView, sectionOffsetBytes, visibleFaces);
			}
		}

		return new PackedRegionAnalysis(
			sectionCount,
			sliceMaskedSectionCount,
			visibleSectionCount,
			emittedCommandCount,
			localSectionCount,
			sharedSectionCount,
			firstVisibleFacesMask,
			firstSliceMask
		);
	}

	public static void shutdown() {
		enabled = false;
		releaseResources();
		disableReason = "shutdown";
	}

	private static ByteBuffer thisFrameRegionDescriptorView(int regionCount) {
		ByteBuffer descriptorView = regionDescriptorView.duplicate().order(ByteOrder.nativeOrder());
		descriptorView.clear();
		descriptorView.limit(regionCount * REGION_DESCRIPTOR_STRIDE_BYTES);
		return descriptorView;
	}

	private static CachedRegionMetadata getOrBuildCachedMetadata(
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
		if (ENABLE_REGION_METADATA_CACHE && cachedMetadata != null) {
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

				if (dirtyTracker.potassium$isFullMetadataDirty()) {
					// Rebuild below.
				} else {
					long[] dirtySectionWords = DIRTY_SECTION_WORDS.get();
					dirtyTracker.potassium$copyDirtySectionBits(dirtySectionWords);
					if (cachedMetadata.refreshDirtySections(regionInput, storageVersion, preferLocalIndices, dirtySectionWords)) {
						dirtyTracker.potassium$clearMetadataDirty();
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
		return rebuiltMetadata;
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
		byte[] templateBytes = new byte[sectionCount * INPUT_STRIDE_BYTES];
		ByteBuffer metadataView = ByteBuffer.wrap(templateBytes).order(ByteOrder.nativeOrder());

		for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
			int localSectionIndex = Byte.toUnsignedInt(sectionOrderSnapshot[sectionIndex]);
			writeSectionMetadata(
				metadataView,
				sectionIndex,
				localSectionIndex,
				storage,
				regionChunkX,
				regionChunkY,
				regionChunkZ,
				preferLocalIndices
			);
		}

		return new CachedRegionMetadata(
			storageVersion,
			preferLocalIndices,
			preserveSectionOrder,
			sectionOrderSnapshot,
			geometryMapSnapshot,
			templateBytes,
			regionSlot,
			true
		);
	}

	private static void writeSectionMetadata(
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
		int sectionChunkX = regionChunkX + LocalSectionIndex.unpackX(localSectionIndex);
		int sectionChunkY = regionChunkY + LocalSectionIndex.unpackY(localSectionIndex);
		int sectionChunkZ = regionChunkZ + LocalSectionIndex.unpackZ(localSectionIndex);
		long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
		int flags = preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer) ? FLAG_USE_LOCAL_INDEX : 0;
		int offset = sectionIndex * INPUT_STRIDE_BYTES;
		metadataView.position(offset);
		metadataView.putFloat((sectionChunkX << 4) + 8.0f);
		metadataView.putFloat((sectionChunkY << 4) + 8.0f);
		metadataView.putFloat((sectionChunkZ << 4) + 8.0f);
		metadataView.putFloat(SECTION_BOUNDING_RADIUS);
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
		metadataView.putInt(0);
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

	private static void uploadCachedMetadataIfDirty(CachedRegionMetadata cachedMetadata) {
		if (!cachedMetadata.isDirty()) {
			return;
		}

		ensureStaticBufferCapacity(cachedMetadata.regionSlot() + 1);
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

	private static void uploadRegionDescriptors(ByteBuffer descriptorView) {
		ByteBuffer uploadView = descriptorView.duplicate().order(ByteOrder.nativeOrder());
		uploadView.flip();
		GL45C.glNamedBufferSubData(regionDescriptorBufferHandle, 0L, uploadView);
	}

	private static void resetCounters(int regionCount) {
		long clearBytes = (long) regionCount * COUNTERS_PER_REGION * Integer.BYTES;
		if (clearBytes <= 0L) {
			return;
		}

		counterClearValue.clear();
		GL45C.glClearNamedBufferSubData(
			counterBufferHandle,
			GL30C.GL_R32UI,
			0L,
			clearBytes,
			GL30C.GL_RED_INTEGER,
			GL11C.GL_UNSIGNED_INT,
			counterClearValue
		);
	}

	private static void readCounters(
		List<RegionBatchInput> regionInputs,
		int regionCount,
		int[] commandCounts,
		int[] visibleSectionCounts
	) {
		ByteBuffer readbackView = counterBufferView.duplicate().order(ByteOrder.nativeOrder());
		readbackView.clear();
		readbackView.limit(regionCount * COUNTERS_PER_REGION * Integer.BYTES);
		GL45C.glGetNamedBufferSubData(counterBufferHandle, 0L, readbackView);

		for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
			int baseOffset = regionIndex * COUNTERS_PER_REGION * Integer.BYTES;
			int commandCount = readbackView.getInt(baseOffset + (REGION_COMMAND_COUNT_OFFSET * Integer.BYTES));
			int visibleSectionCount = readbackView.getInt(baseOffset + (REGION_VISIBLE_SECTION_COUNT_OFFSET * Integer.BYTES));
			if (commandCount < 0) {
				throw new IllegalStateException("Compute produced a negative commandCount.");
			}
			if (visibleSectionCount < 0) {
				throw new IllegalStateException("Compute produced a negative visibleSectionCount.");
			}
			if (commandCount > regionInputs.get(regionIndex).maxCommandCount()) {
				throw new IllegalStateException("Compute generated more commands than the reserved indirect range for one region.");
			}
			if (visibleSectionCount > regionInputs.get(regionIndex).expectedSectionCount()) {
				throw new IllegalStateException("Compute reported more visible sections than exist in one region.");
			}

			commandCounts[regionIndex] = commandCount;
			visibleSectionCounts[regionIndex] = visibleSectionCount;
		}
	}

	private static int checkedInt(long value, String label) {
		try {
			return Math.toIntExact(value);
		} catch (ArithmeticException exception) {
			throw new IllegalStateException("Section metadata field is out of 32-bit range: " + label, exception);
		}
	}

	private static void releaseResources() {
		if (programHandle != 0) {
			GL20C.glDeleteProgram(programHandle);
			programHandle = 0;
		}

		if (sectionBufferHandle != 0) {
			GL15C.glDeleteBuffers(sectionBufferHandle);
			sectionBufferHandle = 0;
		}

		if (regionDescriptorBufferHandle != 0) {
			GL15C.glDeleteBuffers(regionDescriptorBufferHandle);
			regionDescriptorBufferHandle = 0;
		}

		if (counterBufferHandle != 0) {
			GL15C.glDeleteBuffers(counterBufferHandle);
			counterBufferHandle = 0;
		}

		REGION_METADATA_CACHE.clear();
		nextRegionSlot = 0;

		if (regionDescriptorView != null) {
			MemoryUtil.memFree(regionDescriptorView);
			regionDescriptorView = null;
		}

		if (counterBufferView != null) {
			MemoryUtil.memFree(counterBufferView);
			counterBufferView = null;
		}

		if (sectionUploadView != null) {
			MemoryUtil.memFree(sectionUploadView);
			sectionUploadView = null;
		}

		if (counterClearValue != null) {
			MemoryUtil.memFree(counterClearValue);
			counterClearValue = null;
		}

		regionSlotCapacity = 0;
		regionDescriptorCapacity = 0;
	}

	private static void preallocateWorkingSet() {
		GpuMemoryBudget.Budget requestedBudget = GpuMemoryBudget.current();
		if (tryPreallocateBudget(requestedBudget)) {
			return;
		}

		GpuMemoryBudget.Budget fallbackBudget = GpuMemoryBudget.conservativeFallback(
			"fallback after compute buffer preallocation failure"
		);
		if (
			fallbackBudget.computeRegionSlots() != requestedBudget.computeRegionSlots() ||
			fallbackBudget.computeRegionDescriptors() != requestedBudget.computeRegionDescriptors()
		) {
			tryPreallocateBudget(fallbackBudget);
		}
	}

	private static boolean tryPreallocateBudget(GpuMemoryBudget.Budget budget) {
		try {
			ensureStaticBufferCapacity(budget.computeRegionSlots());
			ensureDynamicCapacity(budget.computeRegionDescriptors());
			return true;
		} catch (RuntimeException exception) {
			PotassiumClientMod.LOGGER.warn(
				"Failed to preallocate compute buffers for '{}' preset (regionSlots={}, regionDescriptors={}). Continuing with smaller or on-demand allocations.",
				budget.preset().configName(),
				budget.computeRegionSlots(),
				budget.computeRegionDescriptors(),
				exception
			);
			return false;
		}
	}

	private static void ensureStaticBufferCapacity(int requiredRegionSlots) {
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

	private static void ensureDynamicCapacity(int requiredRegions) {
		if (requiredRegions <= regionDescriptorCapacity) {
			return;
		}

		int newCapacity = nextCapacity(requiredRegions);
		int regionDescriptorBytes = newCapacity * REGION_DESCRIPTOR_STRIDE_BYTES;
		ByteBuffer newRegionDescriptorView = MemoryUtil.memAlloc(regionDescriptorBytes).order(ByteOrder.nativeOrder());
		int counterBufferBytes = newCapacity * COUNTERS_PER_REGION * Integer.BYTES;
		ByteBuffer newCounterBufferView = MemoryUtil.memAlloc(counterBufferBytes).order(ByteOrder.nativeOrder());

		try {
			GL45C.glNamedBufferData(regionDescriptorBufferHandle, regionDescriptorBytes, GL15C.GL_DYNAMIC_DRAW);
			GL45C.glNamedBufferData(counterBufferHandle, counterBufferBytes, GL15C.GL_DYNAMIC_READ);
		} catch (RuntimeException exception) {
			MemoryUtil.memFree(newRegionDescriptorView);
			MemoryUtil.memFree(newCounterBufferView);
			throw exception;
		}

		if (regionDescriptorView != null) {
			MemoryUtil.memFree(regionDescriptorView);
		}
		regionDescriptorView = newRegionDescriptorView;
		if (counterBufferView != null) {
			MemoryUtil.memFree(counterBufferView);
		}
		counterBufferView = newCounterBufferView;
		regionDescriptorCapacity = newCapacity;
	}

	private static String loadShaderSource() {
		try (InputStream stream = SectionVisibilityCompute.class.getClassLoader().getResourceAsStream(SHADER_RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException("Missing compute shader resource: " + SHADER_RESOURCE);
			}

			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to read compute shader resource: " + SHADER_RESOURCE, exception);
		}
	}

	private static int createProgram(String shaderSource) {
		int shaderHandle = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
		GL20C.glShaderSource(shaderHandle, shaderSource);
		GL20C.glCompileShader(shaderHandle);

		if (GL20C.glGetShaderi(shaderHandle, GL20C.GL_COMPILE_STATUS) != GL11C.GL_TRUE) {
			String infoLog = GL20C.glGetShaderInfoLog(shaderHandle);
			GL20C.glDeleteShader(shaderHandle);
			throw new IllegalStateException("Failed to compile section visibility compute shader: " + infoLog);
		}

		int createdProgram = GL20C.glCreateProgram();
		GL20C.glAttachShader(createdProgram, shaderHandle);
		GL20C.glLinkProgram(createdProgram);
		GL20C.glDetachShader(createdProgram, shaderHandle);
		GL20C.glDeleteShader(shaderHandle);

		if (GL20C.glGetProgrami(createdProgram, GL20C.GL_LINK_STATUS) != GL11C.GL_TRUE) {
			String infoLog = GL20C.glGetProgramInfoLog(createdProgram);
			GL20C.glDeleteProgram(createdProgram);
			throw new IllegalStateException("Failed to link section visibility compute program: " + infoLog);
		}

		return createdProgram;
	}

	private static int countSharedCommands(ByteBuffer metadataView, int sectionOffsetBytes, int visibleFaces) {
		int commandCount = 0;
		boolean previousVisible = false;

		for (int facing = 0; facing <= FACING_COUNT; facing++) {
			boolean currentVisible = false;
			int vertexCount = 0;

			if (facing < FACING_COUNT) {
				vertexCount = getPackedVertexCount(metadataView, sectionOffsetBytes, facing);
				if (vertexCount != 0) {
					int faceOrder = getPackedFaceOrder(metadataView, sectionOffsetBytes, facing);
					currentVisible = ((visibleFaces >>> faceOrder) & 1) != 0;
				}
			}

			if (!currentVisible && previousVisible) {
				if (facing < FACING_COUNT && vertexCount == 0) {
					continue;
				}

				commandCount++;
			}

			previousVisible = currentVisible;
		}

		return commandCount;
	}

	private static int getPackedVertexCount(ByteBuffer metadataView, int sectionOffsetBytes, int facing) {
		int vertexCountsOffsetBytes = sectionOffsetBytes + (Integer.BYTES * 12);
		return metadataView.getInt(vertexCountsOffsetBytes + (facing * Integer.BYTES));
	}

	private static int getPackedFaceOrder(ByteBuffer metadataView, int sectionOffsetBytes, int facing) {
		int drawInfoOffsetBytes = sectionOffsetBytes + (Integer.BYTES * 8);
		int lowFacingList = metadataView.getInt(drawInfoOffsetBytes + (Integer.BYTES * 2));
		int highFacingList = metadataView.getInt(drawInfoOffsetBytes + (Integer.BYTES * 3));
		int shift = facing * Byte.SIZE;
		if (shift < Integer.SIZE) {
			return (lowFacingList >>> shift) & 0xFF;
		}

		return (highFacingList >>> (shift - Integer.SIZE)) & 0xFF;
	}

	private static void disable(String reason, RuntimeException exception) {
		PotassiumClientMod.LOGGER.warn("Section visibility compute disabled: {}", reason, exception);
		enabled = false;
		releaseResources();
		disableReason = reason;
	}

	private static int parameterBufferTarget() {
		return GLCapabilities.isVersion46() ? GL46C.GL_PARAMETER_BUFFER : ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
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

	public static final class RegionBatchInput {
		private RenderRegion region;
		private SectionRenderDataStorage storage;
		private ChunkRenderList renderList;
		private int firstCommandIndex;
		private int maxCommandCount;
		private int expectedSectionCount;

		public RegionBatchInput() {
		}

		public RegionBatchInput(
			RenderRegion region,
			SectionRenderDataStorage storage,
			ChunkRenderList renderList,
			int firstCommandIndex,
			int maxCommandCount,
			int expectedSectionCount
		) {
			this.configure(region, storage, renderList, firstCommandIndex, maxCommandCount, expectedSectionCount);
		}

		public RegionBatchInput configure(
			RenderRegion region,
			SectionRenderDataStorage storage,
			ChunkRenderList renderList,
			int firstCommandIndex,
			int maxCommandCount,
			int expectedSectionCount
		) {
			this.region = region;
			this.storage = storage;
			this.renderList = renderList;
			this.firstCommandIndex = firstCommandIndex;
			this.maxCommandCount = maxCommandCount;
			this.expectedSectionCount = expectedSectionCount;
			return this;
		}

		public RenderRegion region() {
			return this.region;
		}

		public SectionRenderDataStorage storage() {
			return this.storage;
		}

		public ChunkRenderList renderList() {
			return this.renderList;
		}

		public int firstCommandIndex() {
			return this.firstCommandIndex;
		}

		public int maxCommandCount() {
			return this.maxCommandCount;
		}

		public int expectedSectionCount() {
			return this.expectedSectionCount;
		}
	}

	public record ComputePassResult(
		boolean dispatched,
		boolean countersReadBack,
		int[] testedSectionCounts,
		int[] commandCounts,
		int[] visibleSectionCounts
	) {
		private static final ComputePassResult EMPTY = new ComputePassResult(false, false, new int[0], new int[0], new int[0]);

		public static ComputePassResult empty() {
			return EMPTY;
		}

		public static ComputePassResult empty(int regionCount) {
			if (regionCount == 0) {
				return EMPTY;
			}

			return new ComputePassResult(false, false, new int[regionCount], new int[regionCount], new int[regionCount]);
		}
	}

	public record PackedRegionAnalysis(
		int sectionCount,
		int sliceMaskedSectionCount,
		int visibleSectionCount,
		int emittedCommandCount,
		int localSectionCount,
		int sharedSectionCount,
		int firstVisibleFacesMask,
		int firstSliceMask
	) {
	}

	private static final class ComputePassScratch {
		private CachedRegionMetadata[] cachedMetadatas = new CachedRegionMetadata[0];
		private int[] testedSectionCounts = new int[0];
		private int[] commandCounts = new int[0];
		private int[] visibleSectionCounts = new int[0];

		private void ensureCapacity(int requiredRegionCount) {
			if (this.cachedMetadatas.length >= requiredRegionCount) {
				return;
			}

			int newCapacity = 1;
			while (newCapacity < requiredRegionCount) {
				newCapacity <<= 1;
			}

			this.cachedMetadatas = new CachedRegionMetadata[newCapacity];
			this.testedSectionCounts = new int[newCapacity];
			this.commandCounts = new int[newCapacity];
			this.visibleSectionCounts = new int[newCapacity];
		}
	}

	private static final class CachedRegionMetadata {
		private int storageVersion;
		private final boolean preferLocalIndices;
		private final boolean preserveSectionOrder;
		private final byte[] sectionOrderSnapshot;
		private final long[] geometryMapSnapshot;
		private final byte[] templateBytes;
		private final int regionSlot;
		private boolean dirty;
		private int dirtySectionStart;
		private int dirtySectionEndExclusive;

		private CachedRegionMetadata(
			int storageVersion,
			boolean preferLocalIndices,
			boolean preserveSectionOrder,
			byte[] sectionOrderSnapshot,
			long[] geometryMapSnapshot,
			byte[] templateBytes,
			int regionSlot,
			boolean dirty
		) {
			this.storageVersion = storageVersion;
			this.preferLocalIndices = preferLocalIndices;
			this.preserveSectionOrder = preserveSectionOrder;
			this.sectionOrderSnapshot = sectionOrderSnapshot;
			this.geometryMapSnapshot = geometryMapSnapshot;
			this.templateBytes = templateBytes;
			this.regionSlot = regionSlot;
			this.dirty = dirty;
			this.dirtySectionStart = dirty ? 0 : -1;
			this.dirtySectionEndExclusive = dirty ? sectionOrderSnapshot.length : -1;
		}

		private int storageVersion() {
			return this.storageVersion;
		}

		private int sectionCount() {
			return this.sectionOrderSnapshot.length;
		}

		private int sectionBaseIndex() {
			return this.regionSlot * MAX_REGION_SECTION_COUNT;
		}

		private int regionSlot() {
			return this.regionSlot;
		}

		private byte[] templateBytes() {
			return this.templateBytes;
		}

		private boolean isDirty() {
			return this.dirty;
		}

		private void markDirty() {
			this.dirty = true;
			this.dirtySectionStart = 0;
			this.dirtySectionEndExclusive = this.sectionOrderSnapshot.length;
		}

		private void markUploaded() {
			this.dirty = false;
			this.dirtySectionStart = -1;
			this.dirtySectionEndExclusive = -1;
		}

		private int dirtyUploadOffsetBytes() {
			return Math.max(this.dirtySectionStart, 0) * INPUT_STRIDE_BYTES;
		}

		private int dirtyUploadLengthBytes() {
			if (!this.dirty || this.dirtySectionStart < 0 || this.dirtySectionEndExclusive <= this.dirtySectionStart) {
				return 0;
			}

			return (this.dirtySectionEndExclusive - this.dirtySectionStart) * INPUT_STRIDE_BYTES;
		}

		private void markDirtyRange(int firstSectionIndex, int endExclusiveSectionIndex) {
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

		private boolean matchesLayout(
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

		private boolean refreshDirtySections(
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
			ByteBuffer metadataView = ByteBuffer.wrap(this.templateBytes).order(ByteOrder.nativeOrder());
			boolean updated = false;
			int firstDirtySectionIndex = Integer.MAX_VALUE;
			int lastDirtySectionIndex = -1;

			for (int sectionIndex = 0; sectionIndex < this.sectionOrderSnapshot.length; sectionIndex++) {
				int localSectionIndex = Byte.toUnsignedInt(this.sectionOrderSnapshot[sectionIndex]);
				int wordIndex = localSectionIndex >>> 6;
				if (wordIndex >= dirtySectionWords.length) {
					return false;
				}
				if ((dirtySectionWords[wordIndex] & (1L << (localSectionIndex & 63))) == 0L) {
					continue;
				}

				writeSectionMetadata(
					metadataView,
					sectionIndex,
					localSectionIndex,
					storage,
					regionChunkX,
					regionChunkY,
					regionChunkZ,
					currentPreferLocalIndices
				);
				updated = true;
				firstDirtySectionIndex = Math.min(firstDirtySectionIndex, sectionIndex);
				lastDirtySectionIndex = Math.max(lastDirtySectionIndex, sectionIndex);
			}

			this.storageVersion = currentStorageVersion;
			if (updated) {
				this.markDirtyRange(firstDirtySectionIndex, lastDirtySectionIndex + 1);
			}
			return true;
		}
	}
}
