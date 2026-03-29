package com.potassium.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.potassium.client.PotassiumClientMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class PotassiumConfig {
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
		.getConfigDir()
		.resolve(PotassiumClientMod.MOD_ID + ".json");

	private static ConfigData data = new ConfigData();
	private static boolean loaded;

	private PotassiumConfig() {
	}

	public static synchronized void load() {
		if (loaded) {
			return;
		}

		loaded = true;
		if (!Files.exists(CONFIG_PATH)) {
			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
			ConfigData loadedData = GSON.fromJson(reader, ConfigData.class);
			data = loadedData != null ? loadedData : new ConfigData();
		} catch (IOException | RuntimeException exception) {
			PotassiumClientMod.LOGGER.warn("Failed to load Potassium config, using defaults", exception);
			data = new ConfigData();
			save();
		}
	}

	public static synchronized void save() {
		if (!loaded) {
			loaded = true;
		}

		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException exception) {
			PotassiumClientMod.LOGGER.warn("Failed to save Potassium config", exception);
		}
	}

	public static synchronized boolean showF3PatchedMarker() {
		load();
		return data.showF3PatchedMarker;
	}

	public static synchronized void setShowF3PatchedMarker(boolean value) {
		load();
		data.showF3PatchedMarker = value;
	}

	public static synchronized boolean showF3Summary() {
		load();
		return data.showF3Summary;
	}

	public static synchronized void setShowF3Summary(boolean value) {
		load();
		data.showF3Summary = value;
	}

	public static synchronized boolean showF3OverrideStats() {
		load();
		return data.showF3OverrideStats;
	}

	public static synchronized void setShowF3OverrideStats(boolean value) {
		load();
		data.showF3OverrideStats = value;
	}

	public static synchronized boolean showF3GenerationStats() {
		load();
		return data.showF3GenerationStats;
	}

	public static synchronized void setShowF3GenerationStats(boolean value) {
		load();
		data.showF3GenerationStats = value;
	}

	public static synchronized boolean showF3ComputeStats() {
		load();
		return data.showF3ComputeStats;
	}

	public static synchronized void setShowF3ComputeStats(boolean value) {
		load();
		data.showF3ComputeStats = value;
	}

	public static synchronized boolean showF3CullingStats() {
		load();
		return data.showF3CullingStats;
	}

	public static synchronized void setShowF3CullingStats(boolean value) {
		load();
		data.showF3CullingStats = value;
	}

	public static synchronized boolean showF3BufferStats() {
		load();
		return data.showF3BufferStats;
	}

	public static synchronized void setShowF3BufferStats(boolean value) {
		load();
		data.showF3BufferStats = value;
	}

	public static synchronized boolean showF3FallbackStats() {
		load();
		return data.showF3FallbackStats;
	}

	public static synchronized void setShowF3FallbackStats(boolean value) {
		load();
		data.showF3FallbackStats = value;
	}

	public static synchronized boolean anyF3DebugLinesEnabled() {
		load();
		return data.showF3Summary ||
			data.showF3OverrideStats ||
			data.showF3GenerationStats ||
			data.showF3ComputeStats ||
			data.showF3CullingStats ||
			data.showF3BufferStats ||
			data.showF3FallbackStats;
	}

	public static synchronized boolean modEnabled() {
		load();
		return data.modEnabled;
	}

	public static synchronized void setModEnabled(boolean value) {
		load();
		data.modEnabled = value;
	}

	public static synchronized boolean persistentMappingEnabled() {
		load();
		return data.persistentMappingEnabled;
	}

	public static synchronized void setPersistentMappingEnabled(boolean value) {
		load();
		data.persistentMappingEnabled = value;
	}

	public static synchronized boolean threadedCommandFillEnabled() {
		load();
		return data.threadedCommandFillEnabled;
	}

	public static synchronized void setThreadedCommandFillEnabled(boolean value) {
		load();
		data.threadedCommandFillEnabled = value;
	}

	public static synchronized boolean frustumCullingEnabled() {
		load();
		return data.frustumCullingEnabled;
	}

	public static synchronized void setFrustumCullingEnabled(boolean value) {
		load();
		data.frustumCullingEnabled = value;
	}

	public static synchronized boolean opaqueComputeCullingEnabled() {
		load();
		return data.opaqueComputeCullingEnabled;
	}

	public static synchronized void setOpaqueComputeCullingEnabled(boolean value) {
		load();
		data.opaqueComputeCullingEnabled = value;
	}

	public static synchronized boolean translucentBatchOverrideEnabled() {
		load();
		return data.translucentBatchOverrideEnabled;
	}

	public static synchronized void setTranslucentBatchOverrideEnabled(boolean value) {
		load();
		data.translucentBatchOverrideEnabled = value;
	}

	public static synchronized boolean gpuIndirectCountEnabled() {
		load();
		return data.gpuIndirectCountEnabled;
	}

	public static synchronized void setGpuIndirectCountEnabled(boolean value) {
		load();
		data.gpuIndirectCountEnabled = value;
	}

	private static final class ConfigData {
		private boolean modEnabled = true;
		private boolean persistentMappingEnabled = true;
		private boolean threadedCommandFillEnabled = true;
		private boolean frustumCullingEnabled = true;
		private boolean opaqueComputeCullingEnabled = true;
		private boolean translucentBatchOverrideEnabled = true;
		private boolean gpuIndirectCountEnabled = true;
		private boolean showF3PatchedMarker;
		private boolean showF3Summary;
		private boolean showF3OverrideStats;
		private boolean showF3GenerationStats;
		private boolean showF3ComputeStats;
		private boolean showF3CullingStats;
		private boolean showF3BufferStats;
		private boolean showF3FallbackStats;
	}
}
