package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.PotassiumChunkShaderInterface;
import com.potassium.client.config.PotassiumFeatures;
import com.potassium.client.gl.GLCapabilities;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderChunkRenderer.class)
public abstract class ShaderChunkRendererMixin {
	@Inject(method = "lambda$createShader$0", at = @At("HEAD"), cancellable = true)
	private static void potassium$usePotassiumShaderInterface(
		ChunkShaderOptions options,
		ShaderBindingContext context,
		CallbackInfoReturnable<ChunkShaderInterface> cir
	) {
		if (!PotassiumFeatures.modEnabled() || !GLCapabilities.hasShaderDrawParameters()) {
			return;
		}

		cir.setReturnValue(new PotassiumChunkShaderInterface(context, options));
	}
}
