package com.potassium.world.data;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record BlockData(int packed) {
	private static final int STATE_ID_BITS = 20;
	private static final int LIGHT_BITS = 4;
	private static final int FLAGS_BITS = 4;
	private static final int FLAGS_SHIFT = STATE_ID_BITS + (LIGHT_BITS * 2);

	private static final int STATE_ID_MASK = (1 << STATE_ID_BITS) - 1;
	private static final int LIGHT_MASK = (1 << LIGHT_BITS) - 1;
	private static final int FLAGS_MASK = (1 << FLAGS_BITS) - 1;

	public static final int BYTES = Integer.BYTES;
	public static final int AIR_PACKED = 0;
	public static final int FLAG_OCCLUDES = 1 << 0;
	public static final int FLAG_FLUID = 1 << 1;
	public static final int FLAG_TRANSLUCENT = 1 << 2;

	public static BlockData fromState(BlockState state) {
		return new BlockData(pack(state, 0, 0, classifyFlags(state)));
	}

	public static BlockData fromState(BlockState state, int blockLight, int skyLight) {
		return new BlockData(pack(state, blockLight, skyLight, classifyFlags(state)));
	}

	public static int pack(BlockState state, int blockLight, int skyLight, int flags) {
		if (state == null || state.isAir()) {
			return AIR_PACKED;
		}

		int stateId = Block.getId(state) & STATE_ID_MASK;
		int packedBlockLight = (blockLight & LIGHT_MASK) << STATE_ID_BITS;
		int packedSkyLight = (skyLight & LIGHT_MASK) << (STATE_ID_BITS + LIGHT_BITS);
		int packedFlags = (flags & FLAGS_MASK) << FLAGS_SHIFT;
		return stateId | packedBlockLight | packedSkyLight | packedFlags;
	}

	public static int stateId(int packedBlock) {
		return packedBlock & STATE_ID_MASK;
	}

	public static int flags(int packedBlock) {
		return (packedBlock >>> FLAGS_SHIFT) & FLAGS_MASK;
	}

	public static int blockLight(int packedBlock) {
		return (packedBlock >>> STATE_ID_BITS) & LIGHT_MASK;
	}

	public static int skyLight(int packedBlock) {
		return (packedBlock >>> (STATE_ID_BITS + LIGHT_BITS)) & LIGHT_MASK;
	}

	private static int classifyFlags(BlockState state) {
		int flags = 0;
		if (state.canOcclude()) {
			flags |= FLAG_OCCLUDES;
		}
		if (!state.getFluidState().isEmpty()) {
			flags |= FLAG_FLUID;
		}
		if (!state.canOcclude()) {
			flags |= FLAG_TRANSLUCENT;
		}

		return flags;
	}
}
