package com.potassium.mixin.sodium;

import com.potassium.client.compat.sodium.SectionRenderDataStorageDirtyTracker;
import com.potassium.client.compat.sodium.SectionRenderDataStorageVersioned;
import net.caffeinemc.mods.sodium.client.gl.arena.GlBufferArena;
import net.caffeinemc.mods.sodium.client.gl.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDataStorage.class)
public abstract class SectionRenderDataStorageMixin implements SectionRenderDataStorageVersioned, SectionRenderDataStorageDirtyTracker {
	@Unique
	private static final int potassium$DIRTY_SECTION_WORD_COUNT =
		(RenderRegion.REGION_WIDTH * RenderRegion.REGION_HEIGHT * RenderRegion.REGION_LENGTH + Long.SIZE - 1) / Long.SIZE;

	@Shadow
	private GlBufferSegment sharedIndexAllocation;

	@Unique
	private int potassium$storageVersion;

	@Unique
	private long potassium$sharedIndexOffsetBeforeUpdate = Long.MIN_VALUE;

	@Unique
	private final long[] potassium$dirtySectionBits = new long[potassium$DIRTY_SECTION_WORD_COUNT];

	@Unique
	private boolean potassium$fullMetadataDirty;

	@Override
	public int potassium$getStorageVersion() {
		return this.potassium$storageVersion;
	}

	@Override
	public synchronized boolean potassium$isFullMetadataDirty() {
		return this.potassium$fullMetadataDirty;
	}

	@Override
	public synchronized boolean potassium$hasDirtySections() {
		for (long dirtySectionWord : this.potassium$dirtySectionBits) {
			if (dirtySectionWord != 0L) {
				return true;
			}
		}

		return false;
	}

	@Override
	public synchronized void potassium$copyDirtySectionBits(long[] destination) {
		if (destination.length < this.potassium$dirtySectionBits.length) {
			throw new IllegalArgumentException("Destination dirty-section array is too small.");
		}

		System.arraycopy(this.potassium$dirtySectionBits, 0, destination, 0, this.potassium$dirtySectionBits.length);
	}

	@Override
	public synchronized void potassium$clearMetadataDirty() {
		this.potassium$fullMetadataDirty = false;
		for (int wordIndex = 0; wordIndex < this.potassium$dirtySectionBits.length; wordIndex++) {
			this.potassium$dirtySectionBits[wordIndex] = 0L;
		}
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

	@Inject(
		method = {
			"setVertexData(ILnet/caffeinemc/mods/sodium/client/gl/arena/GlBufferSegment;[I)V",
			"setIndexData(ILnet/caffeinemc/mods/sodium/client/gl/arena/GlBufferSegment;)V",
			"removeIndexData(I)V",
			"removeVertexData(I)V",
			"removeData(I)V"
		},
		at = @At("TAIL")
	)
	private void potassium$markSectionMetadataDirty(int sectionIndex, CallbackInfo ci) {
		this.potassium$markSectionDirty(sectionIndex);
	}

	@Inject(
		method = {
			"onBufferResized()V",
			"onIndexBufferResized()V",
			"delete()V"
		},
		at = @At("TAIL")
	)
	private void potassium$markAllSectionMetadataDirty(CallbackInfo ci) {
		this.potassium$markAllSectionsDirty();
	}

	@Inject(method = "setSharedIndexUsage", at = @At("RETURN"))
	private void potassium$markSharedIndexUsageDirty(
		int sectionIndex,
		int indexUsage,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (cir.getReturnValue()) {
			this.potassium$storageVersion++;
			this.potassium$markSectionDirty(sectionIndex);
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
			this.potassium$markAllSectionsDirty();
		}
	}

	@Unique
	private long potassium$getSharedIndexOffset() {
		return this.sharedIndexAllocation != null ? this.sharedIndexAllocation.getOffset() : Long.MIN_VALUE;
	}

	@Unique
	private synchronized void potassium$markSectionDirty(int sectionIndex) {
		if (sectionIndex < 0) {
			return;
		}

		int wordIndex = sectionIndex >>> 6;
		if (wordIndex >= this.potassium$dirtySectionBits.length) {
			this.potassium$fullMetadataDirty = true;
			return;
		}

		this.potassium$dirtySectionBits[wordIndex] |= 1L << (sectionIndex & 63);
	}

	@Unique
	private synchronized void potassium$markAllSectionsDirty() {
		this.potassium$fullMetadataDirty = true;
		for (int wordIndex = 0; wordIndex < this.potassium$dirtySectionBits.length; wordIndex++) {
			this.potassium$dirtySectionBits[wordIndex] = -1L;
		}
	}
}
