package com.potassium.client.gl;

public final class GLContextRequest {
	public static final int PREFERRED_MAJOR = 4;
	public static final int PREFERRED_MINOR = 6;
	public static final int MINIMUM_MINOR = 5;

	private static final ThreadLocal<Integer> REQUESTED_MINOR = ThreadLocal.withInitial(() -> PREFERRED_MINOR);

	private GLContextRequest() {
	}

	public static int getRequestedMinor() {
		return REQUESTED_MINOR.get();
	}

	public static void requestPreferred() {
		REQUESTED_MINOR.set(PREFERRED_MINOR);
	}

	public static void requestMinimum() {
		REQUESTED_MINOR.set(MINIMUM_MINOR);
	}

	public static void reset() {
		REQUESTED_MINOR.remove();
	}
}
