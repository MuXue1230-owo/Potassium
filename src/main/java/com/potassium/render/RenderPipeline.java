package com.potassium.render;

import com.potassium.core.PotassiumConfig;
import com.potassium.core.PotassiumLogger;
import com.potassium.gl.DepthPyramid;
import com.potassium.gl.buffer.ChangeQueueBuffer;
import com.potassium.gl.buffer.DrawCommandCountBuffer;
import com.potassium.gl.GLCapabilities;
import com.potassium.gl.OitFramebuffer;
import com.potassium.gl.buffer.IndirectCommandBuffer;
import com.potassium.gl.buffer.MaterialTableBuffer;
import com.potassium.gl.buffer.MeshGenerationStatsBuffer;
import com.potassium.gl.buffer.MeshMetadataBuffer;
import com.potassium.gl.buffer.MeshVertexBuffer;
import com.potassium.gl.buffer.ResidentChunkStateBuffer;
import com.potassium.gl.buffer.VertexBuffer;
import com.potassium.gl.buffer.WorldDataBuffer;
import com.potassium.render.culling.FrustumCuller;
import com.potassium.render.culling.OcclusionCuller;
import com.potassium.render.material.BlockMaterialTable;
import com.potassium.render.shader.ComputeShader;
import com.potassium.render.shader.ShaderProgram;
import com.potassium.world.ChunkManager;
import com.potassium.world.MemoryManager;
import com.potassium.world.WorldChangeTracker;
import com.potassium.world.data.BlockData;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import com.potassium.world.data.ChunkData;
import com.potassium.world.data.ChunkSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class RenderPipeline implements AutoCloseable {
	private static final boolean ENABLE_EXPERIMENTAL_GPU_TERRAIN_REPLACEMENT = true;
	private static final boolean ENABLE_GPU_TERRAIN_SUBMISSION = true;
	private static final boolean ENABLE_SCREEN_PROBE = false;
	private static final int VERTEX_CONSUMER_TERRAIN_BRIDGE_MAX_CHUNKS = 96;
	private static final int BRIDGE_FULL_BRIGHT_LIGHT = 0x00F000F0;
	private static final long MEBIBYTE_BYTES = 1024L * 1024L;
	private static final int INVALID_RESIDENT_SLOT = -1;
	private static final int MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET = 0;
	private static final int MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET = 1;
	private static final int MESH_METADATA_CUTOUT_VERTEX_COUNT_OFFSET = 2;
	private static final int MESH_METADATA_CUTOUT_FIRST_VERTEX_OFFSET = 3;
	private static final int MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET = 4;
	private static final int MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET = 5;
	private static final int MESH_METADATA_MESH_REVISION_OFFSET = 8;
	private static final int WORLD_DATA_BINDING = 0;
	private static final int RESIDENT_CHUNK_STATE_BINDING = WORLD_DATA_BINDING + 1 + WorldDataBuffer.MAX_SHADER_PAGES;
	private static final int MESH_STATS_BINDING = RESIDENT_CHUNK_STATE_BINDING + 1;
	private static final int MESH_METADATA_BINDING = MESH_STATS_BINDING + 1;
	private static final int MESH_VERTEX_BINDING = MESH_METADATA_BINDING + 1;
	private static final int OPAQUE_COMMAND_BINDING = MESH_VERTEX_BINDING + 1;
	private static final int TRANSLUCENT_COMMAND_BINDING = OPAQUE_COMMAND_BINDING + 1;
	private static final int DRAW_COMMAND_COUNT_BINDING = TRANSLUCENT_COMMAND_BINDING + 1;
	private static final int CHANGE_QUEUE_BINDING = DRAW_COMMAND_COUNT_BINDING + 1;
	private static final int MATERIAL_TABLE_BINDING = CHANGE_QUEUE_BINDING + 1;
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
	// Material table binding for mesh generation — matches binding = 13 in material_table.glsl
	private static final int MESH_MATERIAL_TABLE_BINDING = 13;
	private static final int MESH_GENERATION_REQUIRED_SSBO_BINDINGS = MESH_MATERIAL_TABLE_BINDING + 1;
	private static final int FRUSTUM_CULLING_REQUIRED_SSBO_BINDINGS = DRAW_COMMAND_COUNT_BINDING + 1;
	private static final int APPLY_CHANGES_REQUIRED_SSBO_BINDINGS = CHANGE_QUEUE_BINDING + 1;

	private final PotassiumConfig config;
	private final ChunkManager chunkManager;
	private final WorldChangeTracker worldChangeTracker;
	private final FrustumCuller frustumCuller;
	private final OcclusionCuller occlusionCuller;
	private final MemoryManager memoryManager = new MemoryManager();
	private final IntOpenHashSet dirtyMeshSlots = new IntOpenHashSet();
	private final HashMap<Integer, TerrainSubmissionCacheEntry> opaqueTerrainSubmissionCache = new HashMap<>();
	private int[] lastCpuMetadataSnapshot; // CPU snapshot of mesh metadata, updated when mesh generation fence signals
	private final HashMap<Integer, BridgeMeshCacheEntry> bridgeMeshCache = new HashMap<>();
	private final HashMap<Integer, BridgeMeshCacheEntry> bridgeTranslucentMeshCache = new HashMap<>();
	private final HashMap<Integer, BridgeMaterial> bridgeMaterialCache = new HashMap<>();

	private WorldDataBuffer worldDataBuffer;
	private IndirectCommandBuffer indirectCommandBuffer;
	private IndirectCommandBuffer translucentIndirectCommandBuffer;
	private ChangeQueueBuffer changeQueueBuffer;
	private DrawCommandCountBuffer drawCommandCountBuffer;
	private ResidentChunkStateBuffer residentChunkStateBuffer;
	private MeshGenerationStatsBuffer meshGenerationStatsBuffer;
	private MeshMetadataBuffer meshMetadataBuffer;
	private MeshVertexBuffer meshVertexBuffer;
	private MaterialTableBuffer materialTableBuffer;
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
	private BlockMaterialTable blockMaterialTable = BlockMaterialTable.empty();
	private long lastUploadedWorldBytes;
	private int lastSyncedChangeCount;
	private int lastMeshGenerationJobs;
	private int lastMeshGenerationProcessedJobs;
	private int lastMeshGenerationDirtyCandidates;
	private int lastMeshGenerationGeneratedVertices;
	private int lastMeshGenerationClippedJobs;
	private int lastMeshGenerationSampledPackedBlock;
	private long meshGenerationSync = 0; // GL sync object handle for mesh generation fence
	private int worldDataBufferFullFailures;
	private int worldDataBufferExpansionFailures;
	private int worldDataEvictions;
	private int lastRenderedMeshChunks;
	private int lastRenderedMeshVertices;
	private int lastVisibleOpaqueMeshChunks;
	private int lastVisibleTranslucentMeshChunks;
	private int pendingGpuChangeCount;
	private boolean terrainMeshReady;
	private boolean pendingInitialMeshGeneration = true;
	private int dispatchedSlotCount;
	private int targetInitialChunkCount;
	private boolean initialMeshGpuDone;
	private int lastCheckedResidentCount;
	private boolean loggedExperimentalTerrainReplacementDisabled;
	private boolean loggedTerrainSubmissionFailure;
	private boolean loggedTerrainSubmissionCount;
	private boolean loggedBridgeMaterialFallback;
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

	private int dynamicVramBudgetMiB = 0; // 存储动态预算上限

	/**
	 * 根据显存大小动态计算预算上限
	 * 策略：<8G 全占满，8G~16G 占75%，16G+ 占50%
	 */
	private int calculateDynamicVramBudget(int availableVramMiB) {
		if (availableVramMiB <= 0) {
			return 0;
		}

		int budgetMiB;
		if (availableVramMiB < 8192) {
			// <8G: 全占满（仅留 1G 给驱动/系统）
			budgetMiB = Math.max(availableVramMiB - 1024, 256);
		} else if (availableVramMiB < 16384) {
			// 8G~16G: 占75%
			budgetMiB = (int) (availableVramMiB * 0.75);
		} else {
			// 16G+: 占50%
			budgetMiB = (int) (availableVramMiB * 0.5);
		}

		PotassiumLogger.logger().info(
			"Dynamic VRAM budget calculated: {} MiB (available={} MiB, rule={})",
			budgetMiB,
			availableVramMiB,
			availableVramMiB < 8192 ? "<8G full" : (availableVramMiB < 16384 ? "8-16G 75%" : "16G+ 50%")
		);

		return budgetMiB;
	}

	public void initialize() {
		if (this.initialized) {
			return;
		}

		// 计算动态预算上限（但不立即分配）
		int availableVramMiB = GLCapabilities.getEstimatedAvailableVideoMemoryMiB();
		if (availableVramMiB > 0) {
			this.dynamicVramBudgetMiB = calculateDynamicVramBudget(availableVramMiB);

			// 自动更新 maxResidentWorldMiB，避免配置值限制动态预算
			if (this.dynamicVramBudgetMiB > this.config.memory.maxResidentWorldMiB) {
				PotassiumLogger.logger().info(
					"Auto-adjusting maxResidentWorldMiB from {} to {} to match GPU VRAM ({} MiB)",
					this.config.memory.maxResidentWorldMiB,
					this.dynamicVramBudgetMiB,
					availableVramMiB
				);
				this.config.memory.maxResidentWorldMiB = this.dynamicVramBudgetMiB;
			}
		}

		// 初始分配较小值（256MB），按需扩展
		int initialBudgetMiB = 256;
		boolean usePersistentMapping = this.config.general.enablePersistentMapping && GLCapabilities.hasPersistentMapping();
		this.worldDataBuffer = new WorldDataBuffer(toBytes(initialBudgetMiB), usePersistentMapping);
		this.indirectCommandBuffer = new IndirectCommandBuffer(this.config.memory.indirectCommandCapacity, false);
		this.translucentIndirectCommandBuffer = new IndirectCommandBuffer(this.config.memory.indirectCommandCapacity, false);
		this.vertexBuffer = new VertexBuffer();
		this.materialTableBuffer = new MaterialTableBuffer(1, false);
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
		this.materialTableBuffer.upload(this.blockMaterialTable);

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

	private int packWorldBlock(BlockPos position, BlockState state) {
		if (state == null || state.isAir()) {
			return BlockData.AIR_PACKED;
		}

		if (this.activeLevel == null) {
			return BlockData.fromState(state).packed();
		}

		return BlockData.fromState(
			state,
			this.activeLevel.getBrightness(LightLayer.BLOCK, position),
			this.activeLevel.getBrightness(LightLayer.SKY, position)
		).packed();
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

			int packedBlock = this.packWorldBlock(change.position(), change.newState());
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
					this.packWorldBlock(change.position(), change.newState()),
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

	public void updateBlockMaterialTable(BlockMaterialTable blockMaterialTable) {
		this.blockMaterialTable = blockMaterialTable == null ? BlockMaterialTable.empty() : blockMaterialTable;
		if (!this.initialized || this.materialTableBuffer == null) {
			return;
		}

		this.materialTableBuffer.upload(this.blockMaterialTable);
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
		this.pendingInitialMeshGeneration = true;
		this.dispatchedSlotCount = 0;
		this.targetInitialChunkCount = 0;
		this.initialMeshGpuDone = false;
		this.worldDataBufferFullFailures = 0;
		this.worldDataBufferExpansionFailures = 0;
		this.worldDataEvictions = 0;
		this.pendingGpuWorldChanges.clear();
		this.opaqueTerrainSubmissionCache.clear();
		this.lastCpuMetadataSnapshot = null;
		this.bridgeMeshCache.clear();
		this.bridgeTranslucentMeshCache.clear();
		this.bridgeMaterialCache.clear();
		this.loggedExperimentalTerrainReplacementDisabled = false;
		this.loggedTerrainSubmissionCount = false;
		this.loggedVertexConsumerTerrainBridge = false;
		this.loggedVertexConsumerTranslucentTerrainBridge = false;
		this.loggedBridgeMaterialFallback = false;

		if (level == null) {
			return;
		}

		int minSectionY = level.getMinSectionY();
		int sectionsCount = level.getSectionsCount();
		this.worldDataBuffer.configure(minSectionY, sectionsCount);
		this.memoryManager.configure(this.effectiveResidentWorldBudgetBytes(), this.worldDataBuffer.bytesPerChunk());
		this.ensureMeshOutputCapacity(true);
		this.ensureIndirectCommandCapacity();

		PotassiumLogger.logger().info(
			"Configured world data buffer for level layout: minSectionY={}, sections={}, bytesPerChunk={}, residentChunkCapacity={}, meshResidentChunkCapacity={}, pages={}, targetPageBytes={}",
			minSectionY,
			sectionsCount,
			this.worldDataBuffer.bytesPerChunk(),
			this.worldDataBuffer.capacityBytes() / Math.max(this.worldDataBuffer.bytesPerChunk(), 1L),
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
		this.dirtyMeshSlots.add(residentSlot);
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
		this.dirtyMeshSlots.remove(residentSlot);
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

	public boolean renderOpaqueTerrain(
		CameraRenderState cameraState,
		Matrix4fc modelViewMatrix,
		ChunkSectionsToRender vanillaChunkSectionsToRender,
		ChunkSectionLayerGroup chunkSectionLayerGroup,
		GpuSampler gpuSampler
	) {
		if (cameraState == null || modelViewMatrix == null || vanillaChunkSectionsToRender == null || gpuSampler == null) {
			return false;
		}

		if (!ENABLE_EXPERIMENTAL_GPU_TERRAIN_REPLACEMENT) {
			this.logExperimentalTerrainReplacementDisabledOnce();
			return false;
		}

		if (chunkSectionLayerGroup != ChunkSectionLayerGroup.OPAQUE) {
			return false;
		}

		if (!this.canBuildMinecraftTerrainSubmission()) {
			this.logTerrainSubmissionFailureOnce();
			return false;
		}

		try {
			ChunkSectionsToRender potassiumTerrain = this.buildOpaqueChunkSectionsToRender(modelViewMatrix, vanillaChunkSectionsToRender.textureView());
			if (potassiumTerrain == null) {
				PotassiumLogger.logger().warn("Falling back to vanilla opaque terrain because buildOpaqueChunkSectionsToRender returned null.");
				return false;
			}

			if (!this.loggedTerrainSubmissionCount) {
				PotassiumLogger.logger().info(
					"[Potassium GPU render] residentChunks={}, terrainMeshReady={}, renderedChunks={}, lastProcessedJobs={}, lastGeneratedVerts={}",
					this.memoryManager.residentChunks(),
					this.terrainMeshReady,
					this.lastVisibleOpaqueMeshChunks,
					this.lastMeshGenerationProcessedJobs,
					this.lastMeshGenerationGeneratedVertices
				);
				this.loggedTerrainSubmissionCount = true;
			}

			potassiumTerrain.renderGroup(chunkSectionLayerGroup, gpuSampler);
			return true;
		} catch (RuntimeException exception) {
			PotassiumLogger.logger().warn(
				"Falling back to vanilla opaque terrain because Minecraft terrain submission failed: {}",
				exception.getMessage()
			);
			return false;
		}
	}

	public boolean renderTranslucentTerrain(
		CameraRenderState cameraState,
		Matrix4fc modelViewMatrix,
		ChunkSectionsToRender vanillaChunkSectionsToRender,
		ChunkSectionLayerGroup chunkSectionLayerGroup,
		GpuSampler gpuSampler
	) {
		if (cameraState == null || modelViewMatrix == null || vanillaChunkSectionsToRender == null || gpuSampler == null) {
			return false;
		}

		if (!ENABLE_EXPERIMENTAL_GPU_TERRAIN_REPLACEMENT) {
			return false;
		}

		if (chunkSectionLayerGroup != ChunkSectionLayerGroup.TRANSLUCENT) {
			return false;
		}

		if (!this.canBuildMinecraftTerrainSubmission()) {
			return false;
		}

		try {
			ChunkSectionsToRender potassiumTerrain = this.buildTranslucentChunkSectionsToRender(
				cameraState,
				modelViewMatrix,
				vanillaChunkSectionsToRender.textureView()
			);
			if (potassiumTerrain == null) {
				return false;
			}

			potassiumTerrain.renderGroup(chunkSectionLayerGroup, gpuSampler);
			return true;
		} catch (RuntimeException exception) {
			PotassiumLogger.logger().warn(
				"Falling back to vanilla translucent terrain because Minecraft terrain submission failed: {}",
				exception.getMessage()
			);
			return false;
		}
	}

	private boolean canBuildMinecraftTerrainSubmission() {
		return this.initialized
			&& this.worldDataBuffer != null
			&& this.worldDataBuffer.isConfigured()
			&& this.materialTableBuffer != null
			&& this.blockMaterialTable != null
			&& !this.blockMaterialTable.isEmpty()
			&& this.meshMetadataBuffer != null
			&& this.meshVertexBuffer != null
			&& this.meshVertexBuffer.gpuBuffer() != null
			&& this.memoryManager.residentChunks() > 0
			&& this.terrainMeshReady;
	}

	private ChunkSectionsToRender buildOpaqueChunkSectionsToRender(Matrix4fc terrainModelViewMatrix, GpuTextureView textureView) {
		if (textureView == null || RenderSystem.getDynamicUniforms() == null) {
			return null;
		}

		EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer =
			new EnumMap<>(ChunkSectionLayer.class);
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			drawGroupsPerLayer.put(layer, new Int2ObjectOpenHashMap<>());
		}

		Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> solidDrawGroups = drawGroupsPerLayer.get(ChunkSectionLayer.SOLID);
		Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> cutoutDrawGroups = drawGroupsPerLayer.get(ChunkSectionLayer.CUTOUT);
		ArrayList<DynamicUniforms.ChunkSectionInfo> chunkSectionInfos = new ArrayList<>();
		int atlasWidth = textureView.getWidth(0);
		int atlasHeight = textureView.getHeight(0);
		int minBlockY = this.worldDataBuffer.minSectionY() << 4;
		int maxIndicesRequired = 0;
		int baseDrawGroupKey = 173;
		if (this.meshVertexBuffer.gpuBuffer() != null) {
			baseDrawGroupKey = (31 * baseDrawGroupKey) + this.meshVertexBuffer.gpuBuffer().hashCode();
		}

		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}

			TerrainSubmissionCacheEntry solidEntry = this.resolveTerrainSubmissionEntry(
				chunkData,
				MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET,
				MESH_METADATA_OPAQUE_FIRST_VERTEX_OFFSET
			);
			TerrainSubmissionCacheEntry cutoutEntry = this.resolveTerrainSubmissionEntry(
				chunkData,
				MESH_METADATA_CUTOUT_VERTEX_COUNT_OFFSET,
				MESH_METADATA_CUTOUT_FIRST_VERTEX_OFFSET
			);
			if (solidEntry == null && cutoutEntry == null) {
				continue;
			}

			int chunkInfoIndex = chunkSectionInfos.size();
			chunkSectionInfos.add(
				this.createChunkSectionInfo(chunkData, terrainModelViewMatrix, atlasWidth, atlasHeight, minBlockY)
			);
			if (solidEntry != null) {
				maxIndicesRequired = Math.max(maxIndicesRequired, solidEntry.indexCount());
			}
			if (cutoutEntry != null) {
				maxIndicesRequired = Math.max(maxIndicesRequired, cutoutEntry.indexCount());
			}
			final int uploaderChunkInfoIndex = chunkInfoIndex;
			if (solidEntry != null) {
				solidDrawGroups.computeIfAbsent(baseDrawGroupKey, ignored -> new ArrayList<>())
					.add(
						new RenderPass.Draw<>(
							0,
							this.meshVertexBuffer.gpuBuffer(),
							null,
							null,
							0,
							solidEntry.indexCount(),
							solidEntry.firstVertex(),
							(slices, uploader) -> uploader.upload("ChunkSection", slices[uploaderChunkInfoIndex])
						)
					);
			}
			if (cutoutEntry != null) {
				cutoutDrawGroups.computeIfAbsent(baseDrawGroupKey, ignored -> new ArrayList<>())
					.add(
						new RenderPass.Draw<>(
							0,
							this.meshVertexBuffer.gpuBuffer(),
							null,
							null,
							0,
							cutoutEntry.indexCount(),
							cutoutEntry.firstVertex(),
							(slices, uploader) -> uploader.upload("ChunkSection", slices[uploaderChunkInfoIndex])
						)
					);
			}
		}

		if (chunkSectionInfos.isEmpty()) {
			return null;
		}

		GpuBufferSlice[] chunkInfoSlices = RenderSystem.getDynamicUniforms().writeChunkSections(
			chunkSectionInfos.toArray(DynamicUniforms.ChunkSectionInfo[]::new)
		);
		return new ChunkSectionsToRender(textureView, drawGroupsPerLayer, maxIndicesRequired, chunkInfoSlices);
	}

	private ChunkSectionsToRender buildTranslucentChunkSectionsToRender(
		CameraRenderState cameraState,
		Matrix4fc terrainModelViewMatrix,
		GpuTextureView textureView
	) {
		if (cameraState == null || textureView == null || RenderSystem.getDynamicUniforms() == null) {
			return null;
		}

		EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>>> drawGroupsPerLayer =
			new EnumMap<>(ChunkSectionLayer.class);
		for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
			drawGroupsPerLayer.put(layer, new Int2ObjectOpenHashMap<>());
		}

		Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> translucentDrawGroups =
			drawGroupsPerLayer.get(ChunkSectionLayer.TRANSLUCENT);
		ArrayList<SortedTerrainSubmission> translucentSubmissions = new ArrayList<>();
		int atlasWidth = textureView.getWidth(0);
		int atlasHeight = textureView.getHeight(0);
		int minBlockY = this.worldDataBuffer.minSectionY() << 4;
		int maxIndicesRequired = 0;
		int baseDrawGroupKey = 173;
		if (this.meshVertexBuffer.gpuBuffer() != null) {
			baseDrawGroupKey = (31 * baseDrawGroupKey) + this.meshVertexBuffer.gpuBuffer().hashCode();
		}

		double cameraX = cameraState.pos.x;
		double cameraY = cameraState.pos.y;
		double cameraZ = cameraState.pos.z;
		int visibleMinSectionY = this.worldDataBuffer.minSectionY();
		int visibleSectionsCount = this.worldDataBuffer.sectionsCount();

		for (ChunkData chunkData : this.chunkManager.chunks()) {
			if (!chunkData.isResident()) {
				continue;
			}

			TerrainSubmissionCacheEntry translucentEntry = this.resolveTerrainSubmissionEntry(
				chunkData,
				MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET,
				MESH_METADATA_TRANSLUCENT_FIRST_VERTEX_OFFSET
			);
			if (translucentEntry == null) {
				continue;
			}

			double distanceSquared = squaredDistanceToChunkCenter(
				chunkData.chunkPos(),
				visibleMinSectionY,
				visibleSectionsCount,
				cameraX,
				cameraY,
				cameraZ
			);
			maxIndicesRequired = Math.max(maxIndicesRequired, translucentEntry.indexCount());
			translucentSubmissions.add(
				new SortedTerrainSubmission(
					translucentEntry,
					distanceSquared,
					this.createChunkSectionInfo(chunkData, terrainModelViewMatrix, atlasWidth, atlasHeight, minBlockY)
				)
			);
		}

		if (translucentSubmissions.isEmpty()) {
			return null;
		}

		translucentSubmissions.sort(Comparator.comparingDouble(SortedTerrainSubmission::distanceSquared));

		ArrayList<DynamicUniforms.ChunkSectionInfo> chunkSectionInfos = new ArrayList<>(translucentSubmissions.size());
		List<RenderPass.Draw<GpuBufferSlice[]>> translucentDraws = translucentDrawGroups.computeIfAbsent(
			baseDrawGroupKey,
			ignored -> new ArrayList<>()
		);
		for (SortedTerrainSubmission submission : translucentSubmissions) {
			int chunkInfoIndex = chunkSectionInfos.size();
			chunkSectionInfos.add(submission.chunkSectionInfo());
			TerrainSubmissionCacheEntry entry = submission.entry();
			final int uploaderChunkInfoIndex = chunkInfoIndex;
			translucentDraws.add(
				new RenderPass.Draw<>(
					0,
					this.meshVertexBuffer.gpuBuffer(),
					null,
					null,
					0,
					entry.indexCount(),
					entry.firstVertex(),
					(slices, uploader) -> uploader.upload("ChunkSection", slices[uploaderChunkInfoIndex])
				)
			);
		}

		GpuBufferSlice[] chunkInfoSlices = RenderSystem.getDynamicUniforms().writeChunkSections(
			chunkSectionInfos.toArray(DynamicUniforms.ChunkSectionInfo[]::new)
		);
		return new ChunkSectionsToRender(textureView, drawGroupsPerLayer, maxIndicesRequired, chunkInfoSlices);
	}

	private TerrainSubmissionCacheEntry resolveTerrainSubmissionEntry(ChunkData chunkData, int vertexCountOffset, int firstVertexOffset) {
		int residentSlot = chunkData.residentSlot();
		if (residentSlot < 0) {
			return null;
		}
		if (this.lastCpuMetadataSnapshot == null) {
			return null;
		}
		if (this.meshMetadataBuffer == null) {
			return null;
		}

		int slotMeshRevision = this.residentChunkStateBuffer.meshRevision(residentSlot);
		int cacheKey = (residentSlot << 4) | vertexCountOffset;
		TerrainSubmissionCacheEntry cachedEntry = this.opaqueTerrainSubmissionCache.get(cacheKey);
		if (cachedEntry != null && cachedEntry.meshRevision() == slotMeshRevision && cachedEntry.vertexCountOffset() == vertexCountOffset) {
			return cachedEntry.vertexCount() > 0 ? cachedEntry : null;
		}

		// Read from CPU snapshot (no GPU readback)
		int metadataOffset = residentSlot * MeshMetadataBuffer.INTS_PER_ENTRY;
		if (metadataOffset + MeshMetadataBuffer.INTS_PER_ENTRY > this.lastCpuMetadataSnapshot.length) {
			return null;
		}

		int vertexCount = Math.max(this.lastCpuMetadataSnapshot[metadataOffset + vertexCountOffset], 0);
		vertexCount -= vertexCount % MeshVertexBuffer.VERTICES_PER_FACE;
		int firstVertex = Math.max(this.lastCpuMetadataSnapshot[metadataOffset + firstVertexOffset], 0);
		int metadataMeshRevision = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_MESH_REVISION_OFFSET];
		TerrainSubmissionCacheEntry refreshedEntry = new TerrainSubmissionCacheEntry(
			firstVertex,
			vertexCount,
			metadataMeshRevision,
			vertexCountOffset
		);
		if (vertexCount > 0) {
			this.opaqueTerrainSubmissionCache.put(cacheKey, refreshedEntry);
			return refreshedEntry;
		}

		this.opaqueTerrainSubmissionCache.remove(cacheKey);
		return null;
	}

	private DynamicUniforms.ChunkSectionInfo createChunkSectionInfo(
		ChunkData chunkData,
		Matrix4fc terrainModelViewMatrix,
		int atlasWidth,
		int atlasHeight,
		int minBlockY
	) {
		return new DynamicUniforms.ChunkSectionInfo(
			new Matrix4f(terrainModelViewMatrix),
			chunkData.chunkPos().x() << 4,
			minBlockY,
			chunkData.chunkPos().z() << 4,
			1.0f,
			atlasWidth,
			atlasHeight
		);
	}

	private boolean submitVertexConsumerTerrainBridge(SubmitNodeStorage submitNodeStorage, CameraFrameState cameraFrameState) {
		// 桥接路径已废弃：Minecraft 26.1 的模型系统已完全重构，且 CPU fallback 路径与架构原则不符
		return false;
	}

	private boolean submitVertexConsumerTranslucentTerrainBridge(SubmitNodeStorage submitNodeStorage, CameraFrameState cameraFrameState) {
		return false;
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
			int color = bridgeColorArgb(packedBlock, translucent);
			BridgeMaterial material = this.resolveBridgeMaterial(packedBlock);

			VertexPoint a = readVertexPoint(chunkVertices, faceBase, cameraFrameState);
			VertexPoint b = readVertexPoint(chunkVertices, faceBase + MeshVertexBuffer.UINTS_PER_VERTEX, cameraFrameState);
			VertexPoint c = readVertexPoint(chunkVertices, faceBase + (MeshVertexBuffer.UINTS_PER_VERTEX * 2), cameraFrameState);
			VertexPoint d = readVertexPoint(chunkVertices, faceBase + (MeshVertexBuffer.UINTS_PER_VERTEX * 5), cameraFrameState);

			Vector3f ab = new Vector3f(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
			Vector3f ac = new Vector3f(c.x() - a.x(), c.y() - a.y(), c.z() - a.z());
			Vector3f normal = ab.cross(ac);
			if (normal.lengthSquared() <= 1.0e-6f) {
				normal.set(0.0f, 1.0f, 0.0f);
			} else {
				normal.normalize();
			}

			this.emitQuadVertex(vertexConsumer, pose, a, color, material.u0(), material.v1(), normal);
			this.emitQuadVertex(vertexConsumer, pose, b, color, material.u0(), material.v0(), normal);
			this.emitQuadVertex(vertexConsumer, pose, c, color, material.u1(), material.v0(), normal);
			this.emitQuadVertex(vertexConsumer, pose, d, color, material.u1(), material.v1(), normal);
		}
	}

	private BridgeMaterial resolveBridgeMaterial(int packedBlock) {
		int stateId = BlockData.stateId(packedBlock);
		BridgeMaterial cachedMaterial = this.bridgeMaterialCache.get(stateId);
		if (cachedMaterial != null) {
			return cachedMaterial;
		}

		BlockState state = Block.stateById(stateId);
		TextureAtlasSprite sprite = this.resolveBridgeParticleSprite(state);
		BridgeMaterial material = new BridgeMaterial(
			sprite.getU0(),
			sprite.getU1(),
			sprite.getV0(),
			sprite.getV1()
		);
		this.bridgeMaterialCache.put(stateId, material);
		return material;
	}

	private TextureAtlasSprite resolveBridgeParticleSprite(BlockState state) {
		try {
			TextureAtlasSprite sprite = this.tryGetParticleSprite(state);
			if (sprite != null) {
				return sprite;
			}
		} catch (Exception exception) {
			PotassiumLogger.logger().debug("Failed to get particle sprite for block state {}: {}", state, exception.getMessage());
		}
		
		TextureAtlasSprite fallbackSprite = this.resolveMissingBridgeParticleSprite();
		if (fallbackSprite != null) {
			if (!this.loggedBridgeMaterialFallback) {
				PotassiumLogger.logger().warn(
					"Failed to resolve textured bridge sprite for block state {}; falling back to missing texture sprite.",
					state
				);
				this.loggedBridgeMaterialFallback = true;
			}
			return fallbackSprite;
		}
		
		// 最后的回退：使用 stone 的默认材质
		if (!this.loggedBridgeMaterialFallback) {
			PotassiumLogger.logger().warn("Failed to resolve any texture sprite for block state {}; using default stone texture.", state);
			this.loggedBridgeMaterialFallback = true;
		}
		return this.getDefaultStoneSprite();
	}

	private TextureAtlasSprite getDefaultStoneSprite() {
		// 尝试获取 stone 的粒子纹理作为默认值
		try {
			BlockState stoneState = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			TextureAtlasSprite stoneSprite = this.tryGetParticleSprite(stoneState);
			if (stoneSprite != null) {
				return stoneSprite;
			}
		} catch (Exception e) {
			PotassiumLogger.logger().debug("Failed to get default stone sprite: {}", e.getMessage());
		}
		
		// 如果连 stone 都获取不到，抛出错误
		throw new IllegalStateException("Failed to resolve any texture sprite. The model system is completely unavailable.");
	}

	private TextureAtlasSprite tryGetParticleSprite(BlockState state) {
		try {
			Object modelManager = Minecraft.getInstance().getModelManager();
			if (modelManager == null) {
				return null;
			}

			// 尝试方法 1: 通过 BlockModelShaper (旧版本 Minecraft)
			Object blockModelShaper = this.resolveMemberByType(modelManager, "getBlockModelShaper", "BlockModelShaper");
			if (blockModelShaper != null) {
				Method getParticleIcon = this.findMatchingMethodOrNull(
					blockModelShaper.getClass(),
					"getParticleIcon",
					"TextureAtlasSprite",
					state.getClass()
				);
				if (getParticleIcon != null) {
					getParticleIcon.setAccessible(true);
					return (TextureAtlasSprite) getParticleIcon.invoke(blockModelShaper, state);
				}
			}

			// 尝试方法 2: 通过 ModelManager.getModel(state) 获取 BakedModel
			Method getModelMethod = this.findMatchingMethodOrNull(modelManager.getClass(), "getModel", "BakedModel", state.getClass());
			if (getModelMethod != null) {
				getModelMethod.setAccessible(true);
				Object bakedModel = getModelMethod.invoke(modelManager, state);
				if (bakedModel != null) {
					Method getParticleIconMethod = this.findMatchingMethodOrNull(bakedModel.getClass(), "getParticleIcon", "TextureAtlasSprite");
					if (getParticleIconMethod != null) {
						getParticleIconMethod.setAccessible(true);
						return (TextureAtlasSprite) getParticleIconMethod.invoke(bakedModel);
					}
				}
			}

			// 尝试方法 3: 直接通过 BlockState 获取 (如果存在)
			Method stateParticleMethod = this.findMatchingMethodOrNull(state.getClass(), "getParticleIcon", "TextureAtlasSprite");
			if (stateParticleMethod != null) {
				stateParticleMethod.setAccessible(true);
				return (TextureAtlasSprite) stateParticleMethod.invoke(state);
			}
		} catch (Exception e) {
			PotassiumLogger.logger().debug("Exception while trying to get particle sprite: {}", e.getMessage());
		}
		return null;
	}

	private TextureAtlasSprite resolveMissingBridgeParticleSprite() {
		// 尝试通过 ModelManager 获取 missing model
		try {
			Object modelManager = Minecraft.getInstance().getModelManager();
			if (modelManager != null) {
				// 尝试方法 1: getMissingBlockStateModel
				Object missingBlockStateModel = this.resolveMemberByType(modelManager, "getMissingBlockStateModel", "BlockStateModel");
				if (missingBlockStateModel != null) {
					Method particleIcon = this.findMatchingMethodOrNull(missingBlockStateModel.getClass(), "particleIcon", "TextureAtlasSprite");
					if (particleIcon != null) {
						particleIcon.setAccessible(true);
						return (TextureAtlasSprite) particleIcon.invoke(missingBlockStateModel);
					}
				}

				// 尝试方法 2: getMissingModel -> BakedModel.getParticleIcon
				Method getMissingModelMethod = this.findMatchingMethodOrNull(modelManager.getClass(), "getMissingModel", "BakedModel");
				if (getMissingModelMethod != null) {
					getMissingModelMethod.setAccessible(true);
					Object missingModel = getMissingModelMethod.invoke(modelManager);
					if (missingModel != null) {
						Method getParticleIconMethod = this.findMatchingMethodOrNull(missingModel.getClass(), "getParticleIcon", "TextureAtlasSprite");
						if (getParticleIconMethod != null) {
							getParticleIconMethod.setAccessible(true);
							return (TextureAtlasSprite) getParticleIconMethod.invoke(missingModel);
						}
					}
				}
			}
		} catch (Exception e) {
			PotassiumLogger.logger().debug("Exception while trying to get missing texture sprite from ModelManager: {}", e.getMessage());
		}

		// 最终回退：创建一个默认的 TextureAtlasSprite
		// 使用 Minecraft 的 atlas 纹理中的默认缺失纹理
		PotassiumLogger.logger().warn("Using programmatic fallback for missing texture sprite. Textures may appear incorrect.");
		return this.createFallbackSprite();
	}

	private TextureAtlasSprite createFallbackSprite() {
		// 尝试通过反射获取任意可用的 TextureAtlasSprite
		try {
			// 通过 Block registry 获取任意 block 的 particle icon
			net.minecraft.core.Registry<net.minecraft.world.level.block.Block> blockRegistry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
			for (net.minecraft.world.level.block.Block block : blockRegistry) {
				try {
					net.minecraft.world.level.block.state.BlockState defaultState = block.defaultBlockState();
					TextureAtlasSprite sprite = this.tryGetParticleSprite(defaultState);
					if (sprite != null) {
						PotassiumLogger.logger().debug("Successfully got fallback sprite from block: {}", block);
						return sprite;
					}
				} catch (Exception e) {
					// 继续尝试下一个 block
				}
			}
		} catch (Exception e) {
			PotassiumLogger.logger().debug("Failed to get fallback sprite from blocks: {}", e.getMessage());
		}

		// 如果所有方法都失败，记录错误
		PotassiumLogger.logger().error("All texture sprite resolution methods failed. Rendering may appear incorrect.");
		return null;
	}

	private Object resolveMemberByType(Object target, String preferredName, String typeSimpleName) throws ReflectiveOperationException {
		Method method = this.findMatchingMethodOrNull(target.getClass(), preferredName, typeSimpleName);
		if (method != null) {
			return method.invoke(target);
		}

		Field field = this.findFieldByType(target.getClass(), preferredName, typeSimpleName);
		return field.get(target);
	}

	private Method findMatchingMethod(Class<?> owner, String preferredName, String returnTypeSimpleName, Class<?>... parameterTypes) throws NoSuchMethodException {
		Method method = this.findMatchingMethodOrNull(owner, preferredName, returnTypeSimpleName, parameterTypes);
		if (method != null) {
			return method;
		}
		throw new NoSuchMethodException("No matching method on " + owner.getName() + " returns " + returnTypeSimpleName);
	}

	private Method findMatchingMethodOrNull(Class<?> owner, String preferredName, String returnTypeSimpleName, Class<?>... parameterTypes) {
		Method namedMethod = this.findMethodByName(owner, preferredName, returnTypeSimpleName, parameterTypes);
		if (namedMethod != null) {
			return namedMethod;
		}

		for (Method method : owner.getMethods()) {
			if (this.matchesMethodSignature(method, returnTypeSimpleName, parameterTypes)) {
				return method;
			}
		}
		for (Method method : owner.getDeclaredMethods()) {
			if (this.matchesMethodSignature(method, returnTypeSimpleName, parameterTypes)) {
				method.setAccessible(true);
				return method;
			}
		}
		return null;
	}

	private Method findMethodByName(Class<?> owner, String preferredName, String returnTypeSimpleName, Class<?>... parameterTypes) {
		for (Method method : owner.getMethods()) {
			if (method.getName().equals(preferredName) && this.matchesMethodSignature(method, returnTypeSimpleName, parameterTypes)) {
				return method;
			}
		}
		for (Method method : owner.getDeclaredMethods()) {
			if (method.getName().equals(preferredName) && this.matchesMethodSignature(method, returnTypeSimpleName, parameterTypes)) {
				method.setAccessible(true);
				return method;
			}
		}
		return null;
	}

	private Field findFieldByType(Class<?> owner, String preferredName, String typeSimpleName) throws NoSuchFieldException {
		Field namedField = this.findFieldByName(owner, preferredName, typeSimpleName);
		if (namedField != null) {
			return namedField;
		}

		for (Field field : owner.getFields()) {
			if (field.getType().getSimpleName().equals(typeSimpleName)) {
				return field;
			}
		}
		for (Field field : owner.getDeclaredFields()) {
			if (field.getType().getSimpleName().equals(typeSimpleName)) {
				field.setAccessible(true);
				return field;
			}
		}
		throw new NoSuchFieldException("No field on " + owner.getName() + " uses type " + typeSimpleName);
	}

	private Field findFieldByName(Class<?> owner, String preferredName, String typeSimpleName) {
		for (Field field : owner.getFields()) {
			if (field.getName().equals(preferredName) && field.getType().getSimpleName().equals(typeSimpleName)) {
				return field;
			}
		}
		for (Field field : owner.getDeclaredFields()) {
			if (field.getName().equals(preferredName) && field.getType().getSimpleName().equals(typeSimpleName)) {
				field.setAccessible(true);
				return field;
			}
		}
		return null;
	}

	private boolean matchesMethodSignature(Method method, String returnTypeSimpleName, Class<?>... parameterTypes) {
		return method.getReturnType().getSimpleName().equals(returnTypeSimpleName)
			&& this.matchesParameters(method.getParameterTypes(), parameterTypes);
	}

	private boolean matchesParameters(Class<?>[] actualParameterTypes, Class<?>[] expectedParameterTypes) {
		if (actualParameterTypes.length != expectedParameterTypes.length) {
			return false;
		}
		for (int index = 0; index < actualParameterTypes.length; index++) {
			if (!actualParameterTypes[index].isAssignableFrom(expectedParameterTypes[index])
				&& !actualParameterTypes[index].getSimpleName().equals(expectedParameterTypes[index].getSimpleName())) {
				return false;
			}
		}
		return true;
	}

	private void logExperimentalTerrainReplacementDisabledOnce() {
		if (this.loggedExperimentalTerrainReplacementDisabled) {
			return;
		}

		PotassiumLogger.logger().warn(
			"Experimental Potassium terrain replacement is disabled. Vanilla terrain remains active until the GPU path has correct scene integration, materials, and lighting."
		);
		this.loggedExperimentalTerrainReplacementDisabled = true;
	}

	private void logTerrainSubmissionFailureOnce() {
		if (this.loggedTerrainSubmissionFailure) {
			return;
		}

		PotassiumLogger.logger().warn(
			"Falling back to vanilla opaque terrain because canBuildMinecraftTerrainSubmission() returned false. " +
			"initialized={}, worldDataConfigured={}, materialTable={}, materialTableEmpty={}, meshMetadata={}, " +
			"meshVertex={}, meshVertexBuffer={}, residentChunks={}, terrainMeshReady={}",
			this.initialized,
			this.worldDataBuffer != null && this.worldDataBuffer.isConfigured(),
			this.materialTableBuffer != null && this.blockMaterialTable != null,
			this.blockMaterialTable == null || this.blockMaterialTable.isEmpty(),
			this.meshMetadataBuffer != null,
			this.meshVertexBuffer != null,
			this.meshVertexBuffer != null && this.meshVertexBuffer.gpuBuffer() != null,
			this.memoryManager != null && this.memoryManager.residentChunks() > 0,
			this.terrainMeshReady
		);
		this.loggedTerrainSubmissionFailure = true;
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

	private void emitQuadVertex(VertexConsumer vertexConsumer, PoseStack.Pose pose, VertexPoint point, int color, float u, float v, Vector3f normal) {
		vertexConsumer.addVertex(pose, point.x(), point.y(), point.z())
			.setColor(color)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(BRIDGE_FULL_BRIGHT_LIGHT)
			.setNormal(pose, normal);
	}

	private static int bridgeColorArgb(int packedBlock, boolean translucent) {
		int alpha = translucent ? translucentAlpha(packedBlock) : 0xFF;
		return (alpha << 24) | 0x00FFFFFF;
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

	public boolean canRenderTerrainPass() {
		return this.initialized
			&& this.worldDataBuffer != null
			&& this.worldDataBuffer.isConfigured()
			&& this.materialTableBuffer != null
			&& this.blockMaterialTable != null
			&& !this.blockMaterialTable.isEmpty()
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
		if (this.materialTableBuffer != null) {
			this.materialTableBuffer.bind(MESH_MATERIAL_TABLE_BINDING);
		}
		GL30C.glEnableVertexAttribArray(0);
		GL30C.glVertexAttribIPointer(0, 4, GL11C.GL_INT, MeshVertexBuffer.BYTES_PER_VERTEX, 0L);
		GL30C.glEnableVertexAttribArray(1);
		GL30C.glVertexAttribIPointer(1, 2, GL11C.GL_UNSIGNED_INT, MeshVertexBuffer.BYTES_PER_VERTEX, 16L);
	}

	private void finishMeshDrawState() {
		GL11C.glDepthMask(true);
		GL11C.glEnable(GL11C.GL_DEPTH_TEST);
		GL11C.glEnable(GL11C.GL_CULL_FACE);
		GL11C.glDisable(GL11C.GL_BLEND);
		GL30C.glDisableVertexAttribArray(1);
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
		if (this.meshGenerationSync != 0) {
			GL32C.glDeleteSync(this.meshGenerationSync);
			this.meshGenerationSync = 0;
		}
		closeQuietly(this.residentChunkStateBuffer);
		closeQuietly(this.changeQueueBuffer);
		closeQuietly(this.drawCommandCountBuffer);
		closeQuietly(this.meshVertexBuffer);
		closeQuietly(this.meshMetadataBuffer);
		closeQuietly(this.materialTableBuffer);
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
		this.materialTableBuffer = null;
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
		this.blockMaterialTable = BlockMaterialTable.empty();
		this.opaqueTerrainSubmissionCache.clear();
		this.lastCpuMetadataSnapshot = null;
		this.bridgeMeshCache.clear();
		this.bridgeTranslucentMeshCache.clear();
		this.bridgeMaterialCache.clear();
		this.terrainMeshReady = false;
		this.pendingInitialMeshGeneration = true;
		this.dispatchedSlotCount = 0;
		this.targetInitialChunkCount = 0;
		this.initialMeshGpuDone = false;
		this.loggedExperimentalTerrainReplacementDisabled = false;
		this.loggedTerrainSubmissionCount = false;
		this.loggedTerrainSubmissionFailure = false;
		this.loggedBridgeMaterialFallback = false;
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
		// 返回动态预算上限（用于扩展限制），不是当前分配大小
		if (this.dynamicVramBudgetMiB > 0) {
			return this.dynamicVramBudgetMiB;
		}
		
		// 无法检测显存，使用配置值
		return Math.max(64, Math.min(this.config.memory.worldDataBufferMiB, this.config.memory.maxResidentWorldMiB));
	}

	private long effectiveResidentWorldBudgetBytes() {
		if (this.worldDataBuffer == null) {
			return 0L;
		}

		return this.effectiveResidentWorldBudgetBytes(this.worldDataBuffer.capacityBytes());
	}

	private long effectiveResidentWorldBudgetBytes(long worldBudgetBytes) {
		if (this.worldDataBuffer == null) {
			return 0L;
		}

		long bytesPerChunk = Math.max(this.worldDataBuffer.bytesPerChunk(), 1L);
		long meshBudgetBytes = maxResidentMeshWorldBudgetBytes(bytesPerChunk);
		return Math.min(worldBudgetBytes, meshBudgetBytes);
	}

	private long maxResidentMeshWorldBudgetBytes(long worldBytesPerChunk) {
		// 网格缓冲区会自动扩展以匹配世界数据缓冲区容量（ensureMeshOutputCapacity），
		// 因此这里不施加人工限制。真实约束由 GPU 显存可用性决定。
		return Long.MAX_VALUE;
	}

	private long perChunkMeshBudgetBytes() {
		long meshVertexBytes = (long) Math.max(this.config.memory.meshFacesPerChunk, 1)
			* MeshVertexBuffer.VERTICES_PER_FACE
			* MeshVertexBuffer.BYTES_PER_VERTEX;
		long metadataBytes = MeshMetadataBuffer.BYTES_PER_ENTRY;
		long residentStateBytes = ResidentChunkStateBuffer.BYTES_PER_ENTRY;
		long indirectBytes = (long) IndirectCommandBuffer.COMMAND_STRIDE_BYTES * 2L;
		return meshVertexBytes + metadataBytes + residentStateBytes + indirectBytes;
	}

	private boolean tryExpandWorldDataBuffer() {
		int currentBudgetMiB = toMebibytesCeil(this.worldDataBuffer.capacityBytes());

		// 使用动态预算和配置预算的较大值作为上限，允许扩展超出原始配置
		int effectiveMaxMiB = Math.max(this.dynamicVramBudgetMiB, this.config.memory.maxResidentWorldMiB);
		if (effectiveMaxMiB <= 0) {
			return false;
		}

		int availableVideoMemoryMiB = GLCapabilities.getEstimatedAvailableVideoMemoryMiB();
		if (availableVideoMemoryMiB < 0) {
			return false;
		}

		// 动态预留值：当前预算越小，预留值越小（允许渐进式扩展）
		int dynamicReserveMiB = Math.max(64, Math.min(WORLD_DATA_GROWTH_RESERVE_MIB, currentBudgetMiB / 4));
		// 可用增长空间：取动态预算余量和硬件可用余量的较大值
		int budgetHeadroomMiB = effectiveMaxMiB - currentBudgetMiB;
		int hardwareHeadroomMiB = availableVideoMemoryMiB - dynamicReserveMiB - currentBudgetMiB;
		int usableGrowthMiB = Math.max(budgetHeadroomMiB, hardwareHeadroomMiB);
		if (usableGrowthMiB <= 0) {
			return false;
		}

		// 渐进式增长：每次增加 50% 或至少 WORLD_DATA_GROWTH_MIN_MIB，避免
		// 翻倍增长导致的内存峰值（*2 会从 1031 直接跳到 2063 MiB）
		int requestedBudgetMiB = Math.max(
			currentBudgetMiB + currentBudgetMiB / 2,
			currentBudgetMiB + WORLD_DATA_GROWTH_MIN_MIB
		);
		requestedBudgetMiB = Math.min(requestedBudgetMiB, currentBudgetMiB + usableGrowthMiB);
		requestedBudgetMiB = Math.min(requestedBudgetMiB, effectiveMaxMiB);
		if (requestedBudgetMiB <= currentBudgetMiB) {
			return false;
		}

		long requestedWorldBudgetBytes = toBytes(requestedBudgetMiB);
		long currentResidentBudgetBytes = this.memoryManager.budgetBytes();
		long requestedResidentBudgetBytes = this.effectiveResidentWorldBudgetBytes(requestedWorldBudgetBytes);
		if (requestedResidentBudgetBytes <= currentResidentBudgetBytes) {
			return false;
		}

		try {
			boolean expanded = this.worldDataBuffer.ensureCapacity(requestedWorldBudgetBytes);
			if (!expanded) {
				return false;
			}

			long effectiveResidentBudgetBytes = this.effectiveResidentWorldBudgetBytes();
			boolean slotBudgetExpanded = this.memoryManager.expandBudget(effectiveResidentBudgetBytes);
			this.ensureMeshOutputCapacity(false);
			this.ensureIndirectCommandCapacity();
			int newBudgetMiB = toMebibytesCeil(this.worldDataBuffer.capacityBytes());
			int effectiveResidentBudgetMiB = toMebibytesCeil(effectiveResidentBudgetBytes);
			this.worldDataBufferExpansionFailures = 0;
			PotassiumLogger.logger().info(
				"Expanded world data buffer from {} MiB to {} MiB after reaching capacity. Estimated available VRAM={} MiB, resident chunk capacity={}, meshResidentBudget={} MiB, pages={}{}.",
				currentBudgetMiB,
				newBudgetMiB,
				availableVideoMemoryMiB,
				this.memoryManager.capacityChunks(),
				effectiveResidentBudgetMiB,
				this.worldDataBuffer.pageCount(),
				slotBudgetExpanded ? "" : " (resident slot budget unchanged; mesh budget capped)"
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
		ChunkData evictionCandidate = this.chunkManager.findEvictionCandidate(
			incomingChunkPos.x(),
			incomingChunkPos.z(),
			tickIndex
		);
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

		if (this.dirtyMeshSlots.isEmpty()) {
			return;
		}

		int shaderVisibleChunkCapacity = this.worldDataBuffer.shaderVisibleChunkCapacity(WORLD_DATA_BINDING);
		if (shaderVisibleChunkCapacity <= 0) {
			return;
		}

		int residentSlotCapacity = Math.max(this.residentChunkStateBuffer.capacityEntries(), 1);
		this.lastMeshGenerationJobs = residentSlotCapacity;
		this.lastMeshGenerationDirtyCandidates = this.dirtyMeshSlots.size();

		this.worldDataBuffer.bind(WORLD_DATA_BINDING);
		this.residentChunkStateBuffer.bind(RESIDENT_CHUNK_STATE_BINDING);
		this.meshMetadataBuffer.bind(MESH_METADATA_BINDING);
		this.meshVertexBuffer.bindStorage(MESH_VERTEX_BINDING);
		if (this.materialTableBuffer != null) {
			this.materialTableBuffer.bind(MESH_MATERIAL_TABLE_BINDING);
		}

		this.meshGenerationStatsBuffer.clear();
		this.meshGenerationStatsBuffer.bind(MESH_STATS_BINDING);
		this.meshGenerationShader.use();
		if (this.meshFacesPerChunkUniform >= 0) {
			GL30C.glUniform1ui(this.meshFacesPerChunkUniform, this.meshVertexBuffer.facesPerChunk());
		}
		if (this.meshResidentSlotCapacityUniform >= 0) {
			GL30C.glUniform1ui(this.meshResidentSlotCapacityUniform, residentSlotCapacity);
		}

		int groupCountX = (residentSlotCapacity + (MESH_GENERATION_LOCAL_SIZE_X - 1)) / MESH_GENERATION_LOCAL_SIZE_X;
		GL43C.glDispatchCompute(groupCountX, 1, 1);

		if (this.meshGenerationSync != 0) {
			GL32C.glDeleteSync(this.meshGenerationSync);
		}
		this.meshGenerationSync = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

		GL43C.glMemoryBarrier(
			GL43C.GL_SHADER_STORAGE_BARRIER_BIT
				| GL43C.GL_BUFFER_UPDATE_BARRIER_BIT
				| GL43C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
		);

		this.dispatchedSlotCount += this.dirtyMeshSlots.size();
		this.dirtyMeshSlots.clear();

		this.lastMeshGenerationProcessedJobs = -1;
		this.lastMeshGenerationGeneratedVertices = -1;
		this.lastMeshGenerationClippedJobs = -1;
		this.lastMeshGenerationSampledPackedBlock = -1;
	}

	private void refreshTerrainMeshReadiness() {
		int residentChunks = this.memoryManager.residentChunks();
		if (this.meshMetadataBuffer == null || residentChunks <= 0) {
			this.terrainMeshReady = false;
			return;
		}

		// Reset tracking when resident chunk count changes (new chunks loaded or evicted)
		if (residentChunks != this.dispatchedSlotCount) {
			this.terrainMeshReady = false;
		}

		// 非阻塞轮询 fence — GPU 未完成则跳过本次检查，下一帧重试
		this.waitForMeshGenerationSync();
		if (this.meshGenerationSync != 0) {
			// fence 尚未完成，terrain mesh readiness 保持上一次状态
			return;
		}

		IntBuffer metadata = this.meshMetadataBuffer.readEntries();
		int entries = this.meshMetadataBuffer.capacityEntries();
		int totalInts = entries * MeshMetadataBuffer.INTS_PER_ENTRY;

		// Save CPU snapshot of metadata for use during rendering (avoids per-chunk GPU readbacks)
		if (this.lastCpuMetadataSnapshot == null || this.lastCpuMetadataSnapshot.length < totalInts) {
			this.lastCpuMetadataSnapshot = new int[totalInts];
		}
		metadata.get(this.lastCpuMetadataSnapshot, 0, totalInts);

		boolean hasGeometry = false;
		for (int entryIndex = 0; entryIndex < entries; entryIndex++) {
			int metadataOffset = entryIndex * MeshMetadataBuffer.INTS_PER_ENTRY;
			int opaqueVertexCount = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET];
			int cutoutVertexCount = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_CUTOUT_VERTEX_COUNT_OFFSET];
			int translucentVertexCount = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET];
			if (opaqueVertexCount > 0 || cutoutVertexCount > 0 || translucentVertexCount > 0) {
				hasGeometry = true;
				break;
			}
		}

		if (hasGeometry && !this.terrainMeshReady && this.dispatchedSlotCount >= residentChunks) {
			PotassiumLogger.logger().info(
				"Potassium terrain mesh is ready. Resident chunks={}, meshCapacity={}, pages={}.",
				residentChunks,
				this.meshMetadataBuffer.capacityEntries(),
				this.worldDataBuffer.pageCount()
			);
		}

		this.terrainMeshReady = hasGeometry && this.dispatchedSlotCount >= residentChunks;
	}

	private void waitForMeshGenerationSync() {
		if (this.meshGenerationSync == 0) {
			return;
		}

		// 非阻塞检查 GPU 是否已完成
		int result = GL32C.glClientWaitSync(this.meshGenerationSync, 0, 0);
		if (result == GL32C.GL_ALREADY_SIGNALED || result == GL32C.GL_CONDITION_SATISFIED) {
			GL32C.glDeleteSync(this.meshGenerationSync);
			this.meshGenerationSync = 0;
		}
		// 未完成时不阻塞，保留 fence 到下一帧继续轮询
	}

	/**
	 * Records the target number of chunks that must be dispatched before
	 * initial mesh generation is considered complete. Called once during
	 * the initial chunk drain phase.
	 */
	public void setTargetInitialChunkCount(int count) {
		this.targetInitialChunkCount = count;
		this.pendingInitialMeshGeneration = count > 0;
		this.initialMeshGpuDone = false;
	}

	/**
	 * Dispatches dirty mesh slots and blocks with glFinish() until GPU
	 * mesh generation completes. Updates CPU metadata snapshot.
	 * Clears pendingInitialMeshGeneration once done, regardless of
	 * whether any geometry was generated (empty chunks are valid).
	 */
	public void blockUntilInitialMeshGenerationComplete() {
		if (this.meshGenerationShader == null || this.residentChunkStateBuffer == null
			|| this.meshGenerationStatsBuffer == null || this.worldDataBuffer == null) {
			this.pendingInitialMeshGeneration = false;
			return;
		}
		if (this.initialMeshGpuDone || !this.pendingInitialMeshGeneration) {
			return;
		}

		// Keep dispatching and waiting until all target chunks are processed
		int maxIterations = 30;
		for (int i = 0; i < maxIterations && this.dispatchedSlotCount < this.targetInitialChunkCount; i++) {
			// Dispatch any currently dirty slots
			this.dispatchDirtyMeshGeneration();

			// Use glFinish() to truly block until all GPU work completes
			GL11C.glFinish();
			this.meshGenerationSync = 0;
		}

		// Read back metadata into CPU snapshot
		if (this.meshMetadataBuffer != null) {
			IntBuffer metadata = this.meshMetadataBuffer.readEntries();
			int entries = this.meshMetadataBuffer.capacityEntries();
			int totalInts = entries * MeshMetadataBuffer.INTS_PER_ENTRY;

			if (this.lastCpuMetadataSnapshot == null || this.lastCpuMetadataSnapshot.length < totalInts) {
				this.lastCpuMetadataSnapshot = new int[totalInts];
			}
			metadata.get(this.lastCpuMetadataSnapshot, 0, totalInts);
		}

		// Check if any chunk produced geometry (for terrainMeshReady)
		boolean hasGeometry = false;
		if (this.lastCpuMetadataSnapshot != null) {
			for (int entryIndex = 0; entryIndex < this.targetInitialChunkCount; entryIndex++) {
				int metadataOffset = entryIndex * MeshMetadataBuffer.INTS_PER_ENTRY;
				if (metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET >= this.lastCpuMetadataSnapshot.length) {
					break;
				}
				int opaqueVC = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET];
				int cutoutVC = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_CUTOUT_VERTEX_COUNT_OFFSET];
				int translucentVC = this.lastCpuMetadataSnapshot[metadataOffset + MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET];
				if (opaqueVC > 0 || cutoutVC > 0 || translucentVC > 0) {
					hasGeometry = true;
					break;
				}
			}
		}

		this.initialMeshGpuDone = true;
		this.pendingInitialMeshGeneration = false;

		if (hasGeometry) {
			this.terrainMeshReady = true;
			PotassiumLogger.logger().info(
				"Potassium initial mesh generation complete. Dispatched={}, resident={}.",
				this.dispatchedSlotCount, this.memoryManager.residentChunks()
			);
		} else {
			this.terrainMeshReady = false;
			PotassiumLogger.logger().warn(
				"Potassium initial mesh GPU done but no geometry found. Dispatched={}, resident={}. GPU rendering disabled.",
				this.dispatchedSlotCount, this.memoryManager.residentChunks()
			);
		}
	}

	/**
	 * Returns the progress of initial GPU mesh generation as a value between 0.0 and 1.0.
	 * Used by the loading screen to display GPU mesh generation progress.
	 */
	public float getInitialMeshGenerationProgress() {
		if (!this.pendingInitialMeshGeneration || this.targetInitialChunkCount <= 0) {
			return 1.0f;
		}
		return Math.min(1.0f, (float) this.dispatchedSlotCount / (float) this.targetInitialChunkCount);
	}

	public boolean isInitialMeshGenerationPending() {
		return this.pendingInitialMeshGeneration;
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
		this.opaqueTerrainSubmissionCache.remove((residentSlot << 4) | MESH_METADATA_OPAQUE_VERTEX_COUNT_OFFSET);
		this.opaqueTerrainSubmissionCache.remove((residentSlot << 4) | MESH_METADATA_CUTOUT_VERTEX_COUNT_OFFSET);
		this.opaqueTerrainSubmissionCache.remove((residentSlot << 4) | MESH_METADATA_TRANSLUCENT_VERTEX_COUNT_OFFSET);
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
			int slot = chunkData.residentSlot();
			this.residentChunkStateBuffer.markDirty(slot);
			this.dirtyMeshSlots.add(slot);
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

	private record TerrainSubmissionCacheEntry(int firstVertex, int vertexCount, int meshRevision, int vertexCountOffset) {
		private int indexCount() {
			return (this.vertexCount / MeshVertexBuffer.VERTICES_PER_FACE) * 6;
		}
	}

	private record SortedTerrainSubmission(
		TerrainSubmissionCacheEntry entry,
		double distanceSquared,
		DynamicUniforms.ChunkSectionInfo chunkSectionInfo
	) {
	}

	private record BridgeMeshCacheEntry(int firstVertex, int vertexCount, int meshRevision, int[] packedVertices) {
	}

	private record BridgeMaterial(float u0, float u1, float v0, float v1) {
	}

	private record BridgeChunkDraw(BridgeMeshCacheEntry cacheEntry, double distanceSquared) {
	}

	private record SceneFramebufferState(int drawFramebuffer, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
	}

	private record TranslucentDraw(int firstVertex, int vertexCount, double distanceSquared) {
	}
}
