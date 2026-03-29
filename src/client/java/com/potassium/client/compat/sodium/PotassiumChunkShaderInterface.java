package com.potassium.client.compat.sodium;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.EnumMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformBool;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderFogComponent;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderTextureSlot;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.mixin.core.render.texture.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL33C;

public final class PotassiumChunkShaderInterface implements ChunkShaderInterface {
	private final Map<ChunkShaderTextureSlot, GlUniformInt> uniformTextures =
		new EnumMap<>(ChunkShaderTextureSlot.class);

	private final GlUniformMatrix4f uniformModelViewMatrix;
	private final GlUniformMatrix4f uniformProjectionMatrix;
	private final GlUniformFloat3v uniformRegionOffset;
	private final GlUniformFloat2v uniformTexCoordShrink;
	private final GlUniformFloat2v uniformTexelSize;
	private final GlUniformBool uniformRGSS;
	private final ChunkShaderFogComponent fogShader;

	public PotassiumChunkShaderInterface(ShaderBindingContext context, ChunkShaderOptions options) {
		this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
		this.uniformProjectionMatrix = context.bindUniform("u_ProjectionMatrix", GlUniformMatrix4f::new);
		this.uniformRegionOffset = context.bindUniform("u_RegionOffset", GlUniformFloat3v::new);
		this.uniformTexCoordShrink = context.bindUniform("u_TexCoordShrink", GlUniformFloat2v::new);
		this.uniformTexelSize = context.bindUniform("u_TexelSize", GlUniformFloat2v::new);
		this.uniformRGSS = context.bindUniform("u_UseRGSS", GlUniformBool::new);
		this.uniformTextures.put(
			ChunkShaderTextureSlot.BLOCK,
			context.bindUniform("u_BlockTex", GlUniformInt::new)
		);
		this.uniformTextures.put(
			ChunkShaderTextureSlot.LIGHT,
			context.bindUniform("u_LightTex", GlUniformInt::new)
		);
		this.fogShader = options.fog().getFactory().apply(context);
	}

	@Override
	public void setupState(TerrainRenderPass renderPass, FogParameters fogParameters, GpuSampler terrainSampler) {
		this.bindTexture(ChunkShaderTextureSlot.BLOCK, renderPass.getAtlas(), terrainSampler);
		Minecraft minecraft = Minecraft.getInstance();
		GameRenderer gameRenderer = minecraft.gameRenderer;
		this.bindTexture(
			ChunkShaderTextureSlot.LIGHT,
			gameRenderer.lightmap(),
			RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
		);

		TextureManager textureManager = minecraft.getTextureManager();
		AbstractTexture texture = textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS);
		TextureAtlasAccessor textureAtlas = (TextureAtlasAccessor) texture;
		double subTexelPrecisionScale = (double) (1 << GLRenderDevice.INSTANCE.getSubTexelPrecisionBits());
		double halfTexel = 3.0517578125E-5D;
		this.uniformTexCoordShrink.set(
			(float) (halfTexel - ((1.0D / textureAtlas.sodium$getWidth()) / subTexelPrecisionScale)),
			(float) (halfTexel - ((1.0D / textureAtlas.sodium$getHeight()) / subTexelPrecisionScale))
		);
		this.uniformTexelSize.set(
			1.0f / textureAtlas.sodium$getWidth(),
			1.0f / textureAtlas.sodium$getHeight()
		);
		this.uniformRGSS.setBool(
			minecraft.options.textureFiltering().get() == TextureFilteringMethod.RGSS
		);
		this.fogShader.setup(fogParameters);
	}

	@Override
	public void resetState() {
	}

	@Override
	public void setProjectionMatrix(Matrix4fc matrix) {
		this.uniformProjectionMatrix.set(matrix);
	}

	@Override
	public void setModelViewMatrix(Matrix4fc matrix) {
		this.uniformModelViewMatrix.set(matrix);
	}

	@Override
	public void setRegionOffset(float x, float y, float z) {
		this.uniformRegionOffset.set(x, y, z);
	}

	@Override
	public void setChunkData(net.caffeinemc.mods.sodium.client.gl.buffer.GlBuffer chunkData, int currentTime) {
	}

	private void bindTexture(ChunkShaderTextureSlot slot, GpuTextureView textureView, GpuSampler sampler) {
		GlTexture texture = (GlTexture) textureView.texture();
		int textureUnit = GL33C.GL_TEXTURE0 + slot.ordinal();
		GlStateManager._activeTexture(textureUnit);
		GlStateManager._bindTexture(texture.glId());
		GlStateManager._texParameter(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_BASE_LEVEL, textureView.baseMipLevel());
		GlStateManager._texParameter(
			GL33C.GL_TEXTURE_2D,
			GL33C.GL_TEXTURE_MAX_LEVEL,
			textureView.baseMipLevel() + textureView.mipLevels() - 1
		);
		GL33C.glBindSampler(slot.ordinal(), ((GlSampler) sampler).getId());
		this.uniformTextures.get(slot).setInt(slot.ordinal());
	}
}
