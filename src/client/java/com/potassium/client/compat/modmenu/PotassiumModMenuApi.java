package com.potassium.client.compat.modmenu;

import com.potassium.client.config.PotassiumSodiumConfigEntryPoint;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class PotassiumModMenuApi implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return PotassiumSodiumConfigEntryPoint::createConfigScreen;
	}
}
