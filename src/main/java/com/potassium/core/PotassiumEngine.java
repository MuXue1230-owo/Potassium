package com.potassium.core;

import com.potassium.gl.GLCapabilities;
import com.potassium.gl.GLDebug;
import com.potassium.render.RenderPipeline;
import com.potassium.render.material.BlockMaterialReloadListener;
import com.potassium.render.material.BlockMaterialTable;
import com.potassium.ui.DebugOverlay;
import com.potassium.world.ChunkLoader;
import com.potassium.world.ChunkManager;
import com.potassium.world.WorldChangeTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;

public final class PotassiumEngine implements ClientModInitializer {
	private static PotassiumEngine instance;

	private PotassiumConfig config;
	private ChunkManager chunkManager;
	private ChunkLoader chunkLoader;
	private WorldChangeTracker worldChangeTracker;
	private RenderPipeline renderPipeline;
	private DebugOverlay debugOverlay;
	private ClientLevel activeLevel;
	private BlockMaterialTable blockMaterialTable = BlockMaterialTable.empty();
	private ChunkPos lastBootstrapCenterChunk;
	private boolean pendingBlockMaterialUpload;
	private boolean runtimeReady;
	private boolean initialChunksDrained;

	public PotassiumEngine() {
		instance = this;
	}

	public static PotassiumEngine getNullable() {
		return instance;
	}

	@Override
	public void onInitializeClient() {
		this.config = PotassiumConfig.load();
		BlockMaterialReloadListener.register(this::onBlockMaterialTableReloaded);

		ClientLifecycleEvents.CLIENT_STARTED.register(this::startRuntime);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());

