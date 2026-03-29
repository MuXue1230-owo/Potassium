package com.potassium.client.compat.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;

final class PotassiumSodiumChunkRenderer implements ChunkRenderer {
	private final ChunkRenderer delegate;

	PotassiumSodiumChunkRenderer(ChunkRenderer delegate) {
		this.delegate = delegate;
	}

	@Override
	public void render(
		ChunkRenderMatrices matrices,
		CommandList commandList,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		FogParameters fogParameters,
		boolean fragmentDiscard,
		GpuSampler sampler
	) {
		SodiumBridge.beginRenderPass(renderPass, renderLists);

		try {
			this.delegate.render(
				matrices,
				commandList,
				renderLists,
				renderPass,
				cameraTransform,
				fogParameters,
				fragmentDiscard,
				sampler
			);
		} finally {
			SodiumBridge.endRenderPass();
		}
	}

	@Override
	public void delete(CommandList commandList) {
		this.delegate.delete(commandList);
	}
}
