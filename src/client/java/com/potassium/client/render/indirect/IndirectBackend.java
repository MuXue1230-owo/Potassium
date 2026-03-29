package com.potassium.client.render.indirect;

import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.PotassiumClientMod;

public final class IndirectBackend {
	private static final int DEFAULT_COMMAND_CAPACITY = 65_536;

	private static boolean registered;
	private static IndirectCommandBuffer commandBuffer;
	private static IndexedIndirectCommandBuffer indexedCommandBuffer;

	private IndirectBackend() {
	}

	public static void register() {
		if (registered) {
			return;
		}

		boolean persistentMappingSupported = GLCapabilities.hasPersistentMapping();
		boolean persistentMappingEnabled = persistentMappingSupported;

		commandBuffer = new IndirectCommandBuffer(DEFAULT_COMMAND_CAPACITY, persistentMappingEnabled);
		indexedCommandBuffer = new IndexedIndirectCommandBuffer(
			DEFAULT_COMMAND_CAPACITY,
			persistentMappingEnabled
		);
		registered = true;
		PotassiumClientMod.LOGGER.info(
			"Indirect backend ready with {} commands (persistent mapping supported: {}, enabled: {})",
			commandBuffer.capacityCommands(),
			persistentMappingSupported,
			commandBuffer.usesPersistentMapping()
		);
	}

	public static IndirectCommandBuffer commandBuffer() {
		if (!registered || commandBuffer == null) {
			throw new IllegalStateException("Indirect backend has not been registered.");
		}

		return commandBuffer;
	}

	public static IndexedIndirectCommandBuffer indexedCommandBuffer() {
		if (!registered || indexedCommandBuffer == null) {
			throw new IllegalStateException("Indirect backend has not been registered.");
		}

		return indexedCommandBuffer;
	}

	public static void shutdown() {
		if (commandBuffer != null) {
			commandBuffer.close();
			commandBuffer = null;
		}

		if (indexedCommandBuffer != null) {
			indexedCommandBuffer.close();
			indexedCommandBuffer = null;
		}

		registered = false;
	}
}
