package com.potassium.mixin;

import com.potassium.gl.GLContext;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlBackend")
public abstract class WindowContextMixin {
	private static final String DEBUG_CONTEXT_PROPERTY = "potassium.glDebugContext";
	private static final String NO_ERROR_CONTEXT_PROPERTY = "potassium.glNoError";

	@Inject(method = "setWindowHints", at = @At("TAIL"))
	private void potassium$overrideWindowHints(CallbackInfo ci) {
		boolean enableDebugContext = Boolean.getBoolean(DEBUG_CONTEXT_PROPERTY);
		boolean enableNoErrorContext = !"false".equalsIgnoreCase(System.getProperty(NO_ERROR_CONTEXT_PROPERTY, "true"));
		if (enableDebugContext) {
			enableNoErrorContext = false;
		}

		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLContext.PREFERRED_MAJOR);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GLContext.getRequestedMinor());
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, enableDebugContext ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_NO_ERROR, enableNoErrorContext ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
	}
}
