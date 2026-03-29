package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.SodiumBridge;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerMixin {
	@Shadow
	@Final
	@Mutable
	private ChunkRenderer chunkRenderer;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void potassium$wrapRenderer(CallbackInfo ci) {
		this.chunkRenderer = SodiumBridge.wrapChunkRenderer(this.chunkRenderer);
	}

	@Inject(method = "update", at = @At("HEAD"))
	private void potassium$captureViewport(
		Camera camera,
		Viewport viewport,
		FogParameters fogParameters,
		boolean spectator,
		CallbackInfo ci
	) {
		SodiumBridge.captureViewport(viewport);
	}
}
