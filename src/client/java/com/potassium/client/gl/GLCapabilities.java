package com.potassium.client.gl;

import com.potassium.client.PotassiumClientMod;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public final class GLCapabilities {
	private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)");
	private static final int MIN_MAJOR = 4;
	private static final int MIN_MINOR = 5;

	private static boolean initialized;
	private static int majorVersion;
	private static int minorVersion;
	private static boolean hasIndirectDraw;
	private static boolean hasIndirectCount;
	private static boolean hasComputeShader;
	private static boolean hasSSBO;
	private static boolean hasPersistentMapping;
	private static boolean hasDSA;

	private GLCapabilities() {
	}

	public static void initialize() {
		if (initialized) {
			return;
		}

		final org.lwjgl.opengl.GLCapabilities caps;
		try {
			caps = GL.getCapabilities();
		} catch (IllegalStateException exception) {
			throw new IllegalStateException("Potassium could not access an active OpenGL context.", exception);
		}

		String version = GL11.glGetString(GL11.GL_VERSION);
		if (version == null || version.isBlank()) {
			throw new IllegalStateException("Potassium could not read the OpenGL version string.");
		}

		Matcher matcher = VERSION_PATTERN.matcher(version);
		if (!matcher.find()) {
			throw new IllegalStateException("Potassium could not parse the OpenGL version: " + version);
		}

		majorVersion = Integer.parseInt(matcher.group(1));
		minorVersion = Integer.parseInt(matcher.group(2));

		if (!isAtLeast(MIN_MAJOR, MIN_MINOR)) {
			throw new IllegalStateException(
				String.format(
					"Potassium requires OpenGL %d.%d+, but detected %d.%d.",
					MIN_MAJOR,
					MIN_MINOR,
					majorVersion,
					minorVersion
				)
			);
		}

		hasIndirectDraw = caps.GL_ARB_multi_draw_indirect || isAtLeast(4, 3);
		hasIndirectCount = caps.GL_ARB_indirect_parameters || isAtLeast(4, 6);
		hasComputeShader = caps.GL_ARB_compute_shader || isAtLeast(4, 3);
		hasSSBO = caps.GL_ARB_shader_storage_buffer_object || isAtLeast(4, 3);
		hasPersistentMapping = caps.GL_ARB_buffer_storage || isAtLeast(4, 4);
		hasDSA = caps.GL_ARB_direct_state_access || isAtLeast(4, 5);

		if (!hasIndirectDraw) {
			throw new IllegalStateException("Potassium requires ARB_multi_draw_indirect or OpenGL 4.3+.");
		}

		if (!hasSSBO) {
			throw new IllegalStateException("Potassium requires ARB_shader_storage_buffer_object or OpenGL 4.3+.");
		}

		initialized = true;

		PotassiumClientMod.LOGGER.info("Raw OpenGL version string: {}", version);
		PotassiumClientMod.LOGGER.info("OpenGL version: {}.{}", majorVersion, minorVersion);
		PotassiumClientMod.LOGGER.info("Indirect draw: {}", hasIndirectDraw);
		PotassiumClientMod.LOGGER.info("Indirect count: {}", hasIndirectCount);
		PotassiumClientMod.LOGGER.info("Compute shader: {}", hasComputeShader);
		PotassiumClientMod.LOGGER.info("SSBO: {}", hasSSBO);
		PotassiumClientMod.LOGGER.info("Persistent mapping: {}", hasPersistentMapping);
		PotassiumClientMod.LOGGER.info("DSA: {}", hasDSA);
	}

	private static boolean isAtLeast(int requiredMajor, int requiredMinor) {
		return majorVersion > requiredMajor || (majorVersion == requiredMajor && minorVersion >= requiredMinor);
	}

	public static int getMajorVersion() {
		return majorVersion;
	}

	public static int getMinorVersion() {
		return minorVersion;
	}

	public static boolean isVersion46() {
		return isAtLeast(4, 6);
	}

	public static boolean hasIndirectDraw() {
		return hasIndirectDraw;
	}

	public static boolean hasComputeShader() {
		return hasComputeShader;
	}

	public static boolean hasIndirectCount() {
		return hasIndirectCount;
	}

	public static boolean hasSSBO() {
		return hasSSBO;
	}

	public static boolean hasPersistentMapping() {
		return hasPersistentMapping;
	}

	public static boolean hasDSA() {
		return hasDSA;
	}
}
