package com.potassium.mixin.sodium;

import com.potassium.client.config.PotassiumFeatures;
import com.potassium.client.gl.GLCapabilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderLoader.class)
public abstract class ShaderLoaderMixin {
	private static final String SODIUM_NAMESPACE = "sodium";
	private static final String POTASSIUM_NAMESPACE = "potassium";
	private static final String CHUNK_VERTEX_SHADER_PATH = "blocks/block_layer_opaque.vsh";
	private static final String CHUNK_FRAGMENT_SHADER_PATH = "blocks/block_layer_opaque.fsh";

	@Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true)
	private static void potassium$overrideChunkShaderSource(
		Identifier identifier,
		CallbackInfoReturnable<String> cir
	) {
		if (!PotassiumFeatures.modEnabled() || !GLCapabilities.hasShaderDrawParameters()) {
			return;
		}

		if (!SODIUM_NAMESPACE.equals(identifier.getNamespace())) {
			return;
		}

		String path = identifier.getPath();
		if (!CHUNK_VERTEX_SHADER_PATH.equals(path) && !CHUNK_FRAGMENT_SHADER_PATH.equals(path)) {
			return;
		}

		cir.setReturnValue(loadPotassiumShaderSource(path));
	}

	private static String loadPotassiumShaderSource(String path) {
		String resourcePath = "/assets/" + POTASSIUM_NAMESPACE + "/shaders/" + path;
		try (InputStream stream = ShaderLoaderMixin.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new RuntimeException("Missing Potassium shader resource: " + resourcePath);
			}

			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new RuntimeException("Failed to read Potassium shader resource: " + resourcePath, exception);
		}
	}
}
