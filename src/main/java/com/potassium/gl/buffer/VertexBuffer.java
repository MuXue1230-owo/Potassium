package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL45C;

public final class VertexBuffer implements AutoCloseable {
	private int handle;
	private long sizeBytes;

	public VertexBuffer() {
		this.handle = GL45C.glCreateBuffers();
	}

	public void upload(ByteBuffer data, int usage) {
		ByteBuffer source = data.duplicate();
		this.sizeBytes = source.remaining();
		GL45C.glNamedBufferData(this.handle, source, usage);
	}

	public void bindArrayBuffer() {
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.handle);
	}

	public int handle() {
		return this.handle;
	}

	public long sizeBytes() {
		return this.sizeBytes;
	}

	@Override
	public void close() {
		if (this.handle != 0) {
			GL15C.glDeleteBuffers(this.handle);
			this.handle = 0;
		}

		this.sizeBytes = 0L;
	}
}
