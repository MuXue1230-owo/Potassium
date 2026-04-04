package com.potassium.mixin;

import com.potassium.core.PotassiumEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {

	@Inject(
		method = "drawProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V",
		at = @At("RETURN")
	)
	private void potassium$drawGpuProgressBar(
		GuiGraphicsExtractor guiGraphics,
		int centerX,
		int y,
		int width,
		int textOffsetY,
		float progress,
		CallbackInfo ci
	) {
		PotassiumEngine engine = PotassiumEngine.getNullable();
		if (engine == null || !engine.isRuntimeReady()) {
			return;
		}

		if (!engine.isInitialMeshGenerationPending()) {
			return;
		}

		float gpuProgress = engine.getInitialMeshGenerationProgress();
		if (gpuProgress >= 1.0f) {
			return;
		}

		int barX = centerX - width / 2;
		int barEndX = barX + width;

		// Overlay: draw the GPU progress portion on top of the vanilla bar
		// The vanilla bar shows server progress, we show GPU progress as an overlay
		int gpuFillEnd = barX + Mth.floor((float) width * gpuProgress);

		// Draw GPU overlay bar (semi-transparent green over vanilla progress)
		guiGraphics.fill(barX, y, gpuFillEnd, y + 5, 0x806BCC6B);

		// Draw GPU percentage text on the progress bar
		String gpuText = "GPU: " + Mth.floor(gpuProgress * 100.0f) + "%";
		guiGraphics.text(Minecraft.getInstance().font, gpuText, centerX, textOffsetY, 0xFFFFFF);
	}
}
