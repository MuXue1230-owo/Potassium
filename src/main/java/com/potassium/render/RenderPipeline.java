package com.potassium.render;

import com.potassium.core.PotassiumConfig;
import com.potassium.core.PotassiumLogger;
import com.potassium.gl.GLCapabilities;
import com.potassium.gl.buffer.IndirectCommandBuffer;
import com.potassium.gl.buffer.VertexBuffer;
import com.potassium.gl.buffer.WorldDataBuffer;
import com.potassium.render.culling.FrustumCuller;
import com.potassium.render.culling.OcclusionCuller;
import com.potassium.render.shader.ComputeShader;
import com.potassium.render.shader.ShaderProgram;
import com.potassium.world.ChunkManager;
import com.potassium.world.MemoryManager;
import com.potassium.world.WorldChangeTracker;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import com.potassium.world.data.ChunkData;
import com.potassium.world.data.ChunkSnapshot;

public final class RenderPipeline implements AutoCloseable {
	private static final long MEBIBYTE_BYTES = 1024L * 1024L;
	private static final int WORLD_DATA_BINDING = 0;
	private static final int WORLD_DATA_GROWTH_MIN_MIB = 128;
	private static final int WORLD_DATA_GROWTH_RESERVE_MIB = 512;
	private static final int WORLD_DATA_FULL_WARN_INTERVAL = 64;

	private final PotassiumConfig config;
	private final ChunkManager chunkManager;
	private final WorldChangeTracker worldChangeTracker;
	private final FrustumCuller frustumCuller;
	private final OcclusionCuller occlusionCuller;
	private final MemoryManager memoryManager = new MemoryManager();

	private WorldDataBuffer worldDataBuffer;
	private IndirectCommandBuffer indirectCommandBuffer;
	private VertexBuffer vertexBuffer;
	private ShaderProgram chunkProgram;
	private ComputeShader meshGenerationShader;
	private ComputeShader frustumCullingShader;
	private ComputeShader occlusionCullingShader;
	private boolean initialized;
	private ClientLevel activeLevel;
	private long lastUploadedWorldBytes;
	private int lastSyncedChangeCount;
	private int worldDataBufferFullFailures;
	private int worldDataBufferExpansionFailures;

	public RenderPipeline(PotassiumConfig config, ChunkManager chunkManager, WorldChangeTracker worldChangeTracker) {
		this.config = config;
		this.chunkManager = chunkManager;
		this.worldChangeTracker = worldChangeTracker;
		this.frustumCuller = new FrustumCuller(true);
		this.occlusionCuller = new OcclusionCuller(GLCapabilities.hasComputeShader());
	}

	public void initialize() {
		if (this.initialized) {
			return;
		}

		boolean usePersistentMapping = this.config.general.enablePersistentMapping && GLCapabilities.hasPersistentMapping();
		this.worldDataBuffer = new WorldDataBuffer(toBytes(worldDataBudgetMiB()), usePersistentMapping);
		this.indirectCommandBuffer = new IndirectCommandBuffer(this.config.memory.indirectCommandCapacity, usePersistentMapping);
		this.vertexBuffer = new VertexBuffer();
		this.chunkProgram = ShaderProgram.graphics(
			"chunk",
			"shaders/render/chunk.vert",
			"shaders/render/chunk.frag"
		);

		if (GLCapabilities.hasComputeShader()) {
			this.meshGenerationShader = ComputeShader.load("mesh_generation", "shaders/mesh/generation.comp");
			this.frustumCullingShader = ComputeShader.load("frustum_culling", "shaders/culling/frustum.comp");
			this.occlusionCullingShader = ComputeShader.load("occlusion_culling", "shaders/culling/occlusion.comp");
		}

		this.initialized = true;

		PotassiumLogger.logger().info(
			"Render pipeline initialized. worldData={} MiB, indirectCommands={}, persistentMapping={}, computeShaders={}",
			this.config.memory.worldDataBufferMiB,
			this.config.memory.indirectCommandCapacity,
			usePersistentMapping,
			GLCapabilities.hasComputeShader()
		);
	}

	public void beginFrame() {
		if (!this.initialized) {
			return;
		}

		this.worldDataBuffer.beginFrame();
		this.indirectCommandBuffer.beginFrame();
	}

	public void endFrame() {
		if (!this.initialized) {
			return;
		}

		this.worldDataBuffer.endFrame();
		this.indirectCommandBuffer.upload();
		this.indirectCommandBuffer.endFrame();
	}

