package com.potassium.gl.buffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class MeshVertexBuffer implements AutoCloseable {
	public static final int UINTS_PER_VERTEX = 7;
	public static final int BYTES_PER_VERTEX = Integer.BYTES * UINTS_PER_VERTEX;
	public static final int VERTICES_PER_FACE = 4;

	private static final int BUFFER_USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST;
	private static final Field GL_BUFFER_HANDLE_FIELD = resolveGlBufferHandleField();

	private final int facesPerChunk;

	private int capacityChunks;
	private GpuBuffer gpuBuffer;
	private int handle;

	public MeshVertexBuffer(int initialCapacityChunks, int facesPerChunk) {
		this.capacityChunks = Math.max(initialCapacityChunks, 1);
		this.facesPerChunk = Math.max(facesPerChunk, 1);
		this.gpuBuffer = createBuffer(this.capacityChunks);
		this.handle = resolveGlBufferHandle(this.gpuBuffer);
	}

	public void ensureChunkCapacity(int requiredChunks) {
		if (requiredChunks <= this.capacityChunks) {
			return;
		}

		int previousCapacity = this.capacityChunks;
		GpuBuffer previousBuffer = this.gpuBuffer;
		int previousHandle = this.handle;
		this.capacityChunks = requiredChunks;

		GpuBuffer expandedBuffer = createBuffer(this.capacityChunks);
		int expandedHandle = resolveGlBufferHandle(expandedBuffer);
		long copyBytes = toByteSize(previousCapacity);
		if (previousBuffer != null && previousHandle != 0 && copyBytes > 0L) {
			GL45C.glCopyNamedBufferSubData(previousHandle, expandedHandle, 0L, 0L, copyBytes);
			previousBuffer.close();
		}

		this.gpuBuffer = expandedBuffer;
		this.handle = expandedHandle;
	}

	public void bindStorage(int binding) {
		GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, this.handle);
	}

	public void bindArrayBuffer() {
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.handle);
	}

	public int handle() {
		return this.handle;
	}

	public GpuBuffer gpuBuffer() {
		return this.gpuBuffer;
	}

	public int facesPerChunk() {
		return this.facesPerChunk;
	}

	public int verticesPerChunk() {
		return this.facesPerChunk * VERTICES_PER_FACE;
	}

	public IntBuffer readVertices(int firstVertex, int vertexCount) {
		int clampedFirstVertex = Math.max(firstVertex, 0);
		int clampedVertexCount = Math.max(vertexCount, 0);
		IntBuffer readback = MemoryUtil.memAllocInt(clampedVertexCount * UINTS_PER_VERTEX);
		GL45C.glGetNamedBufferSubData(
			this.handle,
			(long) clampedFirstVertex * BYTES_PER_VERTEX,
			readback
		);
		readback.clear();
		return readback;
	}

	@Override
	public void close() {
		if (this.gpuBuffer != null) {
			this.gpuBuffer.close();
			this.gpuBuffer = null;
		}
		this.handle = 0;
		this.capacityChunks = 0;
	}

	private GpuBuffer createBuffer(int chunkCapacity) {
		return RenderSystem.getDevice().createBuffer(
			() -> "Potassium Mesh Vertex Buffer",
			BUFFER_USAGE,
			toByteSize(chunkCapacity)
		);
	}

	private long toByteSize(int chunkCapacity) {
		return (long) Math.max(chunkCapacity, 1) * this.verticesPerChunk() * BYTES_PER_VERTEX;
	}

	private static Field resolveGlBufferHandleField() {
		try {
			Class<?> glBufferClass = Class.forName("com.mojang.blaze3d.opengl.GlBuffer");
			Field handleField = glBufferClass.getDeclaredField("handle");
			handleField.setAccessible(true);
			return handleField;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Failed to resolve Minecraft OpenGL buffer handle access.", exception);
		}
	}

	private static int resolveGlBufferHandle(GpuBuffer gpuBuffer) {
		try {
			return GL_BUFFER_HANDLE_FIELD.getInt(gpuBuffer);
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Failed to access Minecraft OpenGL buffer handle.", exception);
		}
	}
}
