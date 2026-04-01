package com.potassium.gl.buffer;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL43C;

public final class MeshVertexBuffer implements AutoCloseable {
	public static final int UINTS_PER_VERTEX = 4;
	public static final int BYTES_PER_VERTEX = Integer.BYTES * UINTS_PER_VERTEX;
	public static final int VERTICES_PER_FACE = 6;

	private final PersistentBuffer storage;
	private final int facesPerChunk;

	private int capacityChunks;

	public MeshVertexBuffer(int initialCapacityChunks, int facesPerChunk) {
		this.capacityChunks = Math.max(initialCapacityChunks, 1);
		this.facesPerChunk = Math.max(facesPerChunk, 1);
		this.storage = new PersistentBuffer(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			toByteSize(this.capacityChunks),
			false,
			1
		);
	}

	public void ensureChunkCapacity(int requiredChunks) {
		if (requiredChunks <= this.capacityChunks) {
			return;
		}

		this.capacityChunks = requiredChunks;
		this.storage.ensureCapacity(toByteSize(this.capacityChunks));
	}

	public void bindStorage(int binding) {
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public void bindArrayBuffer() {
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.storage.handle());
	}

	public int handle() {
		return this.storage.handle();
	}

	public int facesPerChunk() {
		return this.facesPerChunk;
	}

	public int verticesPerChunk() {
		return this.facesPerChunk * VERTICES_PER_FACE;
	}

	@Override
	public void close() {
		this.storage.close();
		this.capacityChunks = 0;
	}

	private long toByteSize(int chunkCapacity) {
		return (long) Math.max(chunkCapacity, 1) * this.verticesPerChunk() * BYTES_PER_VERTEX;
	}
}
