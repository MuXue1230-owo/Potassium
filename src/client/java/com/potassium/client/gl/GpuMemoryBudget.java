package com.potassium.client.gl;

import com.potassium.client.PotassiumClientMod;
import java.util.Locale;

public final class GpuMemoryBudget {
	private static final String PRESET_PROPERTY = "potassium.vramPreset";
	private static final String COMMAND_CAPACITY_PROPERTY = "potassium.commandCapacity";
	private static final String COMPUTE_REGION_SLOTS_PROPERTY = "potassium.computeRegionSlots";
	private static final String COMPUTE_REGION_DESCRIPTORS_PROPERTY = "potassium.computeRegionDescriptors";
	private static final String PERSISTENT_SEGMENTS_PROPERTY = "potassium.persistentSegments";

	private static Budget currentBudget;

	private GpuMemoryBudget() {
	}

	public static synchronized Budget current() {
		if (currentBudget != null) {
			return currentBudget;
		}

		Budget resolvedBudget = resolveBudget();
		currentBudget = resolvedBudget;
		PotassiumClientMod.LOGGER.info(
			"GPU memory budget selected: preset={}, commandCapacity={}, computeRegionSlots={}, computeRegionDescriptors={}, persistentSegments={}, reason={}",
			resolvedBudget.preset().configName(),
			resolvedBudget.commandCapacity(),
			resolvedBudget.computeRegionSlots(),
			resolvedBudget.computeRegionDescriptors(),
			resolvedBudget.persistentSegments(),
			resolvedBudget.reason()
		);
		return resolvedBudget;
	}

	public static Budget conservativeFallback(String reason) {
		return new Budget(
			Preset.CONSERVATIVE,
			Preset.CONSERVATIVE.commandCapacity(),
			Preset.CONSERVATIVE.computeRegionSlots(),
			Preset.CONSERVATIVE.computeRegionDescriptors(),
			Preset.CONSERVATIVE.persistentSegments(),
			reason
		);
	}

	private static Budget resolveBudget() {
		PresetSelection presetSelection = resolvePresetSelection();
		Preset preset = presetSelection.preset();
		return new Budget(
			preset,
			readIntOverride(COMMAND_CAPACITY_PROPERTY, preset.commandCapacity(), 1),
			readIntOverride(COMPUTE_REGION_SLOTS_PROPERTY, preset.computeRegionSlots(), 1),
			readIntOverride(COMPUTE_REGION_DESCRIPTORS_PROPERTY, preset.computeRegionDescriptors(), 1),
			readIntOverride(PERSISTENT_SEGMENTS_PROPERTY, preset.persistentSegments(), 2),
			presetSelection.reason()
		);
	}

