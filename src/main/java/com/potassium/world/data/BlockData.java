package com.potassium.world.data;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record BlockData(int packed) {
	private static final int STATE_ID_BITS = 20;
	private static final int LIGHT_BITS = 4;
	private static final int FLAGS_BITS = 4;

	private static final int STATE_ID_MASK = (1 << STATE_ID_BITS) - 1;
	private static final int LIGHT_MASK = (1 << LIGHT_BITS) - 1;
	private static final int FLAGS_MASK = (1 << FLAGS_BITS) - 1;

	public static final int BYTES = Integer.BYTES;
	public static final int AIR_PACKED = 0;

	public static BlockData fromState(BlockState state, int flags) {
		return new BlockData(pack(state, 0, 0, flags));
	}

	public static int pack(BlockState state, int blockLight, int skyLight, int flags) {
		int stateId = Block.getId(state) & STATE_ID_MASK;
		int packedBlockLight = (blockLight & LIGHT_MASK) << STATE_ID_BITS;
		int packedSkyLight = (skyLight & LIGHT_MASK) << (STATE_ID_BITS + LIGHT_BITS);
		int packedFlags = (flags & FLAGS_MASK) << (STATE_ID_BITS + (LIGHT_BITS * 2));
		return stateId | packedBlockLight | packedSkyLight | packedFlags;
	}
}
