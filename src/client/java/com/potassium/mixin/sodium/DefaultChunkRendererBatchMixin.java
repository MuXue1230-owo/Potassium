package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.SodiumBridge;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererBatchMixin {
	@Inject(method = "executeDrawBatch", at = @At("HEAD"), cancellable = true)
	private static void potassium$executeIndirectBatch(
		CommandList commandList,
		GlTessellation tessellation,
		MultiDrawBatch batch,
		CallbackInfo ci
	) {
		if (SodiumBridge.tryDrawIndexedBatch(commandList, tessellation, batch)) {
			ci.cancel();
		}
	}
}
