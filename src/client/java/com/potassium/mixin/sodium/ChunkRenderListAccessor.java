package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.ChunkRenderListOrdering;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkRenderList.class)
public interface ChunkRenderListAccessor extends ChunkRenderListOrdering {
	@Override
	@Accessor("sectionsWithGeometry")
	byte[] potassium$getSectionsWithGeometry();

	@Override
	@Accessor("sectionsWithGeometryMap")
	long[] potassium$getSectionsWithGeometryMap();
}
