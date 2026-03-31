package com.potassium.render.culling;

public final class FrustumCuller {
	private final boolean enabled;

	public FrustumCuller(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return this.enabled;
	}
}
