package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void potassium$onClientTick(CallbackInfo ci) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onClientTick((Minecraft) (Object) this);
		}
	}
}
