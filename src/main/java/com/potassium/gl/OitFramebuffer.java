package com.potassium.gl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

public final class OitFramebuffer implements AutoCloseable {
	private int framebuffer;
	private int accumulationTexture;
	private int revealageTexture;
	private int depthRenderbuffer;
	private int width;
	private int height;

	public void ensureSize(int width, int height) {
		int targetWidth = Math.max(width, 1);
		int targetHeight = Math.max(height, 1);
		if (this.framebuffer != 0 && this.width == targetWidth && this.height == targetHeight) {
			return;
		}

		this.close();
		this.width = targetWidth;
		this.height = targetHeight;

		this.framebuffer = GL45C.glCreateFramebuffers();
		this.accumulationTexture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
		GL45C.glTextureStorage2D(this.accumulationTexture, 1, GL30C.GL_RGBA16F, this.width, this.height);
		GL45C.glTextureParameteri(this.accumulationTexture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
		GL45C.glTextureParameteri(this.accumulationTexture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		GL45C.glNamedFramebufferTexture(this.framebuffer, GL30C.GL_COLOR_ATTACHMENT0, this.accumulationTexture, 0);

		this.revealageTexture = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
		GL45C.glTextureStorage2D(this.revealageTexture, 1, GL11C.GL_RGBA8, this.width, this.height);
		GL45C.glTextureParameteri(this.revealageTexture, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
		GL45C.glTextureParameteri(this.revealageTexture, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
		GL45C.glNamedFramebufferTexture(this.framebuffer, GL30C.GL_COLOR_ATTACHMENT1, this.revealageTexture, 0);

		this.depthRenderbuffer = GL45C.glCreateRenderbuffers();
		GL45C.glNamedRenderbufferStorage(this.depthRenderbuffer, GL30C.GL_DEPTH_COMPONENT24, this.width, this.height);
		GL45C.glNamedFramebufferRenderbuffer(
			this.framebuffer,
			GL30C.GL_DEPTH_ATTACHMENT,
			GL30C.GL_RENDERBUFFER,
			this.depthRenderbuffer
		);

		int status = GL45C.glCheckNamedFramebufferStatus(this.framebuffer, GL30C.GL_FRAMEBUFFER);
		if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Failed to create translucent OIT framebuffer. status=0x" + Integer.toHexString(status));
		}
	}

	public void clear() {
		if (this.framebuffer == 0) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer accumulationClear = stack.floats(0.0f, 0.0f, 0.0f, 0.0f);
			FloatBuffer revealageClear = stack.floats(1.0f, 1.0f, 1.0f, 1.0f);
			FloatBuffer depthClear = stack.floats(1.0f);
			GL45C.glClearNamedFramebufferfv(this.framebuffer, GL11C.GL_COLOR, 0, accumulationClear);
			GL45C.glClearNamedFramebufferfv(this.framebuffer, GL11C.GL_COLOR, 1, revealageClear);
			GL45C.glClearNamedFramebufferfv(this.framebuffer, GL11C.GL_DEPTH, 0, depthClear);
		}
	}

	public void copyDepthFrom(int sourceFramebuffer, int sourceViewportX, int sourceViewportY, int sourceViewportWidth, int sourceViewportHeight) {
		if (this.framebuffer == 0) {
			return;
		}

		int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
		int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
		try {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
			GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.framebuffer);
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

	public void bindForAccumulation() {
		GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.framebuffer);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer drawBuffers = stack.ints(GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1);
			GL20C.glDrawBuffers(drawBuffers);
		}
		GL11C.glViewport(0, 0, this.width, this.height);
	}

	public void bindTextures(int accumulationTextureUnit, int revealageTextureUnit) {
		GL45C.glBindTextureUnit(accumulationTextureUnit, this.accumulationTexture);
		GL45C.glBindTextureUnit(revealageTextureUnit, this.revealageTexture);
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	@Override
	public void close() {
		if (this.depthRenderbuffer != 0) {
			GL30C.glDeleteRenderbuffers(this.depthRenderbuffer);
			this.depthRenderbuffer = 0;
		}
		if (this.accumulationTexture != 0) {
			GL11C.glDeleteTextures(this.accumulationTexture);
			this.accumulationTexture = 0;
		}
		if (this.revealageTexture != 0) {
			GL11C.glDeleteTextures(this.revealageTexture);
			this.revealageTexture = 0;
		}
		if (this.framebuffer != 0) {
			GL30C.glDeleteFramebuffers(this.framebuffer);
			this.framebuffer = 0;
		}

		this.width = 0;
		this.height = 0;
	}
}
