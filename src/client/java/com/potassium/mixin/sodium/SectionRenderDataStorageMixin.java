package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.SectionRenderDataStorageVersioned;
import net.caffeinemc.mods.sodium.client.gl.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gl.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDataStorage.class)
public abstract class SectionRenderDataStorageMixin implements SectionRenderDataStorageVersioned {
	@Shadow
	private GlBufferSegment sharedIndexAllocation;

	@Unique
	private int potassium$storageVersion;

	@Unique
	private long potassium$sharedIndexOffsetBeforeUpdate = Long.MIN_VALUE;

	@Override
	public int potassium$getStorageVersion() {
		return this.potassium$storageVersion;
	}

	@Inject(
		method = {
			"setVertexData(ILnet/caffeinemc/mods/sodium/client/gl/arena/GlBufferSegment;[I)V",
			"setIndexData(ILnet/caffeinemc/mods/sodium/client/gl/arena/GlBufferSegment;)V",
			"removeIndexData(I)V",
			"removeVertexData(I)V",
			"removeData(I)V",
			"onBufferResized()V",
			"onIndexBufferResized()V",
			"delete()V"
		},
		at = @At("TAIL")
	)
	private void potassium$markStorageDirty(CallbackInfo ci) {
		this.potassium$storageVersion++;
	}

	@Inject(method = "setSharedIndexUsage", at = @At("RETURN"))
	private void potassium$markSharedIndexUsageDirty(
		int sectionIndex,
		int indexUsage,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (cir.getReturnValue()) {
			this.potassium$storageVersion++;
		}
	}

	@Inject(method = "updateSharedIndexData", at = @At("HEAD"))
	private void potassium$captureSharedIndexOffsetBeforeUpdate(
		CommandList commandList,
		GlBufferArena arena,
		float fillFraction,
		CallbackInfoReturnable<Boolean> cir
	) {
		this.potassium$sharedIndexOffsetBeforeUpdate = this.potassium$getSharedIndexOffset();
	}

	@Inject(method = "updateSharedIndexData", at = @At("RETURN"))
	private void potassium$markSharedIndexDataDirty(
		CommandList commandList,
		GlBufferArena arena,
		float fillFraction,
		CallbackInfoReturnable<Boolean> cir
	) {
		long sharedIndexOffsetAfterUpdate = this.potassium$getSharedIndexOffset();
		if (sharedIndexOffsetAfterUpdate != this.potassium$sharedIndexOffsetBeforeUpdate) {
			this.potassium$storageVersion++;
		}
	}

	@Unique
	private long potassium$getSharedIndexOffset() {
		return this.sharedIndexAllocation != null ? this.sharedIndexAllocation.getOffset() : Long.MIN_VALUE;
	}
}
