package com.potassium.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ConfigScreen extends Screen {
	private final Screen parent;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("potassium.screen.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
