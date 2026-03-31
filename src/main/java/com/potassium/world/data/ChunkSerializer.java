package com.potassium.world.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.lwjgl.system.MemoryUtil;

public final class ChunkSerializer {
	private ChunkSerializer() {
	}

	public static ChunkSnapshot serialize(LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		ByteBuffer buffer = MemoryUtil.memAlloc(sections.length * LevelChunkSection.SECTION_SIZE * BlockData.BYTES)
			.order(ByteOrder.nativeOrder());

		for (LevelChunkSection section : sections) {
			if (section == null || section.hasOnlyAir()) {
				for (int i = 0; i < LevelChunkSection.SECTION_SIZE; i++) {
					buffer.putInt(BlockData.AIR_PACKED);
				}
				continue;
			}

			section.acquire();
			try {
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							buffer.putInt(BlockData.fromState(section.getBlockState(x, y, z), 0).packed());
						}
					}
				}
			} finally {
				section.release();
			}
		}

		buffer.flip();
		return new ChunkSnapshot(
			chunk.getPos(),
			SectionPos.blockToSectionCoord(chunk.getMinY()),
			sections.length,
			buffer
		);
	}
}