	public void flushPendingChanges(List<WorldChangeTracker.BlockChange> changes) {
		if (!this.initialized) {
			return;
		}

		long uploadedBytes = 0L;
		int appliedChanges = 0;

		for (WorldChangeTracker.BlockChange change : changes) {
			ChunkPos chunkPos = ChunkPos.containing(change.position());
			ChunkData chunkData = this.chunkManager.getChunk(chunkPos);
			if (chunkData == null || !chunkData.isResident()) {
				continue;
			}

			BlockState newState = change.newState();
			if (this.worldDataBuffer.applyBlockChange(chunkData.residentSlot(), change.position(), newState, change.flags())) {
				uploadedBytes += this.worldDataBuffer.lastUploadBytes();
				appliedChanges++;
				chunkData.markDirty(change.tick());
			}
		}

		this.lastUploadedWorldBytes = uploadedBytes;
		this.lastSyncedChangeCount = appliedChanges;
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
	}

	public long lastUploadedWorldBytes() {
		return this.lastUploadedWorldBytes;
	}

	public int lastSyncedChangeCount() {
		return this.lastSyncedChangeCount;
	}

	public String summaryLine() {
		return String.format(
			"chunks=%d resident=%d/%d wdbPages=%d lastUpload=%dB trackedChanges=%d frustum=%s occlusion=%s",
			this.chunkManager.size(),
			this.memoryManager.residentChunks(),
			this.memoryManager.capacityChunks(),
			this.worldDataBuffer.pageCount(),
			this.lastUploadedWorldBytes,
			this.worldChangeTracker.pendingChangeCount(),
			this.frustumCuller.isEnabled(),
			this.occlusionCuller.isEnabled()
		);
	}

	public void setLevel(ClientLevel level) {
		if (!this.initialized) {
			return;
		}

		if (this.activeLevel == level && level != null && this.worldDataBuffer.isConfigured()) {
			return;
		}

		this.activeLevel = level;
		this.memoryManager.reset();
		this.lastUploadedWorldBytes = 0L;
		this.lastSyncedChangeCount = 0;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;

		if (level == null) {
			return;
		}

		int minSectionY = level.getMinSectionY();
		int sectionsCount = level.getSectionsCount();
		this.worldDataBuffer.configure(minSectionY, sectionsCount);
		this.memoryManager.configure(this.worldDataBuffer.capacityBytes(), this.worldDataBuffer.bytesPerChunk());

		PotassiumLogger.logger().info(
			"Configured world data buffer for level layout: minSectionY={}, sections={}, bytesPerChunk={}, residentChunkCapacity={}, pages={}, targetPageBytes={}",
			minSectionY,
			sectionsCount,
			this.worldDataBuffer.bytesPerChunk(),
			this.memoryManager.capacityChunks(),
			this.worldDataBuffer.pageCount(),
			this.worldDataBuffer.targetPageCapacityBytes()
		);
	}

	public boolean uploadChunk(ChunkData chunkData, ChunkSnapshot snapshot, long tickIndex) {
		if (!this.initialized || this.activeLevel == null || !this.worldDataBuffer.isConfigured()) {
			return false;
		}

		if (snapshot.sectionsCount() != this.worldDataBuffer.sectionsCount() || snapshot.minSectionY() != this.worldDataBuffer.minSectionY()) {
			PotassiumLogger.logger().warn(
				"Rejected chunk {} because its layout ({}, {}) does not match the active level layout ({}, {}).",
				snapshot.chunkPos(),
				snapshot.minSectionY(),
				snapshot.sectionsCount(),
				this.worldDataBuffer.minSectionY(),
				this.worldDataBuffer.sectionsCount()
			);
			return false;
		}

		int residentSlot = chunkData.isResident() ? chunkData.residentSlot() : this.memoryManager.tryAcquireSlot();
		if (residentSlot < 0) {
			if (this.tryExpandWorldDataBuffer()) {
				residentSlot = this.memoryManager.tryAcquireSlot();
			}
			if (residentSlot < 0) {
				this.logWorldDataBufferFull(snapshot.chunkPos());
				return false;
			}
		}

		this.worldDataBuffer.uploadChunk(residentSlot, snapshot.blockData());
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.lastUploadedWorldBytes = snapshot.byteSize();
		chunkData.markResident(residentSlot, this.worldDataBuffer.chunkOffsetBytes(residentSlot), tickIndex);
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		return true;
	}

	public void unloadChunk(ChunkData chunkData) {
		if (chunkData == null || !chunkData.isResident()) {
			return;
		}

		this.memoryManager.releaseSlot(chunkData.residentSlot());
		chunkData.clearResident();
	}

	public int residentChunkCount() {
		return this.memoryManager.residentChunks();
	}

	public int residentChunkCapacity() {
		return this.memoryManager.capacityChunks();
	}

	public long residentWorldBytes() {
		return this.memoryManager.usedBytes();
	}

