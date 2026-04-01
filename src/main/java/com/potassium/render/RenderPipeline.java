package com.potassium.render;

import com.potassium.core.PotassiumConfig;
import com.potassium.core.PotassiumLogger;
import com.potassium.gl.GLCapabilities;
import com.potassium.gl.buffer.IndirectCommandBuffer;
import com.potassium.gl.buffer.MeshGenerationJobBuffer;
import com.potassium.gl.buffer.MeshGenerationStatsBuffer;
import com.potassium.gl.buffer.MeshMetadataBuffer;
import com.potassium.gl.buffer.MeshVertexBuffer;
import com.potassium.gl.buffer.VertexBuffer;
import com.potassium.gl.buffer.WorldDataBuffer;
import com.potassium.render.culling.FrustumCuller;
import com.potassium.render.culling.OcclusionCuller;
import com.potassium.render.shader.ComputeShader;
import com.potassium.render.shader.ShaderProgram;
import com.potassium.world.ChunkManager;
import com.potassium.world.MemoryManager;
import com.potassium.world.WorldChangeTracker;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import com.potassium.world.data.ChunkData;
import com.potassium.world.data.ChunkSnapshot;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

public final class RenderPipeline implements AutoCloseable {
	private static final long MEBIBYTE_BYTES = 1024L * 1024L;
	private static final int INVALID_RESIDENT_SLOT = -1;
	private static final int MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET = 0;
	private static final int MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET = 1;
	private static final int MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET = 2;
	private static final int MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET = 3;
	private static final int WORLD_DATA_BINDING = 0;
	private static final int MESH_JOB_BINDING = WORLD_DATA_BINDING + 1 + WorldDataBuffer.MAX_SHADER_PAGES;
	private static final int MESH_STATS_BINDING = MESH_JOB_BINDING + 1;
	private static final int MESH_METADATA_BINDING = MESH_STATS_BINDING + 1;
	private static final int MESH_VERTEX_BINDING = MESH_METADATA_BINDING + 1;
	private static final int WORLD_DATA_GROWTH_MIN_MIB = 128;
	private static final int WORLD_DATA_GROWTH_RESERVE_MIB = 512;
	private static final int WORLD_DATA_FULL_WARN_INTERVAL = 64;
	private static final int WORLD_DATA_EVICTION_LOG_INTERVAL = 64;
	private static final int MESH_GENERATION_JOB_LIMIT_PER_FRAME = 256;
	private static final int MESH_GENERATION_LOCAL_SIZE_X = 64;
	private static final int MESH_GENERATION_REQUIRED_SSBO_BINDINGS = MESH_VERTEX_BINDING + 1;

	private final PotassiumConfig config;
	private final ChunkManager chunkManager;
	private final WorldChangeTracker worldChangeTracker;
	private final FrustumCuller frustumCuller;
	private final OcclusionCuller occlusionCuller;
	private final MemoryManager memoryManager = new MemoryManager();

	private WorldDataBuffer worldDataBuffer;
	private IndirectCommandBuffer indirectCommandBuffer;
	private MeshGenerationJobBuffer meshGenerationJobBuffer;
	private MeshGenerationStatsBuffer meshGenerationStatsBuffer;
	private MeshMetadataBuffer meshMetadataBuffer;
	private MeshVertexBuffer meshVertexBuffer;
	private VertexBuffer vertexBuffer;
	private ShaderProgram chunkProgram;
	private ComputeShader meshGenerationShader;
	private ComputeShader frustumCullingShader;
	private ComputeShader occlusionCullingShader;
	private boolean initialized;
	private ClientLevel activeLevel;
	private long lastUploadedWorldBytes;
	private int lastSyncedChangeCount;
	private int lastMeshGenerationJobs;
	private int lastMeshGenerationProcessedJobs;
	private int lastMeshGenerationDirtyCandidates;
	private int lastMeshGenerationGeneratedVertices;
	private int lastMeshGenerationClippedJobs;
	private int lastMeshGenerationSampledPackedBlock;
	private int worldDataBufferFullFailures;
	private int worldDataBufferExpansionFailures;
	private int worldDataEvictions;
	private int lastRenderedMeshChunks;
	private int lastRenderedMeshVertices;
	private int debugMeshVertexArray;
	private int chunkModelViewUniform;
	private int chunkProjectionUniform;
	private int meshFacesPerChunkUniform;

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
		this.debugMeshVertexArray = GL45C.glCreateVertexArrays();
		this.chunkModelViewUniform = GL20C.glGetUniformLocation(this.chunkProgram.handle(), "uModelViewMatrix");
		this.chunkProjectionUniform = GL20C.glGetUniformLocation(this.chunkProgram.handle(), "uProjectionMatrix");

