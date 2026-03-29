package com.potassium.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import com.potassium.client.compat.sodium.SodiumBridge;
import java.util.Iterator;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererRenderMixin {
	@Inject(
		method = "render",
		at = @At("HEAD")
	)
	private void potassium$scheduleGeneratedBatches(
		ChunkRenderMatrices matrices,
		CommandList commandList,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		FogParameters fogParameters,
		boolean fragmentDiscard,
		GpuSampler sampler,
		CallbackInfo ci
	) {
		SodiumBridge.schedulePreparedBatches(matrices, renderLists, renderPass, cameraTransform, fragmentDiscard);
	}

	@Inject(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;executeDrawBatch(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/gl/tessellation/GlTessellation;Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;)V"
		),
		locals = LocalCapture.CAPTURE_FAILHARD
	)
	private void potassium$prepareGeneratedBatch(
		ChunkRenderMatrices matrices,
		CommandList commandList,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		FogParameters fogParameters,
		boolean fragmentDiscard,
		GpuSampler sampler,
		CallbackInfo ci,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices,
		ChunkShaderInterface shaderInterface,
		Iterator<ChunkRenderList> renderListIterator,
		ChunkRenderList renderList,
		RenderRegion region,
		SectionRenderDataStorage storage,
		MultiDrawBatch batch,
		GlTessellation tessellation
	) {
		SodiumBridge.prepareIndexedBatchFromRenderData(
			region,
			storage,
			renderList,
			cameraTransform,
			renderPass,
			fragmentDiscard
		);
	}
}
