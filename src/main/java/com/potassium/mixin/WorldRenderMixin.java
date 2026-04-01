package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRenderMixin {
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
			engine.onRenderLevelEnd(cameraState, modelViewMatrix);
		}
	}
}