	private static PresetSelection resolvePresetSelection() {
		String presetValue = System.getProperty(PRESET_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
		if (presetValue.isEmpty() || "auto".equals(presetValue)) {
			return autoPresetSelection();
		}

		for (Preset preset : Preset.values()) {
			if (preset.configName().equals(presetValue)) {
				return new PresetSelection(preset, "manual override from -" + "D" + PRESET_PROPERTY + "=" + presetValue);
			}
		}

		PotassiumClientMod.LOGGER.warn(
			"Unknown GPU memory preset '{}'. Falling back to auto detection.",
			presetValue
		);
		return autoPresetSelection();
	}

	private static PresetSelection autoPresetSelection() {
		int currentAvailableMiB = GLCapabilities.getCurrentAvailableVideoMemoryMiB();
		if (currentAvailableMiB >= 8_192) {
			return new PresetSelection(Preset.EXTREME, autoReason("currentAvailable", currentAvailableMiB));
		}
		if (currentAvailableMiB >= 4_096) {
			return new PresetSelection(Preset.AGGRESSIVE, autoReason("currentAvailable", currentAvailableMiB));
		}
		if (currentAvailableMiB >= 2_048) {
			return new PresetSelection(Preset.BALANCED, autoReason("currentAvailable", currentAvailableMiB));
		}

		int totalAvailableMiB = GLCapabilities.getTotalAvailableVideoMemoryMiB();
		if (totalAvailableMiB >= 12_288) {
			return new PresetSelection(Preset.EXTREME, autoReason("totalAvailable", totalAvailableMiB));
		}
		if (totalAvailableMiB >= 8_192) {
			return new PresetSelection(Preset.AGGRESSIVE, autoReason("totalAvailable", totalAvailableMiB));
		}
		if (totalAvailableMiB >= 4_096) {
			return new PresetSelection(Preset.BALANCED, autoReason("totalAvailable", totalAvailableMiB));
		}

		int dedicatedMiB = GLCapabilities.getDedicatedVideoMemoryMiB();
		if (dedicatedMiB >= 12_288) {
			return new PresetSelection(Preset.EXTREME, autoReason("dedicated", dedicatedMiB));
		}
		if (dedicatedMiB >= 8_192) {
			return new PresetSelection(Preset.AGGRESSIVE, autoReason("dedicated", dedicatedMiB));
		}
		if (dedicatedMiB >= 4_096) {
			return new PresetSelection(Preset.BALANCED, autoReason("dedicated", dedicatedMiB));
		}

		return new PresetSelection(
			Preset.CONSERVATIVE,
			"auto fallback because GPU memory info is unavailable or below 2048 MiB"
		);
	}

	private static String autoReason(String metricName, int valueMiB) {
		return "auto via " + GLCapabilities.getVideoMemoryInfoSource() + " " + metricName + "=" + valueMiB + " MiB";
	}

	private static int readIntOverride(String propertyName, int fallbackValue, int minimumValue) {
		String rawValue = System.getProperty(propertyName);
		if (rawValue == null || rawValue.isBlank()) {
			return fallbackValue;
		}

		try {
			int parsedValue = Integer.parseInt(rawValue.trim());
			if (parsedValue < minimumValue) {
				PotassiumClientMod.LOGGER.warn(
					"Ignoring -D{}={} because it is below the minimum value {}.",
					propertyName,
					rawValue,
					minimumValue
				);
				return fallbackValue;
			}

			return parsedValue;
		} catch (NumberFormatException exception) {
			PotassiumClientMod.LOGGER.warn(
				"Ignoring -D{}={} because it is not a valid integer.",
				propertyName,
				rawValue
			);
			return fallbackValue;
		}
	}

	public record Budget(
		Preset preset,
		int commandCapacity,
		int computeRegionSlots,
		int computeRegionDescriptors,
		int persistentSegments,
		String reason
	) {
	}

	public enum Preset {
		CONSERVATIVE("conservative", 65_536, 512, 256, 3),
		BALANCED("balanced", 131_072, 1_024, 512, 3),
		AGGRESSIVE("aggressive", 262_144, 2_048, 1_024, 4),
		EXTREME("extreme", 524_288, 4_096, 2_048, 4);

		private final String configName;
		private final int commandCapacity;
		private final int computeRegionSlots;
		private final int computeRegionDescriptors;
		private final int persistentSegments;

		Preset(
			String configName,
			int commandCapacity,
			int computeRegionSlots,
			int computeRegionDescriptors,
			int persistentSegments
		) {
			this.configName = configName;
			this.commandCapacity = commandCapacity;
			this.computeRegionSlots = computeRegionSlots;
			this.computeRegionDescriptors = computeRegionDescriptors;
			this.persistentSegments = persistentSegments;
		}

		public String configName() {
			return this.configName;
		}

		public int commandCapacity() {
			return this.commandCapacity;
		}

		public int computeRegionSlots() {
			return this.computeRegionSlots;
		}

		public int computeRegionDescriptors() {
			return this.computeRegionDescriptors;
		}

		public int persistentSegments() {
			return this.persistentSegments;
		}
	}

	private record PresetSelection(Preset preset, String reason) {
	}
}
