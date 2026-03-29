package com.potassium.client.config;

import com.potassium.client.PotassiumClientMod;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class PotassiumSodiumConfigEntryPoint implements ConfigEntryPoint {
	private static final StorageEventHandler STORAGE_HANDLER = PotassiumConfig::save;
	private static final Identifier MOD_ENABLED_OPTION_ID = optionId("mod_enabled");
	private static final Identifier PERSISTENT_MAPPING_OPTION_ID = optionId("persistent_mapping");
	private static final Identifier THREADED_COMMAND_FILL_OPTION_ID = optionId("threaded_command_fill");
	private static final Identifier FRUSTUM_CULLING_OPTION_ID = optionId("frustum_culling");
	private static final Identifier OPAQUE_COMPUTE_CULLING_OPTION_ID = optionId("opaque_compute_culling");
	private static final Identifier TRANSLUCENT_OVERRIDE_OPTION_ID = optionId("translucent_batch_override");
	private static final Identifier GPU_INDIRECT_COUNT_OPTION_ID = optionId("gpu_indirect_count");

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		PotassiumConfig.load();

		ModOptionsBuilder modOptions = builder.registerModOptions(
			PotassiumClientMod.MOD_ID,
			"Potassium",
			getModVersion()
		);
		modOptions.addPage(
			builder.createOptionPage()
				.setName(Component.literal("Potassium"))
				.addOptionGroup(createCoreGroup(builder))
				.addOptionGroup(createOpenGl45Group(builder))
				.addOptionGroup(createOpenGl46Group(builder))
				.addOptionGroup(createF3OverlayGroup(builder))
				.addOptionGroup(createPerformanceMonitoringGroup(builder))
		);
	}

	public static Screen createConfigScreen(Screen parent) {
		OptionPage potassiumPage = findPotassiumPage();
		if (potassiumPage != null) {
			return VideoSettingsScreen.createScreen(parent, potassiumPage);
		}

		return VideoSettingsScreen.createScreen(parent);
	}

	private static OptionGroupBuilder createCoreGroup(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(Component.literal("Core"))
			.addOption(createBooleanOption(
				builder,
				MOD_ENABLED_OPTION_ID,
				"Enable Potassium",
				"Disable all Potassium rendering hooks and fall back to plain Sodium.",
				true,
				PotassiumConfig::modEnabled,
				PotassiumConfig::setModEnabled
			));
	}

	private static OptionGroupBuilder createOpenGl45Group(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(Component.literal("OpenGL 4.5+ Features"))
			.addOption(createFeatureOption(
				builder,
				PERSISTENT_MAPPING_OPTION_ID,
				"Persistent mapped buffers",
				"Use persistently mapped indirect buffers. Takes effect after restart.",
				true,
				PotassiumConfig::persistentMappingEnabled,
				PotassiumConfig::setPersistentMappingEnabled
			))
			.addOption(createFeatureOption(
				builder,
				THREADED_COMMAND_FILL_OPTION_ID,
				"Threaded command fill",
				"Build Potassium command buffers on worker threads before draw submission.",
				true,
				PotassiumConfig::threadedCommandFillEnabled,
				PotassiumConfig::setThreadedCommandFillEnabled
			))
			.addOption(createFeatureOption(
				builder,
				FRUSTUM_CULLING_OPTION_ID,
				"Frustum culling",
				"Enable Potassium region and section frustum culling.",
				true,
				PotassiumConfig::frustumCullingEnabled,
				PotassiumConfig::setFrustumCullingEnabled
			))
			.addOption(createFeatureOption(
				builder,
				OPAQUE_COMPUTE_CULLING_OPTION_ID,
				"Opaque compute culling",
				"Use compute-generated indirect commands for opaque terrain.",
				true,
				PotassiumConfig::opaqueComputeCullingEnabled,
				PotassiumConfig::setOpaqueComputeCullingEnabled
			))
			.addOption(createFeatureOption(
				builder,
				TRANSLUCENT_OVERRIDE_OPTION_ID,
				"Translucent override",
				"Allow Potassium to replace Sodium's translucent draw submission path.",
				true,
				PotassiumConfig::translucentBatchOverrideEnabled,
				PotassiumConfig::setTranslucentBatchOverrideEnabled
			));
	}

	private static OptionGroupBuilder createOpenGl46Group(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(Component.literal("OpenGL 4.6 Features"))
			.addOption(createGl46FeatureOption(
				builder,
				GPU_INDIRECT_COUNT_OPTION_ID,
				"GPU indirect count",
				"Use GPU-driven indirect count submission. Unsupported on OpenGL 4.5 contexts.",
				PotassiumConfig::gpuIndirectCountEnabled,
				PotassiumConfig::setGpuIndirectCountEnabled
			));
	}

	private static OptionGroupBuilder createF3OverlayGroup(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(Component.literal("F3 Overlay"))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_patched_marker"),
				"Show Potassium marker",
				"Append a red Potassium marker to Sodium's F3 renderer line.",
				false,
				PotassiumConfig::showF3PatchedMarker,
				PotassiumConfig::setShowF3PatchedMarker
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_summary"),
				"Show summary",
				"Show the compact Potassium bridge summary in F3.",
				false,
				PotassiumConfig::showF3Summary,
				PotassiumConfig::setShowF3Summary
			));
	}

	private static OptionGroupBuilder createPerformanceMonitoringGroup(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(Component.literal("Performance Monitoring"))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_override_stats"),
				"Show override stats",
				"Show indirect draw override attempts, successes and failures in F3.",
				false,
				PotassiumConfig::showF3OverrideStats,
				PotassiumConfig::setShowF3OverrideStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_generation_stats"),
				"Show generation stats",
				"Show command generation, translation and async fill counters in F3.",
				false,
				PotassiumConfig::showF3GenerationStats,
				PotassiumConfig::setShowF3GenerationStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_compute_stats"),
				"Show compute stats",
				"Show compute-culling dispatch and GPU-generated command counters in F3.",
				false,
				PotassiumConfig::showF3ComputeStats,
				PotassiumConfig::setShowF3ComputeStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_culling_stats"),
				"Show culling stats",
				"Show region and section frustum-culling counters in F3.",
				false,
				PotassiumConfig::showF3CullingStats,
				PotassiumConfig::setShowF3CullingStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_buffer_stats"),
				"Show buffer stats",
				"Show persistent-mapping state and indirect-buffer usage in F3.",
				false,
				PotassiumConfig::showF3BufferStats,
				PotassiumConfig::setShowF3BufferStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_fallback_stats"),
				"Show fallback stats",
				"Show fallback batches, fallback reasons and pass-level override status in F3.",
				false,
				PotassiumConfig::showF3FallbackStats,
				PotassiumConfig::setShowF3FallbackStats
			));
	}

	private static BooleanOptionBuilder createBooleanOption(
		ConfigBuilder builder,
		Identifier optionId,
		String name,
		String tooltip,
		boolean defaultValue,
		Supplier<Boolean> getter,
		Consumer<Boolean> setter
	) {
		return builder.createBooleanOption(optionId)
			.setName(Component.literal(name))
			.setTooltip(Component.literal(tooltip))
			.setDefaultValue(defaultValue)
			.setImpact(OptionImpact.LOW)
			.setBinding(setter, getter)
			.setStorageHandler(STORAGE_HANDLER);
	}

	private static BooleanOptionBuilder createFeatureOption(
		ConfigBuilder builder,
		Identifier optionId,
		String name,
		String tooltip,
		boolean defaultValue,
		Supplier<Boolean> getter,
		Consumer<Boolean> setter
	) {
		return createBooleanOption(builder, optionId, name, tooltip, defaultValue, getter, setter)
			.setEnabledProvider(state -> state.readBooleanOption(MOD_ENABLED_OPTION_ID), MOD_ENABLED_OPTION_ID);
	}

	private static BooleanOptionBuilder createGl46FeatureOption(
		ConfigBuilder builder,
		Identifier optionId,
		String name,
		String tooltip,
		Supplier<Boolean> getter,
		Consumer<Boolean> setter
	) {
		return createBooleanOption(builder, optionId, name, tooltip, true, getter, setter)
			.setEnabledProvider(
				state -> state.readBooleanOption(MOD_ENABLED_OPTION_ID) && PotassiumFeatures.gl46Available(),
				MOD_ENABLED_OPTION_ID
			);
	}

	private static OptionPage findPotassiumPage() {
		if (ConfigManager.CONFIG == null) {
			return null;
		}

		for (ModOptions modOptions : ConfigManager.CONFIG.getModOptions()) {
			if (!PotassiumClientMod.MOD_ID.equals(modOptions.configId())) {
				continue;
			}

			for (Page page : modOptions.pages()) {
				if (page instanceof OptionPage optionPage) {
					return optionPage;
				}
			}
		}

		return null;
	}

	private static String getModVersion() {
		return FabricLoader.getInstance()
			.getModContainer(PotassiumClientMod.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private static Identifier optionId(String path) {
		return Identifier.parse(PotassiumClientMod.MOD_ID + ":" + path);
	}
}
