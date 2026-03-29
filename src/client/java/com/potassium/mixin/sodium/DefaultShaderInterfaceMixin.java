package com.potassium.mixin.sodium;

import com.potassium.client.config.PotassiumFeatures;
import com.potassium.client.gl.GLCapabilities;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformBlock;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultShaderInterface.class)
public abstract class DefaultShaderInterfaceMixin {
	private static final String CHUNK_DATA_UNIFORM_BLOCK = "ChunkData";
	private static final String CURRENT_TIME_UNIFORM = "u_CurrentTime";
	private static final String FADE_PERIOD_UNIFORM = "u_FadePeriodInv";

	@Shadow
	@Final
	private GlUniformBlock uniformChunkData;

	@Shadow
	@Final
	private GlUniformInt uniformCurrentTime;

	@Shadow
	@Final
	private GlUniformFloat uniformFadePeriod;

	@Redirect(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/shader/ShaderBindingContext;bindUniformBlock(Ljava/lang/String;I)Lnet/caffeinemc/mods/sodium/client/gl/shader/uniform/GlUniformBlock;"
		)
	)
	private GlUniformBlock potassium$bindChunkDataOptional(
		ShaderBindingContext context,
		String name,
		int binding
	) {
		if (
			CHUNK_DATA_UNIFORM_BLOCK.equals(name) &&
			potassium$useSceneShaderPath()
		) {
			return context.bindUniformBlockOptional(name, binding);
		}

		return context.bindUniformBlock(name, binding);
	}

	@Redirect(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/shader/ShaderBindingContext;bindUniform(Ljava/lang/String;Ljava/util/function/IntFunction;)Lnet/caffeinemc/mods/sodium/client/gl/shader/uniform/GlUniform;"
		)
	)
	private <U extends GlUniform<?>> U potassium$bindLegacyUniformOptional(
		ShaderBindingContext context,
		String name,
		java.util.function.IntFunction<U> factory
	) {
		if (
			potassium$useSceneShaderPath() &&
			(CURRENT_TIME_UNIFORM.equals(name) || FADE_PERIOD_UNIFORM.equals(name))
		) {
			return context.bindUniformOptional(name, factory);
		}

		return context.bindUniform(name, factory);
	}

	@Inject(method = "setChunkData", at = @At("HEAD"), cancellable = true)
	private void potassium$skipLegacyChunkDataBinding(GlBuffer chunkData, int currentTime, CallbackInfo ci) {
		if (this.uniformChunkData == null || this.uniformCurrentTime == null) {
			ci.cancel();
		}
	}

	@Redirect(
		method = "setupState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/uniform/GlUniformFloat;setFloat(F)V"
		)
	)
	private void potassium$setFadePeriodIfPresent(GlUniformFloat uniform, float value) {
		if (uniform != null) {
			uniform.setFloat(value);
		}
	}

	private static boolean potassium$useSceneShaderPath() {
		return PotassiumFeatures.modEnabled() && GLCapabilities.hasShaderDrawParameters();
	}
}
