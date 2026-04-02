package com.potassium.gl;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

public record FramebufferDepthFormat(int internalFormat, int attachmentPoint) {
	public static final FramebufferDepthFormat UNDEFINED = new FramebufferDepthFormat(0, 0);

	public boolean isDefined() {
		return this.internalFormat != 0 && this.attachmentPoint != 0;
	}

	public boolean hasStencil() {
		return this.attachmentPoint == GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
	}

	public static FramebufferDepthFormat resolve(int framebuffer) {
		int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
		try {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
			if (framebuffer == 0) {
				return queryDefaultFramebuffer();
			}

			FramebufferDepthFormat depthStencil = queryAttachment(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT);
			if (depthStencil.isDefined()) {
				return depthStencil;
			}

			FramebufferDepthFormat depthOnly = queryAttachment(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT);
			if (depthOnly.isDefined()) {
				return depthOnly;
			}

			return UNDEFINED;
		} finally {
			GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
		}
	}

	private static FramebufferDepthFormat queryDefaultFramebuffer() {
		int depthSize = GL30C.glGetFramebufferAttachmentParameteri(
			GL30C.GL_READ_FRAMEBUFFER,
			GL11C.GL_DEPTH,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE
		);
		if (depthSize <= 0) {
			return UNDEFINED;
		}

		int stencilSize = GL30C.glGetFramebufferAttachmentParameteri(
			GL30C.GL_READ_FRAMEBUFFER,
			GL11C.GL_STENCIL,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE
		);
		int componentType = GL30C.glGetFramebufferAttachmentParameteri(
			GL30C.GL_READ_FRAMEBUFFER,
			GL11C.GL_DEPTH,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE
		);

		int internalFormat = chooseInternalFormat(depthSize, stencilSize, componentType);
		if (internalFormat == 0) {
			return UNDEFINED;
		}

		int attachmentPoint = stencilSize > 0 ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
		return new FramebufferDepthFormat(internalFormat, attachmentPoint);
	}

	private static FramebufferDepthFormat queryAttachment(int target, int attachment) {
		int objectType = GL30C.glGetFramebufferAttachmentParameteri(
			target,
			attachment,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
		);
		if (objectType == GL11C.GL_NONE) {
			return UNDEFINED;
		}

		int depthSize = GL30C.glGetFramebufferAttachmentParameteri(
			target,
			attachment,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE
		);
		if (depthSize <= 0) {
			return UNDEFINED;
		}

		int stencilSize = GL30C.glGetFramebufferAttachmentParameteri(
			target,
			attachment,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE
		);
		int componentType = GL30C.glGetFramebufferAttachmentParameteri(
			target,
			attachment,
			GL30C.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE
		);

		int internalFormat = chooseInternalFormat(depthSize, stencilSize, componentType);
		if (internalFormat == 0) {
			return UNDEFINED;
		}

		int attachmentPoint = stencilSize > 0 ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
		return new FramebufferDepthFormat(internalFormat, attachmentPoint);
	}

	private static int chooseInternalFormat(int depthSize, int stencilSize, int componentType) {
		if (stencilSize > 0) {
			if (depthSize >= 32 && componentType == GL11C.GL_FLOAT) {
				return GL30C.GL_DEPTH32F_STENCIL8;
			}
			return GL30C.GL_DEPTH24_STENCIL8;
		}

		if (depthSize >= 32 && componentType == GL11C.GL_FLOAT) {
			return GL30C.GL_DEPTH_COMPONENT32F;
		}
		if (depthSize >= 24) {
			return GL30C.GL_DEPTH_COMPONENT24;
		}
		if (depthSize >= 16) {
			return GL30C.GL_DEPTH_COMPONENT16;
		}

		return 0;
	}
}
