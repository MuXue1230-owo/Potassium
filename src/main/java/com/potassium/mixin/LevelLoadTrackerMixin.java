package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLoadTracker.class)
public abstract class LevelLoadTrackerMixin {

	@Inject(method = "tickClientLoad", at = @At("HEAD"))
	private void potassium$drainChunksAndBlockForGpu(CallbackInfo ci) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null && engine.isRuntimeReady()) {
			engine.drainInitialChunksAndBlockForGpu();
		}
	}

	@Inject(method = "isLevelReady", at = @At("HEAD"), cancellable = true)
	private void potassium$delayUntilGpuMeshReady(CallbackInfoReturnable<Boolean> cir) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null && engine.isRuntimeReady() && engine.isInitialMeshGenerationPending()) {
			cir.setReturnValue(false);
		}
	}
}
