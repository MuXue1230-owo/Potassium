package com.potassium.render;

import com.potassium.core.PotassiumConfig;
import com.potassium.core.PotassiumLogger;
import com.potassium.gl.DepthPyramid;
import com.potassium.gl.buffer.ChangeQueueBuffer;
import com.potassium.gl.buffer.DrawCommandCountBuffer;
import com.potassium.gl.GLCapabilities;
import com.potassium.gl.OitFramebuffer;
import com.potassium.gl.buffer.IndirectCommandBuffer;
import com.potassium.gl.buffer.MeshGenerationStatsBuffer;
import com.potassium.gl.buffer.MeshMetadataBuffer;
import com.potassium.gl.buffer.MeshVertexBuffer;
import com.potassium.gl.buffer.ResidentChunkStateBuffer;
import com.potassium.gl.buffer.VertexBuffer;
import com.potassium.gl.buffer.WorldDataBuffer;
import com.potassium.render.culling.FrustumCuller;
import com.potassium.render.culling.OcclusionCuller;
import com.potassium.render.shader.ComputeShader;
import com.potassium.render.shader.ShaderProgram;
import com.potassium.world.ChunkManager;
import com.potassium.world.MemoryManager;
import com.potassium.world.WorldChangeTracker;
import com.potassium.world.data.BlockData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.ChunkPos;
import com.potassium.world.data.ChunkData;
import com.potassium.world.data.ChunkSnapshot;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class RenderPipeline implements AutoCloseable {
	private static final boolean ENABLE_GPU_TERRAIN_SUBMISSION = false;
	private static final boolean ENABLE_SCREEN_PROBE = false;
	private static final boolean ENABLE_VERTEX_CONSUMER_PROBE = false;
	private static final boolean ENABLE_VERTEX_CONSUMER_TERRAIN_BRIDGE = true;
	private static final boolean ENABLE_VERTEX_CONSUMER_TRANSLUCENT_BRIDGE = true;
	private static final int VERTEX_CONSUMER_TERRAIN_BRIDGE_MAX_CHUNKS = 96;
	private static final long MEBIBYTE_BYTES = 1024L * 1024L;
	private static final int INVALID_RESIDENT_SLOT = -1;
	private static final int MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET = 0;
	private static final int MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET = 1;
	private static final int MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET = 2;
	private static final int MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET = 3;
	private static final int MESH_METADATA_MESH_REVISION_OFFSET = 6;
	private static final int WORLD_DATA_BINDING = 0;
	private static final int RESIDENT_CHUNK_STATE_BINDING = WORLD_DATA_BINDING + 1 + WorldDataBuffer.MAX_SHADER_PAGES;
	private static final int MESH_STATS_BINDING = RESIDENT_CHUNK_STATE_BINDING + 1;
	private static final int MESH_METADATA_BINDING = MESH_STATS_BINDING + 1;
	private static final int MESH_VERTEX_BINDING = MESH_METADATA_BINDING + 1;
	private static final int OPAQUE_COMMAND_BINDING = MESH_VERTEX_BINDING + 1;
	private static final int TRANSLUCENT_COMMAND_BINDING = OPAQUE_COMMAND_BINDING + 1;
	private static final int DRAW_COMMAND_COUNT_BINDING = TRANSLUCENT_COMMAND_BINDING + 1;
	private static final int CHANGE_QUEUE_BINDING = DRAW_COMMAND_COUNT_BINDING + 1;
	private static final int WORLD_DATA_GROWTH_MIN_MIB = 128;
	private static final int WORLD_DATA_GROWTH_RESERVE_MIB = 512;
	private static final int WORLD_DATA_FULL_WARN_INTERVAL = 64;
	private static final int WORLD_DATA_EVICTION_LOG_INTERVAL = 64;
	private static final int BOUNDARY_MASK_NEG_X = 1 << 0;
	private static final int BOUNDARY_MASK_POS_X = 1 << 1;
	private static final int BOUNDARY_MASK_NEG_Z = 1 << 2;
	private static final int BOUNDARY_MASK_POS_Z = 1 << 3;
	private static final int DEPTH_COPY_LOCAL_SIZE_X = 8;
	private static final int DEPTH_COPY_LOCAL_SIZE_Y = 8;
	private static final int MESH_GENERATION_LOCAL_SIZE_X = 64;
	private static final int MESH_GENERATION_REQUIRED_SSBO_BINDINGS = MESH_VERTEX_BINDING + 1;
	private static final int FRUSTUM_CULLING_REQUIRED_SSBO_BINDINGS = DRAW_COMMAND_COUNT_BINDING + 1;
	private static final int APPLY_CHANGES_REQUIRED_SSBO_BINDINGS = CHANGE_QUEUE_BINDING + 1;

	private final PotassiumConfig config;
	private final ChunkManager chunkManager;
	private final WorldChangeTracker worldChangeTracker;
	private final FrustumCuller frustumCuller;
	private final OcclusionCuller occlusionCuller;
	private final MemoryManager memoryManager = new MemoryManager();
	private final HashMap<Integer, BridgeMeshCacheEntry> bridgeMeshCache = new HashMap<>();
	private final HashMap<Integer, BridgeMeshCacheEntry> bridgeTranslucentMeshCache = new HashMap<>();

	private WorldDataBuffer worldDataBuffer;
	private IndirectCommandBuffer indirectCommandBuffer;
	private IndirectCommandBuffer translucentIndirectCommandBuffer;
	private ChangeQueueBuffer changeQueueBuffer;
	private DrawCommandCountBuffer drawCommandCountBuffer;
	private ResidentChunkStateBuffer residentChunkStateBuffer;
	private MeshGenerationStatsBuffer meshGenerationStatsBuffer;
	private MeshMetadataBuffer meshMetadataBuffer;
	private MeshVertexBuffer meshVertexBuffer;
	private VertexBuffer vertexBuffer;
	private ShaderProgram chunkProgram;
	private ShaderProgram chunkTranslucentProgram;
	private ShaderProgram oitCompositeProgram;
	private ShaderProgram terrainProbeProgram;
	private ComputeShader applyChangesShader;
	private ComputeShader resetFrameStateShader;
	private ComputeShader depthCopyShader;
	private ComputeShader depthDownsampleShader;
	private ComputeShader meshGenerationShader;
	private ComputeShader frustumCullingShader;
	private ComputeShader occlusionCullingShader;
	private DepthPyramid depthPyramid;
	private OitFramebuffer translucentOitFramebuffer;
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
	private int lastVisibleOpaqueMeshChunks;
	private int lastVisibleTranslucentMeshChunks;
	private int pendingGpuChangeCount;
	private boolean terrainMeshReady;
	private boolean forceCpuTerrainDrawThisFrame;
	private boolean loggedCustomGeometryTerrainDraw;
	private boolean loggedVertexConsumerProbe;
	private boolean loggedVertexConsumerTerrainBridge;
	private boolean loggedVertexConsumerTranslucentTerrainBridge;
	private int debugMeshVertexArray;
	private int oitCompositeVertexArray;
	private int chunkModelViewUniform;
	private int chunkProjectionUniform;
	private int chunkTranslucentModelViewUniform;
	private int chunkTranslucentProjectionUniform;
	private int meshFacesPerChunkUniform;
	private int meshResidentSlotCapacityUniform;
	private int frustumViewProjectionUniform;
	private int frustumResidentSlotCapacityUniform;
	private int occlusionViewProjectionUniform;
	private int occlusionViewportSizeUniform;
	private int occlusionDepthPyramidLevelsUniform;
	private int occlusionResidentSlotCapacityUniform;
	private int depthDownsampleSourceMipUniform;
	private final ArrayList<ChangeQueueBuffer.Entry> pendingGpuWorldChanges = new ArrayList<>();

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
		this.indirectCommandBuffer = new IndirectCommandBuffer(this.config.memory.indirectCommandCapacity, false);
		this.translucentIndirectCommandBuffer = new IndirectCommandBuffer(this.config.memory.indirectCommandCapacity, false);
		this.vertexBuffer = new VertexBuffer();
		this.chunkProgram = ShaderProgram.graphics(
			"chunk",
			"shaders/render/chunk.vert",
			"shaders/render/chunk.frag"
		);
		this.chunkTranslucentProgram = ShaderProgram.graphics(
			"chunk_translucent_oit",
			"shaders/render/chunk.vert",
			"shaders/render/chunk_translucent_oit.frag"
		);
		this.oitCompositeProgram = ShaderProgram.graphics(
			"oit_composite",
			"shaders/render/oit_composite.vert",
			"shaders/render/oit_composite.frag"
		);
		this.terrainProbeProgram = ShaderProgram.graphics(
			"terrain_probe",
			"shaders/render/terrain_probe.vert",
			"shaders/render/terrain_probe.frag"
		);
		this.depthPyramid = new DepthPyramid();
		this.translucentOitFramebuffer = new OitFramebuffer();
		this.debugMeshVertexArray = GL45C.glCreateVertexArrays();
		this.oitCompositeVertexArray = GL45C.glCreateVertexArrays();
		this.chunkModelViewUniform = GL20C.glGetUniformLocation(this.chunkProgram.handle(), "uModelViewMatrix");
		this.chunkProjectionUniform = GL20C.glGetUniformLocation(this.chunkProgram.handle(), "uProjectionMatrix");
		this.chunkTranslucentModelViewUniform = GL20C.glGetUniformLocation(this.chunkTranslucentProgram.handle(), "uModelViewMatrix");
		this.chunkTranslucentProjectionUniform = GL20C.glGetUniformLocation(this.chunkTranslucentProgram.handle(), "uProjectionMatrix");

		if (GLCapabilities.hasComputeShader()) {
			if (this.hasGpuFrameStateResetBindingBudget()) {
				this.resetFrameStateShader = ComputeShader.load("reset_frame_state", "shaders/common/reset_frame_state.comp");
			}
			if (this.hasGpuChangeApplyBindingBudget()) {
				this.applyChangesShader = ComputeShader.load("apply_world_changes", "shaders/world/apply_changes.comp");
				this.changeQueueBuffer = new ChangeQueueBuffer(this.config.memory.changeQueueCapacity, usePersistentMapping);
			} else {
				PotassiumLogger.logger().warn(
					"Skipping GPU world-change application because GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS={} is below the required {}.",
					GLCapabilities.getMaxShaderStorageBufferBindings(),
					APPLY_CHANGES_REQUIRED_SSBO_BINDINGS
				);
			}
			if (this.hasMeshGenerationBindingBudget()) {
				this.meshGenerationShader = ComputeShader.load("mesh_generation", "shaders/mesh/generation.comp");
				this.residentChunkStateBuffer = new ResidentChunkStateBuffer(1, usePersistentMapping);
				this.meshGenerationStatsBuffer = new MeshGenerationStatsBuffer();
				this.meshMetadataBuffer = new MeshMetadataBuffer(1);
				this.meshVertexBuffer = new MeshVertexBuffer(1, this.config.memory.meshFacesPerChunk);
				this.meshFacesPerChunkUniform = GL20C.glGetUniformLocation(this.meshGenerationShader.handle(), "uMeshFacesPerChunk");
				this.meshResidentSlotCapacityUniform = GL20C.glGetUniformLocation(this.meshGenerationShader.handle(), "uResidentSlotCapacity");
			} else {
				PotassiumLogger.logger().warn(
					"Skipping mesh-generation compute initialization because GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS={} is below the required {}.",
					GLCapabilities.getMaxShaderStorageBufferBindings(),
					MESH_GENERATION_REQUIRED_SSBO_BINDINGS
				);
			}
			if (this.hasGpuDrivenDrawBindingBudget()) {
				this.frustumCullingShader = ComputeShader.load("frustum_culling", "shaders/culling/frustum.comp");
				this.drawCommandCountBuffer = new DrawCommandCountBuffer(usePersistentMapping);
				this.frustumViewProjectionUniform = GL20C.glGetUniformLocation(this.frustumCullingShader.handle(), "uViewProjectionMatrix");
				this.frustumResidentSlotCapacityUniform = GL20C.glGetUniformLocation(this.frustumCullingShader.handle(), "uResidentSlotCapacity");
			} else {
				PotassiumLogger.logger().warn(
					"Skipping GPU-driven frustum submission because GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS={} is below the required {}.",
					GLCapabilities.getMaxShaderStorageBufferBindings(),
					FRUSTUM_CULLING_REQUIRED_SSBO_BINDINGS
				);
			}
			this.depthCopyShader = ComputeShader.load("depth_copy", "shaders/culling/depth_copy.comp");
			this.depthDownsampleShader = ComputeShader.load("depth_downsample", "shaders/culling/depth_downsample.comp");
			this.occlusionCullingShader = ComputeShader.load("occlusion_culling", "shaders/culling/occlusion.comp");
			this.occlusionViewProjectionUniform = GL20C.glGetUniformLocation(this.occlusionCullingShader.handle(), "uViewProjectionMatrix");
			this.occlusionViewportSizeUniform = GL20C.glGetUniformLocation(this.occlusionCullingShader.handle(), "uViewportSize");
			this.occlusionDepthPyramidLevelsUniform = GL20C.glGetUniformLocation(this.occlusionCullingShader.handle(), "uDepthPyramidLevels");
			this.occlusionResidentSlotCapacityUniform = GL20C.glGetUniformLocation(this.occlusionCullingShader.handle(), "uResidentSlotCapacity");
			this.depthDownsampleSourceMipUniform = GL20C.glGetUniformLocation(this.depthDownsampleShader.handle(), "uSourceMipLevel");
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
		if (this.changeQueueBuffer != null) {
			this.changeQueueBuffer.beginFrame();
		}
		if (this.drawCommandCountBuffer != null) {
			this.drawCommandCountBuffer.beginFrame();
		}
		this.forceCpuTerrainDrawThisFrame = false;
		if (this.residentChunkStateBuffer != null) {
			this.residentChunkStateBuffer.beginFrame();
			this.resetGpuFrameStateBuffers();
			this.dispatchPendingGpuWorldChanges();
			this.dispatchDirtyMeshGeneration();
			this.refreshTerrainMeshReadiness();
		}
	}

	public void endFrame() {
		if (!this.initialized) {
			return;
		}

		this.worldDataBuffer.endFrame();
		this.indirectCommandBuffer.upload();
		this.indirectCommandBuffer.endFrame();
		if (this.changeQueueBuffer != null) {
			this.changeQueueBuffer.endFrame();
		}
		if (this.drawCommandCountBuffer != null) {
			this.drawCommandCountBuffer.endFrame();
		}
		if (this.residentChunkStateBuffer != null) {
			this.residentChunkStateBuffer.endFrame();
		}
	}

	public void flushPendingChanges(List<WorldChangeTracker.BlockChange> changes) {
		if (!this.initialized) {
			return;
		}

		if (this.hasGpuChangeApplyPath()) {
			this.flushPendingChangesGpu(changes);
			return;
		}

		this.flushPendingChangesCpu(changes);
	}

	public boolean handlesWorldChangesOnGpu() {
		return this.hasGpuChangeApplyPath();
	}

	private void flushPendingChangesCpu(List<WorldChangeTracker.BlockChange> changes) {
		long uploadedBytes = 0L;
		int appliedChanges = 0;

		for (WorldChangeTracker.BlockChange change : changes) {
			ChunkPos chunkPos = ChunkPos.containing(change.position());
			ChunkData chunkData = this.chunkManager.getChunk(chunkPos);
			if (chunkData == null || !chunkData.isResident()) {
				continue;
			}

			int localBlockIndex = this.worldDataBuffer.localBlockIndex(change.position());
			if (localBlockIndex < 0) {
				continue;
			}

			int packedBlock = BlockData.fromState(change.newState()).packed();
			if (this.worldDataBuffer.applyPackedBlockChange(chunkData.residentSlot(), localBlockIndex, packedBlock)) {
				uploadedBytes += this.worldDataBuffer.lastUploadBytes();
				appliedChanges++;
				this.markChunkMeshDirty(chunkData, change.tick());
			}
		}

		this.lastUploadedWorldBytes = uploadedBytes;
		this.lastSyncedChangeCount = appliedChanges;
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
	}

	private void flushPendingChangesGpu(List<WorldChangeTracker.BlockChange> changes) {
		long uploadedBytes = 0L;
		int queuedChanges = 0;

		for (WorldChangeTracker.BlockChange change : changes) {
			ChunkPos chunkPos = ChunkPos.containing(change.position());
			ChunkData chunkData = this.chunkManager.getChunk(chunkPos);
			if (chunkData == null || !chunkData.isResident()) {
				continue;
			}

			int localBlockIndex = this.worldDataBuffer.localBlockIndex(change.position());
			if (localBlockIndex < 0) {
				continue;
			}

			this.pendingGpuWorldChanges.add(
				new ChangeQueueBuffer.Entry(
					chunkData.residentSlot(),
					localBlockIndex,
					BlockData.fromState(change.newState()).packed(),
					boundaryMask(change.position())
				)
			);
			queuedChanges++;
		}

		if (queuedChanges > 0 && this.changeQueueBuffer != null) {
			this.changeQueueBuffer.uploadChanges(this.pendingGpuWorldChanges);
			uploadedBytes = this.changeQueueBuffer.lastUploadBytes();
			this.pendingGpuChangeCount = this.pendingGpuWorldChanges.size();
		}

		this.lastUploadedWorldBytes = uploadedBytes;
		this.lastSyncedChangeCount = queuedChanges;
	}

	public long lastUploadedWorldBytes() {
		return this.lastUploadedWorldBytes;
	}

	public int lastSyncedChangeCount() {
		return this.lastSyncedChangeCount;
	}

	public String summaryLine() {
		return String.format(
			"chunks=%d resident=%d/%d wdbPages=%d lastUpload=%dB trackedChanges=%d meshJobs=%d/%d meshProcessed=%s meshVertices=%s meshClipped=%s rendered=%s/%s opaqueVisible=%s translucentVisible=%s sampleBlock=%s frustum=%s occlusion=%s",
			this.chunkManager.size(),
			this.memoryManager.residentChunks(),
			this.memoryManager.capacityChunks(),
			this.worldDataBuffer.pageCount(),
			this.lastUploadedWorldBytes,
			this.worldChangeTracker.pendingChangeCount(),
			this.lastMeshGenerationJobs,
			this.lastMeshGenerationDirtyCandidates,
			describeStat(this.lastMeshGenerationProcessedJobs),
			describeStat(this.lastMeshGenerationGeneratedVertices),
			describeStat(this.lastMeshGenerationClippedJobs),
			describeStat(this.lastRenderedMeshChunks),
			describeStat(this.lastRenderedMeshVertices),
			describeStat(this.lastVisibleOpaqueMeshChunks),
			describeStat(this.lastVisibleTranslucentMeshChunks),
			describeStat(this.lastMeshGenerationSampledPackedBlock),
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
		this.lastVisibleOpaqueMeshChunks = 0;
		this.lastVisibleTranslucentMeshChunks = 0;
		this.pendingGpuChangeCount = 0;
		this.terrainMeshReady = false;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		this.worldDataEvictions = 0;
		this.pendingGpuWorldChanges.clear();
		this.bridgeMeshCache.clear();
		this.bridgeTranslucentMeshCache.clear();
		this.loggedVertexConsumerTerrainBridge = false;
		this.loggedVertexConsumerTranslucentTerrainBridge = false;

		if (level == null) {
			return;
		}

		int minSectionY = level.getMinSectionY();
		int sectionsCount = level.getSectionsCount();
		this.worldDataBuffer.configure(minSectionY, sectionsCount);
		this.memoryManager.configure(this.worldDataBuffer.capacityBytes(), this.worldDataBuffer.bytesPerChunk());
		this.ensureMeshOutputCapacity(true);
		this.ensureIndirectCommandCapacity();

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
		this.syncResidentChunkNeighborhood(snapshot.chunkPos(), true);
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
		this.purgePendingGpuChangesForSlot(residentSlot);
		this.clearMeshSlot(residentSlot);
		if (this.residentChunkStateBuffer != null) {
			this.residentChunkStateBuffer.clearSlot(residentSlot);
		}
		this.memoryManager.releaseSlot(residentSlot);
		chunkData.clearResident();
		this.syncResidentChunkNeighborhood(chunkPos, true);
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
		CameraFrameState cameraFrameState = this.captureCameraFrameState(cameraState);
		if (cameraFrameState == null) {
			return;
		}

		this.renderOpaqueTerrainNow(cameraFrameState);
		this.renderTranslucentTerrainNow(cameraFrameState);
	}

	public boolean submitOpaqueTerrain(CameraRenderState cameraState, SubmitNodeStorage submitNodeStorage) {
		CameraFrameState cameraFrameState = this.captureCameraFrameState(cameraState);
		if (submitNodeStorage == null || cameraFrameState == null) {
			return false;
		}

		if (ENABLE_VERTEX_CONSUMER_TERRAIN_BRIDGE) {
			return this.submitVertexConsumerTerrainBridge(submitNodeStorage, cameraFrameState);
		}

		if (ENABLE_VERTEX_CONSUMER_PROBE) {
			this.submitVertexConsumerProbe(submitNodeStorage, cameraFrameState);
			return false;
		}

		if (!this.canRenderTerrainPass()) {
			return false;
		}

		PoseStack poseStack = new PoseStack();
		submitNodeStorage.submitCustomGeometry(
			poseStack,
			RenderTypes.debugQuads(),
			(pose, vertexConsumer) -> this.renderOpaqueTerrainNow(cameraFrameState)
		);
		return true;
	}

	public boolean submitTranslucentTerrain(CameraRenderState cameraState, SubmitNodeStorage submitNodeStorage) {
		CameraFrameState cameraFrameState = this.captureCameraFrameState(cameraState);
		if (submitNodeStorage == null || cameraFrameState == null) {
			return false;
		}

		if (ENABLE_VERTEX_CONSUMER_TERRAIN_BRIDGE && ENABLE_VERTEX_CONSUMER_TRANSLUCENT_BRIDGE) {
			return this.submitVertexConsumerTranslucentTerrainBridge(submitNodeStorage, cameraFrameState);
		}

		if (ENABLE_VERTEX_CONSUMER_PROBE) {
			return false;
		}

		if (!this.canRenderTerrainPass()) {
			return false;
		}

		PoseStack poseStack = new PoseStack();
		submitNodeStorage.submitCustomGeometry(
			poseStack,
			RenderTypes.linesTranslucent(),
			(pose, vertexConsumer) -> this.renderTranslucentTerrainNow(cameraFrameState)
		);
		return true;
	}

	private boolean submitVertexConsumerTerrainBridge(SubmitNodeStorage submitNodeStorage, CameraFrameState cameraFrameState) {
		if (!this.canRenderTerrainPass() || this.meshVertexBuffer == null) {
			return false;
		}

		ArrayList<BridgeChunkDraw> visibleDraws = this.collectVisibleBridgeDraws(
			cameraFrameState,
			this.bridgeMeshCache,
			MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET,
			MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET,
			false
		);
		if (visibleDraws.isEmpty()) {
			return false;
		}

		if (!this.loggedVertexConsumerTerrainBridge) {
			PotassiumLogger.logger().info("Potassium terrain bridge is replacing vanilla opaque terrain with cached opaque mesh faces.");
			this.loggedVertexConsumerTerrainBridge = true;
		}

		PoseStack poseStack = new PoseStack();
		submitNodeStorage.submitCustomGeometry(
			poseStack,
			RenderTypes.debugQuads(),
			(pose, vertexConsumer) -> this.emitVertexConsumerTerrainBridge(pose, vertexConsumer, cameraFrameState, visibleDraws, false)
		);
		return true;
	}

	private boolean submitVertexConsumerTranslucentTerrainBridge(SubmitNodeStorage submitNodeStorage, CameraFrameState cameraFrameState) {
		if (!this.canRenderTerrainPass() || this.meshVertexBuffer == null) {
			return false;
		}

		ArrayList<BridgeChunkDraw> visibleDraws = this.collectVisibleBridgeDraws(
			cameraFrameState,
			this.bridgeTranslucentMeshCache,
			MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET,
			MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET,
			true
		);
		if (visibleDraws.isEmpty()) {
			return false;
		}

		if (!this.loggedVertexConsumerTranslucentTerrainBridge) {
			PotassiumLogger.logger().info("Potassium terrain bridge is replacing vanilla translucent terrain with cached translucent mesh faces.");
			this.loggedVertexConsumerTranslucentTerrainBridge = true;
		}

		PoseStack poseStack = new PoseStack();
		submitNodeStorage.submitCustomGeometry(
			poseStack,
			RenderTypes.debugQuads(),
			(pose, vertexConsumer) -> this.emitVertexConsumerTerrainBridge(pose, vertexConsumer, cameraFrameState, visibleDraws, true)
		);
		return true;
	}

	private void submitVertexConsumerProbe(SubmitNodeStorage submitNodeStorage, CameraFrameState cameraFrameState) {
		if (!this.loggedVertexConsumerProbe) {
			PotassiumLogger.logger().info("Potassium vertex-consumer terrain probe submitted through SubmitNodeStorage.");
			this.loggedVertexConsumerProbe = true;
		}

		PoseStack poseStack = new PoseStack();
		submitNodeStorage.submitCustomGeometry(
			poseStack,
			RenderTypes.lines(),
			(pose, vertexConsumer) -> this.emitVertexConsumerProbe(pose, vertexConsumer, cameraFrameState)
		);
	}

	private void emitVertexConsumerTerrainBridge(
		PoseStack.Pose pose,
		VertexConsumer vertexConsumer,
		CameraFrameState cameraFrameState,
		List<BridgeChunkDraw> visibleDraws,
		boolean translucent
	) {
		int submittedChunks = 0;
		int submittedVertices = 0;
		for (BridgeChunkDraw draw : visibleDraws) {
			if (submittedChunks >= VERTEX_CONSUMER_TERRAIN_BRIDGE_MAX_CHUNKS) {
				break;
			}

			this.emitChunkFaces(vertexConsumer, pose, draw.cacheEntry().packedVertices(), cameraFrameState, translucent);
			submittedChunks++;
			submittedVertices += draw.cacheEntry().vertexCount();
		}

		if (translucent) {
			this.lastVisibleTranslucentMeshChunks = submittedChunks;
		} else {
			this.lastVisibleOpaqueMeshChunks = submittedChunks;
		}
		this.lastRenderedMeshChunks += submittedChunks;
		this.lastRenderedMeshVertices += submittedVertices;
	}

	private ArrayList<BridgeChunkDraw> collectVisibleBridgeDraws(
		CameraFrameState cameraFrameState,
		HashMap<Integer, BridgeMeshCacheEntry> cache,
		int vertexCountOffset,
		int firstVertexOffset,
		boolean backToFront
	) {
		if (this.meshMetadataBuffer == null || this.residentChunkStateBuffer == null) {
			return new ArrayList<>();
		}

		this.frustumCuller.update(cameraFrameState.projectionMatrix(), cameraFrameState.modelViewMatrix());
		ArrayList<BridgeChunkDraw> visibleDraws = new ArrayList<>();
		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}
			if (!this.frustumCuller.isChunkVisible(chunkData.chunkPos(), this.worldDataBuffer.minSectionY(), this.worldDataBuffer.sectionsCount())) {
				continue;
			}

			BridgeMeshCacheEntry cacheEntry = this.resolveBridgeMeshCacheEntry(
				cache,
				chunkData.residentSlot(),
				vertexCountOffset,
				firstVertexOffset
			);
			if (cacheEntry == null || cacheEntry.vertexCount() <= 0) {
				continue;
			}

			double distanceSquared = squaredDistanceToChunkCenter(
				chunkData.chunkPos(),
				this.worldDataBuffer.minSectionY(),
				this.worldDataBuffer.sectionsCount(),
				cameraFrameState.cameraX(),
				cameraFrameState.cameraY(),
				cameraFrameState.cameraZ()
			);
			visibleDraws.add(new BridgeChunkDraw(cacheEntry, distanceSquared));
		}

		Comparator<BridgeChunkDraw> sortOrder = Comparator.comparingDouble(BridgeChunkDraw::distanceSquared);
		visibleDraws.sort(backToFront ? sortOrder.reversed() : sortOrder);
		return visibleDraws;
	}

	private void emitVertexConsumerProbe(PoseStack.Pose pose, VertexConsumer vertexConsumer, CameraFrameState cameraFrameState) {
		float centerX = cameraFrameState.forwardX() * 3.0f;
		float centerY = cameraFrameState.forwardY() * 3.0f;
		float centerZ = cameraFrameState.forwardZ() * 3.0f;
		float halfExtent = 0.6f;
		int color = 0xFFFF3030;

		float minX = centerX - halfExtent;
		float minY = centerY - halfExtent;
		float minZ = centerZ - halfExtent;
		float maxX = centerX + halfExtent;
		float maxY = centerY + halfExtent;
		float maxZ = centerZ + halfExtent;

		this.emitProbeLine(vertexConsumer, pose, minX, minY, minZ, maxX, minY, minZ, color);
		this.emitProbeLine(vertexConsumer, pose, maxX, minY, minZ, maxX, minY, maxZ, color);
		this.emitProbeLine(vertexConsumer, pose, maxX, minY, maxZ, minX, minY, maxZ, color);
		this.emitProbeLine(vertexConsumer, pose, minX, minY, maxZ, minX, minY, minZ, color);

		this.emitProbeLine(vertexConsumer, pose, minX, maxY, minZ, maxX, maxY, minZ, color);
		this.emitProbeLine(vertexConsumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color);
		this.emitProbeLine(vertexConsumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color);
		this.emitProbeLine(vertexConsumer, pose, minX, maxY, maxZ, minX, maxY, minZ, color);

		this.emitProbeLine(vertexConsumer, pose, minX, minY, minZ, minX, maxY, minZ, color);
		this.emitProbeLine(vertexConsumer, pose, maxX, minY, minZ, maxX, maxY, minZ, color);
		this.emitProbeLine(vertexConsumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color);
		this.emitProbeLine(vertexConsumer, pose, minX, minY, maxZ, minX, maxY, maxZ, color);
	}

	private void emitProbeLine(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		float startX,
		float startY,
		float startZ,
		float endX,
		float endY,
		float endZ,
		int color
	) {
		Vector3f normal = new Vector3f(endX - startX, endY - startY, endZ - startZ);
		if (normal.lengthSquared() <= 1.0e-6f) {
			normal.set(0.0f, 1.0f, 0.0f);
		} else {
			normal.normalize();
		}

		vertexConsumer.addVertex(pose, startX, startY, startZ)
			.setColor(color)
			.setNormal(pose, normal)
			.setLineWidth(4.0f);
		vertexConsumer.addVertex(pose, endX, endY, endZ)
			.setColor(color)
			.setNormal(pose, normal)
			.setLineWidth(4.0f);
	}

	private void emitChunkFaces(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		int[] chunkVertices,
		CameraFrameState cameraFrameState,
		boolean translucent
	) {
		int intsPerFace = MeshVertexBuffer.VERTICES_PER_FACE * MeshVertexBuffer.UINTS_PER_VERTEX;
		for (int faceBase = 0; faceBase + intsPerFace <= chunkVertices.length; faceBase += intsPerFace) {
			int packedBlock = chunkVertices[faceBase + 3];
			int color = debugColorArgb(packedBlock, translucent);

			VertexPoint a = readVertexPoint(chunkVertices, faceBase, cameraFrameState);
			VertexPoint b = readVertexPoint(chunkVertices, faceBase + 4, cameraFrameState);
			VertexPoint c = readVertexPoint(chunkVertices, faceBase + 8, cameraFrameState);
			VertexPoint d = readVertexPoint(chunkVertices, faceBase + 20, cameraFrameState);

			Vector3f ab = new Vector3f(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
			Vector3f ac = new Vector3f(c.x() - a.x(), c.y() - a.y(), c.z() - a.z());
			Vector3f normal = ab.cross(ac);
			if (normal.lengthSquared() <= 1.0e-6f) {
				normal.set(0.0f, 1.0f, 0.0f);
			} else {
				normal.normalize();
			}

			this.emitQuadVertex(vertexConsumer, pose, a, color, normal);
			this.emitQuadVertex(vertexConsumer, pose, b, color, normal);
			this.emitQuadVertex(vertexConsumer, pose, c, color, normal);
			this.emitQuadVertex(vertexConsumer, pose, d, color, normal);
		}
	}

	private BridgeMeshCacheEntry resolveBridgeMeshCacheEntry(
		HashMap<Integer, BridgeMeshCacheEntry> cache,
		int residentSlot,
		int vertexCountOffset,
		int firstVertexOffset
	) {
		int expectedMeshRevision = this.residentChunkStateBuffer.meshRevision(residentSlot);
		BridgeMeshCacheEntry cachedEntry = cache.get(residentSlot);
		if (cachedEntry != null && cachedEntry.meshRevision() == expectedMeshRevision) {
			return cachedEntry;
		}

		int[] metadataEntry = this.meshMetadataBuffer.readEntry(residentSlot);
		int vertexCount = metadataEntry[vertexCountOffset];
		int firstVertex = metadataEntry[firstVertexOffset];
		int metadataMeshRevision = metadataEntry[MESH_METADATA_MESH_REVISION_OFFSET];
		if (vertexCount <= 0 || firstVertex < 0) {
			cache.remove(residentSlot);
			return null;
		}
		if (cachedEntry != null && metadataMeshRevision < expectedMeshRevision) {
			return cachedEntry;
		}

		IntBuffer vertexBuffer = this.meshVertexBuffer.readVertices(firstVertex, vertexCount);
		try {
			int[] packedVertices = new int[vertexBuffer.remaining()];
			vertexBuffer.get(packedVertices);
			BridgeMeshCacheEntry refreshedEntry = new BridgeMeshCacheEntry(firstVertex, vertexCount, metadataMeshRevision, packedVertices);
			cache.put(residentSlot, refreshedEntry);
			return refreshedEntry;
		} finally {
			MemoryUtil.memFree(vertexBuffer);
		}
	}

	private static VertexPoint readVertexPoint(int[] vertices, int baseOffset, CameraFrameState cameraFrameState) {
		return new VertexPoint(
			vertices[baseOffset] - (float) cameraFrameState.cameraX(),
			vertices[baseOffset + 1] - (float) cameraFrameState.cameraY(),
			vertices[baseOffset + 2] - (float) cameraFrameState.cameraZ()
		);
	}

	private void emitQuadVertex(
		VertexConsumer vertexConsumer,
		PoseStack.Pose pose,
		VertexPoint point,
		int color,
		Vector3f normal
	) {
		vertexConsumer.addVertex(pose, point.x(), point.y(), point.z())
			.setColor(color)
			.setNormal(pose, normal);
	}

	private static int debugColorArgb(int packedBlock, boolean translucent) {
		int stateId = BlockData.stateId(packedBlock);
		int hash = (stateId * 1664525) + 1013904223;
		int red = 89 + (((hash) & 0xFF) * 166 / 255);
		int green = 89 + (((hash >>> 8) & 0xFF) * 166 / 255);
		int blue = 89 + (((hash >>> 16) & 0xFF) * 166 / 255);
		int alpha = translucent ? translucentAlpha(packedBlock) : 0xFF;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int translucentAlpha(int packedBlock) {
		int flags = BlockData.flags(packedBlock);
		if ((flags & BlockData.FLAG_FLUID) != 0) {
			return 96;
		}
		if ((flags & BlockData.FLAG_TRANSLUCENT) != 0) {
			return 144;
		}

		return 176;
	}

	private void renderOpaqueTerrainNow(CameraFrameState cameraFrameState) {
		if (!this.canRenderTerrainPass() || cameraFrameState == null) {
			return;
		}

		if (!this.loggedCustomGeometryTerrainDraw) {
			PotassiumLogger.logger().info("Potassium terrain draw is executing through SubmitNodeStorage custom geometry.");
			this.loggedCustomGeometryTerrainDraw = true;
		}

		if (ENABLE_SCREEN_PROBE) {
			this.drawTerrainScreenProbe();
		}

		this.lastRenderedMeshChunks = 0;
		this.lastRenderedMeshVertices = 0;
		this.lastVisibleOpaqueMeshChunks = 0;
		this.lastVisibleTranslucentMeshChunks = 0;

		Matrix4f modelViewMatrix = new Matrix4f(cameraFrameState.modelViewMatrix());
		Matrix4f projectionMatrix = new Matrix4f(cameraFrameState.projectionMatrix());
		this.bindMeshDrawState();
		this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);

		if (ENABLE_GPU_TERRAIN_SUBMISSION && this.hasGpuDrivenDrawPath()) {
			SceneFramebufferState sceneFramebufferState = this.captureSceneFramebufferState();
			this.resetGpuFrameStateBuffers();
			int commandCount = this.dispatchGpuDrivenVisibilityCulling(
				sceneFramebufferState.drawFramebuffer(),
				sceneFramebufferState.viewportX(),
				sceneFramebufferState.viewportY(),
				sceneFramebufferState.viewportWidth(),
				sceneFramebufferState.viewportHeight(),
				projectionMatrix,
				modelViewMatrix
			);
			DrawCommandCountBuffer.Counts counts = this.drawCommandCountBuffer != null ? this.drawCommandCountBuffer.read() : null;
			if (counts != null && counts.opaqueCount() <= 0 && this.terrainMeshReady) {
				this.forceCpuTerrainDrawThisFrame = true;
				this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);
				IntBuffer metadata = this.meshMetadataBuffer.readEntries();
				this.drawOpaqueMetadataFallback(metadata, false);
				this.finishMeshDrawState();
				return;
			}
			GL11C.glDisable(GL11C.GL_BLEND);
			GL11C.glEnable(GL11C.GL_DEPTH_TEST);
			GL11C.glDepthMask(true);
			GL11C.glDisable(GL11C.GL_CULL_FACE);
			this.drawGpuDrivenOpaqueMeshes(commandCount);
			this.lastVisibleOpaqueMeshChunks = -1;
			this.lastVisibleTranslucentMeshChunks = -1;
			this.lastRenderedMeshChunks = -1;
			this.lastRenderedMeshVertices = -1;
		} else {
			IntBuffer metadata = this.meshMetadataBuffer.readEntries();
			if (metadata.remaining() < this.meshMetadataBuffer.capacityEntries() * MeshMetadataBuffer.INTS_PER_ENTRY) {
				this.finishMeshDrawState();
				return;
			}

			this.populateOpaqueDraws(metadata, false);

			GL11C.glDisable(GL11C.GL_BLEND);
			GL11C.glEnable(GL11C.GL_DEPTH_TEST);
			GL11C.glDepthMask(true);
			GL11C.glDisable(GL11C.GL_CULL_FACE);
			this.drawOpaqueDebugMeshes();
		}

		this.finishMeshDrawState();
	}

	private void drawTerrainScreenProbe() {
		if (this.terrainProbeProgram == null || this.oitCompositeVertexArray == 0) {
			return;
		}

		GL30C.glBindVertexArray(this.oitCompositeVertexArray);
		GL20C.glUseProgram(this.terrainProbeProgram.handle());
		GL11C.glDisable(GL11C.GL_DEPTH_TEST);
		GL11C.glDepthMask(false);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		GL11C.glDisable(GL11C.GL_BLEND);
		GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
		GL20C.glUseProgram(0);
		GL30C.glBindVertexArray(0);
	}

	private void renderTranslucentTerrainNow(CameraFrameState cameraFrameState) {
		if (!this.canRenderTerrainPass() || cameraFrameState == null) {
			return;
		}

		Matrix4f modelViewMatrix = new Matrix4f(cameraFrameState.modelViewMatrix());
		Matrix4f projectionMatrix = new Matrix4f(cameraFrameState.projectionMatrix());
		this.bindMeshDrawState();

		if (this.forceCpuTerrainDrawThisFrame) {
			this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);
			IntBuffer metadata = this.meshMetadataBuffer.readEntries();
			if (metadata.remaining() >= this.meshMetadataBuffer.capacityEntries() * MeshMetadataBuffer.INTS_PER_ENTRY) {
				this.drawTranslucentMetadataFallback(metadata, cameraFrameState, false);
			}
			this.finishMeshDrawState();
			return;
		}

		if (ENABLE_GPU_TERRAIN_SUBMISSION && this.hasGpuDrivenDrawPath()) {
			SceneFramebufferState sceneFramebufferState = this.captureSceneFramebufferState();
			this.resetGpuFrameStateBuffers();
			int commandCount = this.dispatchGpuDrivenVisibilityCulling(
				sceneFramebufferState.drawFramebuffer(),
				sceneFramebufferState.viewportX(),
				sceneFramebufferState.viewportY(),
				sceneFramebufferState.viewportWidth(),
				sceneFramebufferState.viewportHeight(),
				projectionMatrix,
				modelViewMatrix
			);
			DrawCommandCountBuffer.Counts counts = this.drawCommandCountBuffer != null ? this.drawCommandCountBuffer.read() : null;
			if (counts != null && counts.translucentCount() <= 0 && this.terrainMeshReady) {
				this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);
				IntBuffer metadata = this.meshMetadataBuffer.readEntries();
				this.drawTranslucentMetadataFallback(metadata, cameraFrameState, false);
				this.finishMeshDrawState();
				return;
			}
			if (this.hasGpuDrivenTranslucentOitPath() && this.renderGpuDrivenTranslucentOit(
				commandCount,
				sceneFramebufferState.drawFramebuffer(),
				sceneFramebufferState.viewportX(),
				sceneFramebufferState.viewportY(),
				sceneFramebufferState.viewportWidth(),
				sceneFramebufferState.viewportHeight(),
				modelViewMatrix,
				projectionMatrix
			)) {
				// Translucent meshes were rendered through the OIT path.
			} else {
				this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);
				GL11C.glEnable(GL11C.GL_BLEND);
				GL11C.glEnable(GL11C.GL_DEPTH_TEST);
				GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
				GL11C.glDepthMask(false);
				GL11C.glDisable(GL11C.GL_CULL_FACE);
				this.drawGpuDrivenTranslucentMeshes(commandCount);
			}
			this.lastVisibleOpaqueMeshChunks = -1;
			this.lastVisibleTranslucentMeshChunks = -1;
			this.lastRenderedMeshChunks = -1;
			this.lastRenderedMeshVertices = -1;
		} else {
			this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);
			IntBuffer metadata = this.meshMetadataBuffer.readEntries();
			if (metadata.remaining() < this.meshMetadataBuffer.capacityEntries() * MeshMetadataBuffer.INTS_PER_ENTRY) {
				this.finishMeshDrawState();
				return;
			}

			ArrayList<TranslucentDraw> translucentDraws = this.collectTranslucentDraws(metadata, cameraFrameState, false);
			GL11C.glEnable(GL11C.GL_BLEND);
			GL11C.glEnable(GL11C.GL_DEPTH_TEST);
			GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
			GL11C.glDepthMask(false);
			GL11C.glDisable(GL11C.GL_CULL_FACE);
			this.drawTranslucentDebugMeshes(translucentDraws);
		}

		this.finishMeshDrawState();
	}

	public boolean canRenderTerrainPass() {
		return this.initialized
			&& this.worldDataBuffer != null
			&& this.worldDataBuffer.isConfigured()
			&& this.meshMetadataBuffer != null
			&& this.meshVertexBuffer != null
			&& this.chunkProgram != null
			&& this.debugMeshVertexArray != 0
			&& this.memoryManager.residentChunks() > 0
			&& this.terrainMeshReady;
	}

	private void bindMeshDrawState() {
		GL30C.glBindVertexArray(this.debugMeshVertexArray);
		this.meshVertexBuffer.bindArrayBuffer();
		GL30C.glEnableVertexAttribArray(0);
		GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_INT, MeshVertexBuffer.BYTES_PER_VERTEX, 0L);
	}

	private void finishMeshDrawState() {
		GL11C.glDepthMask(true);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glEnable(GL11C.GL_CULL_FACE);
		GL11C.glDisable(GL11C.GL_BLEND);
		GL30C.glDisableVertexAttribArray(0);
		GL30C.glBindVertexArray(0);
		GL20C.glUseProgram(0);
	}

	private CameraFrameState captureCameraFrameState(CameraRenderState cameraState) {
		if (cameraState == null) {
			return null;
		}

		return new CameraFrameState(
			this.buildCameraModelViewMatrix(cameraState),
			new Matrix4f(cameraState.projectionMatrix),
			cameraState.pos.x,
			cameraState.pos.y,
			cameraState.pos.z,
			computeProbeForwardX(cameraState),
			computeProbeForwardY(cameraState),
			computeProbeForwardZ(cameraState)
		);
	}

	private Matrix4f buildCameraModelViewMatrix(CameraRenderState cameraState) {
		return new Matrix4f(cameraState.viewRotationMatrix).translate(
			(float) -cameraState.pos.x,
			(float) -cameraState.pos.y,
			(float) -cameraState.pos.z
		);
	}

	private static float computeProbeForwardX(CameraRenderState cameraState) {
		return new Vector3f(0.0f, 0.0f, -1.0f).rotate(cameraState.orientation).x;
	}

	private static float computeProbeForwardY(CameraRenderState cameraState) {
		return new Vector3f(0.0f, 0.0f, -1.0f).rotate(cameraState.orientation).y;
	}

	private static float computeProbeForwardZ(CameraRenderState cameraState) {
		return new Vector3f(0.0f, 0.0f, -1.0f).rotate(cameraState.orientation).z;
	}

	@Override
	public void close() {
		closeQuietly(this.translucentOitFramebuffer);
		closeQuietly(this.depthPyramid);
		closeQuietly(this.depthDownsampleShader);
		closeQuietly(this.depthCopyShader);
		closeQuietly(this.resetFrameStateShader);
		closeQuietly(this.occlusionCullingShader);
		closeQuietly(this.frustumCullingShader);
		closeQuietly(this.meshGenerationShader);
		closeQuietly(this.applyChangesShader);
		closeQuietly(this.meshGenerationStatsBuffer);
		closeQuietly(this.residentChunkStateBuffer);
		closeQuietly(this.changeQueueBuffer);
		closeQuietly(this.drawCommandCountBuffer);
		closeQuietly(this.meshVertexBuffer);
		closeQuietly(this.meshMetadataBuffer);
		closeQuietly(this.terrainProbeProgram);
		closeQuietly(this.oitCompositeProgram);
		closeQuietly(this.chunkTranslucentProgram);
		closeQuietly(this.chunkProgram);
		closeQuietly(this.vertexBuffer);
		closeQuietly(this.translucentIndirectCommandBuffer);
		closeQuietly(this.indirectCommandBuffer);
		closeQuietly(this.worldDataBuffer);

		this.translucentOitFramebuffer = null;
		this.depthPyramid = null;
		this.depthDownsampleShader = null;
		this.depthCopyShader = null;
		this.resetFrameStateShader = null;
		this.occlusionCullingShader = null;
		this.frustumCullingShader = null;
		this.meshGenerationShader = null;
		this.applyChangesShader = null;
		this.meshGenerationStatsBuffer = null;
		this.residentChunkStateBuffer = null;
		this.changeQueueBuffer = null;
		this.drawCommandCountBuffer = null;
		this.meshVertexBuffer = null;
		this.meshMetadataBuffer = null;
		this.terrainProbeProgram = null;
		this.oitCompositeProgram = null;
		this.chunkTranslucentProgram = null;
		this.chunkProgram = null;
		this.vertexBuffer = null;
		this.translucentIndirectCommandBuffer = null;
		this.indirectCommandBuffer = null;
		this.worldDataBuffer = null;
		this.initialized = false;
		if (this.debugMeshVertexArray != 0) {
			GL30C.glDeleteVertexArrays(this.debugMeshVertexArray);
			this.debugMeshVertexArray = 0;
		}
		if (this.oitCompositeVertexArray != 0) {
			GL30C.glDeleteVertexArrays(this.oitCompositeVertexArray);
			this.oitCompositeVertexArray = 0;
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
		this.lastVisibleOpaqueMeshChunks = 0;
		this.lastVisibleTranslucentMeshChunks = 0;
		this.pendingGpuChangeCount = 0;
		this.pendingGpuWorldChanges.clear();
		this.bridgeMeshCache.clear();
		this.bridgeTranslucentMeshCache.clear();
		this.terrainMeshReady = false;
		this.loggedCustomGeometryTerrainDraw = false;
		this.loggedVertexConsumerProbe = false;
		this.loggedVertexConsumerTerrainBridge = false;
		this.loggedVertexConsumerTranslucentTerrainBridge = false;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		this.worldDataEvictions = 0;
		this.chunkModelViewUniform = -1;
		this.chunkProjectionUniform = -1;
		this.chunkTranslucentModelViewUniform = -1;
		this.chunkTranslucentProjectionUniform = -1;
		this.meshFacesPerChunkUniform = -1;
		this.meshResidentSlotCapacityUniform = -1;
		this.frustumViewProjectionUniform = -1;
		this.frustumResidentSlotCapacityUniform = -1;
		this.occlusionViewProjectionUniform = -1;
		this.occlusionViewportSizeUniform = -1;
		this.occlusionDepthPyramidLevelsUniform = -1;
		this.occlusionResidentSlotCapacityUniform = -1;
		this.depthDownsampleSourceMipUniform = -1;
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
			this.ensureIndirectCommandCapacity();
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
		if (this.meshGenerationShader == null || this.residentChunkStateBuffer == null || this.meshGenerationStatsBuffer == null) {
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

		int residentSlotCapacity = Math.max(this.residentChunkStateBuffer.capacityEntries(), 1);
		this.lastMeshGenerationJobs = residentSlotCapacity;
		this.lastMeshGenerationDirtyCandidates = this.memoryManager.residentChunks();
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.residentChunkStateBuffer.bind(RESIDENT_CHUNK_STATE_BINDING);
		this.meshGenerationStatsBuffer.bind(MESH_STATS_BINDING);
		this.meshMetadataBuffer.bind(MESH_METADATA_BINDING);
		this.meshVertexBuffer.bindStorage(MESH_VERTEX_BINDING);
		this.meshGenerationShader.use();
		if (this.meshFacesPerChunkUniform >= 0) {
			GL30C.glUniform1ui(this.meshFacesPerChunkUniform, this.meshVertexBuffer.facesPerChunk());
		}
		if (this.meshResidentSlotCapacityUniform >= 0) {
			GL30C.glUniform1ui(this.meshResidentSlotCapacityUniform, residentSlotCapacity);
		}

		int groupCountX = (residentSlotCapacity + (MESH_GENERATION_LOCAL_SIZE_X - 1)) / MESH_GENERATION_LOCAL_SIZE_X;
		GL43C.glDispatchCompute(groupCountX, 1, 1);
		GL43C.glMemoryBarrier(
			GL43C.GL_SHADER_STORAGE_BARRIER_BIT
				| GL43C.GL_BUFFER_UPDATE_BARRIER_BIT
				| GL43C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
		);
		this.lastMeshGenerationProcessedJobs = -1;
		this.lastMeshGenerationGeneratedVertices = -1;
		this.lastMeshGenerationClippedJobs = -1;
		this.lastMeshGenerationSampledPackedBlock = -1;
	}

	private void refreshTerrainMeshReadiness() {
		if (this.meshMetadataBuffer == null || this.memoryManager.residentChunks() <= 0) {
			this.terrainMeshReady = false;
			return;
		}

		IntBuffer metadata = this.meshMetadataBuffer.readEntries();
		int entries = this.meshMetadataBuffer.capacityEntries();
		boolean hasGeometry = false;
		for (int entryIndex = 0; entryIndex < entries; entryIndex++) {
			int metadataOffset = entryIndex * MeshMetadataBuffer.INTS_PER_ENTRY;
			int opaqueVertexCount = metadata.get(metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET);
			int translucentVertexCount = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET);
			if (opaqueVertexCount > 0 || translucentVertexCount > 0) {
				hasGeometry = true;
				break;
			}
		}

		if (hasGeometry && !this.terrainMeshReady) {
			PotassiumLogger.logger().info(
				"Potassium terrain mesh is ready. Resident chunks={}, meshCapacity={}, pages={}.",
				this.memoryManager.residentChunks(),
				this.meshMetadataBuffer.capacityEntries(),
				this.worldDataBuffer.pageCount()
			);
		}
		this.terrainMeshReady = hasGeometry;
	}

	private boolean hasMeshGenerationBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= MESH_GENERATION_REQUIRED_SSBO_BINDINGS;
	}

	private boolean hasGpuChangeApplyBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= APPLY_CHANGES_REQUIRED_SSBO_BINDINGS;
	}

	private boolean hasGpuFrameStateResetBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= DRAW_COMMAND_COUNT_BINDING + 1;
	}

	private boolean hasGpuChangeApplyPath() {
		return this.applyChangesShader != null
			&& this.changeQueueBuffer != null
			&& this.residentChunkStateBuffer != null
			&& this.worldDataBuffer != null;
	}

	private boolean hasGpuFrameStateResetPath() {
		return this.resetFrameStateShader != null
			&& this.meshGenerationStatsBuffer != null
			&& this.drawCommandCountBuffer != null;
	}

	private boolean hasGpuDrivenDrawBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= FRUSTUM_CULLING_REQUIRED_SSBO_BINDINGS;
	}

	private boolean hasGpuDrivenDrawPath() {
		return GLCapabilities.hasIndirectCount()
			&& this.frustumCullingShader != null
			&& this.drawCommandCountBuffer != null
			&& this.indirectCommandBuffer != null
			&& this.translucentIndirectCommandBuffer != null;
	}

	private boolean hasGpuOcclusionPath() {
		return this.hasGpuDrivenDrawPath()
			&& this.depthPyramid != null
			&& this.depthCopyShader != null
			&& this.depthDownsampleShader != null
			&& this.occlusionCullingShader != null;
	}

	private boolean hasGpuDrivenTranslucentOitPath() {
		return this.hasGpuDrivenDrawPath()
			&& this.chunkTranslucentProgram != null
			&& this.oitCompositeProgram != null
			&& this.translucentOitFramebuffer != null
			&& this.oitCompositeVertexArray != 0;
	}

	private void useChunkProgram(
		ShaderProgram program,
		int modelViewUniform,
		int projectionUniform,
		Matrix4fc modelViewMatrix,
		Matrix4fc projectionMatrix
	) {
		GL20C.glUseProgram(program.handle());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer modelViewBuffer = stack.mallocFloat(16);
			FloatBuffer projectionBuffer = stack.mallocFloat(16);
			modelViewMatrix.get(modelViewBuffer);
			projectionMatrix.get(projectionBuffer);
			GL20C.glUniformMatrix4fv(modelViewUniform, false, modelViewBuffer);
			GL20C.glUniformMatrix4fv(projectionUniform, false, projectionBuffer);
		}
	}

	private int dispatchGpuDrivenFrustumCulling(Matrix4fc projectionMatrix, Matrix4fc modelViewMatrix) {
		int commandCount = Math.max(this.meshMetadataBuffer.capacityEntries(), 1);
		this.ensureIndirectCommandCapacity();

		Matrix4f viewProjectionMatrix = new Matrix4f(projectionMatrix).mul(modelViewMatrix);
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.meshMetadataBuffer.bind(MESH_METADATA_BINDING);
		this.indirectCommandBuffer.bindForStorage(OPAQUE_COMMAND_BINDING);
		this.translucentIndirectCommandBuffer.bindForStorage(TRANSLUCENT_COMMAND_BINDING);
		this.drawCommandCountBuffer.bind(DRAW_COMMAND_COUNT_BINDING);
		this.frustumCullingShader.use();
		if (this.frustumViewProjectionUniform >= 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				FloatBuffer buffer = stack.mallocFloat(16);
				viewProjectionMatrix.get(buffer);
				GL20C.glUniformMatrix4fv(this.frustumViewProjectionUniform, false, buffer);
			}
		}
		if (this.frustumResidentSlotCapacityUniform >= 0) {
			GL30C.glUniform1ui(this.frustumResidentSlotCapacityUniform, this.meshMetadataBuffer.capacityEntries());
		}

		int groupCountX = (commandCount + (MESH_GENERATION_LOCAL_SIZE_X - 1)) / MESH_GENERATION_LOCAL_SIZE_X;
		GL43C.glDispatchCompute(groupCountX, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		return commandCount;
	}

	private int dispatchGpuDrivenVisibilityCulling(
		int sceneDrawFramebuffer,
		int sceneViewportX,
		int sceneViewportY,
		int sceneViewportWidth,
		int sceneViewportHeight,
		Matrix4fc projectionMatrix,
		Matrix4fc modelViewMatrix
	) {
		if (!this.hasGpuOcclusionPath() || sceneViewportWidth <= 0 || sceneViewportHeight <= 0) {
			return this.dispatchGpuDrivenFrustumCulling(projectionMatrix, modelViewMatrix);
		}

		if (!this.buildDepthPyramid(sceneDrawFramebuffer, sceneViewportX, sceneViewportY, sceneViewportWidth, sceneViewportHeight)) {
			return this.dispatchGpuDrivenFrustumCulling(projectionMatrix, modelViewMatrix);
		}
		return this.dispatchGpuDrivenOcclusionCulling(sceneViewportWidth, sceneViewportHeight, projectionMatrix, modelViewMatrix);
	}

	private boolean buildDepthPyramid(
		int sceneDrawFramebuffer,
		int sceneViewportX,
		int sceneViewportY,
		int sceneViewportWidth,
		int sceneViewportHeight
	) {
		if (this.depthPyramid == null || this.depthCopyShader == null || this.depthDownsampleShader == null) {
			return false;
		}

		if (!this.depthPyramid.ensureSizeFromSource(sceneDrawFramebuffer, sceneViewportWidth, sceneViewportHeight)) {
			return false;
		}
		this.depthPyramid.copyDepthFrom(sceneDrawFramebuffer, sceneViewportX, sceneViewportY, sceneViewportWidth, sceneViewportHeight);
		this.depthPyramid.bindDepthCopyTexture(0);
		this.depthPyramid.bindPyramidLevelForWrite(0, 0);
		this.depthCopyShader.use();
		GL43C.glDispatchCompute(
			(sceneViewportWidth + (DEPTH_COPY_LOCAL_SIZE_X - 1)) / DEPTH_COPY_LOCAL_SIZE_X,
			(sceneViewportHeight + (DEPTH_COPY_LOCAL_SIZE_Y - 1)) / DEPTH_COPY_LOCAL_SIZE_Y,
			1
		);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);

		this.depthDownsampleShader.use();
		this.depthPyramid.bindPyramidTexture(0);
		for (int mipLevel = 1; mipLevel < this.depthPyramid.levels(); mipLevel++) {
			if (this.depthDownsampleSourceMipUniform >= 0) {
				GL20C.glUniform1i(this.depthDownsampleSourceMipUniform, mipLevel - 1);
			}
			this.depthPyramid.bindPyramidLevelForWrite(0, mipLevel);
			GL43C.glDispatchCompute(
				(this.depthPyramid.levelWidth(mipLevel) + (DEPTH_COPY_LOCAL_SIZE_X - 1)) / DEPTH_COPY_LOCAL_SIZE_X,
				(this.depthPyramid.levelHeight(mipLevel) + (DEPTH_COPY_LOCAL_SIZE_Y - 1)) / DEPTH_COPY_LOCAL_SIZE_Y,
				1
			);
			GL43C.glMemoryBarrier(GL43C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
		}
		return true;
	}

	private int dispatchGpuDrivenOcclusionCulling(
		int viewportWidth,
		int viewportHeight,
		Matrix4fc projectionMatrix,
		Matrix4fc modelViewMatrix
	) {
		int commandCount = Math.max(this.meshMetadataBuffer.capacityEntries(), 1);
		this.ensureIndirectCommandCapacity();

		Matrix4f viewProjectionMatrix = new Matrix4f(projectionMatrix).mul(modelViewMatrix);
		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.meshMetadataBuffer.bind(MESH_METADATA_BINDING);
		this.indirectCommandBuffer.bindForStorage(OPAQUE_COMMAND_BINDING);
		this.translucentIndirectCommandBuffer.bindForStorage(TRANSLUCENT_COMMAND_BINDING);
		this.drawCommandCountBuffer.bind(DRAW_COMMAND_COUNT_BINDING);
		this.depthPyramid.bindPyramidTexture(0);
		this.occlusionCullingShader.use();
		if (this.occlusionViewProjectionUniform >= 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				FloatBuffer buffer = stack.mallocFloat(16);
				viewProjectionMatrix.get(buffer);
				GL20C.glUniformMatrix4fv(this.occlusionViewProjectionUniform, false, buffer);
			}
		}
		if (this.occlusionViewportSizeUniform >= 0) {
			GL20C.glUniform2f(this.occlusionViewportSizeUniform, viewportWidth, viewportHeight);
		}
		if (this.occlusionDepthPyramidLevelsUniform >= 0) {
			GL20C.glUniform1i(this.occlusionDepthPyramidLevelsUniform, this.depthPyramid.levels());
		}
		if (this.occlusionResidentSlotCapacityUniform >= 0) {
			GL30C.glUniform1ui(this.occlusionResidentSlotCapacityUniform, this.meshMetadataBuffer.capacityEntries());
		}

		int groupCountX = (commandCount + (MESH_GENERATION_LOCAL_SIZE_X - 1)) / MESH_GENERATION_LOCAL_SIZE_X;
		GL43C.glDispatchCompute(groupCountX, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
		return commandCount;
	}

	private void dispatchPendingGpuWorldChanges() {
		if (!this.hasGpuChangeApplyPath() || this.pendingGpuChangeCount <= 0) {
			return;
		}

		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.residentChunkStateBuffer.bind(RESIDENT_CHUNK_STATE_BINDING);
		this.changeQueueBuffer.bind(CHANGE_QUEUE_BINDING);
		this.applyChangesShader.use();

		int groupCountX = (this.pendingGpuChangeCount + (MESH_GENERATION_LOCAL_SIZE_X - 1)) / MESH_GENERATION_LOCAL_SIZE_X;
		GL43C.glDispatchCompute(groupCountX, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
		this.pendingGpuWorldChanges.clear();
		this.pendingGpuChangeCount = 0;
	}

	private void resetGpuFrameStateBuffers() {
		if (!this.hasGpuFrameStateResetPath()) {
			return;
		}

		this.meshGenerationStatsBuffer.bind(MESH_STATS_BINDING);
		this.drawCommandCountBuffer.bind(DRAW_COMMAND_COUNT_BINDING);
		this.resetFrameStateShader.use();
		GL43C.glDispatchCompute(1, 1, 1);
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);
	}

	private boolean renderGpuDrivenTranslucentOit(
		int commandCount,
		int sceneDrawFramebuffer,
		int sceneViewportX,
		int sceneViewportY,
		int sceneViewportWidth,
		int sceneViewportHeight,
		Matrix4fc modelViewMatrix,
		Matrix4fc projectionMatrix
	) {
		if (commandCount <= 0 || sceneViewportWidth <= 0 || sceneViewportHeight <= 0 || this.translucentOitFramebuffer == null) {
			return false;
		}

		if (!this.translucentOitFramebuffer.ensureSizeFromSource(sceneDrawFramebuffer, sceneViewportWidth, sceneViewportHeight)) {
			return false;
		}
		this.translucentOitFramebuffer.clear();
		this.translucentOitFramebuffer.copyDepthFrom(
			sceneDrawFramebuffer,
			sceneViewportX,
			sceneViewportY,
			sceneViewportWidth,
			sceneViewportHeight
		);
		this.translucentOitFramebuffer.bindForAccumulation();
		this.useChunkProgram(
			this.chunkTranslucentProgram,
			this.chunkTranslucentModelViewUniform,
			this.chunkTranslucentProjectionUniform,
			modelViewMatrix,
			projectionMatrix
		);

		GL11C.glEnable(GL11C.GL_BLEND);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glDepthMask(false);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		GL40C.glBlendEquationi(0, GL14C.GL_FUNC_ADD);
		GL40C.glBlendFunci(0, GL11C.GL_ONE, GL11C.GL_ONE);
		GL40C.glBlendEquationi(1, GL14C.GL_FUNC_ADD);
		GL40C.glBlendFunci(1, GL11C.GL_ZERO, GL11C.GL_ONE_MINUS_SRC_ALPHA);
		this.drawGpuDrivenTranslucentMeshes(commandCount);

		GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sceneDrawFramebuffer);
		GL11C.glViewport(sceneViewportX, sceneViewportY, sceneViewportWidth, sceneViewportHeight);
		GL11C.glDisable(GL11C.GL_DEPTH_TEST);
		GL11C.glDepthMask(false);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		GL11C.glEnable(GL11C.GL_BLEND);
		GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
		GL14C.glBlendEquation(GL14C.GL_FUNC_ADD);
		this.translucentOitFramebuffer.bindTextures(0, 1);
		GL30C.glBindVertexArray(this.oitCompositeVertexArray);
		GL20C.glUseProgram(this.oitCompositeProgram.handle());
		GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
		GL30C.glBindVertexArray(this.debugMeshVertexArray);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		return true;
	}

	private int resolveResidentSlot(int chunkX, int chunkZ) {
		ChunkData neighborChunk = this.chunkManager.getChunk(new ChunkPos(chunkX, chunkZ));
		if (neighborChunk == null || !neighborChunk.isResident()) {
			return INVALID_RESIDENT_SLOT;
		}

		return neighborChunk.residentSlot();
	}

	private void ensureMeshOutputCapacity(boolean clearMetadata) {
		if (this.meshMetadataBuffer == null || this.meshVertexBuffer == null) {
			return;
		}

		int requiredChunkCapacity = Math.max(this.memoryManager.capacityChunks(), 1);
		if (this.residentChunkStateBuffer != null) {
			this.residentChunkStateBuffer.ensureCapacity(requiredChunkCapacity);
			if (clearMetadata) {
				this.residentChunkStateBuffer.clearAll();
			}
		}
		this.meshMetadataBuffer.ensureCapacity(requiredChunkCapacity);
		this.meshVertexBuffer.ensureChunkCapacity(requiredChunkCapacity);
		if (clearMetadata) {
			this.meshMetadataBuffer.clearAll();
		}
	}

	private void ensureIndirectCommandCapacity() {
		int requiredCommands = Math.max(this.meshMetadataBuffer != null ? this.meshMetadataBuffer.capacityEntries() : this.memoryManager.capacityChunks(), 1);
		if (this.indirectCommandBuffer != null) {
			this.indirectCommandBuffer.ensureCapacity(requiredCommands);
		}
		if (this.translucentIndirectCommandBuffer != null) {
			this.translucentIndirectCommandBuffer.ensureCapacity(requiredCommands);
		}
	}

	private void purgePendingGpuChangesForSlot(int residentSlot) {
		if (residentSlot < 0 || this.pendingGpuWorldChanges.isEmpty()) {
			return;
		}

		if (this.pendingGpuWorldChanges.removeIf(entry -> entry.residentSlot() == residentSlot)) {
			this.pendingGpuChangeCount = this.pendingGpuWorldChanges.size();
			if (this.changeQueueBuffer != null) {
				this.changeQueueBuffer.uploadChanges(this.pendingGpuWorldChanges);
				this.lastUploadedWorldBytes = this.changeQueueBuffer.lastUploadBytes();
			}
		}
	}

	private void clearMeshSlot(int residentSlot) {
		if (this.meshMetadataBuffer == null || residentSlot < 0) {
			return;
		}

		this.meshMetadataBuffer.clearSlot(residentSlot);
		this.bridgeMeshCache.remove(residentSlot);
		this.bridgeTranslucentMeshCache.remove(residentSlot);
	}

	private void syncResidentChunkNeighborhood(ChunkPos centerChunkPos, boolean dirtyTouchedChunks) {
		this.syncResidentChunkState(centerChunkPos, dirtyTouchedChunks);
		this.syncResidentChunkState(new ChunkPos(centerChunkPos.x() - 1, centerChunkPos.z()), dirtyTouchedChunks);
		this.syncResidentChunkState(new ChunkPos(centerChunkPos.x() + 1, centerChunkPos.z()), dirtyTouchedChunks);
		this.syncResidentChunkState(new ChunkPos(centerChunkPos.x(), centerChunkPos.z() - 1), dirtyTouchedChunks);
		this.syncResidentChunkState(new ChunkPos(centerChunkPos.x(), centerChunkPos.z() + 1), dirtyTouchedChunks);
	}

	private void markResidentChunkMeshDirty(int chunkX, int chunkZ, long tickIndex) {
		ChunkData chunkData = this.chunkManager.getChunk(new ChunkPos(chunkX, chunkZ));
		if (chunkData == null || !chunkData.isResident()) {
			return;
		}

		this.markChunkMeshDirty(chunkData, tickIndex);
	}

	private void markChunkMeshDirty(ChunkData chunkData, long tickIndex) {
		chunkData.markDirty(tickIndex);
		if (this.residentChunkStateBuffer != null && chunkData.isResident()) {
			this.residentChunkStateBuffer.markDirty(chunkData.residentSlot());
		}
	}

	private void syncResidentChunkState(ChunkPos chunkPos, boolean dirty) {
		if (this.residentChunkStateBuffer == null) {
			return;
		}

		ChunkData chunkData = this.chunkManager.getChunk(chunkPos);
		if (chunkData == null || !chunkData.isResident()) {
			return;
		}

		this.residentChunkStateBuffer.writeResidentEntry(
			chunkData.residentSlot(),
			chunkPos.x(),
			chunkPos.z(),
			this.resolveResidentSlot(chunkPos.x() - 1, chunkPos.z()),
			this.resolveResidentSlot(chunkPos.x() + 1, chunkPos.z()),
			this.resolveResidentSlot(chunkPos.x(), chunkPos.z() - 1),
			this.resolveResidentSlot(chunkPos.x(), chunkPos.z() + 1),
			dirty
		);
	}

	private SceneFramebufferState captureSceneFramebufferState() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer viewport = stack.mallocInt(4);
			GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
			int sceneDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
			return new SceneFramebufferState(
				sceneDrawFramebuffer,
				viewport.get(0),
				viewport.get(1),
				viewport.get(2),
				viewport.get(3)
			);
		}
	}

	private void populateVisibleOpaqueDraws(IntBuffer metadata) {
		this.populateOpaqueDraws(metadata, true);
	}

	private void populateOpaqueDraws(IntBuffer metadata, boolean applyFrustum) {
		int visibleMinSectionY = this.worldDataBuffer.minSectionY();
		int visibleSectionsCount = this.worldDataBuffer.sectionsCount();

		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}
			if (applyFrustum && !this.frustumCuller.isChunkVisible(chunkData.chunkPos(), visibleMinSectionY, visibleSectionsCount)) {
				continue;
			}

			int metadataOffset = chunkData.residentSlot() * MeshMetadataBuffer.INTS_PER_ENTRY;
			int opaqueVertexCount = metadata.get(metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET);
			if (opaqueVertexCount <= 0) {
				continue;
			}

			int firstVertex = metadata.get(metadataOffset + MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET);
			this.indirectCommandBuffer.addDrawArraysCommand(opaqueVertexCount, 1, firstVertex, 0);
			this.lastRenderedMeshVertices += opaqueVertexCount;
			this.lastVisibleOpaqueMeshChunks++;
			this.lastRenderedMeshChunks++;
		}
	}

	private ArrayList<TranslucentDraw> collectVisibleTranslucentDraws(IntBuffer metadata, CameraRenderState cameraState) {
		return this.collectTranslucentDraws(metadata, cameraState, true);
	}

	private ArrayList<TranslucentDraw> collectTranslucentDraws(IntBuffer metadata, CameraRenderState cameraState, boolean applyFrustum) {
		ArrayList<TranslucentDraw> translucentDraws = new ArrayList<>();
		double cameraX = cameraState.pos.x;
		double cameraY = cameraState.pos.y;
		double cameraZ = cameraState.pos.z;
		int visibleMinSectionY = this.worldDataBuffer.minSectionY();
		int visibleSectionsCount = this.worldDataBuffer.sectionsCount();

		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}
			if (applyFrustum && !this.frustumCuller.isChunkVisible(chunkData.chunkPos(), visibleMinSectionY, visibleSectionsCount)) {
				continue;
			}

			int metadataOffset = chunkData.residentSlot() * MeshMetadataBuffer.INTS_PER_ENTRY;
			int translucentVertexCount = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET);
			if (translucentVertexCount <= 0) {
				continue;
			}

			int translucentFirstVertex = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET);
			double distanceSquared = squaredDistanceToChunkCenter(
				chunkData.chunkPos(),
				visibleMinSectionY,
				visibleSectionsCount,
				cameraX,
				cameraY,
				cameraZ
			);
			translucentDraws.add(new TranslucentDraw(translucentFirstVertex, translucentVertexCount, distanceSquared));
			this.lastRenderedMeshVertices += translucentVertexCount;
			this.lastVisibleTranslucentMeshChunks++;
			if (metadata.get(metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET) <= 0) {
				this.lastRenderedMeshChunks++;
			}
		}

		return translucentDraws;
	}

	private ArrayList<TranslucentDraw> collectTranslucentDraws(IntBuffer metadata, CameraFrameState cameraFrameState, boolean applyFrustum) {
		ArrayList<TranslucentDraw> translucentDraws = new ArrayList<>();
		double cameraX = cameraFrameState.cameraX();
		double cameraY = cameraFrameState.cameraY();
		double cameraZ = cameraFrameState.cameraZ();
		int visibleMinSectionY = this.worldDataBuffer.minSectionY();
		int visibleSectionsCount = this.worldDataBuffer.sectionsCount();

		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}
			if (applyFrustum && !this.frustumCuller.isChunkVisible(chunkData.chunkPos(), visibleMinSectionY, visibleSectionsCount)) {
				continue;
			}

			int metadataOffset = chunkData.residentSlot() * MeshMetadataBuffer.INTS_PER_ENTRY;
			int translucentVertexCount = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET);
			if (translucentVertexCount <= 0) {
				continue;
			}

			int translucentFirstVertex = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET);
			double distanceSquared = squaredDistanceToChunkCenter(
				chunkData.chunkPos(),
				visibleMinSectionY,
				visibleSectionsCount,
				cameraX,
				cameraY,
				cameraZ
			);
			translucentDraws.add(new TranslucentDraw(translucentFirstVertex, translucentVertexCount, distanceSquared));
			this.lastRenderedMeshVertices += translucentVertexCount;
			this.lastVisibleTranslucentMeshChunks++;
			if (metadata.get(metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET) <= 0) {
				this.lastRenderedMeshChunks++;
			}
		}

		return translucentDraws;
	}

	private void drawOpaqueMetadataFallback(IntBuffer metadata, boolean applyFrustum) {
		this.indirectCommandBuffer.beginFrame();
		if (applyFrustum) {
			this.frustumCuller.update(new Matrix4f(), new Matrix4f());
		}
		this.populateOpaqueDraws(metadata, applyFrustum);
		GL11C.glDisable(GL11C.GL_BLEND);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glDepthMask(true);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		this.drawOpaqueDebugMeshes();
	}

	private void drawTranslucentMetadataFallback(IntBuffer metadata, CameraRenderState cameraState, boolean applyFrustum) {
		ArrayList<TranslucentDraw> translucentDraws = this.collectTranslucentDraws(metadata, cameraState, applyFrustum);
		GL11C.glEnable(GL11C.GL_BLEND);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
		GL11C.glDepthMask(false);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		this.drawTranslucentDebugMeshes(translucentDraws);
	}

	private void drawTranslucentMetadataFallback(IntBuffer metadata, CameraFrameState cameraFrameState, boolean applyFrustum) {
		ArrayList<TranslucentDraw> translucentDraws = this.collectTranslucentDraws(metadata, cameraFrameState, applyFrustum);
		GL11C.glEnable(GL11C.GL_BLEND);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
		GL11C.glDepthMask(false);
		GL11C.glDisable(GL11C.GL_CULL_FACE);
		this.drawTranslucentDebugMeshes(translucentDraws);
	}

	private void populateVisibleDraws(IntBuffer metadata, List<TranslucentDraw> translucentDraws, CameraRenderState cameraState) {
		double cameraX = cameraState.pos.x;
		double cameraY = cameraState.pos.y;
		double cameraZ = cameraState.pos.z;
		int visibleMinSectionY = this.worldDataBuffer.minSectionY();
		int visibleSectionsCount = this.worldDataBuffer.sectionsCount();

		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}
			if (!this.frustumCuller.isChunkVisible(chunkData.chunkPos(), visibleMinSectionY, visibleSectionsCount)) {
				continue;
			}

			int metadataOffset = chunkData.residentSlot() * MeshMetadataBuffer.INTS_PER_ENTRY;
			boolean renderedChunk = false;
			int opaqueVertexCount = metadata.get(metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET);
			if (opaqueVertexCount > 0) {
				int firstVertex = metadata.get(metadataOffset + MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET);
				this.indirectCommandBuffer.addDrawArraysCommand(opaqueVertexCount, 1, firstVertex, 0);
				this.lastRenderedMeshVertices += opaqueVertexCount;
				this.lastVisibleOpaqueMeshChunks++;
				renderedChunk = true;
			}

			int translucentVertexCount = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET);
			if (translucentVertexCount > 0) {
				int translucentFirstVertex = metadata.get(metadataOffset + MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET);
				double distanceSquared = squaredDistanceToChunkCenter(
					chunkData.chunkPos(),
					visibleMinSectionY,
					visibleSectionsCount,
					cameraX,
					cameraY,
					cameraZ
				);
				translucentDraws.add(new TranslucentDraw(translucentFirstVertex, translucentVertexCount, distanceSquared));
				this.lastRenderedMeshVertices += translucentVertexCount;
				this.lastVisibleTranslucentMeshChunks++;
				renderedChunk = true;
			}
			if (renderedChunk) {
				this.lastRenderedMeshChunks++;
			}
		}
	}

	private void drawOpaqueDebugMeshes() {
		if (this.indirectCommandBuffer.commandCount() <= 0) {
			return;
		}

		this.indirectCommandBuffer.upload();
		this.indirectCommandBuffer.bindForDraw();
		GL43C.glMultiDrawArraysIndirect(
			GL11C.GL_TRIANGLES,
			this.indirectCommandBuffer.drawOffsetBytes(0),
			this.indirectCommandBuffer.commandCount(),
			IndirectCommandBuffer.COMMAND_STRIDE_BYTES
		);
	}

	private void drawGpuDrivenOpaqueMeshes(int commandCount) {
		if (commandCount <= 0 || this.indirectCommandBuffer == null) {
			return;
		}

		if (GLCapabilities.hasIndirectCount() && this.drawCommandCountBuffer != null) {
			this.drawCommandCountBuffer.bindForDrawCount();
			this.indirectCommandBuffer.bindForDraw();
			GL46C.glMultiDrawArraysIndirectCount(
				GL11C.GL_TRIANGLES,
				0L,
				DrawCommandCountBuffer.OPAQUE_COUNT_OFFSET_BYTES,
				commandCount,
				IndirectCommandBuffer.COMMAND_STRIDE_BYTES
			);
			return;
		}

		this.indirectCommandBuffer.bindForDraw();
		GL43C.glMultiDrawArraysIndirect(
			GL11C.GL_TRIANGLES,
			0L,
			commandCount,
			IndirectCommandBuffer.COMMAND_STRIDE_BYTES
		);
	}

	private void drawTranslucentDebugMeshes(List<TranslucentDraw> translucentDraws) {
		if (translucentDraws.isEmpty()) {
			return;
		}

		translucentDraws.sort(Comparator.comparingDouble(TranslucentDraw::distanceSquared).reversed());
		for (TranslucentDraw draw : translucentDraws) {
			GL11C.glDrawArrays(GL11C.GL_TRIANGLES, draw.firstVertex(), draw.vertexCount());
		}
	}

	private void drawGpuDrivenTranslucentMeshes(int commandCount) {
		if (commandCount <= 0 || this.translucentIndirectCommandBuffer == null) {
			return;
		}

		if (GLCapabilities.hasIndirectCount() && this.drawCommandCountBuffer != null) {
			this.drawCommandCountBuffer.bindForDrawCount();
			this.translucentIndirectCommandBuffer.bindForDraw();
			GL46C.glMultiDrawArraysIndirectCount(
				GL11C.GL_TRIANGLES,
				0L,
				DrawCommandCountBuffer.TRANSLUCENT_COUNT_OFFSET_BYTES,
				commandCount,
				IndirectCommandBuffer.COMMAND_STRIDE_BYTES
			);
			return;
		}

		this.translucentIndirectCommandBuffer.bindForDraw();
		GL43C.glMultiDrawArraysIndirect(
			GL11C.GL_TRIANGLES,
			0L,
			commandCount,
			IndirectCommandBuffer.COMMAND_STRIDE_BYTES
		);
	}

	private static double squaredDistanceToChunkCenter(
		ChunkPos chunkPos,
		int minSectionY,
		int sectionsCount,
		double cameraX,
		double cameraY,
		double cameraZ
	) {
		double centerX = (chunkPos.x() * 16.0) + 8.0;
		double centerY = (minSectionY * 16.0) + (sectionsCount * 8.0);
		double centerZ = (chunkPos.z() * 16.0) + 8.0;
		double deltaX = centerX - cameraX;
		double deltaY = centerY - cameraY;
		double deltaZ = centerZ - cameraZ;
		return (deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ);
	}

	private static String describeStat(int value) {
		return value >= 0 ? Integer.toString(value) : "gpu";
	}

	private static int boundaryMask(net.minecraft.core.BlockPos position) {
		int mask = 0;
		int localX = position.getX() & 15;
		int localZ = position.getZ() & 15;
		if (localX == 0) {
			mask |= BOUNDARY_MASK_NEG_X;
		}
		if (localX == 15) {
			mask |= BOUNDARY_MASK_POS_X;
		}
		if (localZ == 0) {
			mask |= BOUNDARY_MASK_NEG_Z;
		}
		if (localZ == 15) {
			mask |= BOUNDARY_MASK_POS_Z;
		}
		return mask;
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

	private record CameraFrameState(
		Matrix4f modelViewMatrix,
		Matrix4f projectionMatrix,
		double cameraX,
		double cameraY,
		double cameraZ,
		float forwardX,
		float forwardY,
		float forwardZ
	) {
	}

	private record VertexPoint(float x, float y, float z) {
	}

	private record BridgeMeshCacheEntry(int firstVertex, int vertexCount, int meshRevision, int[] packedVertices) {
	}

	private record BridgeChunkDraw(BridgeMeshCacheEntry cacheEntry, double distanceSquared) {
	}

	private record SceneFramebufferState(int drawFramebuffer, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
	}

	private record TranslucentDraw(int firstVertex, int vertexCount, double distanceSquared) {
	}
}