		PotassiumLogger.logger().info("Potassium bootstrap installed.");
	}

	public void onClientTick(Minecraft client) {
		if (!this.runtimeReady) {
			return;
		}

		if (this.activeLevel != client.level) {
			this.activeLevel = client.level;
			this.chunkLoader.clear();
			this.chunkManager.clear();
			this.worldChangeTracker.clear();
			this.lastBootstrapCenterChunk = null;
			this.initialChunksDrained = false;
			this.renderPipeline.setLevel(this.activeLevel);

			this.refreshBootstrapWindow(client);

			PotassiumLogger.logger().info("World context changed; cleared resident chunk and change tracking state.");
		}

		// During initial GPU mesh generation, do NOT drain more chunk loads.
		// The loading screen will block until GPU work is done, and we don't
		// want VRAM to explode from the load queue filling up during that time.
		if (this.renderPipeline.isInitialMeshGenerationPending()) {
			// Still refresh bootstrap and flush materials/changes, but skip chunk loading
			this.refreshBootstrapWindow(client);
			if (this.pendingBlockMaterialUpload) {
				this.renderPipeline.updateBlockMaterialTable(this.blockMaterialTable);
				this.pendingBlockMaterialUpload = false;
			}
			return;
		}

		this.refreshBootstrapWindow(client);
		this.chunkLoader.drainQueues(client.level, this.renderPipeline, this.worldChangeTracker.tickIndex());
		if (this.pendingBlockMaterialUpload) {
			this.renderPipeline.updateBlockMaterialTable(this.blockMaterialTable);
			this.pendingBlockMaterialUpload = false;
		}
		this.renderPipeline.flushPendingChanges(this.worldChangeTracker.drainChanges());
		this.worldChangeTracker.advanceTick();
	}

	/**
	 * Called by LevelLoadTrackerMixin during the loading screen.
	 * Drains all pending chunk loads (bypassing per-tick limit) once,
	 * records the target chunk count, then dispatches GPU mesh generation
	 * for the currently loaded chunks.
	 */
	public void drainInitialChunksAndBlockForGpu() {
		if (this.activeLevel == null || this.renderPipeline == null) {
			return;
		}

		// Only drain once — the target count is fixed at first call
		if (!this.initialChunksDrained) {
			this.initialChunksDrained = true;

			// Drain all pending chunk loads at once (no per-tick limit)
			int drained = 0;
			while (this.chunkLoader.loadQueueSize() > 0) {
				this.chunkLoader.drainQueues(this.activeLevel, this.renderPipeline, this.worldChangeTracker.tickIndex());
				drained++;
			}

			int residentCount = this.chunkManager.size();
			this.renderPipeline.setTargetInitialChunkCount(residentCount);

			if (drained > 0) {
				PotassiumLogger.logger().info(
					"Drained {} initial chunk load batches, targetInitialChunks={}.",
					drained, residentCount
				);
			}
		}

		// Dispatch dirty slots + wait GPU fence (non-blocking, returns when fence signals)
		this.renderPipeline.blockUntilInitialMeshGenerationComplete();
	}

	/**
	 * Returns true if GPU mesh generation for the initial world load is still pending.
	 * Used by LevelLoadTrackerMixin to delay loading screen closure.
	 */
	public boolean isInitialMeshGenerationPending() {
		return this.renderPipeline != null && this.renderPipeline.isInitialMeshGenerationPending();
	}

	/**
	 * Returns the progress of initial GPU mesh generation (0.0 to 1.0).
	 * Used by LevelLoadingScreenMixin to display GPU loading progress.
	 */
	public float getInitialMeshGenerationProgress() {
		if (this.renderPipeline == null) {
			return 1.0f;
		}
		return this.renderPipeline.getInitialMeshGenerationProgress();
	}

	public void onRenderLevelStart() {
		if (this.runtimeReady) {
			this.renderPipeline.beginFrame();
		}
	}

	public boolean onRenderOpaqueTerrain(
		CameraRenderState cameraState,
		Matrix4fc modelViewMatrix,
		ChunkSectionsToRender vanillaChunkSectionsToRender,
		ChunkSectionLayerGroup chunkSectionLayerGroup,
		GpuSampler gpuSampler
	) {
		if (this.runtimeReady && this.renderPipeline != null) {
			return this.renderPipeline.renderOpaqueTerrain(
				cameraState,
				modelViewMatrix,
				vanillaChunkSectionsToRender,
				chunkSectionLayerGroup,
				gpuSampler
			);
		}

		return false;
	}

	public boolean onRenderTranslucentTerrain(
		CameraRenderState cameraState,
		Matrix4fc modelViewMatrix,
		ChunkSectionsToRender vanillaChunkSectionsToRender,
		ChunkSectionLayerGroup chunkSectionLayerGroup,
		GpuSampler gpuSampler
	) {
		if (this.runtimeReady && this.renderPipeline != null) {
			return this.renderPipeline.renderTranslucentTerrain(
				cameraState,
				modelViewMatrix,
				vanillaChunkSectionsToRender,
				chunkSectionLayerGroup,
				gpuSampler
			);
		}

		return false;
	}

	public void onRenderLevelEnd() {
		if (this.runtimeReady) {
			this.renderPipeline.endFrame();
		}
	}

	public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState, int flags) {
		if (!this.runtimeReady || level == null) {
			return;
		}

		this.worldChangeTracker.record(pos, oldState, newState, flags);
		ChunkPos chunkPos = ChunkPos.containing(pos);
		long tickIndex = this.worldChangeTracker.tickIndex();
		var chunkData = this.chunkManager.touchChunk(chunkPos, tickIndex);
		if (!this.renderPipeline.handlesWorldChangesOnGpu()) {
			this.renderPipeline.markBoundaryNeighborsDirty(pos, tickIndex);
		}
		if (!chunkData.isResident()) {
			this.chunkLoader.requestRefresh(chunkPos);
		}

		if (this.config.debug.verboseWorldChangeLogging) {
			PotassiumLogger.logger().debug(
				"Tracked block change at {} from {} to {} (flags={}).",
				pos,
				oldState,
				newState,
				flags
			);
		}
	}

	public boolean isRuntimeReady() {
		return this.runtimeReady;
	}

	public DebugOverlay debugOverlay() {
		return this.debugOverlay;
	}

	public String describeStatus() {
		if (!this.runtimeReady) {
			return "runtime=idle";
		}

		return String.format(
			"runtime=ready gl=%s queues=%d/%d pendingChanges=%d %s",
			GLCapabilities.getVersionString(),
			this.chunkLoader.loadQueueSize(),
			this.chunkLoader.unloadQueueSize(),
			this.worldChangeTracker.pendingChangeCount(),
			this.renderPipeline.summaryLine()
		);
	}

	public void onChunkLoaded(ChunkPos chunkPos) {
		if (this.runtimeReady) {
			this.chunkLoader.requestLoad(chunkPos);
		}
	}

	public void onChunkUnloaded(ChunkPos chunkPos) {
		if (this.runtimeReady) {
			this.chunkLoader.requestUnload(chunkPos);
		}
	}

	private void startRuntime(Minecraft client) {
		if (this.runtimeReady) {
			return;
		}

		if (!this.config.general.engineEnabled) {
			PotassiumLogger.logger().warn("Potassium is disabled in config; runtime will stay inactive.");
			return;
		}

		GLCapabilities.initialize();
		if (this.config.general.requireOpenGl46 && !GLCapabilities.isVersion46()) {
			throw new IllegalStateException("Potassium is configured to require an OpenGL 4.6 context.");
		}

		GLDebug.initialize(this.config);

		this.chunkManager = new ChunkManager();
		this.chunkLoader = new ChunkLoader(this.chunkManager);
		this.worldChangeTracker = new WorldChangeTracker();
		this.renderPipeline = new RenderPipeline(this.config, this.chunkManager, this.worldChangeTracker);
		this.debugOverlay = new DebugOverlay();
		this.activeLevel = client.level;

		this.renderPipeline.initialize();
		this.renderPipeline.updateBlockMaterialTable(this.blockMaterialTable);
		this.pendingBlockMaterialUpload = false;
		this.renderPipeline.setLevel(this.activeLevel);
		this.lastBootstrapCenterChunk = null;
		this.refreshBootstrapWindow(client);

		this.runtimeReady = true;

		PotassiumLogger.logger().info(
			"Potassium runtime ready. GL={}, vendor={}, renderer={}, persistentMapping={}, compute={}",
			GLCapabilities.getVersionString(),
			GLCapabilities.getVendorString(),
			GLCapabilities.getRendererString(),
			GLCapabilities.hasPersistentMapping(),
			GLCapabilities.hasComputeShader()
		);
	}

	private void shutdown() {
		if (this.renderPipeline != null) {
			this.renderPipeline.close();
			this.renderPipeline = null;
		}

		GLDebug.shutdown();

		if (this.chunkManager != null) {
			this.chunkManager.clear();
			this.chunkManager = null;
		}

		if (this.chunkLoader != null) {
			this.chunkLoader.clear();
			this.chunkLoader = null;
		}

		if (this.worldChangeTracker != null) {
			this.worldChangeTracker.clear();
			this.worldChangeTracker = null;
		}

		this.debugOverlay = null;
		this.activeLevel = null;
		this.pendingBlockMaterialUpload = false;
		this.lastBootstrapCenterChunk = null;
		this.runtimeReady = false;

		PotassiumLogger.logger().info("Potassium runtime stopped.");
	}

	private void refreshBootstrapWindow(Minecraft client) {
		if (this.activeLevel == null || client.player == null) {
			this.lastBootstrapCenterChunk = null;
			return;
		}

		ChunkPos centerChunk = ChunkPos.containing(client.player.blockPosition());
		if (centerChunk.equals(this.lastBootstrapCenterChunk)) {
			return;
		}

		int bootstrapRadius = Math.min(
			Math.max(client.options.getEffectiveRenderDistance(), 2),
			this.config.general.targetRenderDistanceChunks
		);
		this.chunkLoader.bootstrapLoadedChunks(this.activeLevel, client.player.blockPosition(), bootstrapRadius);
		this.lastBootstrapCenterChunk = centerChunk;
	}

	private void onBlockMaterialTableReloaded(BlockMaterialTable blockMaterialTable) {
		this.blockMaterialTable = blockMaterialTable;
		this.pendingBlockMaterialUpload = true;
		PotassiumLogger.logger().info(
			"Queued block material table upload: states={}, resolvedStates={}.",
			blockMaterialTable.stateCount(),
			blockMaterialTable.resolvedStates()
		);
	}
}
