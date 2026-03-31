package com.potassium.ui;

import com.potassium.core.PotassiumEngine;
import com.potassium.gl.GLCapabilities;
import java.util.List;

public final class DebugOverlay {
	public List<String> buildLines(PotassiumEngine engine) {
		if (engine == null || !engine.isRuntimeReady()) {
			return List.of("Potassium: runtime idle");
		}

		return List.of(
			"Potassium",
			"GL: " + GLCapabilities.getVersionString(),
			"Vendor: " + GLCapabilities.getVendorString(),
			engine.describeStatus()
		);
	}
}
