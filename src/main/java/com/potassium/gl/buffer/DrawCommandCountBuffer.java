package com.potassium.gl.buffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

public final class DrawCommandCountBuffer implements AutoCloseable {
	public static final int OPAQUE_COUNT_OFFSET_BYTES = 0;
	public static final int TRANSLUCENT_COUNT_OFFSET_BYTES = Integer.BYTES;
	private static final int BUFFER_BYTES = Integer.BYTES * 2;

	private final PersistentBuffer storage;
	private final ByteBuffer zeroBuffer;
	private final ByteBuffer readbackBuffer;

	public DrawCommandCountBuffer(boolean persistentMappingEnabled) {
		this.storage = new PersistentBuffer(GL46C.GL_PARAMETER_BUFFER, BUFFER_BYTES, persistentMappingEnabled, 1);
		this.zeroBuffer = MemoryUtil.memCalloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());
		this.readbackBuffer = MemoryUtil.memAlloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());
		this.reset();
	}

	public void beginFrame() {
		this.storage.beginFrame();
	}

	public void endFrame() {
		this.storage.endFrame();
	}

	public void resetAndBind(int binding) {
		this.reset();
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	public void bindForDrawCount() {
		GL15C.glBindBuffer(GL46C.GL_PARAMETER_BUFFER, this.storage.handle());
	}

	public Counts read() {
		ByteBuffer readback = this.readbackBuffer.duplicate().order(ByteOrder.nativeOrder());
		readback.clear();
		org.lwjgl.opengl.GL45C.glGetNamedBufferSubData(this.storage.handle(), 0L, readback);
		return new Counts(
			this.readbackBuffer.getInt(OPAQUE_COUNT_OFFSET_BYTES),
			this.readbackBuffer.getInt(TRANSLUCENT_COUNT_OFFSET_BYTES)
		);
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.zeroBuffer);
		MemoryUtil.memFree(this.readbackBuffer);
	}

	private void reset() {
		ByteBuffer zeros = this.zeroBuffer.duplicate().order(ByteOrder.nativeOrder());
		zeros.clear();
		this.storage.upload(zeros, 0L);
	}

	public record Counts(int opaqueCount, int translucentCount) {
	}
}
