package com.potassium.client.config;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.gl.GLCapabilities;

public final class PotassiumFeatures {
	private PotassiumFeatures() {
	}

	public static void clampToCurrentGlCapabilities() {
		boolean changed = false;

		if (!GLCapabilities.isVersion46() && PotassiumConfig.gpuIndirectCountEnabled()) {
			PotassiumConfig.setGpuIndirectCountEnabled(false);
			changed = true;
		}

		if (changed) {
			PotassiumConfig.save();
			PotassiumClientMod.LOGGER.info("Potassium config was clamped to the current OpenGL capability set.");
		}
	}

	public static boolean modEnabled() {
		return PotassiumConfig.modEnabled();
	}

	public static boolean persistentMappingEnabled() {
		return modEnabled() && PotassiumConfig.persistentMappingEnabled() && GLCapabilities.hasPersistentMapping();
	}

	public static boolean threadedCommandFillEnabled() {
		return modEnabled() && PotassiumConfig.threadedCommandFillEnabled();
	}

	public static boolean frustumCullingEnabled() {
		return modEnabled() && PotassiumConfig.frustumCullingEnabled();
	}

	public static boolean opaqueComputeCullingEnabled() {
		return modEnabled() && PotassiumConfig.opaqueComputeCullingEnabled();
	}

	public static boolean translucentBatchOverrideEnabled() {
		return modEnabled() && PotassiumConfig.translucentBatchOverrideEnabled();
	}

	public static boolean gpuIndirectCountEnabled() {
		return modEnabled() && PotassiumConfig.gpuIndirectCountEnabled() && GLCapabilities.isVersion46();
	}

	public static boolean gl46Available() {
		return GLCapabilities.isVersion46();
	}
}
