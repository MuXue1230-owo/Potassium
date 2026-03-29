package com.potassium.mixin;

import com.potassium.client.gl.GLContextRequest;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlBackend")
public abstract class GlBackendWindowHintsMixin {
	@Inject(method = "setWindowHints", at = @At("TAIL"))
	private void potassium$overrideWindowHints(CallbackInfo ci) {
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLContextRequest.PREFERRED_MAJOR);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GLContextRequest.getRequestedMinor());
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
		GLFW.glfwWindowHint(
			GLFW.GLFW_OPENGL_DEBUG_CONTEXT,
			FabricLoader.getInstance().isDevelopmentEnvironment() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE
		);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_NO_ERROR, GLFW.GLFW_FALSE);
	}
}
