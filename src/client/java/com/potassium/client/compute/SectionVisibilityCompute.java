package com.potassium.client.compute;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.render.indirect.IndexedIndirectCommandBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class SectionVisibilityCompute {
	private static final String SHADER_RESOURCE = "assets/potassium/shaders/compute/section_visibility.comp";
	private static final int PLANE_COUNT = 6;
	private static final int LOCAL_SIZE_X = 64;
	private static final int INPUT_STRIDE_BYTES = Integer.BYTES * 20;
	private static final int COUNTER_BUFFER_BYTES = Integer.BYTES * 2;
	private static final int COUNTER_COMMAND_COUNT_OFFSET = 0;
	private static final int COUNTER_VISIBLE_SECTION_COUNT_OFFSET = Integer.BYTES;
	private static final int FLAG_USE_LOCAL_INDEX = 1;
	private static final float SECTION_BOUNDING_RADIUS = 14.0f;

	private static boolean enabled;
	private static String disableReason = "not initialized";
	private static int programHandle;
	private static int inputBufferHandle;
	private static int counterBufferHandle;
	private static int frustumPlanesLocation = -1;
	private static int sectionCountLocation = -1;
	private static int cameraBlockPositionLocation = -1;
	private static int useBlockFaceCullingLocation = -1;
	private static int commandBaseIndexLocation = -1;
	private static int capacitySections;
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
			GL45C.glNamedBufferData(counterBufferHandle, COUNTER_BUFFER_BYTES, GL15C.GL_DYNAMIC_READ);
			counterBufferView = MemoryUtil.memAlloc(COUNTER_BUFFER_BYTES).order(ByteOrder.nativeOrder());
			frustumPlanesLocation = GL20C.glGetUniformLocation(programHandle, "uFrustumPlanes");
			sectionCountLocation = GL20C.glGetUniformLocation(programHandle, "uSectionCount");
			cameraBlockPositionLocation = GL20C.glGetUniformLocation(programHandle, "uCameraBlockPosition");
			useBlockFaceCullingLocation = GL20C.glGetUniformLocation(programHandle, "uUseBlockFaceCulling");
			commandBaseIndexLocation = GL20C.glGetUniformLocation(programHandle, "uCommandBaseIndex");
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

	public static GpuCommandGenerationResult generateIndexedCommands(
		IndexedIndirectCommandBuffer commandBuffer,
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		float[] frustumPlanes,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices
	) {
		if (!enabled) {
			return GpuCommandGenerationResult.empty();
		}

		ByteIterator iterator = renderList.sectionsWithGeometryIterator(renderPass.isTranslucent());
		if (iterator == null) {
			return GpuCommandGenerationResult.empty();
		}

		int sectionCount = renderList.getSectionsWithGeometryCount();
		if (sectionCount <= 0) {
			return GpuCommandGenerationResult.empty();
		}

		ensureCapacity(sectionCount);

		int packedSectionCount = packSectionMetadata(
			region,
			storage,
			cameraTransform,
			preferLocalIndices,
			iterator
		);
		if (packedSectionCount == 0) {
			return GpuCommandGenerationResult.empty();
		}

		int maxCommandCount = Math.max(Math.multiplyExact(packedSectionCount, ModelQuadFacing.COUNT), 1);
		int firstCommandIndex = commandBuffer.reserveGpuCommandRange(maxCommandCount);
		uploadSectionMetadata(packedSectionCount);
		resetCounters();

		int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, inputBufferHandle);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, counterBufferHandle);
		commandBuffer.bindAsStorage(2);
		try {
			GL20C.glUseProgram(programHandle);
			GL20C.glUniform4fv(frustumPlanesLocation, frustumPlanes);
			GL30C.glUniform1ui(sectionCountLocation, packedSectionCount);
			GL20C.glUniform3i(
				cameraBlockPositionLocation,
				cameraTransform.intX,
				cameraTransform.intY,
				cameraTransform.intZ
			);
			GL30C.glUniform1ui(useBlockFaceCullingLocation, useBlockFaceCulling ? 1 : 0);
			GL30C.glUniform1ui(commandBaseIndexLocation, firstCommandIndex);
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

		ByteBuffer readbackView = counterBufferView.duplicate().order(ByteOrder.nativeOrder());
		readbackView.clear();
		readbackView.limit(COUNTER_BUFFER_BYTES);
		GL45C.glGetNamedBufferSubData(counterBufferHandle, 0L, readbackView);
		int commandCount = readbackView.getInt(COUNTER_COMMAND_COUNT_OFFSET);
		int visibleSectionCount = readbackView.getInt(COUNTER_VISIBLE_SECTION_COUNT_OFFSET);
		if (commandCount < 0) {
			throw new IllegalStateException("Compute produced a negative commandCount.");
		}
		if (visibleSectionCount < 0) {
			throw new IllegalStateException("Compute produced a negative visibleSectionCount.");
		}

		if (commandCount > maxCommandCount) {
			throw new IllegalStateException("Compute generated more commands than the reserved indirect range.");
		}
		if (visibleSectionCount > packedSectionCount) {
			throw new IllegalStateException("Compute reported more visible sections than were dispatched.");
		}

		return new GpuCommandGenerationResult(
			true,
			firstCommandIndex,
			commandCount,
			packedSectionCount,
			visibleSectionCount
		);
	}

	public static void shutdown() {
		enabled = false;
		releaseResources();
		disableReason = "shutdown";
	}

	private static int packSectionMetadata(
		RenderRegion region,
		SectionRenderDataStorage storage,
		CameraTransform cameraTransform,
		boolean preferLocalIndices,
		ByteIterator iterator
	) {
		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		float cameraX = cameraTransform.intX + cameraTransform.fracX;
		float cameraY = cameraTransform.intY + cameraTransform.fracY;
		float cameraZ = cameraTransform.intZ + cameraTransform.fracZ;
		ByteBuffer metadataView = sectionMetadataBuffer.duplicate().order(ByteOrder.nativeOrder());
		metadataView.clear();
		int ordinal = 0;

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			long dataPointer = storage.getDataPointer(localSectionIndex);
			int sectionChunkX = regionChunkX + LocalSectionIndex.unpackX(localSectionIndex);
			int sectionChunkY = regionChunkY + LocalSectionIndex.unpackY(localSectionIndex);
			int sectionChunkZ = regionChunkZ + LocalSectionIndex.unpackZ(localSectionIndex);
			float centerX = ((sectionChunkX << 4) + 8.0f) - cameraX;
			float centerY = ((sectionChunkY << 4) + 8.0f) - cameraY;
			float centerZ = ((sectionChunkZ << 4) + 8.0f) - cameraZ;
			long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
			int flags = preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer) ? FLAG_USE_LOCAL_INDEX : 0;

			metadataView.putFloat(centerX);
			metadataView.putFloat(centerY);
			metadataView.putFloat(centerZ);
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
			metadataView.putInt(flags);
			ordinal++;
		}

		return ordinal;
	}

	private static void uploadSectionMetadata(int packedSectionCount) {
		ByteBuffer metadataView = sectionMetadataBuffer.duplicate().order(ByteOrder.nativeOrder());
		metadataView.clear();
		metadataView.limit(packedSectionCount * INPUT_STRIDE_BYTES);
		GL45C.glNamedBufferSubData(inputBufferHandle, 0L, metadataView);
	}

	private static void resetCounters() {
		ByteBuffer counterResetView = counterBufferView.duplicate().order(ByteOrder.nativeOrder());
		counterResetView.clear();
		counterResetView.putInt(0);
		counterResetView.putInt(0);
		counterResetView.flip();
		GL45C.glNamedBufferSubData(counterBufferHandle, 0L, counterResetView);
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

		if (sectionMetadataBuffer != null) {
			MemoryUtil.memFree(sectionMetadataBuffer);
			sectionMetadataBuffer = null;
		}

		if (counterBufferView != null) {
			MemoryUtil.memFree(counterBufferView);
			counterBufferView = null;
		}

		capacitySections = 0;
	}

	private static void ensureCapacity(int requiredSections) {
		if (requiredSections <= capacitySections) {
			return;
		}

		if (sectionMetadataBuffer != null) {
			MemoryUtil.memFree(sectionMetadataBuffer);
		}

		capacitySections = Integer.highestOneBit(Math.max(requiredSections - 1, 1)) << 1;
		sectionMetadataBuffer = MemoryUtil.memAlloc(capacitySections * INPUT_STRIDE_BYTES).order(ByteOrder.nativeOrder());
		GL45C.glNamedBufferData(inputBufferHandle, (long) capacitySections * INPUT_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
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

	public record GpuCommandGenerationResult(
		boolean dispatched,
		int firstCommandIndex,
		int commandCount,
		int testedSectionCount,
		int visibleSectionCount
	) {
		private static final GpuCommandGenerationResult EMPTY = new GpuCommandGenerationResult(false, 0, 0, 0, 0);

		public static GpuCommandGenerationResult empty() {
			return EMPTY;
		}

		public int culledSectionCount() {
			return this.testedSectionCount - this.visibleSectionCount;
		}
	}
}