		if (GLCapabilities.hasComputeShader()) {
			if (this.hasMeshGenerationBindingBudget()) {
				this.meshGenerationShader = ComputeShader.load("mesh_generation", "shaders/mesh/generation.comp");
				this.meshGenerationJobBuffer = new MeshGenerationJobBuffer(MESH_GENERATION_JOB_LIMIT_PER_FRAME, usePersistentMapping);
				this.meshGenerationStatsBuffer = new MeshGenerationStatsBuffer();
				this.meshMetadataBuffer = new MeshMetadataBuffer(1);
				this.meshVertexBuffer = new MeshVertexBuffer(1, this.config.memory.meshFacesPerChunk);
				this.meshFacesPerChunkUniform = GL20C.glGetUniformLocation(this.meshGenerationShader.handle(), "uMeshFacesPerChunk");
			} else {
				PotassiumLogger.logger().warn(
					"Skipping mesh-generation compute initialization because GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS={} is below the required {}.",
					GLCapabilities.getMaxShaderStorageBufferBindings(),
					MESH_GENERATION_REQUIRED_SSBO_BINDINGS
				);
			}
			this.frustumCullingShader = ComputeShader.load("frustum_culling", "shaders/culling/frustum.comp");
			this.occlusionCullingShader = ComputeShader.load("occlusion_culling", "shaders/culling/occlusion.comp");
		}

		this.initialized = true;

		PotassiumLogger.logger().info(
			"Render pipeline initialized. worldData={} MiB, indirectCommands={}, meshFacesPerChunk={}, persistentMapping={}, computeShaders={}",
			this.config.memory.worldDataBufferMiB,
			this.config.memory.indirectCommandCapacity,
			this.config.memory.meshFacesPerChunk,
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
		if (this.meshGenerationJobBuffer != null) {
			this.meshGenerationJobBuffer.beginFrame();
			this.dispatchDirtyMeshGeneration();
		}
	}

