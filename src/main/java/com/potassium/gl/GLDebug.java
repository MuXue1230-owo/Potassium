package com.potassium.gl;

import com.potassium.core.PotassiumConfig;
import com.potassium.core.PotassiumLogger;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;

public final class GLDebug {
	private static Callback callback;

	private GLDebug() {
	}

	public static void initialize(PotassiumConfig config) {
		if (!config.debug.glDebugOutput) {
			return;
		}

		if (!GLCapabilities.hasDebugOutput()) {
			PotassiumLogger.logger().warn("OpenGL debug output was requested but is not available.");
			return;
		}

		if (callback != null) {
			return;
		}

		GL11C.glEnable(GL43C.GL_DEBUG_OUTPUT);
		GL11C.glEnable(GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
		callback = GLUtil.setupDebugMessageCallback(System.err);

		if (callback != null) {
			PotassiumLogger.logger().info("OpenGL debug output enabled.");
		} else {
			PotassiumLogger.logger().warn("OpenGL debug output was requested but no callback could be installed.");
		}
	}

	public static void shutdown() {
		if (callback != null) {
			callback.free();
			callback = null;
		}
	}
}
