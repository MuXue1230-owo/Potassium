package com.potassium.render.culling;

import net.minecraft.world.level.ChunkPos;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class FrustumCuller {
	private final boolean enabled;
	private final Matrix4f clipMatrix = new Matrix4f();
	private final FrustumIntersection frustum = new FrustumIntersection();

	public FrustumCuller(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void update(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix) {
		if (!this.enabled) {
			return;
		}

		this.clipMatrix.set(projectionMatrix).mul(modelViewMatrix);
		this.frustum.set(this.clipMatrix);
	}

	public boolean isChunkVisible(ChunkPos chunkPos, int minSectionY, int sectionsCount) {
		if (!this.enabled) {
			return true;
		}

		float minX = chunkPos.x() * 16.0f;
		float minY = minSectionY * 16.0f;
		float minZ = chunkPos.z() * 16.0f;
		float maxX = minX + 16.0f;
		float maxY = minY + (sectionsCount * 16.0f);
		float maxZ = minZ + 16.0f;
		return this.frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
