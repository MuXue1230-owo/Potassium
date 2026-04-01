package com.potassium.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
		.resolve(PotassiumLogger.MOD_ID + ".json");

	private static PotassiumConfig current = new PotassiumConfig();
	private static boolean loaded;

	public General general = new General();
	public Debug debug = new Debug();
	public Memory memory = new Memory();

	public static synchronized PotassiumConfig load() {
		if (loaded) {
			return current;
		}

		loaded = true;
		if (!Files.exists(CONFIG_PATH)) {
			current.normalize();
			save();
			return current;
		}

		try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
			PotassiumConfig loadedConfig = GSON.fromJson(reader, PotassiumConfig.class);
			current = loadedConfig != null ? loadedConfig : new PotassiumConfig();
		} catch (IOException | RuntimeException exception) {
			PotassiumLogger.logger().warn("Failed to load Potassium config, using defaults.", exception);
			current = new PotassiumConfig();
		}

		current.normalize();
		save();
		return current;
	}

	public static synchronized PotassiumConfig current() {
		return loaded ? current : load();
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(current, writer);
			}
		} catch (IOException exception) {
			PotassiumLogger.logger().warn("Failed to save Potassium config.", exception);
		}
	}

	private void normalize() {
		if (this.general == null) {
			this.general = new General();
		}
		if (this.debug == null) {
			this.debug = new Debug();
		}
		if (this.memory == null) {
			this.memory = new Memory();
		}

		this.general.targetRenderDistanceChunks = Math.max(this.general.targetRenderDistanceChunks, 2);
		this.memory.worldDataBufferMiB = Math.max(this.memory.worldDataBufferMiB, 64);
		this.memory.vertexUploadBufferMiB = Math.max(this.memory.vertexUploadBufferMiB, 16);
		this.memory.maxResidentWorldMiB = Math.max(this.memory.maxResidentWorldMiB, this.memory.worldDataBufferMiB);
		this.memory.indirectCommandCapacity = Math.max(this.memory.indirectCommandCapacity, 1024);
		this.memory.meshFacesPerChunk = Math.max(this.memory.meshFacesPerChunk, 32);
	}

	public static final class General {
		public boolean engineEnabled = true;
		public boolean preferOpenGl46 = true;
		public boolean requireOpenGl46;
		public boolean enablePersistentMapping = true;
		public int targetRenderDistanceChunks = 64;
	}

	public static final class Debug {
		public boolean debugContext;
		public boolean glDebugOutput;
		public boolean verboseWorldChangeLogging;
	}

	public static final class Memory {
		public int worldDataBufferMiB = 256;
		public int vertexUploadBufferMiB = 64;
		public int maxResidentWorldMiB = 3072;
		public int indirectCommandCapacity = 32768;
		public int meshFacesPerChunk = 256;
	}
}
