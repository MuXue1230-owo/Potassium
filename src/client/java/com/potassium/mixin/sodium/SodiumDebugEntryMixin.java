package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.SodiumBridge;
import com.potassium.client.config.PotassiumConfig;
import com.potassium.client.config.PotassiumFeatures;
import java.util.List;
import net.caffeinemc.mods.sodium.client.gui.SodiumDebugEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.resources.language.I18n;
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
		if (!PotassiumFeatures.modEnabled() || !PotassiumConfig.showF3PatchedMarker()) {
			return line;
		}

		String marker = I18n.get("potassium.debug.marker");
		if (line.contains(marker)) {
			return line;
		}

		return line + ChatFormatting.WHITE + " - " + ChatFormatting.RED + marker;
	}

	@Inject(method = "display", at = @At("TAIL"))
	private void potassium$appendBridgeStats(
		DebugScreenDisplayer displayer,
		Level level,
		LevelChunk clientChunk,
		LevelChunk serverChunk,
		CallbackInfo ci
	) {
		List<String> debugLines = SodiumBridge.getDebugLines();
		if (!debugLines.isEmpty()) {
			displayer.addToGroup(DEBUG_GROUP, debugLines);
		}
	}
}