	@Override
	public void close() {
		closeQuietly(this.occlusionCullingShader);
		closeQuietly(this.frustumCullingShader);
		closeQuietly(this.meshGenerationShader);
		closeQuietly(this.chunkProgram);
		closeQuietly(this.vertexBuffer);
		closeQuietly(this.indirectCommandBuffer);
		closeQuietly(this.worldDataBuffer);

		this.occlusionCullingShader = null;
		this.frustumCullingShader = null;
		this.meshGenerationShader = null;
		this.chunkProgram = null;
		this.vertexBuffer = null;
		this.indirectCommandBuffer = null;
		this.worldDataBuffer = null;
		this.initialized = false;
		this.activeLevel = null;
		this.memoryManager.reset();
		this.lastUploadedWorldBytes = 0L;
		this.lastSyncedChangeCount = 0;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
	}

	private static long toBytes(int mebibytes) {
		return (long) mebibytes * MEBIBYTE_BYTES;
	}

	private int worldDataBudgetMiB() {
		return Math.max(64, Math.min(this.config.memory.worldDataBufferMiB, this.config.memory.maxResidentWorldMiB));
	}

	private boolean tryExpandWorldDataBuffer() {
		int currentBudgetMiB = toMebibytesCeil(this.worldDataBuffer.capacityBytes());
		int maxBudgetMiB = Math.max(currentBudgetMiB, this.config.memory.maxResidentWorldMiB);
		if (currentBudgetMiB >= maxBudgetMiB) {
			return false;
		}

		int availableVideoMemoryMiB = GLCapabilities.getEstimatedAvailableVideoMemoryMiB();
		if (availableVideoMemoryMiB < 0) {
			return false;
		}

		int usableGrowthMiB = availableVideoMemoryMiB - WORLD_DATA_GROWTH_RESERVE_MIB;
		if (usableGrowthMiB <= 0) {
			return false;
		}

		int requestedBudgetMiB = Math.max(currentBudgetMiB * 2, currentBudgetMiB + WORLD_DATA_GROWTH_MIN_MIB);
		requestedBudgetMiB = Math.min(requestedBudgetMiB, currentBudgetMiB + usableGrowthMiB);
		requestedBudgetMiB = Math.min(requestedBudgetMiB, maxBudgetMiB);
		if (requestedBudgetMiB <= currentBudgetMiB) {
			return false;
		}

		try {
			boolean expanded = this.worldDataBuffer.ensureCapacity(toBytes(requestedBudgetMiB));
			if (!expanded) {
				return false;
			}

			boolean slotBudgetExpanded = this.memoryManager.expandBudget(this.worldDataBuffer.capacityBytes());
			int newBudgetMiB = toMebibytesCeil(this.worldDataBuffer.capacityBytes());
			this.worldDataBufferExpansionFailures = 0;
			PotassiumLogger.logger().info(
				"Expanded world data buffer from {} MiB to {} MiB after reaching capacity. Estimated available VRAM={} MiB, resident chunk capacity={}, pages={}{}.",
				currentBudgetMiB,
				newBudgetMiB,
				availableVideoMemoryMiB,
				this.memoryManager.capacityChunks(),
				this.worldDataBuffer.pageCount(),
				slotBudgetExpanded ? "" : " (slot budget unchanged)"
			);
			return true;
		} catch (RuntimeException exception) {
			this.worldDataBufferExpansionFailures++;
			if (this.worldDataBufferExpansionFailures == 1 || (this.worldDataBufferExpansionFailures % WORLD_DATA_FULL_WARN_INTERVAL) == 0) {
				PotassiumLogger.logger().warn(
					"World data buffer expansion from {} MiB to {} MiB failed. Estimated available VRAM={} MiB, failures={}, reason={}",
					currentBudgetMiB,
					requestedBudgetMiB,
					availableVideoMemoryMiB,
					this.worldDataBufferExpansionFailures,
					exception.getMessage()
				);
			}
			return false;
		}
	}

	private void logWorldDataBufferFull(ChunkPos chunkPos) {
		this.worldDataBufferFullFailures++;
		if (this.worldDataBufferFullFailures == 1 || (this.worldDataBufferFullFailures % WORLD_DATA_FULL_WARN_INTERVAL) == 0) {
			PotassiumLogger.logger().warn(
				"World data buffer is full; could not resident chunk {}. Resident chunks={}/{}. Failures={} maxResidentWorldMiB={} estimatedAvailableVRAM={} MiB.",
				chunkPos,
				this.memoryManager.residentChunks(),
				this.memoryManager.capacityChunks(),
				this.worldDataBufferFullFailures,
				this.config.memory.maxResidentWorldMiB,
				GLCapabilities.getEstimatedAvailableVideoMemoryMiB()
			);
		}
	}

	private static int toMebibytesCeil(long bytes) {
		return (int) ((bytes + (MEBIBYTE_BYTES - 1)) / MEBIBYTE_BYTES);
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}

		try {
			closeable.close();
		} catch (Exception exception) {
			PotassiumLogger.logger().warn("Failed to close render resource cleanly.", exception);
		}
	}
}
