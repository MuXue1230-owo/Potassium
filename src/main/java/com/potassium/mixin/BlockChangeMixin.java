package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class BlockChangeMixin {
	@Inject(method = "sendBlockUpdated", at = @At("TAIL"))
	private void potassium$trackBlockChange(
		BlockPos pos,
		BlockState oldState,
		BlockState newState,
		int flags,
		CallbackInfo ci
	) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState, flags);
		}
	}
}
