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
				.setName(text("potassium.config.page"))
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
			.setName(text("potassium.config.group.core"))
			.addOption(createBooleanOption(
				builder,
				MOD_ENABLED_OPTION_ID,
				"potassium.config.option.mod_enabled",
				"potassium.config.option.mod_enabled.tooltip",
				true,
				PotassiumConfig::modEnabled,
				PotassiumConfig::setModEnabled
			));
	}

	private static OptionGroupBuilder createOpenGl45Group(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(text("potassium.config.group.gl45"))
			.addOption(createFeatureOption(
				builder,
				PERSISTENT_MAPPING_OPTION_ID,
				"potassium.config.option.persistent_mapping",
				"potassium.config.option.persistent_mapping.tooltip",
				true,
				PotassiumConfig::persistentMappingEnabled,
				PotassiumConfig::setPersistentMappingEnabled
			))
			.addOption(createFeatureOption(
				builder,
				THREADED_COMMAND_FILL_OPTION_ID,
				"potassium.config.option.threaded_command_fill",
				"potassium.config.option.threaded_command_fill.tooltip",
				true,
				PotassiumConfig::threadedCommandFillEnabled,
				PotassiumConfig::setThreadedCommandFillEnabled
			))
			.addOption(createFeatureOption(
				builder,
				FRUSTUM_CULLING_OPTION_ID,
				"potassium.config.option.frustum_culling",
				"potassium.config.option.frustum_culling.tooltip",
				true,
				PotassiumConfig::frustumCullingEnabled,
				PotassiumConfig::setFrustumCullingEnabled
			))
			.addOption(createFeatureOption(
				builder,
				OPAQUE_COMPUTE_CULLING_OPTION_ID,
				"potassium.config.option.opaque_compute_culling",
				"potassium.config.option.opaque_compute_culling.tooltip",
				true,
				PotassiumConfig::opaqueComputeCullingEnabled,
				PotassiumConfig::setOpaqueComputeCullingEnabled
			))
			.addOption(createFeatureOption(
				builder,
				TRANSLUCENT_OVERRIDE_OPTION_ID,
				"potassium.config.option.translucent_override",
				"potassium.config.option.translucent_override.tooltip",
				true,
				PotassiumConfig::translucentBatchOverrideEnabled,
				PotassiumConfig::setTranslucentBatchOverrideEnabled
			));
	}

	private static OptionGroupBuilder createOpenGl46Group(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(text("potassium.config.group.gl46"))
			.addOption(createGl46FeatureOption(
				builder,
				GPU_INDIRECT_COUNT_OPTION_ID,
				"potassium.config.option.gpu_indirect_count",
				"potassium.config.option.gpu_indirect_count.tooltip",
				PotassiumConfig::gpuIndirectCountEnabled,
				PotassiumConfig::setGpuIndirectCountEnabled
			));
	}

	private static OptionGroupBuilder createF3OverlayGroup(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(text("potassium.config.group.f3"))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_patched_marker"),
				"potassium.config.option.show_f3_patched_marker",
				"potassium.config.option.show_f3_patched_marker.tooltip",
				false,
				PotassiumConfig::showF3PatchedMarker,
				PotassiumConfig::setShowF3PatchedMarker
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_summary"),
				"potassium.config.option.show_f3_summary",
				"potassium.config.option.show_f3_summary.tooltip",
				false,
				PotassiumConfig::showF3Summary,
				PotassiumConfig::setShowF3Summary
			));
	}

	private static OptionGroupBuilder createPerformanceMonitoringGroup(ConfigBuilder builder) {
		return builder.createOptionGroup()
			.setName(text("potassium.config.group.monitoring"))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_override_stats"),
				"potassium.config.option.show_f3_override_stats",
				"potassium.config.option.show_f3_override_stats.tooltip",
				true,
				PotassiumConfig::showF3OverrideStats,
				PotassiumConfig::setShowF3OverrideStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_generation_stats"),
				"potassium.config.option.show_f3_generation_stats",
				"potassium.config.option.show_f3_generation_stats.tooltip",
				true,
				PotassiumConfig::showF3GenerationStats,
				PotassiumConfig::setShowF3GenerationStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_compute_stats"),
				"potassium.config.option.show_f3_compute_stats",
				"potassium.config.option.show_f3_compute_stats.tooltip",
				true,
				PotassiumConfig::showF3ComputeStats,
				PotassiumConfig::setShowF3ComputeStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_culling_stats"),
				"potassium.config.option.show_f3_culling_stats",
				"potassium.config.option.show_f3_culling_stats.tooltip",
				false,
				PotassiumConfig::showF3CullingStats,
				PotassiumConfig::setShowF3CullingStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_buffer_stats"),
				"potassium.config.option.show_f3_buffer_stats",
				"potassium.config.option.show_f3_buffer_stats.tooltip",
				false,
				PotassiumConfig::showF3BufferStats,
				PotassiumConfig::setShowF3BufferStats
			))
			.addOption(createBooleanOption(
				builder,
				optionId("show_f3_fallback_stats"),
				"potassium.config.option.show_f3_fallback_stats",
				"potassium.config.option.show_f3_fallback_stats.tooltip",
				false,
				PotassiumConfig::showF3FallbackStats,
				PotassiumConfig::setShowF3FallbackStats
			));
	}

	private static BooleanOptionBuilder createBooleanOption(
		ConfigBuilder builder,
		Identifier optionId,
		String nameKey,
		String tooltipKey,
		boolean defaultValue,
		Supplier<Boolean> getter,
		Consumer<Boolean> setter
	) {
		return builder.createBooleanOption(optionId)
			.setName(text(nameKey))
			.setTooltip(text(tooltipKey))
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

	private static Component text(String key) {
		return Component.translatable(key);
	}
}
