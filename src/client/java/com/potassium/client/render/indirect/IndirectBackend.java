package com.potassium.client.render.indirect;

import com.potassium.client.config.PotassiumFeatures;
import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.gl.GpuMemoryBudget;
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
		boolean persistentMappingEnabled = PotassiumFeatures.persistentMappingEnabled();
		GpuMemoryBudget.Budget requestedBudget = GpuMemoryBudget.current();
		GpuMemoryBudget.Budget appliedBudget = requestedBudget;
		int persistentSegmentCount = persistentMappingEnabled ? requestedBudget.persistentSegments() : 1;

		try {
			allocateBuffers(requestedBudget.commandCapacity(), persistentMappingEnabled, persistentSegmentCount);
		} catch (RuntimeException exception) {
			appliedBudget = GpuMemoryBudget.conservativeFallback("fallback after indirect buffer allocation failure");
			persistentSegmentCount = persistentMappingEnabled ? appliedBudget.persistentSegments() : 1;
			PotassiumClientMod.LOGGER.warn(
				"Falling back to conservative indirect buffer allocation after '{}' preset failed.",
				requestedBudget.preset().configName(),
				exception
			);
			allocateBuffers(appliedBudget.commandCapacity(), persistentMappingEnabled, persistentSegmentCount);
		}
		registered = true;
		PotassiumClientMod.LOGGER.info(
			"Indirect backend ready with {} commands (persistent mapping supported: {}, enabled: {}, segments: {}, GPU reservation: {} MiB, budget: {})",
			commandBuffer.capacityCommands(),
			persistentMappingSupported,
			commandBuffer.usesPersistentMapping(),
			commandBuffer.segmentCount(),
			describeReservationMiB(commandBuffer.capacityCommands(), commandBuffer.segmentCount()),
			appliedBudget.preset().configName()
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

	private static void allocateBuffers(int commandCapacity, boolean persistentMappingEnabled, int persistentSegmentCount) {
		IndirectCommandBuffer newCommandBuffer = null;
		IndexedIndirectCommandBuffer newIndexedCommandBuffer = null;

		try {
			newCommandBuffer = new IndirectCommandBuffer(commandCapacity, persistentMappingEnabled, persistentSegmentCount);
			newIndexedCommandBuffer = new IndexedIndirectCommandBuffer(commandCapacity, persistentMappingEnabled, persistentSegmentCount);
			commandBuffer = newCommandBuffer;
			indexedCommandBuffer = newIndexedCommandBuffer;
		} catch (RuntimeException exception) {
			if (newIndexedCommandBuffer != null) {
				newIndexedCommandBuffer.close();
			}
			if (newCommandBuffer != null) {
				newCommandBuffer.close();
			}
			throw exception;
		}
	}

	private static long describeReservationMiB(int commandCapacity, int segmentCount) {
		long drawBytes = (long) commandCapacity * IndirectCommandBuffer.COMMAND_STRIDE_BYTES * segmentCount;
		long indexedBytes = (long) commandCapacity * IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES * segmentCount;
		return (drawBytes + indexedBytes) / (1024L * 1024L);
	}
}
