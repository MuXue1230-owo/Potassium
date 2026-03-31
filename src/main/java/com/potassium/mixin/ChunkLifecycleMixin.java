package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ChunkLifecycleMixin {
	@Inject(method = "onChunkLoaded", at = @At("TAIL"))
	private void potassium$trackChunkLoad(ChunkPos chunkPos, CallbackInfo ci) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onChunkLoaded(chunkPos);
		}
	}

	@Inject(method = "unload", at = @At("HEAD"))
	private void potassium$trackChunkUnload(LevelChunk chunk, CallbackInfo ci) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine != null) {
			engine.onChunkUnloaded(chunk.getPos());
		}
	}
}
