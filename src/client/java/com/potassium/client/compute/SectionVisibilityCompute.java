package com.potassium.client.compute;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.compat.sodium.ChunkRenderListOrdering;
import com.potassium.client.compat.sodium.SectionRenderDataStorageVersioned;
import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.render.indirect.IndexedIndirectCommandBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.system.MemoryUtil;

public final class SectionVisibilityCompute {
	private static final String SHADER_RESOURCE = "assets/potassium/shaders/compute/section_visibility.comp";
	private static final int PLANE_COUNT = 6;
	private static final int LOCAL_SIZE_X = 64;
	private static final int INPUT_STRIDE_BYTES = Integer.BYTES * 24;
	private static final int COUNTERS_PER_REGION = 2;
	private static final int REGION_COMMAND_COUNT_OFFSET = 0;
	private static final int REGION_VISIBLE_SECTION_COUNT_OFFSET = 1;
	private static final int REGION_INFO_OFFSET_BYTES = Integer.BYTES * 20;
	private static final int REGION_INDEX_OFFSET_BYTES = REGION_INFO_OFFSET_BYTES;
	private static final int REGION_COMMAND_BASE_OFFSET_BYTES = REGION_INFO_OFFSET_BYTES + Integer.BYTES;
	private static final int FLAG_USE_LOCAL_INDEX = 1;
	private static final float SECTION_BOUNDING_RADIUS = 14.0f;
	private static final Map<SectionRenderDataStorage, CachedRegionMetadata> REGION_METADATA_CACHE = new WeakHashMap<>();

