package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public final class MeshGenerationStatsBuffer implements AutoCloseable {
	private static final int BUFFER_BYTES = Integer.BYTES * 4;

	private final ByteBuffer readbackBuffer;

	private int handle;

	public MeshGenerationStatsBuffer() {
		this.handle = GL45C.glCreateBuffers();
		GL45C.glNamedBufferStorage(this.handle, BUFFER_BYTES, GL45C.GL_DYNAMIC_STORAGE_BIT);
		this.readbackBuffer = MemoryUtil.memAlloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());
	}

	public void bind(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, this.handle);
	}

	public void clear() {
		this.readbackBuffer.clear();
		this.readbackBuffer.putInt(0).putInt(0).putInt(0).putInt(0);
		this.readbackBuffer.flip();
		GL45C.glNamedBufferSubData(this.handle, 0L, this.readbackBuffer);
	}

	public Stats read() {
		ByteBuffer readback = this.readbackBuffer.duplicate().order(ByteOrder.nativeOrder());
		readback.clear();
		GL45C.glGetNamedBufferSubData(this.handle, 0L, readback);
		return new Stats(
			this.readbackBuffer.getInt(0),
			this.readbackBuffer.getInt(Integer.BYTES),
			this.readbackBuffer.getInt(Integer.BYTES * 2),
			this.readbackBuffer.getInt(Integer.BYTES * 3)
		);
	}

	@Override
	public void close() {
		if (this.handle != 0) {
			GL15C.glDeleteBuffers(this.handle);
			this.handle = 0;
		}

		MemoryUtil.memFree(this.readbackBuffer);
	}

	public record Stats(int processedJobs, int generatedVertices, int clippedJobs, int lastSampledPackedBlock) {
	}
}
