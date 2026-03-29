package com.potassium.client.compute;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.gl.GLCapabilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
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
	private static final int SECTION_STRIDE_BYTES = Float.BYTES * 4;
	private static final int VISIBILITY_STRIDE_BYTES = Integer.BYTES;
	private static final int LOCAL_SIZE_X = 64;
	private static final float SECTION_BOUNDING_RADIUS = 14.0f;

	private static boolean enabled;
	private static String disableReason = "not initialized";
	private static int programHandle;
	private static int inputBufferHandle;
	private static int outputBufferHandle;
	private static int frustumPlanesLocation = -1;
	private static int sectionCountLocation = -1;
	private static int capacitySections;
	private static ByteBuffer sectionInputBuffer;
	private static ByteBuffer sectionOutputBuffer;
	private static int[] localSectionIndices = new int[0];
	private static byte[] visibilityFlags = new byte[0];

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
			outputBufferHandle = GL45C.glCreateBuffers();
			frustumPlanesLocation = GL20C.glGetUniformLocation(programHandle, "uFrustumPlanes");
			sectionCountLocation = GL20C.glGetUniformLocation(programHandle, "uSectionCount");
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

	public static SectionVisibilityMask cullVisibleSections(
		RenderRegion region,
		ChunkRenderList renderList,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		float[] frustumPlanes
	) {
		if (!enabled) {
			return SectionVisibilityMask.empty();
		}

		ByteIterator iterator = renderList.sectionsWithGeometryIterator(renderPass.isTranslucent());
		if (iterator == null) {
			return SectionVisibilityMask.empty();
		}

		int sectionCount = renderList.getSectionsWithGeometryCount();
		ensureCapacity(sectionCount);

		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		float cameraX = cameraTransform.intX + cameraTransform.fracX;
		float cameraY = cameraTransform.intY + cameraTransform.fracY;
		float cameraZ = cameraTransform.intZ + cameraTransform.fracZ;
		ByteBuffer inputView = sectionInputBuffer.duplicate().order(ByteOrder.nativeOrder());
		inputView.clear();
		int ordinal = 0;

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			localSectionIndices[ordinal] = localSectionIndex;
			float centerX = (((regionChunkX + LocalSectionIndex.unpackX(localSectionIndex)) << 4) + 8.0f) - cameraX;
			float centerY = (((regionChunkY + LocalSectionIndex.unpackY(localSectionIndex)) << 4) + 8.0f) - cameraY;
			float centerZ = (((regionChunkZ + LocalSectionIndex.unpackZ(localSectionIndex)) << 4) + 8.0f) - cameraZ;
			inputView.putFloat(centerX);
			inputView.putFloat(centerY);
			inputView.putFloat(centerZ);
			inputView.putFloat(SECTION_BOUNDING_RADIUS);
			ordinal++;
		}

		if (ordinal == 0) {
			return SectionVisibilityMask.empty();
		}

		inputView.flip();
		GL45C.glNamedBufferData(inputBufferHandle, (long) ordinal * SECTION_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
		GL45C.glNamedBufferSubData(inputBufferHandle, 0L, inputView);
		GL45C.glNamedBufferData(outputBufferHandle, (long) ordinal * VISIBILITY_STRIDE_BYTES, GL15C.GL_DYNAMIC_READ);

		int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, inputBufferHandle);
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, outputBufferHandle);
		try {
			GL20C.glUseProgram(programHandle);
			GL20C.glUniform4fv(frustumPlanesLocation, frustumPlanes);
			GL30C.glUniform1ui(sectionCountLocation, ordinal);
			GL43C.glDispatchCompute((ordinal + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X, 1, 1);
			GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
		} finally {
			GL20C.glUseProgram(previousProgram);
		}

		ByteBuffer outputView = sectionOutputBuffer.duplicate().order(ByteOrder.nativeOrder());
		outputView.clear();
		outputView.limit(ordinal * VISIBILITY_STRIDE_BYTES);
		GL45C.glGetNamedBufferSubData(outputBufferHandle, 0L, outputView);
		outputView.clear();

		int visibleSectionCount = 0;
		for (int index = 0; index < ordinal; index++) {
			byte visible = (byte) (outputView.getInt(index * VISIBILITY_STRIDE_BYTES) != 0 ? 1 : 0);
			visibilityFlags[index] = visible;
			visibleSectionCount += visible;
		}

		return new SectionVisibilityMask(localSectionIndices, visibilityFlags, ordinal, visibleSectionCount);
	}

	public static void shutdown() {
		enabled = false;
		releaseResources();
		disableReason = "shutdown";
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

		if (outputBufferHandle != 0) {
			GL15C.glDeleteBuffers(outputBufferHandle);
			outputBufferHandle = 0;
		}

		if (sectionInputBuffer != null) {
			MemoryUtil.memFree(sectionInputBuffer);
			sectionInputBuffer = null;
		}

		if (sectionOutputBuffer != null) {
			MemoryUtil.memFree(sectionOutputBuffer);
			sectionOutputBuffer = null;
		}

		capacitySections = 0;
		localSectionIndices = new int[0];
		visibilityFlags = new byte[0];
	}

	private static void ensureCapacity(int requiredSections) {
		if (requiredSections <= capacitySections) {
			return;
		}

		if (sectionInputBuffer != null) {
			MemoryUtil.memFree(sectionInputBuffer);
		}
		if (sectionOutputBuffer != null) {
			MemoryUtil.memFree(sectionOutputBuffer);
		}

		capacitySections = Integer.highestOneBit(Math.max(requiredSections - 1, 1)) << 1;
		sectionInputBuffer = MemoryUtil.memAlloc(capacitySections * SECTION_STRIDE_BYTES).order(ByteOrder.nativeOrder());
		sectionOutputBuffer = MemoryUtil.memAlloc(capacitySections * VISIBILITY_STRIDE_BYTES).order(ByteOrder.nativeOrder());
		localSectionIndices = new int[capacitySections];
		visibilityFlags = new byte[capacitySections];
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

	public record SectionVisibilityMask(int[] localSectionIndices, byte[] visibilityFlags, int sectionCount, int visibleSectionCount) {
		private static final SectionVisibilityMask EMPTY = new SectionVisibilityMask(new int[0], new byte[0], 0, 0);

		public static SectionVisibilityMask empty() {
			return EMPTY;
		}

		public boolean isVisible(int ordinal) {
			return ordinal >= 0 && ordinal < this.sectionCount && this.visibilityFlags[ordinal] != 0;
		}

		public int culledSectionCount() {
			return this.sectionCount - this.visibleSectionCount;
		}
	}
}
