package com.potassium.gl.buffer;

import com.potassium.render.material.BlockMaterialTable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class MaterialTableBuffer implements AutoCloseable {
	public static final int BYTES_PER_STATE = BlockMaterialTable.WORDS_PER_STATE * Integer.BYTES;

	private final PersistentBuffer storage;
	private ByteBuffer uploadBuffer;
	private IntBuffer uploadInts;

	private int capacityStates;

	public MaterialTableBuffer(int initialCapacityStates, boolean persistentMappingEnabled) {
		this.capacityStates = Math.max(initialCapacityStates, 1);
		this.storage = new PersistentBuffer(
			GL43C.GL_SHADER_STORAGE_BUFFER,
			toByteSize(this.capacityStates),
			persistentMappingEnabled,
			1
		);
		this.uploadBuffer = allocateUploadBuffer(this.capacityStates);
		this.uploadInts = this.uploadBuffer.asIntBuffer();
	}

	public void ensureStateCapacity(int requiredStates) {
		if (requiredStates <= this.capacityStates) {
			return;
		}

		this.capacityStates = requiredStates;
		this.storage.ensureCapacity(toByteSize(this.capacityStates));
		MemoryUtil.memFree(this.uploadBuffer);
		this.uploadBuffer = allocateUploadBuffer(this.capacityStates);
		this.uploadInts = this.uploadBuffer.asIntBuffer();
	}

	public void upload(BlockMaterialTable materialTable) {
		int stateCount = Math.max(materialTable.stateCount(), 1);
		this.ensureStateCapacity(stateCount);
		MemoryUtil.memSet(MemoryUtil.memAddress(this.uploadBuffer), 0, this.uploadBuffer.capacity());
		IntBuffer ints = this.uploadInts.duplicate();
		ints.clear();
		ints.put(materialTable.words(), 0, materialTable.words().length);

		ByteBuffer bytes = this.uploadBuffer.duplicate().order(ByteOrder.nativeOrder());
		bytes.clear();
		bytes.limit(materialTable.words().length * Integer.BYTES);
		this.storage.upload(bytes, 0L);
	}

	public void bind(int binding) {
		this.storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
	}

	@Override
	public void close() {
		this.storage.close();
		MemoryUtil.memFree(this.uploadBuffer);
		this.capacityStates = 0;
	}

	private static ByteBuffer allocateUploadBuffer(int states) {
		return MemoryUtil.memAlloc(Math.toIntExact(toByteSize(states))).order(ByteOrder.nativeOrder());
	}

	private static long toByteSize(int states) {
		return (long) Math.max(states, 1) * BYTES_PER_STATE;
	}
}