	public void endFrame() {
		if (!this.initialized) {
			return;
		}

		this.worldDataBuffer.endFrame();
		this.indirectCommandBuffer.upload();
		this.indirectCommandBuffer.endFrame();
		if (this.meshGenerationJobBuffer != null) {
			this.meshGenerationJobBuffer.endFrame();
		}
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
			"chunks=%d resident=%d/%d wdbPages=%d lastUpload=%dB trackedChanges=%d meshJobs=%d/%d meshProcessed=%d meshVertices=%d meshClipped=%d rendered=%d/%d sampleBlock=%d frustum=%s occlusion=%s",
			this.chunkManager.size(),
			this.memoryManager.residentChunks(),
			this.memoryManager.capacityChunks(),
			this.worldDataBuffer.pageCount(),
			this.lastUploadedWorldBytes,
			this.worldChangeTracker.pendingChangeCount(),
			this.lastMeshGenerationJobs,
			this.lastMeshGenerationDirtyCandidates,
			this.lastMeshGenerationProcessedJobs,
			this.lastMeshGenerationGeneratedVertices,
			this.lastMeshGenerationClippedJobs,
			this.lastRenderedMeshChunks,
			this.lastRenderedMeshVertices,
			this.lastMeshGenerationSampledPackedBlock,
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
		this.lastMeshGenerationJobs = 0;
		this.lastMeshGenerationProcessedJobs = 0;
		this.lastMeshGenerationDirtyCandidates = 0;
		this.lastMeshGenerationGeneratedVertices = 0;
		this.lastMeshGenerationClippedJobs = 0;
		this.lastMeshGenerationSampledPackedBlock = 0;
		this.lastRenderedMeshChunks = 0;
		this.lastRenderedMeshVertices = 0;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		this.worldDataEvictions = 0;

		if (level == null) {
			return;
		}

		int minSectionY = level.getMinSectionY();
		int sectionsCount = level.getSectionsCount();
		this.worldDataBuffer.configure(minSectionY, sectionsCount);
		this.memoryManager.configure(this.worldDataBuffer.capacityBytes(), this.worldDataBuffer.bytesPerChunk());
		this.ensureMeshOutputCapacity(true);

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
				residentSlot = this.tryRecycleResidentSlot(snapshot.chunkPos(), tickIndex);
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
		this.clearMeshSlot(residentSlot);
		this.markAdjacentResidentChunksMeshDirty(snapshot.chunkPos(), tickIndex);
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		return true;
	}

	public void unloadChunk(ChunkData chunkData) {
		if (chunkData == null || !chunkData.isResident()) {
			return;
		}

		ChunkPos chunkPos = chunkData.chunkPos();
		int residentSlot = chunkData.residentSlot();
		this.clearMeshSlot(residentSlot);
		this.memoryManager.releaseSlot(residentSlot);
		chunkData.clearResident();
		this.markAdjacentResidentChunksMeshDirty(chunkPos, this.worldChangeTracker.tickIndex());
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

	public void markBoundaryNeighborsDirty(net.minecraft.core.BlockPos position, long tickIndex) {
		ChunkPos chunkPos = ChunkPos.containing(position);
		int localX = position.getX() & 15;
		int localZ = position.getZ() & 15;
		if (localX == 0) {
			this.markResidentChunkMeshDirty(chunkPos.x() - 1, chunkPos.z(), tickIndex);
		}
		if (localX == 15) {
			this.markResidentChunkMeshDirty(chunkPos.x() + 1, chunkPos.z(), tickIndex);
		}
		if (localZ == 0) {
			this.markResidentChunkMeshDirty(chunkPos.x(), chunkPos.z() - 1, tickIndex);
		}
		if (localZ == 15) {
			this.markResidentChunkMeshDirty(chunkPos.x(), chunkPos.z() + 1, tickIndex);
		}
	}

	public void renderDebugMeshes(CameraRenderState cameraState, Matrix4fc modelViewMatrixState) {
		if (!this.initialized || this.meshMetadataBuffer == null || this.meshVertexBuffer == null || this.chunkProgram == null) {
			return;
		}
		if (cameraState == null || modelViewMatrixState == null) {
			return;
		}

		IntBuffer metadata = this.meshMetadataBuffer.readEntries();
		if (metadata.remaining() < this.meshMetadataBuffer.capacityEntries() * MeshMetadataBuffer.INTS_PER_ENTRY) {
			return;
		}

		this.lastRenderedMeshChunks = this.countRenderedDebugMeshChunks(metadata);
		this.lastRenderedMeshVertices = 0;

		Matrix4f modelViewMatrix = new Matrix4f(modelViewMatrixState);
		Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);

		GL20C.glUseProgram(this.chunkProgram.handle());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer modelViewBuffer = stack.mallocFloat(16);
			FloatBuffer projectionBuffer = stack.mallocFloat(16);
			modelViewMatrix.get(modelViewBuffer);
			projectionMatrix.get(projectionBuffer);
			GL20C.glUniformMatrix4fv(this.chunkModelViewUniform, false, modelViewBuffer);
			GL20C.glUniformMatrix4fv(this.chunkProjectionUniform, false, projectionBuffer);
		}

