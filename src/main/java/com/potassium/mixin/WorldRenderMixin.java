package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class WorldRenderMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void potassium$beginFrame(CallbackInfo ci) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onRenderLevelStart();
		}
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void potassium$endFrame(CallbackInfo ci) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onRenderLevelEnd();
		}
	}
}
