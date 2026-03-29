package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.SodiumBridge;
import net.caffeinemc.mods.sodium.client.gui.SodiumDebugEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SodiumDebugEntry.class)
public abstract class SodiumDebugEntryMixin {
	@Shadow
	@Final
	private static Identifier DEBUG_GROUP;

	@ModifyArg(
		method = "display",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;addToGroup(Lnet/minecraft/resources/Identifier;Ljava/lang/String;)V",
			ordinal = 0
		),
		index = 1
	)
	private String potassium$appendPatchedMarker(String line) {
		if (line.contains("PotassiumPatched")) {
			return line;
		}

		return line + " " + ChatFormatting.RED + "PotassiumPatched";
	}

	@Inject(method = "display", at = @At("TAIL"))
	private void potassium$appendBridgeStats(
		DebugScreenDisplayer displayer,
		Level level,
		LevelChunk clientChunk,
		LevelChunk serverChunk,
		CallbackInfo ci
	) {
		displayer.addToGroup(DEBUG_GROUP, SodiumBridge.getDebugLines());
	}
}