		GL30C.glBindVertexArray(this.debugMeshVertexArray);
		this.meshVertexBuffer.bindArrayBuffer();
		GL30C.glEnableVertexAttribArray(0);
		GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_INT, MeshVertexBuffer.BYTES_PER_VERTEX, 0L);

		GL11C.glDisable(GL11C.GL_BLEND);
		GL11C.glEnable(GL11C.GL_CULL_FACE);
		this.drawDebugMeshPass(
			metadata,
			MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET,
			MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET
		);

		GL11C.glEnable(GL11C.GL_BLEND);
		GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
		GL11C.glDepthMask(false);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		this.drawDebugMeshPass(
			metadata,
			MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET,
			MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET
		);

		GL11C.glDepthMask(true);
		GL11C.glEnable(GL11C.GL_CULL_FACE);
		GL11C.glDisable(GL11C.GL_BLEND);
		GL30C.glDisableVertexAttribArray(0);
		GL30C.glBindVertexArray(0);
		GL20C.glUseProgram(0);
	}

	@Override
	public void close() {
		closeQuietly(this.occlusionCullingShader);
		closeQuietly(this.frustumCullingShader);
		closeQuietly(this.meshGenerationShader);
		closeQuietly(this.meshGenerationStatsBuffer);
		closeQuietly(this.meshGenerationJobBuffer);
		closeQuietly(this.meshVertexBuffer);
		closeQuietly(this.meshMetadataBuffer);
		closeQuietly(this.chunkProgram);
		closeQuietly(this.vertexBuffer);
		closeQuietly(this.indirectCommandBuffer);
		closeQuietly(this.worldDataBuffer);

		this.occlusionCullingShader = null;
		this.frustumCullingShader = null;
		this.meshGenerationShader = null;
		this.meshGenerationStatsBuffer = null;
		this.meshGenerationJobBuffer = null;
		this.meshVertexBuffer = null;
		this.meshMetadataBuffer = null;
		this.chunkProgram = null;
		this.vertexBuffer = null;
		this.indirectCommandBuffer = null;
		this.worldDataBuffer = null;
		this.initialized = false;
		if (this.debugMeshVertexArray != 0) {
			GL30C.glDeleteVertexArrays(this.debugMeshVertexArray);
			this.debugMeshVertexArray = 0;
		}
		this.activeLevel = null;
		this.memoryManager.reset();
		this.lastUploadedWorldBytes = 0L;
		this.lastSyncedChangeCount = 0;
		this.lastMeshGenerationJobs = 0;
		this.lastMeshGenerationProcessedJobs = 0;
		this.lastMeshGenerationDirtyCandidates = 0;
		this.lastMeshGenerationGeneratedVertices = 0;
		this.lastMeshGenerationClippedJobs = 0;
		this.lastMeshGenerationSampledPackedBlock = 0;
		this.lastRenderedMeshChunks = 0;
		this.lastRenderedMeshVertices = 0;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		this.worldDataEvictions = 0;
		this.chunkModelViewUniform = -1;
		this.chunkProjectionUniform = -1;
		this.meshFacesPerChunkUniform = -1;
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
			this.ensureMeshOutputCapacity(false);
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

	private int tryRecycleResidentSlot(ChunkPos incomingChunkPos, long tickIndex) {
		ChunkData evictionCandidate = this.chunkManager.findEvictionCandidate();
		if (evictionCandidate == null) {
			return -1;
		}

		ChunkPos evictedChunkPos = evictionCandidate.chunkPos();
		int previousSlot = evictionCandidate.residentSlot();
		this.unloadChunk(evictionCandidate);
		int recycledSlot = this.memoryManager.tryAcquireSlot();
		if (recycledSlot < 0) {
			return -1;
		}

		this.worldDataEvictions++;
		if (this.worldDataEvictions == 1 || (this.worldDataEvictions % WORLD_DATA_EVICTION_LOG_INTERVAL) == 0) {
			PotassiumLogger.logger().info(
				"Evicted resident chunk {} from slot {} to make room for {}. Evictions={}, resident chunks={}/{}.",
				evictedChunkPos,
				previousSlot,
				incomingChunkPos,
				this.worldDataEvictions,
				this.memoryManager.residentChunks(),
				this.memoryManager.capacityChunks()
			);
		}

		evictionCandidate.touch(tickIndex);
		return recycledSlot;
	}

	private void dispatchDirtyMeshGeneration() {
		if (this.meshGenerationShader == null || this.meshGenerationJobBuffer == null || this.meshGenerationStatsBuffer == null) {
			return;
		}

		this.lastMeshGenerationJobs = 0;
		this.lastMeshGenerationProcessedJobs = 0;
		this.lastMeshGenerationDirtyCandidates = 0;
		this.lastMeshGenerationGeneratedVertices = 0;
		this.lastMeshGenerationClippedJobs = 0;
		this.lastMeshGenerationSampledPackedBlock = 0;

		int shaderVisibleChunkCapacity = this.worldDataBuffer.shaderVisibleChunkCapacity(WORLD_DATA_BINDING);
		if (shaderVisibleChunkCapacity <= 0) {
			return;
		}

		ArrayList<ChunkData> scheduledChunks = new ArrayList<>(MESH_GENERATION_JOB_LIMIT_PER_FRAME);
		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident() || !chunkData.isMeshDirty()) {
				continue;
			}

			this.lastMeshGenerationDirtyCandidates++;
			if (chunkData.residentSlot() >= shaderVisibleChunkCapacity || scheduledChunks.size() >= MESH_GENERATION_JOB_LIMIT_PER_FRAME) {
				continue;
			}

			scheduledChunks.add(chunkData);
			this.meshGenerationJobBuffer.addJob(
				chunkData.residentSlot(),
				chunkData.chunkPos(),
				chunkData.version(),
				this.resolveNeighborResidentSlot(chunkData.chunkPos().x() - 1, chunkData.chunkPos().z(), shaderVisibleChunkCapacity),
				this.resolveNeighborResidentSlot(chunkData.chunkPos().x() + 1, chunkData.chunkPos().z(), shaderVisibleChunkCapacity),
				this.resolveNeighborResidentSlot(chunkData.chunkPos().x(), chunkData.chunkPos().z() - 1, shaderVisibleChunkCapacity),
				this.resolveNeighborResidentSlot(chunkData.chunkPos().x(), chunkData.chunkPos().z() + 1, shaderVisibleChunkCapacity)
			);
		}

		if (scheduledChunks.isEmpty()) {
			return;
		}

		this.meshGenerationJobBuffer.upload();
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.meshGenerationJobBuffer.bind(MESH_JOB_BINDING);
		this.meshGenerationStatsBuffer.resetAndBind(MESH_STATS_BINDING);
		this.meshMetadataBuffer.bind(MESH_METADATA_BINDING);
		this.meshVertexBuffer.bindStorage(MESH_VERTEX_BINDING);
		this.meshGenerationShader.use();
		if (this.meshFacesPerChunkUniform >= 0) {
			GL30C.glUniform1ui(this.meshFacesPerChunkUniform, this.meshVertexBuffer.facesPerChunk());
		}

		int groupCountX = (scheduledChunks.size() + (MESH_GENERATION_LOCAL_SIZE_X - 1)) / MESH_GENERATION_LOCAL_SIZE_X;
		GL43C.glDispatchCompute(groupCountX, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);

		MeshGenerationStatsBuffer.Stats stats = this.meshGenerationStatsBuffer.read();
		this.lastMeshGenerationJobs = scheduledChunks.size();
		this.lastMeshGenerationProcessedJobs = stats.processedJobs();
		this.lastMeshGenerationGeneratedVertices = stats.generatedVertices();
		this.lastMeshGenerationClippedJobs = stats.clippedJobs();
		this.lastMeshGenerationSampledPackedBlock = stats.lastSampledPackedBlock();
		for (ChunkData chunkData : scheduledChunks) {
			chunkData.markMeshClean();
		}
	}

	private boolean hasMeshGenerationBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= MESH_GENERATION_REQUIRED_SSBO_BINDINGS;
	}

	private int resolveNeighborResidentSlot(int chunkX, int chunkZ, int shaderVisibleChunkCapacity) {
		ChunkData neighborChunk = this.chunkManager.getChunk(new ChunkPos(chunkX, chunkZ));
		if (neighborChunk == null || !neighborChunk.isResident()) {
			return INVALID_RESIDENT_SLOT;
		}

		int residentSlot = neighborChunk.residentSlot();
		return residentSlot < shaderVisibleChunkCapacity ? residentSlot : INVALID_RESIDENT_SLOT;
	}

	private void ensureMeshOutputCapacity(boolean clearMetadata) {
		if (this.meshMetadataBuffer == null || this.meshVertexBuffer == null) {
			return;
		}

		int requiredChunkCapacity = Math.max(this.memoryManager.capacityChunks(), 1);
		this.meshMetadataBuffer.ensureCapacity(requiredChunkCapacity);
		this.meshVertexBuffer.ensureChunkCapacity(requiredChunkCapacity);
		if (clearMetadata) {
			this.meshMetadataBuffer.clearAll();
		}
	}

	private void clearMeshSlot(int residentSlot) {
		if (this.meshMetadataBuffer == null || residentSlot < 0) {
			return;
		}

		this.meshMetadataBuffer.clearSlot(residentSlot);
	}

	private void markAdjacentResidentChunksMeshDirty(ChunkPos centerChunkPos, long tickIndex) {
		this.markResidentChunkMeshDirty(centerChunkPos.x() - 1, centerChunkPos.z(), tickIndex);
		this.markResidentChunkMeshDirty(centerChunkPos.x() + 1, centerChunkPos.z(), tickIndex);
		this.markResidentChunkMeshDirty(centerChunkPos.x(), centerChunkPos.z() - 1, tickIndex);
		this.markResidentChunkMeshDirty(centerChunkPos.x(), centerChunkPos.z() + 1, tickIndex);
	}

	private void markResidentChunkMeshDirty(int chunkX, int chunkZ, long tickIndex) {
		ChunkData chunkData = this.chunkManager.getChunk(new ChunkPos(chunkX, chunkZ));
		if (chunkData == null || !chunkData.isResident()) {
			return;
		}

		chunkData.touch(tickIndex);
		chunkData.markMeshDirty();
	}

	private void drawDebugMeshPass(IntBuffer metadata, int vertexCountOffset, int firstVertexOffset) {
		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}

			int metadataOffset = chunkData.residentSlot() * MeshMetadataBuffer.INTS_PER_ENTRY;
			int vertexCount = metadata.get(metadataOffset + vertexCountOffset);
			if (vertexCount <= 0) {
				continue;
			}

			int firstVertex = metadata.get(metadataOffset + firstVertexOffset);
			GL11C.glDrawArrays(GL11C.GL_TRIANGLES, firstVertex, vertexCount);
			this.lastRenderedMeshVertices += vertexCount;
		}
	}

	private int countRenderedDebugMeshChunks(IntBuffer metadata) {
		int renderedChunks = 0;
		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}

			int metadataOffset = chunkData.residentSlot() * MeshMetadataBuffer.INTS_PER_ENTRY;
			int opaqueVertexCount = metadata.get(metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET);
			int translucentVertexCount = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET);
			if (opaqueVertexCount > 0 || translucentVertexCount > 0) {
				renderedChunks++;
			}
		}

		return renderedChunks;
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
