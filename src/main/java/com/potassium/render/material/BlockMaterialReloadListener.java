package com.potassium.render.material;

import com.potassium.core.PotassiumLogger;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourceReloadListenerKeys;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class BlockMaterialReloadListener implements SimpleSynchronousResourceReloadListener {
	private static final Identifier LISTENER_ID = Identifier.fromNamespaceAndPath(
		PotassiumLogger.MOD_ID,
		"block_material_table"
	);

	private final Consumer<BlockMaterialTable> reloadConsumer;

	public BlockMaterialReloadListener(Consumer<BlockMaterialTable> reloadConsumer) {
		this.reloadConsumer = reloadConsumer;
	}

	public static void register(Consumer<BlockMaterialTable> reloadConsumer) {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new BlockMaterialReloadListener(reloadConsumer));
	}

	@Override
	public Identifier getFabricId() {
		return LISTENER_ID;
	}

	@Override
	public Collection<Identifier> getFabricDependencies() {
		return List.of(ResourceReloadListenerKeys.MODELS, ResourceReloadListenerKeys.TEXTURES);
	}

	@Override
	public void onResourceManagerReload(ResourceManager resourceManager) {
		this.reloadConsumer.accept(BlockMaterialTable.captureFromMinecraft());
	}
}
