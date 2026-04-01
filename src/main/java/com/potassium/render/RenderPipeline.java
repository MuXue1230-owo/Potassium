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
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.ChunkPos;
import com.potassium.world.data.ChunkData;
import com.potassium.world.data.ChunkSnapshot;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL46C;
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
	private ComputeShader applyChangesShader;
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
	private int debugMeshVertexArray;
	private int oitCompositeVertexArray;
	private int chunkModelViewUniform;
	private int chunkProjectionUniform;
	private int chunkTranslucentModelViewUniform;
	private int chunkTranslucentProjectionUniform;
	private int meshFacesPerChunkUniform;
	private int meshResidentSlotCapacityUniform;
	private int frustumViewProjectionUniform;
	private int occlusionViewProjectionUniform;
	private int occlusionViewportSizeUniform;
	private int occlusionDepthPyramidLevelsUniform;
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
		this.depthPyramid = new DepthPyramid();
		this.translucentOitFramebuffer = new OitFramebuffer();
		this.debugMeshVertexArray = GL45C.glCreateVertexArrays();
		this.oitCompositeVertexArray = GL45C.glCreateVertexArrays();
		this.chunkModelViewUniform = GL20C.glGetUniformLocation(this.chunkProgram.handle(), "uModelViewMatrix");
		this.chunkProjectionUniform = GL20C.glGetUniformLocation(this.chunkProgram.handle(), "uProjectionMatrix");
		this.chunkTranslucentModelViewUniform = GL20C.glGetUniformLocation(this.chunkTranslucentProgram.handle(), "uModelViewMatrix");
		this.chunkTranslucentProjectionUniform = GL20C.glGetUniformLocation(this.chunkTranslucentProgram.handle(), "uProjectionMatrix");

		if (GLCapabilities.hasComputeShader()) {
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
		if (this.residentChunkStateBuffer != null) {
			this.residentChunkStateBuffer.beginFrame();
			this.dispatchPendingGpuWorldChanges();
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
			"chunks=%d resident=%d/%d wdbPages=%d lastUpload=%dB trackedChanges=%d meshJobs=%d/%d meshProcessed=%d meshVertices=%d meshClipped=%d rendered=%d/%d opaqueVisible=%d translucentVisible=%d sampleBlock=%d frustum=%s occlusion=%s",
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
			this.lastVisibleOpaqueMeshChunks,
			this.lastVisibleTranslucentMeshChunks,
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
		this.lastVisibleOpaqueMeshChunks = 0;
		this.lastVisibleTranslucentMeshChunks = 0;
		this.pendingGpuChangeCount = 0;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		this.worldDataEvictions = 0;
		this.pendingGpuWorldChanges.clear();

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
		if (!this.initialized || this.meshMetadataBuffer == null || this.meshVertexBuffer == null || this.chunkProgram == null) {
			return;
		}
		if (cameraState == null || modelViewMatrixState == null) {
			return;
		}

		this.lastRenderedMeshChunks = 0;
		this.lastRenderedMeshVertices = 0;
		this.lastVisibleOpaqueMeshChunks = 0;
		this.lastVisibleTranslucentMeshChunks = 0;

		Matrix4f modelViewMatrix = new Matrix4f(modelViewMatrixState);
		Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);
		this.useChunkProgram(this.chunkProgram, this.chunkModelViewUniform, this.chunkProjectionUniform, modelViewMatrix, projectionMatrix);

		GL30C.glBindVertexArray(this.debugMeshVertexArray);
		this.meshVertexBuffer.bindArrayBuffer();
		GL30C.glEnableVertexAttribArray(0);
		GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_INT, MeshVertexBuffer.BYTES_PER_VERTEX, 0L);

		if (this.hasGpuDrivenDrawPath()) {
			int sceneDrawFramebuffer;
			int viewportX;
			int viewportY;
			int viewportWidth;
			int viewportHeight;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer viewport = stack.mallocInt(4);
				GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
				sceneDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
				viewportX = viewport.get(0);
				viewportY = viewport.get(1);
				viewportWidth = viewport.get(2);
				viewportHeight = viewport.get(3);
			}

			int commandCount = this.dispatchGpuDrivenVisibilityCulling(
				sceneDrawFramebuffer,
				viewportX,
				viewportY,
				viewportWidth,
				viewportHeight,
				projectionMatrix,
				modelViewMatrix
			);
			GL11C.glDisable(GL11C.GL_BLEND);
			GL11C.glDepthMask(true);
			GL11C.glEnable(GL11C.GL_CULL_FACE);
			this.drawGpuDrivenOpaqueMeshes(commandCount);

			if (this.hasGpuDrivenTranslucentOitPath()) {
				this.renderGpuDrivenTranslucentOit(
					commandCount,
					sceneDrawFramebuffer,
					viewportX,
					viewportY,
					viewportWidth,
					viewportHeight,
					modelViewMatrix,
					projectionMatrix
				);
			} else {
				GL11C.glEnable(GL11C.GL_BLEND);
				GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
				GL11C.glDepthMask(false);
				GL11C.glDisable(GL11C.GL_CULL_FACE);
				this.drawGpuDrivenTranslucentMeshes(commandCount);
			}

			if (this.drawCommandCountBuffer != null) {
				DrawCommandCountBuffer.Counts commandCounts = this.drawCommandCountBuffer.read();
				this.lastVisibleOpaqueMeshChunks = commandCounts.opaqueCount();
				this.lastVisibleTranslucentMeshChunks = commandCounts.translucentCount();
				this.lastRenderedMeshChunks = commandCounts.opaqueCount() + commandCounts.translucentCount();
			}
		} else {
			IntBuffer metadata = this.meshMetadataBuffer.readEntries();
			if (metadata.remaining() < this.meshMetadataBuffer.capacityEntries() * MeshMetadataBuffer.INTS_PER_ENTRY) {
				GL30C.glDisableVertexAttribArray(0);
				GL30C.glBindVertexArray(0);
				GL20C.glUseProgram(0);
				return;
			}

			this.frustumCuller.update(projectionMatrix, modelViewMatrix);
			ArrayList<TranslucentDraw> translucentDraws = new ArrayList<>();
			this.populateVisibleDraws(metadata, translucentDraws, cameraState);

			GL11C.glDisable(GL11C.GL_BLEND);
			GL11C.glDepthMask(true);
			GL11C.glEnable(GL11C.GL_CULL_FACE);
			this.drawOpaqueDebugMeshes();

			GL11C.glEnable(GL11C.GL_BLEND);
			GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);
			GL11C.glDepthMask(false);
			GL11C.glDisable(GL11C.GL_CULL_FACE);
			this.drawTranslucentDebugMeshes(translucentDraws);
		}

		GL11C.glDepthMask(true);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glEnable(GL11C.GL_CULL_FACE);
		GL11C.glDisable(GL11C.GL_BLEND);
		GL30C.glDisableVertexAttribArray(0);
		GL30C.glBindVertexArray(0);
		GL20C.glUseProgram(0);
	}

	@Override
	public void close() {
		closeQuietly(this.translucentOitFramebuffer);
		closeQuietly(this.depthPyramid);
		closeQuietly(this.depthDownsampleShader);
		closeQuietly(this.depthCopyShader);
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
		this.occlusionViewProjectionUniform = -1;
		this.occlusionViewportSizeUniform = -1;
		this.occlusionDepthPyramidLevelsUniform = -1;
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
		this.meshGenerationStatsBuffer.resetAndBind(MESH_STATS_BINDING);
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
		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_BUFFER_UPDATE_BARRIER_BIT);

		MeshGenerationStatsBuffer.Stats stats = this.meshGenerationStatsBuffer.read();
		this.lastMeshGenerationProcessedJobs = stats.processedJobs();
		this.lastMeshGenerationGeneratedVertices = stats.generatedVertices();
		this.lastMeshGenerationClippedJobs = stats.clippedJobs();
		this.lastMeshGenerationSampledPackedBlock = stats.lastSampledPackedBlock();
	}

	private boolean hasMeshGenerationBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= MESH_GENERATION_REQUIRED_SSBO_BINDINGS;
	}

	private boolean hasGpuChangeApplyBindingBudget() {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		return maxBindings <= 0 || maxBindings >= APPLY_CHANGES_REQUIRED_SSBO_BINDINGS;
	}

	private boolean hasGpuChangeApplyPath() {
		return this.applyChangesShader != null
			&& this.changeQueueBuffer != null
			&& this.residentChunkStateBuffer != null
			&& this.worldDataBuffer != null;
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
		this.drawCommandCountBuffer.resetAndBind(DRAW_COMMAND_COUNT_BINDING);
		this.frustumCullingShader.use();
		if (this.frustumViewProjectionUniform >= 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				FloatBuffer buffer = stack.mallocFloat(16);
				viewProjectionMatrix.get(buffer);
				GL20C.glUniformMatrix4fv(this.frustumViewProjectionUniform, false, buffer);
			}
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

		this.buildDepthPyramid(sceneDrawFramebuffer, sceneViewportX, sceneViewportY, sceneViewportWidth, sceneViewportHeight);
		return this.dispatchGpuDrivenOcclusionCulling(sceneViewportWidth, sceneViewportHeight, projectionMatrix, modelViewMatrix);
	}

	private void buildDepthPyramid(
		int sceneDrawFramebuffer,
		int sceneViewportX,
		int sceneViewportY,
		int sceneViewportWidth,
		int sceneViewportHeight
	) {
		if (this.depthPyramid == null || this.depthCopyShader == null || this.depthDownsampleShader == null) {
			return;
		}

		this.depthPyramid.ensureSize(sceneViewportWidth, sceneViewportHeight);
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
		this.drawCommandCountBuffer.resetAndBind(DRAW_COMMAND_COUNT_BINDING);
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

	private void renderGpuDrivenTranslucentOit(
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
			return;
		}

		this.translucentOitFramebuffer.ensureSize(sceneViewportWidth, sceneViewportHeight);
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

	private record TranslucentDraw(int firstVertex, int vertexCount, double distanceSquared) {
	}
}
