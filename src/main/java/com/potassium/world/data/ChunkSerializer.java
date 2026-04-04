package com.potassium.world.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
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
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int minY = chunk.getMinY();
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
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
							int worldY = minY + (sectionIndex << 4) + y;
							cursor.set(baseX + x, worldY, baseZ + z);
							buffer.putInt(
								BlockData.fromState(
									section.getBlockState(x, y, z),
									chunk.getLevel().getBrightness(LightLayer.BLOCK, cursor),
									chunk.getLevel().getBrightness(LightLayer.SKY, cursor)
								).packed()
							);
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
