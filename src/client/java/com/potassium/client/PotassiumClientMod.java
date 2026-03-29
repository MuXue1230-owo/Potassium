package com.potassium.client;

import com.potassium.client.compat.sodium.SodiumBridge;
import com.potassium.client.compute.SectionVisibilityCompute;
import com.potassium.client.config.PotassiumConfig;
import com.potassium.client.config.PotassiumFeatures;
import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.render.indirect.IndirectBackend;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PotassiumClientMod implements ClientModInitializer {
	public static final String MOD_ID = "potassium";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		PotassiumConfig.load();

		if (!FabricLoader.getInstance().isModLoaded("sodium")) {
			throw new IllegalStateException(
				"Potassium needs Sodium to run. Install Sodium from https://modrinth.com/mod/sodium"
			);
		}

		LOGGER.info("Potassium client bootstrap ready");
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> initializeRuntime());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			SectionVisibilityCompute.shutdown();
			SodiumBridge.shutdown();
			IndirectBackend.shutdown();
		});
	}

	private static void initializeRuntime() {
		LOGGER.info("Potassium is initializing");

		GLCapabilities.initialize();
		PotassiumFeatures.clampToCurrentGlCapabilities();
		IndirectBackend.register();
		SectionVisibilityCompute.initialize();

		LOGGER.info("Potassium initialized");
		LOGGER.info("OpenGL version: {}.{}", GLCapabilities.getMajorVersion(), GLCapabilities.getMinorVersion());
		LOGGER.info("Indirect draw: {}", GLCapabilities.hasIndirectDraw());
		LOGGER.info("Indirect count: {}", GLCapabilities.hasIndirectCount());
		LOGGER.info("Compute shader: {}", GLCapabilities.hasComputeShader());
		LOGGER.info("Persistent mapping: {}", GLCapabilities.hasPersistentMapping());
		LOGGER.info("Section visibility compute: {}", SectionVisibilityCompute.isEnabled());
		LOGGER.info(
			"Feature toggles: enabled={}, persistentMapping={}, threadedFill={}, frustumCulling={}, opaqueCompute={}, translucentOverride={}, gpuIndirectCount={}",
			PotassiumFeatures.modEnabled(),
			PotassiumFeatures.persistentMappingEnabled(),
			PotassiumFeatures.threadedCommandFillEnabled(),
			PotassiumFeatures.frustumCullingEnabled(),
			PotassiumFeatures.opaqueComputeCullingEnabled(),
			PotassiumFeatures.translucentBatchOverrideEnabled(),
			PotassiumFeatures.gpuIndirectCountEnabled()
		);
	}
}
