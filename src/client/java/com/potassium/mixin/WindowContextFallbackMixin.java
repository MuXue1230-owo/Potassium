package com.potassium.mixin;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.GLFWErrorScope;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.potassium.client.PotassiumClientMod;
import com.potassium.client.gl.GLContextRequest;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowContextFallbackMixin {
	@Shadow
	@Final
	private static Logger LOGGER;

	@Inject(method = "createGlfwWindow", at = @At("HEAD"), cancellable = true)
	private static void potassium$createPreferredContext(
		int width,
		int height,
		String title,
		long monitor,
		GpuBackend backend,
		CallbackInfoReturnable<Long> cir
	) throws BackendCreationException {
		if (!"OpenGL".equals(backend.getName())) {
			return;
		}

		try {
			GLContextRequest.requestPreferred();
			GLFWErrorCapture preferredErrors = new GLFWErrorCapture();
			long preferredHandle = potassium$attemptCreateWindow(
				width,
				height,
				title,
				monitor,
				backend,
				preferredErrors
			);

			if (preferredHandle != 0L) {
				potassium$logErrors(preferredErrors);
				cir.setReturnValue(preferredHandle);
				return;
			}

			potassium$logErrors(preferredErrors);
			PotassiumClientMod.LOGGER.warn("OpenGL 4.6 context creation failed, retrying with OpenGL 4.5.");

			GLContextRequest.requestMinimum();
			GLFWErrorCapture minimumErrors = new GLFWErrorCapture();
			long minimumHandle = potassium$attemptCreateWindow(
				width,
				height,
				title,
				monitor,
				backend,
				minimumErrors
			);
			potassium$logErrors(minimumErrors);

			if (minimumHandle != 0L) {
				PotassiumClientMod.LOGGER.info("OpenGL 4.5 fallback context created successfully.");
				cir.setReturnValue(minimumHandle);
				return;
			}

			backend.handleWindowCreationErrors(minimumErrors.firstError());
		} finally {
			GLContextRequest.reset();
		}
	}

	@Unique
	private static long potassium$attemptCreateWindow(
		int width,
		int height,
		String title,
		long monitor,
		GpuBackend backend,
		GLFWErrorCapture errors
	) {
		try (GLFWErrorScope ignored = new GLFWErrorScope(errors)) {
			backend.setWindowHints();
			return GLFW.glfwCreateWindow(width, height, title, monitor, 0L);
		}
	}

	@Unique
	private static void potassium$logErrors(GLFWErrorCapture errors) {
		for (GLFWErrorCapture.Error error : errors) {
			LOGGER.error("GLFW error collected during GL backend initialization: {}", error);
		}
	}
}
