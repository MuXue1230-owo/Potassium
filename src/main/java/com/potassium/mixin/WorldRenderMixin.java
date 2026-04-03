package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRenderMixin {
	@Shadow
	private SubmitNodeStorage submitNodeStorage;

	@Inject(
		method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V",
		at = @At("HEAD")
	)
	private void potassium$beginFrame(
		GraphicsResourceAllocator graphicsResourceAllocator,
		DeltaTracker deltaTracker,
		boolean shouldRenderBlockOutline,
		CameraRenderState cameraState,
		Matrix4fc modelViewMatrix,
		GpuBufferSlice fogBuffer,
		Vector4f fogParameters,
		boolean shouldRenderSky,
		ChunkSectionsToRender chunkSectionsToRender,
		CallbackInfo ci
	) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onRenderLevelStart();
		}
	}

	@Inject(
		method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V",
		at = @At("RETURN")
	)
	private void potassium$endFrame(
		GraphicsResourceAllocator graphicsResourceAllocator,
		DeltaTracker deltaTracker,
		boolean shouldRenderBlockOutline,
		CameraRenderState cameraState,
		Matrix4fc modelViewMatrix,
		GpuBufferSlice fogBuffer,
		Vector4f fogParameters,
		boolean shouldRenderSky,
		ChunkSectionsToRender chunkSectionsToRender,
		CallbackInfo ci
	) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onRenderLevelEnd();
		}
	}

	@Redirect(
		method = "lambda$addMainPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;ZLorg/joml/Matrix4fc;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
			ordinal = 0
		)
	)
	private void potassium$renderOpaqueTerrain(
		ChunkSectionsToRender chunkSectionsToRender,
		ChunkSectionLayerGroup chunkSectionLayerGroup,
		GpuSampler gpuSampler,
		GpuBufferSlice fogBuffer,
		LevelRenderState levelRenderState,
		ProfilerFiller profilerFiller,
		ChunkSectionsToRender ignoredChunkSectionsToRender,
		ResourceHandle<?> colorTarget,
		ResourceHandle<?> depthTarget,
		ResourceHandle<?> entityOutlineTarget,
		ResourceHandle<?> translucentTarget,
		ResourceHandle<?> weatherTarget,
		boolean renderBlockOutline,
		Matrix4fc modelViewMatrix
	) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null && engine.onRenderOpaqueTerrain(levelRenderState.cameraRenderState, modelViewMatrix, this.submitNodeStorage)) {
			return;
		}

		chunkSectionsToRender.renderGroup(chunkSectionLayerGroup, gpuSampler);
	}

	@Redirect(
		method = "lambda$addMainPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;Lcom/mojang/blaze3d/resource/ResourceHandle;ZLorg/joml/Matrix4fc;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
			ordinal = 1
		)
	)
	private void potassium$renderTranslucentTerrain(
		ChunkSectionsToRender chunkSectionsToRender,
		ChunkSectionLayerGroup chunkSectionLayerGroup,
		GpuSampler gpuSampler,
		GpuBufferSlice fogBuffer,
		LevelRenderState levelRenderState,
		ProfilerFiller profilerFiller,
		ChunkSectionsToRender ignoredChunkSectionsToRender,
		ResourceHandle<?> colorTarget,
		ResourceHandle<?> depthTarget,
		ResourceHandle<?> entityOutlineTarget,
		ResourceHandle<?> translucentTarget,
		ResourceHandle<?> weatherTarget,
		boolean renderBlockOutline,
		Matrix4fc modelViewMatrix
	) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null && engine.onRenderTranslucentTerrain(levelRenderState.cameraRenderState, modelViewMatrix, this.submitNodeStorage)) {
			return;
		}

		chunkSectionsToRender.renderGroup(chunkSectionLayerGroup, gpuSampler);
	}
}