	private static boolean enabled;
	private static String disableReason = "not initialized";
	private static int programHandle;
	private static int inputBufferHandle;
	private static int counterBufferHandle;
	private static int frustumPlanesLocation = -1;
	private static int sectionCountLocation = -1;
	private static int cameraPositionLocation = -1;
	private static int cameraBlockPositionLocation = -1;
	private static int useBlockFaceCullingLocation = -1;
	private static int capacitySections;
	private static int capacityRegions;
	private static ByteBuffer sectionMetadataBuffer;
	private static ByteBuffer counterBufferView;

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
			inputBufferHandle = GL45C.glCreateBuffers();
			counterBufferHandle = GL45C.glCreateBuffers();
			frustumPlanesLocation = GL20C.glGetUniformLocation(programHandle, "uFrustumPlanes");
			sectionCountLocation = GL20C.glGetUniformLocation(programHandle, "uSectionCount");
			cameraPositionLocation = GL20C.glGetUniformLocation(programHandle, "uCameraPosition");
			cameraBlockPositionLocation = GL20C.glGetUniformLocation(programHandle, "uCameraBlockPosition");
			useBlockFaceCullingLocation = GL20C.glGetUniformLocation(programHandle, "uUseBlockFaceCulling");
			enabled = true;
			disableReason = "none";
			PotassiumClientMod.LOGGER.info("Section visibility compute shader ready");
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
			destination[offset + 3] = plane.w;
		}
	}

	public static ComputePassResult generateIndexedCommands(
		IndexedIndirectCommandBuffer commandBuffer,
		List<RegionBatchInput> regionInputs,
		boolean translucentPass,
		CameraTransform cameraTransform,
		float[] frustumPlanes,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices,
		boolean readBackCounters
	) {
		if (!enabled || regionInputs.isEmpty()) {
			return ComputePassResult.empty();
		}

		int totalSections = 0;
		for (RegionBatchInput regionInput : regionInputs) {
			totalSections += regionInput.expectedSectionCount();
		}

		if (totalSections <= 0) {
			return ComputePassResult.empty(regionInputs.size());
		}

		ensureCapacity(totalSections, regionInputs.size());

		int[] testedSectionCounts = new int[regionInputs.size()];
		int packedSectionCount = packPassMetadata(
			regionInputs,
			preferLocalIndices,
			testedSectionCounts
		);
		if (packedSectionCount == 0) {
			return ComputePassResult.empty(regionInputs.size());
		}

		uploadSectionMetadata(packedSectionCount);
		resetCounters(regionInputs.size());

		int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, inputBufferHandle);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, counterBufferHandle);
		commandBuffer.bindAsStorage(2);
		try {
			GL20C.glUseProgram(programHandle);
			GL20C.glUniform4fv(frustumPlanesLocation, frustumPlanes);
			GL30C.glUniform1ui(sectionCountLocation, packedSectionCount);
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
			GL43C.glDispatchCompute((packedSectionCount + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X, 1, 1);
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
		}

		if (!readBackCounters) {
			return new ComputePassResult(true, false, testedSectionCounts, new int[regionInputs.size()], new int[regionInputs.size()]);
		}

		int[] commandCounts = new int[regionInputs.size()];
		int[] visibleSectionCounts = new int[regionInputs.size()];
		readCounters(regionInputs, commandCounts, visibleSectionCounts);
		return new ComputePassResult(true, true, testedSectionCounts, commandCounts, visibleSectionCounts);
	}

	public static void bindCountersAsParameterBuffer() {
		GL15C.glBindBuffer(parameterBufferTarget(), counterBufferHandle);
	}

	public static long commandCountOffsetBytes(int regionIndex) {
		return (long) ((regionIndex * COUNTERS_PER_REGION) + REGION_COMMAND_COUNT_OFFSET) * Integer.BYTES;
	}

	public static void shutdown() {
		enabled = false;
		releaseResources();
		disableReason = "shutdown";
	}

	private static int packPassMetadata(
		List<RegionBatchInput> regionInputs,
		boolean preferLocalIndices,
		int[] testedSectionCounts
	) {
		ByteBuffer metadataView = sectionMetadataBuffer.duplicate().order(ByteOrder.nativeOrder());
		metadataView.clear();
		int packedSectionCount = 0;

		for (int regionOrdinal = 0; regionOrdinal < regionInputs.size(); regionOrdinal++) {
			RegionBatchInput regionInput = regionInputs.get(regionOrdinal);
			CachedRegionMetadata cachedMetadata = getOrBuildCachedMetadata(regionInput, preferLocalIndices);
			int sectionCount = cachedMetadata.sectionCount();
			testedSectionCounts[regionOrdinal] = sectionCount;
			if (sectionCount == 0) {
				continue;
			}

			int regionMetadataOffset = metadataView.position();
			metadataView.put(cachedMetadata.templateBytes());
			patchRegionInfo(metadataView, regionMetadataOffset, sectionCount, regionOrdinal, regionInput.firstCommandIndex());
			packedSectionCount += sectionCount;
		}

		return packedSectionCount;
	}

	private static CachedRegionMetadata getOrBuildCachedMetadata(RegionBatchInput regionInput, boolean preferLocalIndices) {
		ChunkRenderList renderList = regionInput.renderList();
		SectionRenderDataStorage storage = regionInput.storage();
		if (!(renderList instanceof ChunkRenderListOrdering ordering)) {
			throw new IllegalStateException("Potassium chunk render list accessor mixin is not applied.");
		}
		if (!(storage instanceof SectionRenderDataStorageVersioned versioned)) {
			throw new IllegalStateException("Potassium section render storage version mixin is not applied.");
		}

		byte[] sectionsWithGeometry = ordering.potassium$getSectionsWithGeometry();
		int sectionCount = renderList.getSectionsWithGeometryCount();
		int storageVersion = versioned.potassium$getStorageVersion();
		CachedRegionMetadata cachedMetadata = REGION_METADATA_CACHE.get(storage);
		if (cachedMetadata != null && cachedMetadata.matches(storageVersion, preferLocalIndices, sectionsWithGeometry, sectionCount)) {
			return cachedMetadata;
		}

		CachedRegionMetadata rebuiltMetadata = buildCachedMetadata(
			regionInput,
			storageVersion,
			preferLocalIndices,
			sectionsWithGeometry,
			sectionCount
		);
		REGION_METADATA_CACHE.put(storage, rebuiltMetadata);
		return rebuiltMetadata;
	}

	private static CachedRegionMetadata buildCachedMetadata(
		RegionBatchInput regionInput,
		int storageVersion,
		boolean preferLocalIndices,
		byte[] sectionsWithGeometry,
		int sectionCount
	) {
		RenderRegion region = regionInput.region();
		SectionRenderDataStorage storage = regionInput.storage();
		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		byte[] sectionOrderSnapshot = new byte[sectionCount];
		System.arraycopy(sectionsWithGeometry, 0, sectionOrderSnapshot, 0, sectionCount);
		byte[] templateBytes = new byte[sectionCount * INPUT_STRIDE_BYTES];
		ByteBuffer metadataView = ByteBuffer.wrap(templateBytes).order(ByteOrder.nativeOrder());

		for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
			int localSectionIndex = Byte.toUnsignedInt(sectionOrderSnapshot[sectionIndex]);
			long dataPointer = storage.getDataPointer(localSectionIndex);
			int sectionChunkX = regionChunkX + LocalSectionIndex.unpackX(localSectionIndex);
			int sectionChunkY = regionChunkY + LocalSectionIndex.unpackY(localSectionIndex);
			int sectionChunkZ = regionChunkZ + LocalSectionIndex.unpackZ(localSectionIndex);
			long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
			int flags = preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer) ? FLAG_USE_LOCAL_INDEX : 0;

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

		return new CachedRegionMetadata(storageVersion, preferLocalIndices, sectionOrderSnapshot, templateBytes);
	}

	private static void patchRegionInfo(
		ByteBuffer metadataView,
		int regionMetadataOffset,
		int sectionCount,
		int regionOrdinal,
		int firstCommandIndex
	) {
		for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
			int recordOffset = regionMetadataOffset + (sectionIndex * INPUT_STRIDE_BYTES);
			metadataView.putInt(recordOffset + REGION_INDEX_OFFSET_BYTES, regionOrdinal);
			metadataView.putInt(recordOffset + REGION_COMMAND_BASE_OFFSET_BYTES, firstCommandIndex);
		}
	}

	private static void uploadSectionMetadata(int packedSectionCount) {
		ByteBuffer metadataView = sectionMetadataBuffer.duplicate().order(ByteOrder.nativeOrder());
		metadataView.clear();
		metadataView.limit(packedSectionCount * INPUT_STRIDE_BYTES);
		GL45C.glNamedBufferSubData(inputBufferHandle, 0L, metadataView);
	}

	private static void resetCounters(int regionCount) {
		ByteBuffer counterResetView = counterBufferView.duplicate().order(ByteOrder.nativeOrder());
		counterResetView.clear();
		counterResetView.limit(regionCount * COUNTERS_PER_REGION * Integer.BYTES);
		while (counterResetView.hasRemaining()) {
			counterResetView.putInt(0);
		}
		counterResetView.flip();
		GL45C.glNamedBufferSubData(counterBufferHandle, 0L, counterResetView);
	}

	private static void readCounters(
		List<RegionBatchInput> regionInputs,
		int[] commandCounts,
		int[] visibleSectionCounts
	) {
		ByteBuffer readbackView = counterBufferView.duplicate().order(ByteOrder.nativeOrder());
		readbackView.clear();
		readbackView.limit(regionInputs.size() * COUNTERS_PER_REGION * Integer.BYTES);
		GL45C.glGetNamedBufferSubData(counterBufferHandle, 0L, readbackView);

		for (int regionIndex = 0; regionIndex < regionInputs.size(); regionIndex++) {
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

		if (inputBufferHandle != 0) {
			GL15C.glDeleteBuffers(inputBufferHandle);
			inputBufferHandle = 0;
		}

		if (counterBufferHandle != 0) {
			GL15C.glDeleteBuffers(counterBufferHandle);
			counterBufferHandle = 0;
		}

		REGION_METADATA_CACHE.clear();

		if (sectionMetadataBuffer != null) {
			MemoryUtil.memFree(sectionMetadataBuffer);
			sectionMetadataBuffer = null;
		}

		if (counterBufferView != null) {
			MemoryUtil.memFree(counterBufferView);
			counterBufferView = null;
		}

		capacitySections = 0;
		capacityRegions = 0;
	}

	private static void ensureCapacity(int requiredSections, int requiredRegions) {
		if (requiredSections > capacitySections) {
			if (sectionMetadataBuffer != null) {
				MemoryUtil.memFree(sectionMetadataBuffer);
			}

			capacitySections = Integer.highestOneBit(Math.max(requiredSections - 1, 1)) << 1;
			sectionMetadataBuffer = MemoryUtil.memAlloc(capacitySections * INPUT_STRIDE_BYTES).order(ByteOrder.nativeOrder());
			GL45C.glNamedBufferData(inputBufferHandle, (long) capacitySections * INPUT_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
		}

		if (requiredRegions > capacityRegions) {
			if (counterBufferView != null) {
				MemoryUtil.memFree(counterBufferView);
			}

			capacityRegions = Integer.highestOneBit(Math.max(requiredRegions - 1, 1)) << 1;
			int counterBufferBytes = capacityRegions * COUNTERS_PER_REGION * Integer.BYTES;
			counterBufferView = MemoryUtil.memAlloc(counterBufferBytes).order(ByteOrder.nativeOrder());
			GL45C.glNamedBufferData(counterBufferHandle, counterBufferBytes, GL15C.GL_DYNAMIC_READ);
		}
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

	private static void disable(String reason, RuntimeException exception) {
		PotassiumClientMod.LOGGER.warn("Section visibility compute disabled: {}", reason, exception);
		enabled = false;
		releaseResources();
		disableReason = reason;
	}

	private static int parameterBufferTarget() {
		return GLCapabilities.isVersion46() ? GL46C.GL_PARAMETER_BUFFER : ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
	}

	public record RegionBatchInput(
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		int firstCommandIndex,
		int maxCommandCount,
		int expectedSectionCount
	) {
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

	private record CachedRegionMetadata(
		int storageVersion,
		boolean preferLocalIndices,
		byte[] sectionOrderSnapshot,
		byte[] templateBytes
	) {
		private int sectionCount() {
			return this.sectionOrderSnapshot.length;
		}

		private boolean matches(
			int currentStorageVersion,
			boolean currentPreferLocalIndices,
			byte[] currentSectionOrder,
			int currentSectionCount
		) {
			if (
				this.storageVersion != currentStorageVersion ||
				this.preferLocalIndices != currentPreferLocalIndices ||
				this.sectionOrderSnapshot.length != currentSectionCount
			) {
				return false;
			}

			for (int sectionIndex = 0; sectionIndex < currentSectionCount; sectionIndex++) {
				if (this.sectionOrderSnapshot[sectionIndex] != currentSectionOrder[sectionIndex]) {
					return false;
				}
			}

			return true;
		}
	}
}
