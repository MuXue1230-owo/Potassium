package com.potassium.render.material;

import com.potassium.core.PotassiumLogger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.data.AtlasIds;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material.Baked;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockMaterialTable {
	public static final int FACE_COUNT = 6;
	public static final int WORDS_PER_FACE = 6;
	public static final int WORDS_PER_STATE = FACE_COUNT * WORDS_PER_FACE;

	public static final int FACE_U0_OFFSET = 0;
	public static final int FACE_U1_OFFSET = 1;
	public static final int FACE_V0_OFFSET = 2;
	public static final int FACE_V1_OFFSET = 3;
	public static final int FACE_TINT_INDEX_OFFSET = 4;
	public static final int FACE_FLAGS_OFFSET = 5;

	public static final int FLAG_SHADE = 1 << 0;
	public static final int FLAG_USE_AO = 1 << 1;
	public static final int FLAG_TINTED = 1 << 2;
	public static final int FLAG_LAYER_CUTOUT = 1 << 3;
	public static final int FLAG_LAYER_TRANSLUCENT = 1 << 4;

	private static final Direction[] FACE_DIRECTIONS = {
		Direction.UP,
		Direction.DOWN,
		Direction.EAST,
		Direction.WEST,
		Direction.SOUTH,
		Direction.NORTH
	};

	private final int stateCount;
	private final int resolvedStates;
	private final int[] words;

	private BlockMaterialTable(int stateCount, int resolvedStates, int[] words) {
		this.stateCount = Math.max(stateCount, 1);
		this.resolvedStates = Math.max(resolvedStates, 0);
		this.words = words;
	}

	public static BlockMaterialTable empty() {
		return new BlockMaterialTable(1, 0, new int[WORDS_PER_STATE]);
	}

	public static BlockMaterialTable captureFromMinecraft() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return empty();
		}

		ModelManager modelManager = minecraft.getModelManager();
		if (modelManager == null) {
			PotassiumLogger.logger().warn("Skipping block material table rebuild because ModelManager is unavailable.");
			return empty();
		}

		BlockStateModelSet stateModels;
		try {
			stateModels = modelManager.getBlockStateModelSet();
		} catch (RuntimeException exception) {
			PotassiumLogger.logger().warn(
				"Skipping block material table rebuild because block models are not ready yet: {}",
				exception.getMessage(),
				exception
			);
			return empty();
		}
		if (stateModels == null) {
			PotassiumLogger.logger().warn("Skipping block material table rebuild because BlockStateModelSet is unavailable.");
			return empty();
		}

		TextureAtlasSprite missingSprite;
		try {
			missingSprite = minecraft.getAtlasManager()
				.getAtlasOrThrow(AtlasIds.BLOCKS)
				.missingSprite();
		} catch (RuntimeException exception) {
			PotassiumLogger.logger().warn(
				"Skipping block material table rebuild because the block atlas is not ready yet: {}",
				exception.getMessage(),
				exception
			);
			return empty();
		}
		RandomSource random = RandomSource.create(0L);
		ArrayList<BlockStateModelPart> parts = new ArrayList<>();
		int maxStateId = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				maxStateId = Math.max(maxStateId, Block.getId(state));
			}
		}

		int[] words = new int[(maxStateId + 1) * WORDS_PER_STATE];
		int resolvedStates = 0;
		int faceOverrides = 0;

		for (Block block : BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				int stateId = Block.getId(state);
				TextureAtlasSprite defaultSprite = resolveParticleSprite(stateModels, state, missingSprite);
				if (defaultSprite == null) {
					defaultSprite = missingSprite;
				}

				int defaultFaceFlags = 0;
				Baked particleMaterial = stateModels.getParticleMaterial(state);
				if (particleMaterial != null && particleMaterial.forceTranslucent()) {
					defaultFaceFlags |= FLAG_LAYER_TRANSLUCENT;
				}

				for (int faceIndex = 0; faceIndex < FACE_COUNT; faceIndex++) {
					writeFace(words, stateId, faceIndex, defaultSprite, -1, defaultFaceFlags);
				}

				BlockStateModel stateModel = stateModels.get(state);
				if (stateModel == null) {
					continue;
				}

				resolvedStates++;
				parts.clear();
				random.setSeed(0L);
				stateModel.collectParts(random, parts);
				for (int faceIndex = 0; faceIndex < FACE_COUNT; faceIndex++) {
					Direction direction = FACE_DIRECTIONS[faceIndex];
					FaceMaterial override = resolveFaceMaterial(parts, direction);
					if (override == null) {
						continue;
					}

					writeFace(words, stateId, faceIndex, override.sprite(), override.tintIndex(), override.flags());
					faceOverrides++;
				}
			}
		}

		PotassiumLogger.logger().info(
			"Rebuilt block material table: states={}, resolvedStates={}, faceOverrides={}.",
			maxStateId + 1,
			resolvedStates,
			faceOverrides
		);
		return new BlockMaterialTable(maxStateId + 1, resolvedStates, words);
	}

	public int stateCount() {
		return this.stateCount;
	}

	public int resolvedStates() {
		return this.resolvedStates;
	}

	public boolean isEmpty() {
		return this.resolvedStates <= 0;
	}

	public int[] words() {
		return this.words;
	}

	private static TextureAtlasSprite resolveParticleSprite(
		BlockStateModelSet stateModels,
		BlockState state,
		TextureAtlasSprite missingSprite
	) {
		Baked particleMaterial = stateModels.getParticleMaterial(state);
		if (particleMaterial != null && particleMaterial.sprite() != null) {
			return particleMaterial.sprite();
		}

		return missingSprite;
	}

	private static FaceMaterial resolveFaceMaterial(List<BlockStateModelPart> parts, Direction direction) {
		for (BlockStateModelPart part : parts) {
			List<BakedQuad> quads = part.getQuads(direction);
			if (quads == null || quads.isEmpty()) {
				continue;
			}

			BakedQuad quad = quads.getFirst();
			TextureAtlasSprite sprite = quad.materialInfo().sprite();
			if (sprite == null) {
				continue;
			}

			int faceFlags = 0;
			if (part.useAmbientOcclusion()) {
				faceFlags |= FLAG_USE_AO;
			}
			if (quad.materialInfo().shade()) {
				faceFlags |= FLAG_SHADE;
			}
			if (quad.materialInfo().isTinted()) {
				faceFlags |= FLAG_TINTED;
			}
			ChunkSectionLayer layer = quad.materialInfo().layer();
			if (layer == ChunkSectionLayer.CUTOUT) {
				faceFlags |= FLAG_LAYER_CUTOUT;
			} else if (layer == ChunkSectionLayer.TRANSLUCENT || (quad.materialInfo().flags() & BakedQuad.FLAG_TRANSLUCENT) != 0) {
				faceFlags |= FLAG_LAYER_TRANSLUCENT;
			}

			return new FaceMaterial(sprite, quad.materialInfo().tintIndex(), faceFlags);
		}

		return null;
	}

	private static void writeFace(
		int[] words,
		int stateId,
		int faceIndex,
		TextureAtlasSprite sprite,
		int tintIndex,
		int flags
	) {
		int base = (stateId * WORDS_PER_STATE) + (faceIndex * WORDS_PER_FACE);
		words[base + FACE_U0_OFFSET] = Float.floatToRawIntBits(sprite.getU0());
		words[base + FACE_U1_OFFSET] = Float.floatToRawIntBits(sprite.getU1());
		words[base + FACE_V0_OFFSET] = Float.floatToRawIntBits(sprite.getV0());
		words[base + FACE_V1_OFFSET] = Float.floatToRawIntBits(sprite.getV1());
		words[base + FACE_TINT_INDEX_OFFSET] = tintIndex;
		words[base + FACE_FLAGS_OFFSET] = flags;
	}

	private record FaceMaterial(TextureAtlasSprite sprite, int tintIndex, int flags) {
	}
}
