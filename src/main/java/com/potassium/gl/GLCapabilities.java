package com.potassium.gl;

import com.potassium.core.PotassiumLogger;
import java.nio.IntBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.ATIMeminfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.NVXGPUMemoryInfo;
import org.lwjgl.system.MemoryUtil;

public final class GLCapabilities {
	private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)");
	private static final int MIN_MAJOR = 4;
	private static final int MIN_MINOR = 5;

	private static boolean initialized;
	private static int majorVersion;
	private static int minorVersion;
	private static boolean hasComputeShader;
	private static boolean hasSsbo;
	private static boolean hasPersistentMapping;
	private static boolean hasIndirectDraw;
	private static boolean hasIndirectCount;
	private static boolean hasDsa;
	private static boolean hasDebugOutput;
	private static String versionString = "unknown";
	private static String vendorString = "unknown";
	private static String rendererString = "unknown";
	private static String videoMemoryInfoSource = "unavailable";
	private static int dedicatedVideoMemoryMiB = -1;
	private static int totalAvailableVideoMemoryMiB = -1;
	private static int currentAvailableVideoMemoryMiB = -1;
	private static int maxShaderStorageBufferBindings = -1;
	private static long maxShaderStorageBlockSizeBytes = -1L;

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

		versionString = requireString(GL11C.glGetString(GL11C.GL_VERSION), "OpenGL version");
		vendorString = requireString(GL11C.glGetString(GL11C.GL_VENDOR), "OpenGL vendor");
		rendererString = requireString(GL11C.glGetString(GL11C.GL_RENDERER), "OpenGL renderer");

		Matcher matcher = VERSION_PATTERN.matcher(versionString);
		if (!matcher.find()) {
			throw new IllegalStateException("Potassium could not parse the OpenGL version: " + versionString);
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

		hasComputeShader = caps.GL_ARB_compute_shader || isAtLeast(4, 3);
		hasSsbo = caps.GL_ARB_shader_storage_buffer_object || isAtLeast(4, 3);
		hasPersistentMapping = caps.GL_ARB_buffer_storage || isAtLeast(4, 4);
		hasIndirectDraw = caps.GL_ARB_multi_draw_indirect || isAtLeast(4, 3);
		hasIndirectCount = caps.GL_ARB_indirect_parameters || isAtLeast(4, 6);
		hasDsa = caps.GL_ARB_direct_state_access || isAtLeast(4, 5);
		hasDebugOutput = caps.GL_KHR_debug || caps.GL_ARB_debug_output || isAtLeast(4, 3);
		maxShaderStorageBufferBindings = GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
		maxShaderStorageBlockSizeBytes = GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
		detectVideoMemory(caps);

		if (!hasSsbo) {
			throw new IllegalStateException("Potassium requires shader storage buffer objects.");
		}
		if (!hasIndirectDraw) {
			throw new IllegalStateException("Potassium requires indirect draw support.");
		}
		if (!hasDsa) {
			throw new IllegalStateException("Potassium requires OpenGL direct state access.");
		}

		initialized = true;

		PotassiumLogger.logger().info("OpenGL version: {}", versionString);
		PotassiumLogger.logger().info("OpenGL vendor: {}", vendorString);
		PotassiumLogger.logger().info("OpenGL renderer: {}", rendererString);
		PotassiumLogger.logger().info(
			"OpenGL capabilities: compute={}, ssbo={}, persistentMapping={}, indirectDraw={}, indirectCount={}, dsa={}, debugOutput={}",
			hasComputeShader,
			hasSsbo,
			hasPersistentMapping,
			hasIndirectDraw,
			hasIndirectCount,
			hasDsa,
			hasDebugOutput
		);
		PotassiumLogger.logger().info(
			"OpenGL storage limits: ssboBindings={}, maxSsboBlockSize={} bytes",
			maxShaderStorageBufferBindings,
			maxShaderStorageBlockSizeBytes
		);
		PotassiumLogger.logger().info(
			"GPU memory info ({}): dedicated={} MiB, totalAvailable={} MiB, currentAvailable={} MiB",
			videoMemoryInfoSource,
			describeMemoryValue(dedicatedVideoMemoryMiB),
			describeMemoryValue(totalAvailableVideoMemoryMiB),
			describeMemoryValue(currentAvailableVideoMemoryMiB)
		);
	}

	public static String getVersionString() {
		return versionString;
	}

	public static String getVendorString() {
		return vendorString;
	}

	public static String getRendererString() {
		return rendererString;
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

	public static boolean hasComputeShader() {
		return hasComputeShader;
	}

	public static boolean hasSsbo() {
		return hasSsbo;
	}

	public static boolean hasPersistentMapping() {
		return hasPersistentMapping;
	}

	public static boolean hasIndirectDraw() {
		return hasIndirectDraw;
	}

	public static boolean hasIndirectCount() {
		return hasIndirectCount;
	}

	public static boolean hasDsa() {
		return hasDsa;
	}

	public static boolean hasDebugOutput() {
		return hasDebugOutput;
	}

	public static int getDedicatedVideoMemoryMiB() {
		return dedicatedVideoMemoryMiB;
	}

	public static int getTotalAvailableVideoMemoryMiB() {
		return totalAvailableVideoMemoryMiB;
	}

	public static int getCurrentAvailableVideoMemoryMiB() {
		return currentAvailableVideoMemoryMiB;
	}

	public static int getEstimatedAvailableVideoMemoryMiB() {
		if (currentAvailableVideoMemoryMiB >= 0) {
			return currentAvailableVideoMemoryMiB;
		}
		if (totalAvailableVideoMemoryMiB >= 0) {
			return totalAvailableVideoMemoryMiB;
		}
		if (dedicatedVideoMemoryMiB >= 0) {
			return dedicatedVideoMemoryMiB;
		}

		return -1;
	}

	public static int getMaxShaderStorageBufferBindings() {
		return maxShaderStorageBufferBindings;
	}

	public static long getMaxShaderStorageBlockSizeBytes() {
		return maxShaderStorageBlockSizeBytes;
	}

	private static boolean isAtLeast(int requiredMajor, int requiredMinor) {
		return majorVersion > requiredMajor || (majorVersion == requiredMajor && minorVersion >= requiredMinor);
	}

	private static void detectVideoMemory(org.lwjgl.opengl.GLCapabilities caps) {
		videoMemoryInfoSource = "unavailable";
		dedicatedVideoMemoryMiB = -1;
		totalAvailableVideoMemoryMiB = -1;
		currentAvailableVideoMemoryMiB = -1;

		if (caps.GL_NVX_gpu_memory_info) {
			videoMemoryInfoSource = "NVX_gpu_memory_info";
			dedicatedVideoMemoryMiB = queryMemoryValueMiB(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX);
			totalAvailableVideoMemoryMiB = queryMemoryValueMiB(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
			currentAvailableVideoMemoryMiB = queryMemoryValueMiB(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
			return;
		}

		if (caps.GL_ATI_meminfo) {
			videoMemoryInfoSource = "ATI_meminfo";
			IntBuffer memoryInfo = MemoryUtil.memAllocInt(4);
			try {
				GL11C.glGetIntegerv(ATIMeminfo.GL_VBO_FREE_MEMORY_ATI, memoryInfo);
				currentAvailableVideoMemoryMiB = kibToMib(memoryInfo.get(0));
			} finally {
				MemoryUtil.memFree(memoryInfo);
			}
		}
	}

	private static int queryMemoryValueMiB(int pname) {
		return kibToMib(GL11C.glGetInteger(pname));
	}

	private static int kibToMib(int valueKiB) {
		return valueKiB > 0 ? valueKiB / 1024 : -1;
	}

	private static String describeMemoryValue(int valueMiB) {
		return valueMiB >= 0 ? Integer.toString(valueMiB) : "n/a";
	}

	private static String requireString(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Potassium could not read " + label + ".");
		}

		return value;
	}
}
