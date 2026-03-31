package com.potassium.entity;

import com.potassium.render.RenderPass;

public class EntityRenderer {
	public RenderPass renderPass() {
		return RenderPass.ENTITIES;
	}

	public boolean isGpuDriven() {
		return false;
	}
}
