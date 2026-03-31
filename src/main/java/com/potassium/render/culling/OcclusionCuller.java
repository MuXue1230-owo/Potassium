package com.potassium.render.culling;

public final class OcclusionCuller {
	private final boolean enabled;

	public OcclusionCuller(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return this.enabled;
	}
}
