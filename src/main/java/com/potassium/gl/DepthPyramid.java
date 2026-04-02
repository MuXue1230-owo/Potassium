package com.potassium.gl;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL45C;

public final class DepthPyramid implements AutoCloseable {
	private int copyFramebuffer;
	private int depthCopyTexture;
	private int pyramidTexture;
	private int width;
	private int height;
	private int levels;
	private int depthCopyInternalFormat;

	public boolean ensureSizeFromSource(int sourceFramebuffer, int width, int height) {
		FramebufferDepthFormat sourceDepthFormat = FramebufferDepthFormat.resolve(sourceFramebuffer);
		if (!sourceDepthFormat.isDefined()) {
			return false;
		}

		int targetWidth = Math.max(width, 1);
		int targetHeight = Math.max(height, 1);
		int targetLevels = mipLevels(targetWidth, targetHeight);
		if (this.copyFramebuffer != 0
			&& this.width == targetWidth
			&& this.height == targetHeight
			&& this.levels == targetLevels
			&& this.depthCopyInternalFormat == sourceDepthFormat.internalFormat()) {
			return true;
		}

		this.close();
		this.width = targetWidth;
		this.height = targetHeight;
		this.levels = targetLevels;
		this.depthCopyInternalFormat = sourceDepthFormat.internalFormat();

		this.copyFramebuffer = GL45C.glCreateFramebuffers();
		this.depthCopyTexture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
		GL45C.glTextureStorage2D(this.depthCopyTexture, 1, this.depthCopyInternalFormat, this.width, this.height);
		GL45C.glTextureParameteri(this.depthCopyTexture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
		GL45C.glTextureParameteri(this.depthCopyTexture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		if (sourceDepthFormat.hasStencil()) {
			GL45C.glTextureParameteri(this.depthCopyTexture, GL43C.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11C.GL_DEPTH_COMPONENT);
		}
		GL45C.glNamedFramebufferTexture(this.copyFramebuffer, sourceDepthFormat.attachmentPoint(), this.depthCopyTexture, 0);
		GL45C.glNamedFramebufferDrawBuffer(this.copyFramebuffer, GL11C.GL_NONE);
		GL45C.glNamedFramebufferReadBuffer(this.copyFramebuffer, GL11C.GL_NONE);

		int framebufferStatus = GL45C.glCheckNamedFramebufferStatus(this.copyFramebuffer, GL30C.GL_FRAMEBUFFER);
		if (framebufferStatus != GL30C.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Failed to create depth-copy framebuffer. status=0x" + Integer.toHexString(framebufferStatus));
		}

		this.pyramidTexture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
		GL45C.glTextureStorage2D(this.pyramidTexture, this.levels, GL30C.GL_R32F, this.width, this.height);
		GL45C.glTextureParameteri(this.pyramidTexture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
		GL45C.glTextureParameteri(this.pyramidTexture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		GL45C.glTextureParameteri(this.pyramidTexture, GL12C.GL_TEXTURE_MAX_LEVEL, this.levels - 1);
		return true;
	}

	public void copyDepthFrom(int sourceFramebuffer, int sourceViewportX, int sourceViewportY, int sourceViewportWidth, int sourceViewportHeight) {
		if (this.copyFramebuffer == 0) {
			return;
		}

		int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
		int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
		try {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.copyFramebuffer);
			GL30C.glBlitFramebuffer(
				sourceViewportX,
				sourceViewportY,
				sourceViewportX + sourceViewportWidth,
				sourceViewportY + sourceViewportHeight,
				0,
				0,
				this.width,
				this.height,
				GL11C.GL_DEPTH_BUFFER_BIT,
				GL11C.GL_NEAREST
			);
		} finally {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
		}
	}

	public void bindDepthCopyTexture(int textureUnit) {
		GL45C.glBindTextureUnit(textureUnit, this.depthCopyTexture);
	}

	public void bindPyramidTexture(int textureUnit) {
		GL45C.glBindTextureUnit(textureUnit, this.pyramidTexture);
	}

	public void bindPyramidLevelForWrite(int imageUnit, int level) {
		GL42C.glBindImageTexture(imageUnit, this.pyramidTexture, level, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public int levels() {
		return this.levels;
	}

	public int levelWidth(int level) {
		return Math.max(this.width >> level, 1);
	}

	public int levelHeight(int level) {
		return Math.max(this.height >> level, 1);
	}

	@Override
	public void close() {
		if (this.copyFramebuffer != 0) {
			GL30C.glDeleteFramebuffers(this.copyFramebuffer);
			this.copyFramebuffer = 0;
		}
		if (this.depthCopyTexture != 0) {
			GL11C.glDeleteTextures(this.depthCopyTexture);
			this.depthCopyTexture = 0;
		}
		if (this.pyramidTexture != 0) {
			GL11C.glDeleteTextures(this.pyramidTexture);
			this.pyramidTexture = 0;
		}

		this.width = 0;
		this.height = 0;
		this.levels = 0;
		this.depthCopyInternalFormat = 0;
	}

	private static int mipLevels(int width, int height) {
		int maxDimension = Math.max(width, height);
		int levels = 1;
		while (maxDimension > 1) {
			maxDimension >>= 1;
			levels++;
		}
		return levels;
	}
}
