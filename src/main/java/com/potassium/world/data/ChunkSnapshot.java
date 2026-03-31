package com.potassium.world.data;

import java.nio.ByteBuffer;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.system.MemoryUtil;

public final class ChunkSnapshot implements AutoCloseable {
	private final ChunkPos chunkPos;
	private final int minSectionY;
	private final int sectionsCount;
	private ByteBuffer blockData;

	public ChunkSnapshot(ChunkPos chunkPos, int minSectionY, int sectionsCount, ByteBuffer blockData) {
		this.chunkPos = chunkPos;
		this.minSectionY = minSectionY;
		this.sectionsCount = sectionsCount;
		this.blockData = blockData;
	}

	public ChunkPos chunkPos() {
		return this.chunkPos;
	}

	public int minSectionY() {
		return this.minSectionY;
	}

	public int sectionsCount() {
		return this.sectionsCount;
	}

	public ByteBuffer blockData() {
		return this.blockData.duplicate();
	}

	public int byteSize() {
		return this.blockData.remaining();
	}

	@Override
	public void close() {
		if (this.blockData != null) {
			MemoryUtil.memFree(this.blockData);
			this.blockData = null;
		}
	}
}
