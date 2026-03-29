package com.potassium.client.compat.sodium;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.compute.GpuSceneDataStore;
import com.potassium.client.compute.SectionVisibilityCompute;
import com.potassium.client.compute.SectionVisibilityCompute.ComputePassResult;
import com.potassium.client.compute.SectionVisibilityCompute.RegionBatchInput;
import com.potassium.client.compute.GpuResidentGeometryBookkeeping;
import com.potassium.client.config.PotassiumConfig;
import com.potassium.client.config.PotassiumFeatures;
import com.potassium.client.gl.GLCapabilities;
import com.potassium.client.render.indirect.IndexedCommandScratchBuffer;
import com.potassium.client.render.indirect.IndexedIndirectCommandBuffer;
import com.potassium.client.render.indirect.IndirectBackend;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

public final class SodiumBridge {
	private static final int MAX_CONSECUTIVE_OVERRIDE_FAILURES = 8;
	private static final int GRAPHICS_SCENE_DATA_BINDING = 4;
	private static final boolean ENABLE_COMPUTE_DEBUG_COMPARISON = false;
	private static final boolean ENABLE_DRAW_DEBUG_COMPARISON = false;
	private static final int MAX_COMPUTE_DEBUG_BATCHES_PER_PASS = 8;
	private static final int MAX_DRAW_DEBUG_BATCHES_PER_PASS = 8;
	private static final boolean FORCE_TRANSLATED_BATCH_SUBMISSION = false;
	private static final boolean ENABLE_COMPUTE_GENERATED_BATCHES = true;
	private static final boolean ENABLE_TRANSLUCENT_BATCH_OVERRIDE = true;
	private static final boolean FORCE_TRANSLUCENT_TRANSLATED_SUBMISSION = true;
	private static final boolean FORCE_FRAGMENT_DISCARD_TRANSLATED_SUBMISSION = true;
	private static final int FACING_COUNT = ModelQuadFacing.COUNT;
	private static final int ALL_FACES_MASK = ModelQuadFacing.ALL;
	private static final boolean USE_GPU_INDIRECT_COUNT = true;
	private static final int[] LOCAL_SECTION_CHUNK_X = new int[RenderRegion.REGION_WIDTH * RenderRegion.REGION_HEIGHT * RenderRegion.REGION_LENGTH];
	private static final int[] LOCAL_SECTION_CHUNK_Y = new int[RenderRegion.REGION_WIDTH * RenderRegion.REGION_HEIGHT * RenderRegion.REGION_LENGTH];
	private static final int[] LOCAL_SECTION_CHUNK_Z = new int[RenderRegion.REGION_WIDTH * RenderRegion.REGION_HEIGHT * RenderRegion.REGION_LENGTH];
	private static final float REGION_HALF_WIDTH_BLOCKS = RenderRegion.REGION_WIDTH * 8.0f;
	private static final float REGION_HALF_HEIGHT_BLOCKS = RenderRegion.REGION_HEIGHT * 8.0f;
	private static final float REGION_HALF_LENGTH_BLOCKS = RenderRegion.REGION_LENGTH * 8.0f;
	private static final float REGION_FRUSTUM_RADIUS = (float) Math.sqrt(
		(REGION_HALF_WIDTH_BLOCKS * REGION_HALF_WIDTH_BLOCKS) +
		(REGION_HALF_HEIGHT_BLOCKS * REGION_HALF_HEIGHT_BLOCKS) +
		(REGION_HALF_LENGTH_BLOCKS * REGION_HALF_LENGTH_BLOCKS)
	);
	private static final int COMMAND_FILL_WORKER_COUNT = computeCommandFillWorkerCount();
	private static final ExecutorService COMMAND_FILL_EXECUTOR = createCommandFillExecutor();
	private static final ThreadLocal<RenderPassContext> PASS_CONTEXT_POOL = ThreadLocal.withInitial(RenderPassContext::new);
	private static final ThreadLocal<IndexedCommandScratchBuffer> CPU_COMMAND_SCRATCH =
		ThreadLocal.withInitial(() -> new IndexedCommandScratchBuffer(256));
	private static final GeneratedCommandBatch REGION_FRUSTUM_CULLED_BATCH = GeneratedCommandBatch.skip(1, 1, 0, 0, 0);
	private static final CompletableFuture<GeneratedCommandBatch> REGION_FRUSTUM_CULLED_FUTURE =
		CompletableFuture.completedFuture(REGION_FRUSTUM_CULLED_BATCH);

	private static final ThreadLocal<RenderPassContext> ACTIVE_PASS = new ThreadLocal<>();
	private static final BridgeDebugStats DEBUG_STATS = new BridgeDebugStats();
	private static volatile Viewport currentViewport;
	private static boolean installed;
	private static boolean drawOverrideEnabled = true;
	private static String drawOverrideDisableReason = "none";
	private static long drawOverrideAttemptCount;
	private static long drawOverrideSuccessCount;
	private static long drawOverrideFailureCount;
	private static int consecutiveOverrideFailureCount;

	private SodiumBridge() {
	}

	static {
		for (int localSectionIndex = 0; localSectionIndex < LOCAL_SECTION_CHUNK_X.length; localSectionIndex++) {
			LOCAL_SECTION_CHUNK_X[localSectionIndex] = LocalSectionIndex.unpackX(localSectionIndex);
			LOCAL_SECTION_CHUNK_Y[localSectionIndex] = LocalSectionIndex.unpackY(localSectionIndex);
			LOCAL_SECTION_CHUNK_Z[localSectionIndex] = LocalSectionIndex.unpackZ(localSectionIndex);
		}
	}

	public static ChunkRenderer wrapChunkRenderer(ChunkRenderer delegate) {
		if (delegate instanceof PotassiumSodiumChunkRenderer) {
			return delegate;
		}

		if (!installed) {
			installed = true;
			PotassiumClientMod.LOGGER.info("Sodium bridge installed");
		}

		return new PotassiumSodiumChunkRenderer(delegate);
	}

	public static void captureViewport(Viewport viewport) {
		currentViewport = viewport;
	}

	public static void beginRenderPass(TerrainRenderPass pass, ChunkRenderListIterable renderLists) {
		if (!PotassiumFeatures.modEnabled()) {
			ACTIVE_PASS.remove();
			return;
		}

		RenderPassContext context = PASS_CONTEXT_POOL.get();
		context.begin(pass, currentViewport);
		IndexedIndirectCommandBuffer commandBuffer = IndirectBackend.indexedCommandBuffer();
		commandBuffer.beginFrame();
		GpuSceneDataStore.bindAsStorage(GRAPHICS_SCENE_DATA_BINDING);
		context.commandBuffer = commandBuffer;
		context.computeCullingEnabled = isComputeGeneratedBatchesEnabled() && SectionVisibilityCompute.isEnabled();
		context.persistentMappingEnabled = commandBuffer.usesPersistentMapping();
		ACTIVE_PASS.set(context);
	}

	public static void schedulePreparedBatches(
		ChunkRenderMatrices matrices,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		boolean fragmentDiscard
	) {
		RenderPassContext context = ACTIVE_PASS.get();
		if (
			context == null ||
			context.preparedBatchesScheduled ||
			!drawOverrideEnabled ||
			FORCE_TRANSLATED_BATCH_SUBMISSION ||
			renderLists == null ||
			renderPass == null ||
			cameraTransform == null
		) {
			return;
		}
		if (renderPass.isTranslucent() && !isTranslucentBatchOverrideEnabled()) {
			context.preparedBatchesScheduled = true;
			return;
		}
		if (shouldForceTranslatedSubmission(renderPass, fragmentDiscard)) {
			context.preparedBatchesScheduled = true;
			return;
		}

		boolean useComputePath =
			isComputeGeneratedBatchesEnabled() &&
			context.computeCullingEnabled &&
			shouldUseComputeSubmission(renderPass, fragmentDiscard);
		context.computeCullingEnabled = useComputePath;
		if (useComputePath) {
			if (isFrustumCullingEnabled()) {
				SectionVisibilityCompute.captureFrustumPlanes(matrices, context.computeFrustumPlanes);
			}
			scheduleComputeGeneratedBatches(
				context,
				renderLists,
				renderPass,
				cameraTransform,
				fragmentDiscard
			);
			context.preparedBatchesScheduled = true;
			return;
		}

		if (!isThreadedCommandFillEnabled()) {
			context.preparedBatchesScheduled = true;
			return;
		}

		boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		boolean preferLocalIndices = renderPass.isTranslucent() && fragmentDiscard;
		Viewport cullingViewport = effectiveViewport(context.viewport);

		for (java.util.Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isTranslucent()); iterator.hasNext(); ) {
			ChunkRenderList renderList = iterator.next();
			RenderRegion region = renderList.getRegion();
			SectionRenderDataStorage storage = region.getStorage(renderPass);
			if (storage == null) {
				continue;
			}

			context.scheduledBatchCount++;
			if (cullingViewport != null && !isRegionVisible(cullingViewport, region)) {
				context.scheduledGeneratedBatches.put(region, REGION_FRUSTUM_CULLED_FUTURE);
				continue;
			}

			context.scheduledGeneratedBatches.put(
				region,
				CompletableFuture.supplyAsync(
					() -> buildGeneratedBatch(
						region,
						storage,
						renderList,
						cameraTransform,
						renderPass,
						useBlockFaceCulling,
						preferLocalIndices,
						cullingViewport
					),
					COMMAND_FILL_EXECUTOR
				)
			);
		}

		context.visibleRegionCount = context.scheduledBatchCount;
		context.preparedBatchesScheduled = true;
	}

	public static void prepareIndexedBatchFromRenderData(
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		TerrainRenderPass renderPass,
		boolean fragmentDiscard
	) {
		RenderPassContext context = ACTIVE_PASS.get();
		if (
			context == null ||
			!drawOverrideEnabled ||
			(context.pass.isTranslucent() && !isTranslucentBatchOverrideEnabled()) ||
			region == null ||
			storage == null ||
			renderList == null ||
			cameraTransform == null ||
			renderPass == null
		) {
			return;
		}

		boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		boolean preferLocalIndices = renderPass.isTranslucent() && fragmentDiscard;
		SectionVisibilityCompute.SectionSceneIds sceneIds = SectionVisibilityCompute.resolveSceneIds(
			region,
			storage,
			renderList,
			preferLocalIndices,
			renderPass.isTranslucent()
		);
		if (GLCapabilities.hasShaderDrawParameters()) {
			GpuSceneDataStore.updateSectionVisibility(sceneIds, region, renderList, renderPass.isTranslucent());
		}

		if (context.pendingPreparedBatch != null) {
			if (!context.pendingPreparedBatch.skipDraw()) {
				rollbackBatch(context, context.pendingPreparedBatch);
			}
			context.pendingPreparedBatch = null;
		}

		if (FORCE_TRANSLATED_BATCH_SUBMISSION || shouldForceTranslatedSubmission(renderPass, fragmentDiscard)) {
			prepareTranslatedSceneIds(
				context,
				sceneIds,
				region,
				storage,
				renderList,
				cameraTransform,
				renderPass,
				fragmentDiscard
			);
			return;
		}

		GeneratedCommandBatch generatedBatch;
		if (context.computeCullingEnabled) {
			generatedBatch = consumePreparedGeneratedBatch(context, region);
		} else {
			generatedBatch = consumeScheduledGeneratedBatch(context, region);
		}

		if (generatedBatch == null) {
			context.syncGeneratedBatchCount++;
			generatedBatch = buildGeneratedBatch(
				region,
				storage,
				renderList,
				cameraTransform,
				renderPass,
				useBlockFaceCulling,
				preferLocalIndices,
				effectiveViewport(context.viewport)
			);
		}

		if (ENABLE_COMPUTE_DEBUG_COMPARISON) {
			debugCompareComputeBatch(
				context,
				generatedBatch,
				region,
				storage,
				renderList,
				cameraTransform,
				renderPass,
				useBlockFaceCulling,
				preferLocalIndices
			);
		}
		applyGeneratedBatch(context, generatedBatch);
	}

	private static boolean shouldForceTranslatedSubmission(TerrainRenderPass renderPass, boolean fragmentDiscard) {
		if (renderPass == null) {
			return false;
		}

		if (renderPass.isTranslucent()) {
			return FORCE_TRANSLUCENT_TRANSLATED_SUBMISSION;
		}

		return fragmentDiscard && FORCE_FRAGMENT_DISCARD_TRANSLATED_SUBMISSION;
	}

	private static boolean shouldUseComputeSubmission(TerrainRenderPass renderPass, boolean fragmentDiscard) {
		return renderPass != null && !renderPass.isTranslucent() && !fragmentDiscard;
	}

	private static boolean isComputeGeneratedBatchesEnabled() {
		return ENABLE_COMPUTE_GENERATED_BATCHES && PotassiumFeatures.opaqueComputeCullingEnabled();
	}

	private static boolean isTranslucentBatchOverrideEnabled() {
		return ENABLE_TRANSLUCENT_BATCH_OVERRIDE && PotassiumFeatures.translucentBatchOverrideEnabled();
	}

	private static boolean isThreadedCommandFillEnabled() {
		return PotassiumFeatures.threadedCommandFillEnabled();
	}

	private static boolean isFrustumCullingEnabled() {
		return PotassiumFeatures.frustumCullingEnabled();
	}

	private static boolean isGpuIndirectCountEnabled() {
		return USE_GPU_INDIRECT_COUNT && PotassiumFeatures.gpuIndirectCountEnabled();
	}

	private static Viewport effectiveViewport(Viewport viewport) {
		return isFrustumCullingEnabled() ? viewport : null;
	}

	public static boolean tryDrawIndexedBatch(CommandList commandList, GlTessellation tessellation, MultiDrawBatch batch) {
		RenderPassContext context = ACTIVE_PASS.get();
		if (context == null || batch == null || batch.isEmpty()) {
			return false;
		}

		context.seenBatchCount++;
		context.seenCommandCount += batch.size;
		drawOverrideAttemptCount++;

		if (context.pass.isTranslucent() && !isTranslucentBatchOverrideEnabled()) {
			recordFallback(context, batch, "translucent override disabled");
			return false;
		}

		if (!drawOverrideEnabled) {
			recordFallback(context, batch, drawOverrideDisableReason);
			return false;
		}

		if (FORCE_TRANSLATED_BATCH_SUBMISSION) {
			return drawTranslatedBatch(commandList, tessellation, batch, context);
		}

		if (commandList == null || tessellation == null) {
			recordFallback(context, batch, "missing draw state");
			return false;
		}

		PreparedBatch preparedBatch = consumePreparedBatch(context);
		try {
			if (preparedBatch != null && preparedBatch.skipDraw()) {
				context.culledBatchCount++;
				recordOverrideSuccess();
				return true;
			}

			if (preparedBatch == null) {
				int firstCommandIndex = context.commandBuffer.commandCount();
				PreparedTranslatedSceneIds translatedSceneIds = consumePreparedTranslatedSceneIds(context);
				int commandCount = appendTranslatedIndexedBatch(context.commandBuffer, batch, translatedSceneIds);
				if (commandCount <= 0) {
					recordFallback(context, batch, "empty translated batch");
					return false;
				}

				preparedBatch = PreparedBatch.draw(firstCommandIndex, commandCount, CommandSource.TRANSLATED, true);
				recordMirroredBatch(context, preparedBatch);
			}

			if (ENABLE_DRAW_DEBUG_COMPARISON) {
				debugComparePreparedBatchAgainstSodiumBatch(context, preparedBatch, batch);
			}

			if (preparedBatch.needsUpload()) {
				context.commandBuffer.uploadAppendedCommands(preparedBatch.firstCommandIndex());
			}

			try (DrawCommandList ignored = commandList.beginTessellating(tessellation)) {
				context.commandBuffer.bindForDraw();
				if (preparedBatch.gpuCounted()) {
					SectionVisibilityCompute.bindCountersAsParameterBuffer();
					drawIndexedIndirectCount(
						tessellation,
						context.commandBuffer.drawOffsetBytes(preparedBatch.firstCommandIndex()),
						preparedBatch.drawCountOffsetBytes(),
						preparedBatch.commandCount()
					);
				} else {
					GL43C.glMultiDrawElementsIndirect(
						tessellation.getPrimitiveType().getId(),
						GL11C.GL_UNSIGNED_INT,
						context.commandBuffer.drawOffsetBytes(preparedBatch.firstCommandIndex()),
						preparedBatch.commandCount(),
						IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES
					);
				}
			}

			context.executedBatchCount++;
			if (!preparedBatch.gpuCounted()) {
				context.executedCommandCount += preparedBatch.commandCount();
			}
			recordOverrideSuccess();
			return true;
		} catch (RuntimeException exception) {
			if (preparedBatch != null && !preparedBatch.skipDraw()) {
				rollbackBatch(context, preparedBatch);
			}
			String reason = describeOverrideFailure(exception);
			recordFallback(context, batch, reason);
			recordOverrideFailure(reason, exception);
			return false;
		}
	}

	public static void endRenderPass() {
		RenderPassContext context = ACTIVE_PASS.get();
		if (context == null) {
			return;
		}

		context.commandBuffer.upload();
		context.commandBuffer.endFrame();
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, GRAPHICS_SCENE_DATA_BINDING, 0);
		DEBUG_STATS.recordPass(context);

		if (context.commandBuffer.commandCount() > 0 || context.culledBatchCount > 0) {
			PotassiumClientMod.LOGGER.debug(
				"Sodium pass observed: pass={}, regions={}, seenBatches={}, mirroredBatches={}, executedBatches={}, culledBatches={}, fallbackBatches={}",
				context.describePass(),
				context.visibleRegionCount,
				context.seenBatchCount,
				context.mirroredBatchCount,
				context.executedBatchCount,
				context.culledBatchCount,
				context.fallbackBatchCount
			);
		}

		context.finish();
		ACTIVE_PASS.remove();
	}

	public static List<String> getDebugLines() {
		if (!PotassiumFeatures.modEnabled()) {
			return List.of();
		}

		boolean showSummary = PotassiumConfig.showF3Summary();
		boolean showOverrideStats = PotassiumConfig.showF3OverrideStats();
		boolean showGenerationStats = PotassiumConfig.showF3GenerationStats();
		boolean showComputeStats = PotassiumConfig.showF3ComputeStats();
		boolean showCullingStats = PotassiumConfig.showF3CullingStats();
		boolean showBufferStats = PotassiumConfig.showF3BufferStats();
		boolean showFallbackStats = PotassiumConfig.showF3FallbackStats();
		if (!showSummary &&
			!showOverrideStats &&
			!showGenerationStats &&
			!showComputeStats &&
			!showCullingStats &&
			!showBufferStats &&
			!showFallbackStats) {
			return List.of();
		}

		return DEBUG_STATS.toDebugLines(
			showSummary,
			showOverrideStats,
			showGenerationStats,
			showComputeStats,
			showCullingStats,
			showBufferStats,
			showFallbackStats,
			installed,
			drawOverrideEnabled,
			drawOverrideDisableReason,
			drawOverrideAttemptCount,
			drawOverrideSuccessCount,
			drawOverrideFailureCount,
			consecutiveOverrideFailureCount
		);
	}

	public static void shutdown() {
		ACTIVE_PASS.remove();
		PASS_CONTEXT_POOL.remove();
		DEBUG_STATS.reset();
		currentViewport = null;
		installed = false;
		drawOverrideEnabled = true;
		drawOverrideDisableReason = "none";
		drawOverrideAttemptCount = 0L;
		drawOverrideSuccessCount = 0L;
		drawOverrideFailureCount = 0L;
		consecutiveOverrideFailureCount = 0;
		COMMAND_FILL_EXECUTOR.shutdownNow();
	}

	private static GeneratedCommandBatch consumeScheduledGeneratedBatch(RenderPassContext context, RenderRegion region) {
		CompletableFuture<GeneratedCommandBatch> future = context.scheduledGeneratedBatches.remove(region);
		if (future == null) {
			return null;
		}

		try {
			if (future.isDone()) {
				context.asyncReadyBatchCount++;
			} else {
				context.asyncWaitedBatchCount++;
			}
			return future.join();
		} catch (CompletionException exception) {
			context.asyncFailedBatchCount++;
			PotassiumClientMod.LOGGER.warn(
				"Potassium async command generation failed for one render region. Falling back to synchronous generation.",
				exception.getCause()
			);
			return null;
		}
	}

	private static GeneratedCommandBatch consumePreparedGeneratedBatch(RenderPassContext context, RenderRegion region) {
		return context.preparedGeneratedBatches.remove(region);
	}

	private static void scheduleComputeGeneratedBatches(
		RenderPassContext context,
		ChunkRenderListIterable renderLists,
		TerrainRenderPass renderPass,
		CameraTransform cameraTransform,
		boolean fragmentDiscard
	) {
		boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		boolean preferLocalIndices = renderPass.isTranslucent() && fragmentDiscard;
		boolean useGpuDrawCount = isGpuIndirectCountEnabled();
		Viewport cullingViewport = effectiveViewport(context.viewport);
		List<RegionBatchInput> regionInputs = context.regionInputs;
		int regionInputCount = 0;
		int nextFirstCommandIndex = 0;

		for (java.util.Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isTranslucent()); iterator.hasNext(); ) {
			ChunkRenderList renderList = iterator.next();
			RenderRegion region = renderList.getRegion();
			SectionRenderDataStorage storage = region.getStorage(renderPass);
			if (storage == null) {
				continue;
			}

			context.scheduledBatchCount++;
			if (cullingViewport != null && !isRegionVisible(cullingViewport, region)) {
				context.preparedGeneratedBatches.put(region, GeneratedCommandBatch.skip(1, 1, 0, 0, 0));
				continue;
			}

			int expectedSectionCount = renderList.getSectionsWithGeometryCount();
			if (expectedSectionCount <= 0) {
				context.preparedGeneratedBatches.put(
					region,
					GeneratedCommandBatch.skip(cullingViewport != null ? 1 : 0, 0, 0, 0, 0)
				);
				continue;
			}

			int maxCommandCount = estimateCommandCapacity(renderList);
			RegionBatchInput regionInput;
			if (regionInputCount < regionInputs.size()) {
				regionInput = regionInputs.get(regionInputCount);
			} else {
				regionInput = new RegionBatchInput();
				regionInputs.add(regionInput);
			}
			regionInput.configure(
				region,
				storage,
				renderList,
				nextFirstCommandIndex,
				maxCommandCount,
				expectedSectionCount
			);
			regionInputCount++;
			nextFirstCommandIndex = Math.addExact(nextFirstCommandIndex, maxCommandCount);
		}

		if (regionInputCount == 0) {
			context.visibleRegionCount = context.scheduledBatchCount;
			return;
		}

		context.visibleRegionCount = context.scheduledBatchCount;
		context.commandBuffer.reserveGpuCommandRange(nextFirstCommandIndex);

		try {
			ComputePassResult computePassResult = SectionVisibilityCompute.generateIndexedCommands(
				context.commandBuffer,
				regionInputs,
				regionInputCount,
				renderPass.isTranslucent(),
				cameraTransform,
				context.computeFrustumPlanes,
				cullingViewport != null,
				useBlockFaceCulling,
				preferLocalIndices,
				!useGpuDrawCount
			);
			if (computePassResult.dispatched()) {
				context.computeDispatchCount++;
				context.commandBuffer.commitGpuGeneratedCommands(0, nextFirstCommandIndex);
			}

			for (int regionIndex = 0; regionIndex < regionInputCount; regionIndex++) {
				RegionBatchInput regionInput = regionInputs.get(regionIndex);
				int testedSectionCount = computePassResult.testedSectionCounts()[regionIndex];
				GeneratedCommandBatch generatedBatch;
				if (useGpuDrawCount) {
					generatedBatch = GeneratedCommandBatch.computeCounted(
						regionInput.firstCommandIndex(),
						regionInput.maxCommandCount(),
						SectionVisibilityCompute.commandCountOffsetBytes(regionIndex),
						cullingViewport != null ? 1 : 0,
						0,
						testedSectionCount,
						0,
						0
					);
				} else {
					int commandCount = computePassResult.commandCounts()[regionIndex];
					int visibleSectionCount = computePassResult.visibleSectionCounts()[regionIndex];
					generatedBatch = commandCount > 0
						? GeneratedCommandBatch.compute(
							regionInput.firstCommandIndex(),
							commandCount,
							cullingViewport != null ? 1 : 0,
							0,
							testedSectionCount,
							visibleSectionCount,
							testedSectionCount - visibleSectionCount
						)
						: GeneratedCommandBatch.skip(
							cullingViewport != null ? 1 : 0,
							0,
							testedSectionCount,
							visibleSectionCount,
							testedSectionCount - visibleSectionCount
						);
				}
				context.preparedGeneratedBatches.put(regionInput.region(), generatedBatch);
			}
		} catch (RuntimeException exception) {
			context.computeFailureCount++;
			context.preparedGeneratedBatches.clear();
			PotassiumClientMod.LOGGER.warn(
				"Potassium compute command generation failed for one render pass. Falling back to CPU generation.",
				exception
			);
		}
	}

	private static void applyGeneratedBatch(RenderPassContext context, GeneratedCommandBatch generatedBatch) {
		if (generatedBatch == null) {
			return;
		}

		context.frustumTestedRegionCount += generatedBatch.frustumTestedRegionCount();
		context.frustumCulledRegionCount += generatedBatch.frustumCulledRegionCount();
		context.frustumTestedSectionCount += generatedBatch.frustumTestedSectionCount();
		context.frustumVisibleSectionCount += generatedBatch.frustumVisibleSectionCount();
		context.frustumCulledSectionCount += generatedBatch.frustumCulledSectionCount();

		if (generatedBatch.skipDraw()) {
			context.generatedSkipBatchCount++;
			context.pendingPreparedBatch = PreparedBatch.skip(CommandSource.GENERATED_CPU);
			return;
		}

		PreparedBatch preparedBatch;
		if (generatedBatch.gpuGenerated()) {
			preparedBatch = generatedBatch.gpuCounted()
				? PreparedBatch.drawCounted(
					generatedBatch.firstCommandIndex(),
					generatedBatch.commandCount(),
					generatedBatch.drawCountOffsetBytes(),
					CommandSource.GENERATED_COMPUTE
				)
				: PreparedBatch.draw(
					generatedBatch.firstCommandIndex(),
					generatedBatch.commandCount(),
					CommandSource.GENERATED_COMPUTE,
					false
				);
		} else {
			int firstCommandIndex = context.commandBuffer.appendCommands(
				generatedBatch.commands(),
				generatedBatch.commandCount()
			);
			preparedBatch = PreparedBatch.draw(
				firstCommandIndex,
				generatedBatch.commandCount(),
				CommandSource.GENERATED_CPU,
				true
			);
		}

		recordMirroredBatch(context, preparedBatch);
		context.pendingPreparedBatch = preparedBatch;
	}

	private static GeneratedCommandBatch buildGeneratedBatch(
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		TerrainRenderPass renderPass,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices,
		Viewport viewport
	) {
		if (viewport != null && !isRegionVisible(viewport, region)) {
			return GeneratedCommandBatch.skip(1, 1, 0, 0, 0);
		}

		ByteIterator iterator = renderList.sectionsWithGeometryIterator(renderPass.isTranslucent());
		if (iterator == null) {
			return GeneratedCommandBatch.skip(0, 0, 0, 0, 0);
		}

		int estimatedCommandCapacity = estimateCommandCapacity(renderList);
		IndexedCommandScratchBuffer commandBuffer = CPU_COMMAND_SCRATCH.get();
		commandBuffer.reset(estimatedCommandCapacity);
		SectionVisibilityCompute.SectionSceneIds sceneIds = SectionVisibilityCompute.resolveSceneIds(
			region,
			storage,
			renderList,
			preferLocalIndices,
			renderPass.isTranslucent()
		);
		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		int frustumTestedSectionCount = 0;
		int frustumVisibleSectionCount = 0;

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			long dataPointer = storage.getDataPointer(localSectionIndex);
			int sectionChunkX = regionChunkX + LOCAL_SECTION_CHUNK_X[localSectionIndex];
			int sectionChunkY = regionChunkY + LOCAL_SECTION_CHUNK_Y[localSectionIndex];
			int sectionChunkZ = regionChunkZ + LOCAL_SECTION_CHUNK_Z[localSectionIndex];
			if (viewport != null) {
				frustumTestedSectionCount++;
				if (!isSectionVisible(viewport, sectionChunkX, sectionChunkY, sectionChunkZ)) {
					continue;
				}

				frustumVisibleSectionCount++;
			}

			int visibleFaces = useBlockFaceCulling
				? DefaultChunkRenderer.getVisibleFaces(
					cameraTransform.intX,
					cameraTransform.intY,
					cameraTransform.intZ,
					sectionChunkX,
					sectionChunkY,
					sectionChunkZ
				)
				: ALL_FACES_MASK;

			visibleFaces &= SectionRenderDataUnsafe.getSliceMask(dataPointer);
			if (visibleFaces == 0) {
				continue;
			}

			int sectionSceneId = sceneIds.sectionSceneId(localSectionIndex);
			if (preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer)) {
				appendLocalIndexedCommands(commandBuffer, dataPointer, visibleFaces, sectionSceneId);
			} else {
				appendSharedIndexedCommands(commandBuffer, dataPointer, visibleFaces, sectionSceneId);
			}
		}

		if (commandBuffer.commandCount() == 0) {
			return GeneratedCommandBatch.skip(
				viewport != null ? 1 : 0,
				0,
				frustumTestedSectionCount,
				frustumVisibleSectionCount,
				frustumTestedSectionCount - frustumVisibleSectionCount
			);
		}

		return GeneratedCommandBatch.draw(
			commandBuffer.view(),
			commandBuffer.commandCount(),
			viewport != null ? 1 : 0,
			0,
			frustumTestedSectionCount,
			frustumVisibleSectionCount,
			frustumTestedSectionCount - frustumVisibleSectionCount
		);
	}

	private static void debugCompareComputeBatch(
		RenderPassContext context,
		GeneratedCommandBatch computeBatch,
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		TerrainRenderPass renderPass,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices
	) {
		if (
			!ENABLE_COMPUTE_DEBUG_COMPARISON ||
			!context.computeCullingEnabled ||
			computeBatch == null ||
			computeBatch.gpuCounted() ||
			context.computeComparedBatchCount >= MAX_COMPUTE_DEBUG_BATCHES_PER_PASS
		) {
			return;
		}

		context.computeComparedBatchCount++;
		boolean computeBlockFaceCullingEnabled = useBlockFaceCulling;
		GeneratedCommandBatch cpuBatch = buildGeneratedBatch(
			region,
			storage,
			renderList,
			cameraTransform,
			renderPass,
			computeBlockFaceCullingEnabled,
			preferLocalIndices,
			context.viewport
			// debug path intentionally uses the live viewport state
		);
		String mismatchReason = compareGeneratedBatches(context.commandBuffer, computeBatch, cpuBatch);
		if (mismatchReason == null) {
			context.computeMatchedBatchCount++;
			return;
		}

		CpuBatchAnalysis liveAnalysis = analyzeLiveGeneratedBatch(
			region,
			storage,
			renderList,
			cameraTransform,
			renderPass,
			computeBlockFaceCullingEnabled,
			preferLocalIndices
		);
		SectionVisibilityCompute.PackedRegionAnalysis packedAnalysis = SectionVisibilityCompute.debugAnalyzePackedRegion(
			new RegionBatchInput(
				region,
				storage,
				renderList,
				0,
				estimateCommandCapacity(renderList),
				renderList.getSectionsWithGeometryCount()
			),
			cameraTransform,
			computeBlockFaceCullingEnabled,
			preferLocalIndices
		);
		String detailedMismatchReason = mismatchReason + formatComputeMismatchDetails(computeBatch, cpuBatch, liveAnalysis, packedAnalysis);

		context.computeMismatchedBatchCount++;
		recordComputeMismatchCategory(context, mismatchReason);
		context.lastComputeMismatchReason = detailedMismatchReason;
		PotassiumClientMod.LOGGER.warn("Potassium compute mismatch in {} pass: {}", context.describePass(), detailedMismatchReason);
	}

	private static String formatComputeMismatchDetails(
		GeneratedCommandBatch computeBatch,
		GeneratedCommandBatch cpuBatch,
		CpuBatchAnalysis liveAnalysis,
		SectionVisibilityCompute.PackedRegionAnalysis packedAnalysis
	) {
		return String.format(
			" [gpuCommands=%d, gpuVisibleSections=%d, cpuCommands=%d, liveVisibleSections=%d, packedVisibleSections=%d, liveSliceMaskedSections=%d, packedSliceMaskedSections=%d, liveLocalSections=%d, liveSharedSections=%d, packedLocalSections=%d, packedSharedSections=%d, liveFirstVisibleFaces=0x%02X, liveFirstSliceMask=0x%02X, packedFirstVisibleFaces=0x%02X, packedFirstSliceMask=0x%02X]",
			computeBatch.commandCount(),
			computeBatch.frustumVisibleSectionCount(),
			cpuBatch.commandCount(),
			liveAnalysis.visibleSectionCount(),
			packedAnalysis.visibleSectionCount(),
			liveAnalysis.sliceMaskedSectionCount(),
			packedAnalysis.sliceMaskedSectionCount(),
			liveAnalysis.localSectionCount(),
			liveAnalysis.sharedSectionCount(),
			packedAnalysis.localSectionCount(),
			packedAnalysis.sharedSectionCount(),
			liveAnalysis.firstVisibleFacesMask(),
			liveAnalysis.firstSliceMask(),
			packedAnalysis.firstVisibleFacesMask(),
			packedAnalysis.firstSliceMask()
		);
	}

	private static CpuBatchAnalysis analyzeLiveGeneratedBatch(
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		TerrainRenderPass renderPass,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices
	) {
		ByteIterator iterator = renderList.sectionsWithGeometryIterator(renderPass.isTranslucent());
		if (iterator == null) {
			return new CpuBatchAnalysis(0, 0, 0, 0, 0, 0, 0, 0);
		}

		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		int sectionCount = 0;
		int sliceMaskedSectionCount = 0;
		int visibleSectionCount = 0;
		int emittedCommandCount = 0;
		int localSectionCount = 0;
		int sharedSectionCount = 0;
		int firstVisibleFacesMask = 0;
		int firstSliceMask = 0;
		boolean capturedFirstVisibleSection = false;

		while (iterator.hasNext()) {
			sectionCount++;
			int localSectionIndex = iterator.nextByteAsInt();
			long dataPointer = storage.getDataPointer(localSectionIndex);
			int sectionChunkX = regionChunkX + LOCAL_SECTION_CHUNK_X[localSectionIndex];
			int sectionChunkY = regionChunkY + LOCAL_SECTION_CHUNK_Y[localSectionIndex];
			int sectionChunkZ = regionChunkZ + LOCAL_SECTION_CHUNK_Z[localSectionIndex];
			int visibleFaces = useBlockFaceCulling
				? DefaultChunkRenderer.getVisibleFaces(
					cameraTransform.intX,
					cameraTransform.intY,
					cameraTransform.intZ,
					sectionChunkX,
					sectionChunkY,
					sectionChunkZ
				)
				: ALL_FACES_MASK;
			int sliceMask = SectionRenderDataUnsafe.getSliceMask(dataPointer);
			if (sliceMask != 0) {
				sliceMaskedSectionCount++;
			}

			visibleFaces &= sliceMask;
			if (visibleFaces == 0) {
				continue;
			}

			visibleSectionCount++;
			if (!capturedFirstVisibleSection) {
				firstVisibleFacesMask = visibleFaces;
				firstSliceMask = sliceMask;
				capturedFirstVisibleSection = true;
			}

			if (preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer)) {
				localSectionCount++;
				emittedCommandCount += Integer.bitCount(visibleFaces & ALL_FACES_MASK);
			} else {
				sharedSectionCount++;
				emittedCommandCount += countSharedCommandRuns(dataPointer, visibleFaces);
			}
		}

		return new CpuBatchAnalysis(
			sectionCount,
			sliceMaskedSectionCount,
			visibleSectionCount,
			emittedCommandCount,
			localSectionCount,
			sharedSectionCount,
			firstVisibleFacesMask,
			firstSliceMask
		);
	}

	private static int countSharedCommandRuns(long dataPointer, int visibleFaces) {
		int commandCount = 0;
		long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
		boolean previousVisible = false;

		for (int facing = 0; facing <= FACING_COUNT; facing++) {
			boolean currentVisible = false;
			long vertexCount = 0L;

			if (facing < FACING_COUNT) {
				vertexCount = SectionRenderDataUnsafe.getVertexCount(dataPointer, facing);
				if (vertexCount != 0L) {
					long faceOrder = (facingList >>> (facing * 8)) & 0xFFL;
					currentVisible = ((visibleFaces >>> (int) faceOrder) & 1) != 0;
				}
			}

			if (!currentVisible && previousVisible) {
				if (facing < FACING_COUNT && vertexCount == 0L) {
					continue;
				}

				commandCount++;
			}

			previousVisible = currentVisible;
		}

		return commandCount;
	}

	private static String compareGeneratedBatches(
		IndexedIndirectCommandBuffer commandBuffer,
		GeneratedCommandBatch computeBatch,
		GeneratedCommandBatch cpuBatch
	) {
		if (computeBatch.skipDraw() != cpuBatch.skipDraw()) {
			return "skip mismatch: gpu=" + computeBatch.skipDraw() + ", cpu=" + cpuBatch.skipDraw();
		}
		if (computeBatch.commandCount() != cpuBatch.commandCount()) {
			return "commandCount mismatch: gpu=" + computeBatch.commandCount() + ", cpu=" + cpuBatch.commandCount();
		}
		if (computeBatch.frustumVisibleSectionCount() != cpuBatch.frustumVisibleSectionCount()) {
			return "visibleSectionCount mismatch: gpu=" + computeBatch.frustumVisibleSectionCount() + ", cpu=" + cpuBatch.frustumVisibleSectionCount();
		}
		if (computeBatch.commandCount() == 0) {
			return null;
		}

		ByteBuffer gpuCommands = commandBuffer.readCommands(computeBatch.firstCommandIndex(), computeBatch.commandCount());
		try {
			ByteBuffer gpuView = gpuCommands.duplicate();
			ByteBuffer cpuView = cpuBatch.commands().duplicate();
			gpuView.clear();
			cpuView.clear();
			int byteCount = computeBatch.commandCount() * IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES;
			gpuView.limit(byteCount);
			cpuView.limit(byteCount);

			List<String> gpuCommandKeys = collectSortedCommandKeys(gpuView, computeBatch.commandCount());
			List<String> cpuCommandKeys = collectSortedCommandKeys(cpuView, cpuBatch.commandCount());
			for (int commandIndex = 0; commandIndex < gpuCommandKeys.size(); commandIndex++) {
				String gpuCommandKey = gpuCommandKeys.get(commandIndex);
				String cpuCommandKey = cpuCommandKeys.get(commandIndex);
				if (!gpuCommandKey.equals(cpuCommandKey)) {
					return "command mismatch at sorted index " + commandIndex + ": gpu=" + gpuCommandKey + ", cpu=" + cpuCommandKey;
				}
			}
		} finally {
			MemoryUtil.memFree(gpuCommands);
		}

		return null;
	}

	private static void debugComparePreparedBatchAgainstSodiumBatch(
		RenderPassContext context,
		PreparedBatch preparedBatch,
		MultiDrawBatch sodiumBatch
	) {
		if (
			!ENABLE_DRAW_DEBUG_COMPARISON ||
			preparedBatch == null ||
			preparedBatch.gpuCounted() ||
			sodiumBatch == null ||
			context.drawComparedBatchCount >= MAX_DRAW_DEBUG_BATCHES_PER_PASS
		) {
			return;
		}

		context.drawComparedBatchCount++;
		String mismatchReason = comparePreparedBatchAgainstSodiumBatch(context.commandBuffer, preparedBatch, sodiumBatch);
		if (mismatchReason == null) {
			context.drawMatchedBatchCount++;
			return;
		}

		context.drawMismatchedBatchCount++;
		context.lastDrawMismatchReason = mismatchReason;
		PotassiumClientMod.LOGGER.warn("Potassium draw mismatch in {} pass: {}", context.describePass(), mismatchReason);
	}

	private static String comparePreparedBatchAgainstSodiumBatch(
		IndexedIndirectCommandBuffer commandBuffer,
		PreparedBatch preparedBatch,
		MultiDrawBatch sodiumBatch
	) {
		if (preparedBatch.skipDraw()) {
			return sodiumBatch.isEmpty() ? null : "skip mismatch: indirect skipped, sodium=" + sodiumBatch.size;
		}
		if (preparedBatch.commandCount() != sodiumBatch.size) {
			return "sodium batch size mismatch: indirect=" + preparedBatch.commandCount() + ", sodium=" + sodiumBatch.size;
		}
		if (preparedBatch.commandCount() == 0) {
			return null;
		}

		ByteBuffer indirectCommands = commandBuffer.readCommands(preparedBatch.firstCommandIndex(), preparedBatch.commandCount());
		try {
			List<String> indirectCommandKeys = collectSortedCommandKeys(indirectCommands, preparedBatch.commandCount(), false);
			List<String> sodiumCommandKeys = collectSortedSodiumCommandKeys(sodiumBatch);
			for (int commandIndex = 0; commandIndex < indirectCommandKeys.size(); commandIndex++) {
				String indirectCommandKey = indirectCommandKeys.get(commandIndex);
				String sodiumCommandKey = sodiumCommandKeys.get(commandIndex);
				if (!indirectCommandKey.equals(sodiumCommandKey)) {
					return "draw command mismatch at sorted index " + commandIndex + ": indirect=" + indirectCommandKey + ", sodium=" + sodiumCommandKey;
				}
			}
		} finally {
			MemoryUtil.memFree(indirectCommands);
		}

		return null;
	}

	private static void recordComputeMismatchCategory(RenderPassContext context, String mismatchReason) {
		if (mismatchReason == null) {
			return;
		}

		if (mismatchReason.startsWith("skip mismatch")) {
			context.computeSkipMismatchCount++;
			return;
		}
		if (mismatchReason.startsWith("commandCount mismatch")) {
			context.computeCommandCountMismatchCount++;
			return;
		}
		if (mismatchReason.startsWith("visibleSectionCount mismatch")) {
			context.computeVisibleSectionMismatchCount++;
			return;
		}
		if (mismatchReason.startsWith("command mismatch")) {
			context.computeCommandMismatchCount++;
		}
	}

	private record CpuBatchAnalysis(
		int sectionCount,
		int sliceMaskedSectionCount,
		int visibleSectionCount,
		int emittedCommandCount,
		int localSectionCount,
		int sharedSectionCount,
		int firstVisibleFacesMask,
		int firstSliceMask
	) {
	}

	private static List<String> collectSortedCommandKeys(ByteBuffer commands, int commandCount) {
		return collectSortedCommandKeys(commands, commandCount, true);
	}

	private static List<String> collectSortedCommandKeys(ByteBuffer commands, int commandCount, boolean includeBaseInstance) {
		List<String> commandKeys = new ArrayList<>(commandCount);
		for (int commandIndex = 0; commandIndex < commandCount; commandIndex++) {
			int commandOffset = commandIndex * IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES;
			commandKeys.add(
				commands.getInt(commandOffset) +
				":" + commands.getInt(commandOffset + Integer.BYTES) +
				":" + commands.getInt(commandOffset + (Integer.BYTES * 2)) +
				":" + commands.getInt(commandOffset + (Integer.BYTES * 3)) +
				":" + (includeBaseInstance ? commands.getInt(commandOffset + (Integer.BYTES * 4)) : 0)
			);
		}
		commandKeys.sort(String::compareTo);
		return commandKeys;
	}

	private static List<String> collectSortedSodiumCommandKeys(MultiDrawBatch sodiumBatch) {
		List<String> commandKeys = new ArrayList<>(sodiumBatch.size);
		for (int commandIndex = 0; commandIndex < sodiumBatch.size; commandIndex++) {
			long pointerAddress = sodiumBatch.pElementPointer + ((long) commandIndex * Pointer.POINTER_SIZE);
			long elementPointer = MemoryUtil.memGetAddress(pointerAddress);
			if ((elementPointer & (Integer.BYTES - 1L)) != 0L) {
				return List.of("unaligned-index-pointer");
			}

			int firstIndex = Math.toIntExact(elementPointer / Integer.BYTES);
			int elementCount = MemoryUtil.memGetInt(sodiumBatch.pElementCount + ((long) commandIndex * Integer.BYTES));
			int baseVertex = MemoryUtil.memGetInt(sodiumBatch.pBaseVertex + ((long) commandIndex * Integer.BYTES));
			commandKeys.add(elementCount + ":1:" + firstIndex + ":" + baseVertex + ":0");
		}
		commandKeys.sort(String::compareTo);
		return commandKeys;
	}

	private static int appendTranslatedIndexedBatch(
		IndexedIndirectCommandBuffer commandBuffer,
		MultiDrawBatch batch,
		PreparedTranslatedSceneIds translatedSceneIds
	) {
		int initialCommandCount = commandBuffer.commandCount();

		for (int i = 0; i < batch.size; i++) {
			long pointerAddress = batch.pElementPointer + ((long) i * Pointer.POINTER_SIZE);
			long elementPointer = MemoryUtil.memGetAddress(pointerAddress);
			if ((elementPointer & (Integer.BYTES - 1L)) != 0L) {
				commandBuffer.rewindToCommandCount(initialCommandCount);
				throw new IllegalStateException("Indirect index pointer is not aligned to 32-bit indices.");
			}

			int firstIndex = Math.toIntExact(elementPointer / Integer.BYTES);
			int elementCount = MemoryUtil.memGetInt(batch.pElementCount + ((long) i * Integer.BYTES));
			int baseVertex = MemoryUtil.memGetInt(batch.pBaseVertex + ((long) i * Integer.BYTES));
			int baseInstance = i < translatedSceneIds.commandCount() ? translatedSceneIds.sceneIds()[i] : 0;

			commandBuffer.addDrawElementsCommand(elementCount, 1, firstIndex, baseVertex, baseInstance);
		}

		return commandBuffer.commandCount() - initialCommandCount;
	}

	private static boolean drawTranslatedBatch(
		CommandList commandList,
		GlTessellation tessellation,
		MultiDrawBatch batch,
		RenderPassContext context
	) {
		PreparedTranslatedSceneIds translatedSceneIds = consumePreparedTranslatedSceneIds(context);
		if (commandList == null || tessellation == null) {
			recordFallback(context, batch, "missing draw state");
			return false;
		}

		int firstCommandIndex = context.commandBuffer.commandCount();
		try {
			int commandCount = appendTranslatedIndexedBatch(context.commandBuffer, batch, translatedSceneIds);
			if (commandCount <= 0) {
				recordFallback(context, batch, "empty translated batch");
				return false;
			}

			context.commandBuffer.uploadAppendedCommands(firstCommandIndex);
			try (DrawCommandList ignored = commandList.beginTessellating(tessellation)) {
				context.commandBuffer.bindForDraw();
				GL43C.glMultiDrawElementsIndirect(
					tessellation.getPrimitiveType().getId(),
					GL11C.GL_UNSIGNED_INT,
					context.commandBuffer.drawOffsetBytes(firstCommandIndex),
					commandCount,
					IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES
				);
			}

			PreparedBatch preparedBatch = PreparedBatch.draw(firstCommandIndex, commandCount, CommandSource.TRANSLATED, false);
			recordMirroredBatch(context, preparedBatch);
			debugComparePreparedBatchAgainstSodiumBatch(context, preparedBatch, batch);
			context.executedBatchCount++;
			context.executedCommandCount += commandCount;
			recordOverrideSuccess();
			return true;
		} catch (RuntimeException exception) {
			context.commandBuffer.rewindToCommandCount(firstCommandIndex);
			String reason = describeOverrideFailure(exception);
			recordFallback(context, batch, reason);
			recordOverrideFailure(reason, exception);
			return false;
		}
	}

	private static void recordFallback(RenderPassContext context, MultiDrawBatch batch, String reason) {
		context.fallbackBatchCount++;
		context.fallbackCommandCount += batch.size;
		context.lastFallbackReason = reason;
	}

	private static PreparedBatch consumePreparedBatch(RenderPassContext context) {
		PreparedBatch preparedBatch = context.pendingPreparedBatch;
		context.pendingPreparedBatch = null;
		return preparedBatch;
	}

	private static PreparedTranslatedSceneIds consumePreparedTranslatedSceneIds(RenderPassContext context) {
		PreparedTranslatedSceneIds preparedSceneIds = context.preparedTranslatedSceneIds.pollFirst();
		return preparedSceneIds != null ? preparedSceneIds : PreparedTranslatedSceneIds.EMPTY;
	}

	private static void recordMirroredBatch(RenderPassContext context, PreparedBatch preparedBatch) {
		context.mirroredBatchCount++;
		if (!preparedBatch.gpuCounted()) {
			context.mirroredCommandCount += preparedBatch.commandCount();
		}

		if (preparedBatch.source() == CommandSource.TRANSLATED) {
			context.translatedBatchCount++;
			context.translatedCommandCount += preparedBatch.commandCount();
			return;
		}

		context.generatedBatchCount++;
		if (!preparedBatch.gpuCounted()) {
			context.generatedCommandCount += preparedBatch.commandCount();
		}
		if (preparedBatch.source() == CommandSource.GENERATED_COMPUTE && !preparedBatch.gpuCounted()) {
			context.computeGeneratedBatchCount++;
			context.computeGeneratedCommandCount += preparedBatch.commandCount();
		} else if (preparedBatch.source() == CommandSource.GENERATED_COMPUTE) {
			context.computeGeneratedBatchCount++;
		}
	}

	private static void rollbackBatch(RenderPassContext context, PreparedBatch preparedBatch) {
		if (preparedBatch.source() != CommandSource.GENERATED_COMPUTE) {
			int executedCommandCount = context.executedCommandCount;
			context.commandBuffer.rewindToCommandCount(executedCommandCount);
			context.mirroredCommandCount = Math.max(context.mirroredCommandCount - preparedBatch.commandCount(), executedCommandCount);
		} else if (!preparedBatch.gpuCounted()) {
			context.mirroredCommandCount = Math.max(context.mirroredCommandCount - preparedBatch.commandCount(), 0);
		}

		context.mirroredBatchCount = Math.max(context.mirroredBatchCount - 1, context.executedBatchCount);

		if (preparedBatch.source() == CommandSource.TRANSLATED) {
			context.translatedBatchCount = Math.max(context.translatedBatchCount - 1, 0);
			context.translatedCommandCount = Math.max(context.translatedCommandCount - preparedBatch.commandCount(), 0);
			return;
		}

		context.generatedBatchCount = Math.max(context.generatedBatchCount - 1, 0);
		if (!preparedBatch.gpuCounted()) {
			context.generatedCommandCount = Math.max(context.generatedCommandCount - preparedBatch.commandCount(), 0);
		}
		if (preparedBatch.source() == CommandSource.GENERATED_COMPUTE && !preparedBatch.gpuCounted()) {
			context.computeGeneratedBatchCount = Math.max(context.computeGeneratedBatchCount - 1, 0);
			context.computeGeneratedCommandCount = Math.max(context.computeGeneratedCommandCount - preparedBatch.commandCount(), 0);
		} else if (preparedBatch.source() == CommandSource.GENERATED_COMPUTE) {
			context.computeGeneratedBatchCount = Math.max(context.computeGeneratedBatchCount - 1, 0);
		}
	}

	private static void appendLocalIndexedCommands(
		IndexedCommandScratchBuffer commandBuffer,
		long dataPointer,
		int visibleFaces,
		int sectionSceneId
	) {
		long baseElement = SectionRenderDataUnsafe.getBaseElement(dataPointer);
		long baseVertex = SectionRenderDataUnsafe.getBaseVertex(dataPointer);

		for (int facing = 0; facing < FACING_COUNT; facing++) {
			long vertexCount = SectionRenderDataUnsafe.getVertexCount(dataPointer, facing);
			long elementCount = (vertexCount >> 2) * 6L;
			if (((visibleFaces >>> facing) & 1) != 0) {
				commandBuffer.addDrawElementsCommand(
					Math.toIntExact(elementCount),
					1,
					Math.toIntExact(baseElement),
					Math.toIntExact(baseVertex),
					sectionSceneId
				);
			}

			baseVertex += vertexCount;
			baseElement += elementCount;
		}
	}

	private static void appendSharedIndexedCommands(
		IndexedCommandScratchBuffer commandBuffer,
		long dataPointer,
		int visibleFaces,
		int sectionSceneId
	) {
		long baseElement = SectionRenderDataUnsafe.getBaseElement(dataPointer);
		long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
		long visibleRunVertexCount = 0L;
		long currentBaseVertex = SectionRenderDataUnsafe.getBaseVertex(dataPointer);
		boolean previousVisible = false;

		for (int facing = 0; facing <= FACING_COUNT; facing++) {
			boolean currentVisible = false;
			long vertexCount = 0L;

			if (facing < FACING_COUNT) {
				vertexCount = SectionRenderDataUnsafe.getVertexCount(dataPointer, facing);
				if (vertexCount != 0L) {
					long faceOrder = (facingList >>> (facing * 8)) & 0xFFL;
					currentVisible = ((visibleFaces >>> (int) faceOrder) & 1) != 0;
				}
			}

			if (!currentVisible) {
				if (previousVisible) {
					if (facing < FACING_COUNT && vertexCount == 0L) {
						continue;
					}

					long elementCount = (visibleRunVertexCount >> 2) * 6L;
					commandBuffer.addDrawElementsCommand(
						Math.toIntExact(elementCount),
						1,
						Math.toIntExact(baseElement),
						Math.toIntExact(currentBaseVertex),
						sectionSceneId
					);
					currentBaseVertex += visibleRunVertexCount;
					visibleRunVertexCount = 0L;
				}

				currentBaseVertex += vertexCount;
			} else {
				visibleRunVertexCount += vertexCount;
			}

			previousVisible = currentVisible;
		}
	}

	private static void drawIndexedIndirectCount(
		GlTessellation tessellation,
		long indirectOffsetBytes,
		long drawCountOffsetBytes,
		int maxDrawCount
	) {
		if (GLCapabilities.isVersion46()) {
			GL46C.glMultiDrawElementsIndirectCount(
				tessellation.getPrimitiveType().getId(),
				GL11C.GL_UNSIGNED_INT,
				indirectOffsetBytes,
				drawCountOffsetBytes,
				maxDrawCount,
				IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES
			);
			return;
		}

		ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(
			tessellation.getPrimitiveType().getId(),
			GL11C.GL_UNSIGNED_INT,
			indirectOffsetBytes,
			drawCountOffsetBytes,
			maxDrawCount,
			IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES
		);
	}

	private static boolean isRegionVisible(Viewport viewport, RenderRegion region) {
		CameraTransform cameraTransform = viewport.getTransform();
		float relativeCenterX = (region.getOriginX() + REGION_HALF_WIDTH_BLOCKS) - (cameraTransform.intX + cameraTransform.fracX);
		float relativeCenterY = (region.getOriginY() + REGION_HALF_HEIGHT_BLOCKS) - (cameraTransform.intY + cameraTransform.fracY);
		float relativeCenterZ = (region.getOriginZ() + REGION_HALF_LENGTH_BLOCKS) - (cameraTransform.intZ + cameraTransform.fracZ);
		return viewport.isBoxVisibleDirect(
			relativeCenterX,
			relativeCenterY,
			relativeCenterZ,
			REGION_FRUSTUM_RADIUS
		);
	}

	private static boolean isSectionVisible(Viewport viewport, int sectionChunkX, int sectionChunkY, int sectionChunkZ) {
		return viewport.isBoxVisible(sectionChunkX, sectionChunkY, sectionChunkZ);
	}

	private static void prepareTranslatedSceneIds(
		RenderPassContext context,
		SectionVisibilityCompute.SectionSceneIds sceneIds,
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		TerrainRenderPass renderPass,
		boolean fragmentDiscard
	) {
		boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		boolean preferLocalIndices = renderPass.isTranslucent() && fragmentDiscard;
		ByteIterator iterator = renderList.sectionsWithGeometryIterator(renderPass.isTranslucent());
		if (iterator == null) {
			context.preparedTranslatedSceneIds.addLast(PreparedTranslatedSceneIds.EMPTY);
			return;
		}

		int[] commandSceneIds = new int[Math.max(estimateCommandCapacity(renderList), 1)];
		int commandCount = 0;
		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			long dataPointer = storage.getDataPointer(localSectionIndex);
			int sectionChunkX = regionChunkX + LOCAL_SECTION_CHUNK_X[localSectionIndex];
			int sectionChunkY = regionChunkY + LOCAL_SECTION_CHUNK_Y[localSectionIndex];
			int sectionChunkZ = regionChunkZ + LOCAL_SECTION_CHUNK_Z[localSectionIndex];
			int visibleFaces = useBlockFaceCulling
				? DefaultChunkRenderer.getVisibleFaces(
					cameraTransform.intX,
					cameraTransform.intY,
					cameraTransform.intZ,
					sectionChunkX,
					sectionChunkY,
					sectionChunkZ
				)
				: ALL_FACES_MASK;
			visibleFaces &= SectionRenderDataUnsafe.getSliceMask(dataPointer);
			if (visibleFaces == 0) {
				continue;
			}

			int sectionSceneId = sceneIds.sectionSceneId(localSectionIndex);
			if (preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer)) {
				commandCount = appendLocalSceneIds(commandSceneIds, commandCount, visibleFaces, sectionSceneId);
			} else {
				commandCount = appendSharedSceneIds(commandSceneIds, commandCount, dataPointer, visibleFaces, sectionSceneId);
			}
		}

		context.preparedTranslatedSceneIds.addLast(
			commandCount == 0
				? PreparedTranslatedSceneIds.EMPTY
				: new PreparedTranslatedSceneIds(Arrays.copyOf(commandSceneIds, commandCount), commandCount)
		);
	}

	private static int appendLocalSceneIds(int[] commandSceneIds, int commandCount, int visibleFaces, int sectionSceneId) {
		for (int facing = 0; facing < FACING_COUNT; facing++) {
			if (((visibleFaces >>> facing) & 1) != 0) {
				commandSceneIds[commandCount++] = sectionSceneId;
			}
		}

		return commandCount;
	}

	private static int appendSharedSceneIds(
		int[] commandSceneIds,
		int commandCount,
		long dataPointer,
		int visibleFaces,
		int sectionSceneId
	) {
		long facingList = SectionRenderDataUnsafe.getFacingList(dataPointer);
		boolean previousVisible = false;

		for (int facing = 0; facing <= FACING_COUNT; facing++) {
			boolean currentVisible = false;
			long vertexCount = 0L;

			if (facing < FACING_COUNT) {
				vertexCount = SectionRenderDataUnsafe.getVertexCount(dataPointer, facing);
				if (vertexCount != 0L) {
					long faceOrder = (facingList >>> (facing * 8)) & 0xFFL;
					currentVisible = ((visibleFaces >>> (int) faceOrder) & 1) != 0;
				}
			}

			if (!currentVisible && previousVisible) {
				if (facing < FACING_COUNT && vertexCount == 0L) {
					continue;
				}

				commandSceneIds[commandCount++] = sectionSceneId;
			}

			previousVisible = currentVisible;
		}

		return commandCount;
	}

	private static int estimateCommandCapacity(ChunkRenderList renderList) {
		return Math.max(renderList.getSectionsWithGeometryCount(), 1) * FACING_COUNT;
	}

	private static void disableDrawOverride(String reason) {
		drawOverrideEnabled = false;
		drawOverrideDisableReason = reason;
	}

	private static void recordOverrideSuccess() {
		drawOverrideSuccessCount++;
		consecutiveOverrideFailureCount = 0;
	}

	private static void recordOverrideFailure(String reason, RuntimeException exception) {
		drawOverrideFailureCount++;
		consecutiveOverrideFailureCount++;

		if (consecutiveOverrideFailureCount >= MAX_CONSECUTIVE_OVERRIDE_FAILURES) {
			disableDrawOverride(reason);
			PotassiumClientMod.LOGGER.error(
				"Potassium disabled Sodium draw override after {} consecutive failures. Last reason: {}",
				consecutiveOverrideFailureCount,
				reason,
				exception
			);
			return;
		}

		PotassiumClientMod.LOGGER.warn(
			"Potassium fell back to Sodium for one draw batch (failure {}/{}). Reason: {}",
			consecutiveOverrideFailureCount,
			MAX_CONSECUTIVE_OVERRIDE_FAILURES,
			reason,
			exception
		);
	}

	private static String describeOverrideFailure(RuntimeException exception) {
		String message = exception.getMessage();
		if (message != null && !message.isBlank()) {
			return message;
		}

		return exception.getClass().getSimpleName();
	}

	private static int computeCommandFillWorkerCount() {
		return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 6));
	}

	private static ExecutorService createCommandFillExecutor() {
		ThreadFactory threadFactory = new ThreadFactory() {
			private final AtomicInteger nextThreadId = new AtomicInteger(1);

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "Potassium-CommandFill-" + this.nextThreadId.getAndIncrement());
				thread.setDaemon(true);
				return thread;
			}
		};

		return Executors.newFixedThreadPool(COMMAND_FILL_WORKER_COUNT, threadFactory);
	}

	private static final class RenderPassContext {
		private TerrainRenderPass pass;
		private int visibleRegionCount;
		private Viewport viewport;
		private final float[] computeFrustumPlanes = new float[24];
		private final List<RegionBatchInput> regionInputs = new ArrayList<>();
		private final Map<RenderRegion, GeneratedCommandBatch> preparedGeneratedBatches = new IdentityHashMap<>();
		private final Map<RenderRegion, CompletableFuture<GeneratedCommandBatch>> scheduledGeneratedBatches = new IdentityHashMap<>();
		private final ArrayDeque<PreparedTranslatedSceneIds> preparedTranslatedSceneIds = new ArrayDeque<>();
		private IndexedIndirectCommandBuffer commandBuffer;
		private PreparedBatch pendingPreparedBatch;
		private boolean preparedBatchesScheduled;
		private boolean persistentMappingEnabled;
		private boolean computeCullingEnabled;
		private int scheduledBatchCount;
		private int asyncReadyBatchCount;
		private int asyncWaitedBatchCount;
		private int asyncFailedBatchCount;
		private int syncGeneratedBatchCount;
		private int computeDispatchCount;
		private int computeFailureCount;
		private int computeComparedBatchCount;
		private int computeMatchedBatchCount;
		private int computeMismatchedBatchCount;
		private int computeSkipMismatchCount;
		private int computeCommandCountMismatchCount;
		private int computeVisibleSectionMismatchCount;
		private int computeCommandMismatchCount;
		private String lastComputeMismatchReason = "none";
		private int drawComparedBatchCount;
		private int drawMatchedBatchCount;
		private int drawMismatchedBatchCount;
		private String lastDrawMismatchReason = "none";
		private int generatedSkipBatchCount;
		private int frustumTestedRegionCount;
		private int frustumCulledRegionCount;
		private int frustumTestedSectionCount;
		private int frustumVisibleSectionCount;
		private int frustumCulledSectionCount;
		private int seenBatchCount;
		private int seenCommandCount;
		private int mirroredBatchCount;
		private int mirroredCommandCount;
		private int generatedBatchCount;
		private int generatedCommandCount;
		private int computeGeneratedBatchCount;
		private int computeGeneratedCommandCount;
		private int translatedBatchCount;
		private int translatedCommandCount;
		private int executedBatchCount;
		private int executedCommandCount;
		private int culledBatchCount;
		private int fallbackBatchCount;
		private int fallbackCommandCount;
		private String lastFallbackReason = "none";

		private RenderPassContext() {
		}

		private void begin(TerrainRenderPass pass, Viewport viewport) {
			this.pass = pass;
			this.visibleRegionCount = 0;
			this.viewport = viewport;
			this.commandBuffer = null;
			this.pendingPreparedBatch = null;
			this.preparedBatchesScheduled = false;
			this.persistentMappingEnabled = false;
			this.computeCullingEnabled = false;
			this.scheduledBatchCount = 0;
			this.asyncReadyBatchCount = 0;
			this.asyncWaitedBatchCount = 0;
			this.asyncFailedBatchCount = 0;
			this.syncGeneratedBatchCount = 0;
			this.computeDispatchCount = 0;
			this.computeFailureCount = 0;
			this.computeComparedBatchCount = 0;
			this.computeMatchedBatchCount = 0;
			this.computeMismatchedBatchCount = 0;
			this.computeSkipMismatchCount = 0;
			this.computeCommandCountMismatchCount = 0;
			this.computeVisibleSectionMismatchCount = 0;
			this.computeCommandMismatchCount = 0;
			this.lastComputeMismatchReason = "none";
			this.drawComparedBatchCount = 0;
			this.drawMatchedBatchCount = 0;
			this.drawMismatchedBatchCount = 0;
			this.lastDrawMismatchReason = "none";
			this.generatedSkipBatchCount = 0;
			this.frustumTestedRegionCount = 0;
			this.frustumCulledRegionCount = 0;
			this.frustumTestedSectionCount = 0;
			this.frustumVisibleSectionCount = 0;
			this.frustumCulledSectionCount = 0;
			this.seenBatchCount = 0;
			this.seenCommandCount = 0;
			this.mirroredBatchCount = 0;
			this.mirroredCommandCount = 0;
			this.generatedBatchCount = 0;
			this.generatedCommandCount = 0;
			this.computeGeneratedBatchCount = 0;
			this.computeGeneratedCommandCount = 0;
			this.translatedBatchCount = 0;
			this.translatedCommandCount = 0;
			this.executedBatchCount = 0;
			this.executedCommandCount = 0;
			this.culledBatchCount = 0;
			this.fallbackBatchCount = 0;
			this.fallbackCommandCount = 0;
			this.lastFallbackReason = "none";
			this.preparedGeneratedBatches.clear();
			this.scheduledGeneratedBatches.clear();
			this.preparedTranslatedSceneIds.clear();
		}

		private void finish() {
			this.pendingPreparedBatch = null;
			this.commandBuffer = null;
			this.preparedGeneratedBatches.clear();
			this.scheduledGeneratedBatches.clear();
			this.preparedTranslatedSceneIds.clear();
			this.viewport = null;
			this.pass = null;
		}

		private String describePass() {
			return this.pass != null && this.pass.isTranslucent() ? "translucent" : "opaque";
		}
	}

	private static final class BridgeDebugStats {
		private String lastPass = "none";
		private int lastVisibleRegionCount;
		private int lastSeenBatchCount;
		private int lastSeenCommandCount;
		private int lastBatchCount;
		private int lastCommandCount;
		private int lastGeneratedBatchCount;
		private int lastGeneratedCommandCount;
		private int lastComputeGeneratedBatchCount;
		private int lastComputeGeneratedCommandCount;
		private int lastTranslatedBatchCount;
		private int lastTranslatedCommandCount;
		private int lastBufferCapacity;
		private int lastBufferBytes;
		private int lastResidentRegionCount;
		private int lastResidentSectionCount;
		private int lastResidentLocalIndexSectionCount;
		private int lastResidentSharedIndexSectionCount;
		private int lastResidentMetadataBytes;
		private int lastMaxResidentSceneId;
		private int lastMaxResidentSectionId;
		private int lastExecutedBatchCount;
		private int lastExecutedCommandCount;
		private int lastCulledBatchCount;
		private int lastFallbackBatchCount;
		private int lastFallbackCommandCount;
		private String lastFallbackReason = "none";
		private boolean lastPassFullyOverridden;
		private boolean lastPersistentMappingEnabled;
		private boolean lastComputeCullingEnabled;
		private int lastScheduledBatchCount;
		private int lastAsyncReadyBatchCount;
		private int lastAsyncWaitedBatchCount;
		private int lastAsyncFailedBatchCount;
		private int lastSyncGeneratedBatchCount;
		private int lastComputeDispatchCount;
		private int lastComputeFailureCount;
		private int lastComputeComparedBatchCount;
		private int lastComputeMatchedBatchCount;
		private int lastComputeMismatchedBatchCount;
		private String lastComputeMismatchReason = "none";
		private int lastDrawComparedBatchCount;
		private int lastDrawMatchedBatchCount;
		private int lastDrawMismatchedBatchCount;
		private String lastDrawMismatchReason = "none";
		private int lastGeneratedSkipBatchCount;
		private int lastFrustumTestedRegionCount;
		private int lastFrustumCulledRegionCount;
		private int lastFrustumTestedSectionCount;
		private int lastFrustumVisibleSectionCount;
		private int lastFrustumCulledSectionCount;
		private boolean lastOpaqueComputeCullingEnabled;
		private int lastOpaqueComputeDispatchCount;
		private int lastOpaqueComputeFailureCount;
		private int lastOpaqueComputeComparedBatchCount;
		private int lastOpaqueComputeMatchedBatchCount;
		private int lastOpaqueComputeMismatchedBatchCount;
		private int lastOpaqueComputeSkipMismatchCount;
		private int lastOpaqueComputeCommandCountMismatchCount;
		private int lastOpaqueComputeVisibleSectionMismatchCount;
		private int lastOpaqueComputeCommandMismatchCount;
		private String lastOpaqueComputeMismatchReason = "none";
		private int lastOpaqueComputeGeneratedBatchCount;
		private int lastOpaqueComputeGeneratedCommandCount;
		private long overriddenPassCount;
		private long fallbackPassCount;
		private long completedPassCount;

		private void recordPass(RenderPassContext context) {
			this.lastPass = context.describePass();
			this.lastVisibleRegionCount = context.visibleRegionCount;
			this.lastSeenBatchCount = context.seenBatchCount;
			this.lastSeenCommandCount = context.seenCommandCount;
			this.lastBatchCount = context.mirroredBatchCount;
			this.lastCommandCount = context.mirroredCommandCount;
			this.lastGeneratedBatchCount = context.generatedBatchCount;
			this.lastGeneratedCommandCount = context.generatedCommandCount;
			this.lastComputeGeneratedBatchCount = context.computeGeneratedBatchCount;
			this.lastComputeGeneratedCommandCount = context.computeGeneratedCommandCount;
			this.lastTranslatedBatchCount = context.translatedBatchCount;
			this.lastTranslatedCommandCount = context.translatedCommandCount;
			this.lastBufferCapacity = context.commandBuffer.capacityCommands();
			this.lastBufferBytes = context.commandBuffer.usedBytes();
			GpuResidentGeometryBookkeeping.Snapshot residentSnapshot = GpuResidentGeometryBookkeeping.snapshot();
			this.lastResidentRegionCount = residentSnapshot.residentRegionCount();
			this.lastResidentSectionCount = residentSnapshot.residentSectionCount();
			this.lastResidentLocalIndexSectionCount = residentSnapshot.residentLocalIndexSectionCount();
			this.lastResidentSharedIndexSectionCount = residentSnapshot.residentSharedIndexSectionCount();
			this.lastResidentMetadataBytes = residentSnapshot.residentMetadataBytes();
			this.lastMaxResidentSceneId = residentSnapshot.maxSceneId();
			this.lastMaxResidentSectionId = residentSnapshot.maxSectionId();
			this.lastExecutedBatchCount = context.executedBatchCount;
			this.lastExecutedCommandCount = context.executedCommandCount;
			this.lastCulledBatchCount = context.culledBatchCount;
			this.lastFallbackBatchCount = context.fallbackBatchCount;
			this.lastFallbackCommandCount = context.fallbackCommandCount;
			this.lastFallbackReason = context.lastFallbackReason;
			this.lastPassFullyOverridden = context.seenBatchCount > 0 && context.fallbackBatchCount == 0;
			this.lastPersistentMappingEnabled = context.persistentMappingEnabled;
			this.lastComputeCullingEnabled = context.computeCullingEnabled;
			this.lastScheduledBatchCount = context.scheduledBatchCount;
			this.lastAsyncReadyBatchCount = context.asyncReadyBatchCount;
			this.lastAsyncWaitedBatchCount = context.asyncWaitedBatchCount;
			this.lastAsyncFailedBatchCount = context.asyncFailedBatchCount;
			this.lastSyncGeneratedBatchCount = context.syncGeneratedBatchCount;
			this.lastComputeDispatchCount = context.computeDispatchCount;
			this.lastComputeFailureCount = context.computeFailureCount;
			this.lastComputeComparedBatchCount = context.computeComparedBatchCount;
			this.lastComputeMatchedBatchCount = context.computeMatchedBatchCount;
			this.lastComputeMismatchedBatchCount = context.computeMismatchedBatchCount;
			this.lastComputeMismatchReason = context.lastComputeMismatchReason;
			this.lastDrawComparedBatchCount = context.drawComparedBatchCount;
			this.lastDrawMatchedBatchCount = context.drawMatchedBatchCount;
			this.lastDrawMismatchedBatchCount = context.drawMismatchedBatchCount;
			this.lastDrawMismatchReason = context.lastDrawMismatchReason;
			this.lastGeneratedSkipBatchCount = context.generatedSkipBatchCount;
			this.lastFrustumTestedRegionCount = context.frustumTestedRegionCount;
			this.lastFrustumCulledRegionCount = context.frustumCulledRegionCount;
			this.lastFrustumTestedSectionCount = context.frustumTestedSectionCount;
			this.lastFrustumVisibleSectionCount = context.frustumVisibleSectionCount;
			this.lastFrustumCulledSectionCount = context.frustumCulledSectionCount;
			if (!context.pass.isTranslucent()) {
				this.lastOpaqueComputeCullingEnabled = context.computeCullingEnabled;
				this.lastOpaqueComputeDispatchCount = context.computeDispatchCount;
				this.lastOpaqueComputeFailureCount = context.computeFailureCount;
				this.lastOpaqueComputeComparedBatchCount = context.computeComparedBatchCount;
				this.lastOpaqueComputeMatchedBatchCount = context.computeMatchedBatchCount;
				this.lastOpaqueComputeMismatchedBatchCount = context.computeMismatchedBatchCount;
				this.lastOpaqueComputeSkipMismatchCount = context.computeSkipMismatchCount;
				this.lastOpaqueComputeCommandCountMismatchCount = context.computeCommandCountMismatchCount;
				this.lastOpaqueComputeVisibleSectionMismatchCount = context.computeVisibleSectionMismatchCount;
				this.lastOpaqueComputeCommandMismatchCount = context.computeCommandMismatchCount;
				this.lastOpaqueComputeMismatchReason = context.lastComputeMismatchReason;
				this.lastOpaqueComputeGeneratedBatchCount = context.computeGeneratedBatchCount;
				this.lastOpaqueComputeGeneratedCommandCount = context.computeGeneratedCommandCount;
			}
			if (this.lastPassFullyOverridden) {
				this.overriddenPassCount++;
			} else if (context.seenBatchCount > 0) {
				this.fallbackPassCount++;
			}
			this.completedPassCount++;
		}

		private List<String> toDebugLines(
			boolean showSummary,
			boolean showOverrideStats,
			boolean showGenerationStats,
			boolean showComputeStats,
			boolean showCullingStats,
			boolean showBufferStats,
			boolean showFallbackStats,
			boolean bridgeInstalled,
			boolean overrideEnabled,
			String overrideDisableReason,
			long overrideAttemptCount,
			long overrideSuccessCount,
			long overrideFailureCount,
			int consecutiveOverrideFailureCount
		) {
			List<String> lines = new ArrayList<>();
			lines.add(tr("potassium.debug.title"));
			if (showSummary) {
				lines.add(tr("potassium.debug.summary.bridge_installed", bridgeInstalled));
				lines.add(tr("potassium.debug.summary.draw_override", overrideEnabled));
				lines.add(tr("potassium.debug.summary.last_pass", this.lastPass));
				lines.add(tr("potassium.debug.summary.last_pass_fully_overridden", this.lastPassFullyOverridden));
				lines.add(tr("potassium.debug.summary.completed_passes", this.completedPassCount));
			}
			if (showOverrideStats) {
				lines.add(tr("potassium.debug.override.attempts", overrideAttemptCount));
				lines.add(tr("potassium.debug.override.successes", overrideSuccessCount));
				lines.add(tr("potassium.debug.override.failures", overrideFailureCount));
				lines.add(tr("potassium.debug.override.consecutive_failures", consecutiveOverrideFailureCount));
			}
			if (showGenerationStats) {
				lines.add(tr("potassium.debug.generation.seen_batches", this.lastSeenBatchCount));
				lines.add(tr("potassium.debug.generation.seen_commands", this.lastSeenCommandCount));
				lines.add(tr("potassium.debug.generation.mirrored_batches", this.lastBatchCount));
				lines.add(tr("potassium.debug.generation.mirrored_commands", this.lastCommandCount));
				lines.add(tr("potassium.debug.generation.generated_batches", this.lastGeneratedBatchCount));
				lines.add(tr("potassium.debug.generation.generated_commands", this.lastGeneratedCommandCount));
				lines.add(tr("potassium.debug.generation.compute_generated_batches", this.lastComputeGeneratedBatchCount));
				lines.add(tr("potassium.debug.generation.compute_generated_commands", this.lastComputeGeneratedCommandCount));
				lines.add(tr("potassium.debug.generation.translated_batches", this.lastTranslatedBatchCount));
				lines.add(tr("potassium.debug.generation.translated_commands", this.lastTranslatedCommandCount));
				lines.add(tr("potassium.debug.generation.scheduled_batches", this.lastScheduledBatchCount));
				lines.add(tr("potassium.debug.generation.async_ready_batches", this.lastAsyncReadyBatchCount));
				lines.add(tr("potassium.debug.generation.async_waited_batches", this.lastAsyncWaitedBatchCount));
				lines.add(tr("potassium.debug.generation.async_failed_batches", this.lastAsyncFailedBatchCount));
				lines.add(tr("potassium.debug.generation.sync_generated_batches", this.lastSyncGeneratedBatchCount));
				lines.add(tr("potassium.debug.generation.potassium_batches", this.lastExecutedBatchCount));
				lines.add(tr("potassium.debug.generation.potassium_commands", this.lastExecutedCommandCount));
			}
			if (showComputeStats) {
				lines.add(tr("potassium.debug.compute.compute_culling", this.lastComputeCullingEnabled));
				lines.add(tr("potassium.debug.compute.opaque_enabled", this.lastOpaqueComputeCullingEnabled));
				lines.add(tr("potassium.debug.compute.opaque_dispatches", this.lastOpaqueComputeDispatchCount));
				lines.add(tr("potassium.debug.compute.opaque_failures", this.lastOpaqueComputeFailureCount));
				lines.add(tr("potassium.debug.compute.opaque_generated_batches", this.lastOpaqueComputeGeneratedBatchCount));
				lines.add(tr("potassium.debug.compute.opaque_generated_commands", this.lastOpaqueComputeGeneratedCommandCount));
				lines.add(tr("potassium.debug.compute.dispatches", this.lastComputeDispatchCount));
				lines.add(tr("potassium.debug.compute.failures", this.lastComputeFailureCount));
			}
			if (showCullingStats) {
				lines.add(tr("potassium.debug.culling.visible_regions", this.lastVisibleRegionCount));
				lines.add(tr("potassium.debug.culling.generated_skip_batches", this.lastGeneratedSkipBatchCount));
				lines.add(tr("potassium.debug.culling.culled_batches", this.lastCulledBatchCount));
				lines.add(tr("potassium.debug.culling.frustum_tested_regions", this.lastFrustumTestedRegionCount));
				lines.add(tr("potassium.debug.culling.frustum_culled_regions", this.lastFrustumCulledRegionCount));
				lines.add(tr("potassium.debug.culling.frustum_tested_sections", this.lastFrustumTestedSectionCount));
				lines.add(tr("potassium.debug.culling.frustum_visible_sections", this.lastFrustumVisibleSectionCount));
				lines.add(tr("potassium.debug.culling.frustum_culled_sections", this.lastFrustumCulledSectionCount));
			}
			if (showBufferStats) {
				lines.add(tr("potassium.debug.buffer.command_fill_workers", COMMAND_FILL_WORKER_COUNT));
				lines.add(tr("potassium.debug.buffer.persistent_mapping", this.lastPersistentMappingEnabled));
				lines.add(tr("potassium.debug.buffer.indexed_indirect_buffer", this.lastCommandCount, this.lastBufferCapacity));
				lines.add(tr("potassium.debug.buffer.indexed_command_bytes", this.lastBufferBytes));
				lines.add(tr("potassium.debug.buffer.resident_regions", this.lastResidentRegionCount, this.lastResidentSectionCount));
				lines.add(tr("potassium.debug.buffer.resident_index_modes", this.lastResidentLocalIndexSectionCount, this.lastResidentSharedIndexSectionCount));
				lines.add(tr("potassium.debug.buffer.resident_metadata_bytes", this.lastResidentMetadataBytes));
				lines.add(tr("potassium.debug.buffer.resident_scene_ids", this.lastMaxResidentSceneId, this.lastMaxResidentSectionId));
			}
			if (showFallbackStats) {
				lines.add(tr("potassium.debug.fallback.override_disable_reason", overrideDisableReason));
				lines.add(tr("potassium.debug.fallback.fallback_batches", this.lastFallbackBatchCount));
				lines.add(tr("potassium.debug.fallback.fallback_commands", this.lastFallbackCommandCount));
				lines.add(tr("potassium.debug.fallback.last_fallback_reason", this.lastFallbackReason));
				lines.add(tr("potassium.debug.fallback.overridden_passes", this.overriddenPassCount));
				lines.add(tr("potassium.debug.fallback.fallback_passes", this.fallbackPassCount));
			}
			return lines;
		}

		private void reset() {
			this.lastPass = "none";
			this.lastVisibleRegionCount = 0;
			this.lastSeenBatchCount = 0;
			this.lastSeenCommandCount = 0;
			this.lastBatchCount = 0;
			this.lastCommandCount = 0;
			this.lastGeneratedBatchCount = 0;
			this.lastGeneratedCommandCount = 0;
			this.lastComputeGeneratedBatchCount = 0;
			this.lastComputeGeneratedCommandCount = 0;
			this.lastTranslatedBatchCount = 0;
			this.lastTranslatedCommandCount = 0;
			this.lastBufferCapacity = 0;
			this.lastBufferBytes = 0;
			this.lastResidentRegionCount = 0;
			this.lastResidentSectionCount = 0;
			this.lastResidentLocalIndexSectionCount = 0;
			this.lastResidentSharedIndexSectionCount = 0;
			this.lastResidentMetadataBytes = 0;
			this.lastMaxResidentSceneId = 0;
			this.lastMaxResidentSectionId = 0;
			this.lastExecutedBatchCount = 0;
			this.lastExecutedCommandCount = 0;
			this.lastCulledBatchCount = 0;
			this.lastFallbackBatchCount = 0;
			this.lastFallbackCommandCount = 0;
			this.lastFallbackReason = "none";
			this.lastPassFullyOverridden = false;
			this.lastPersistentMappingEnabled = false;
			this.lastComputeCullingEnabled = false;
			this.lastScheduledBatchCount = 0;
			this.lastAsyncReadyBatchCount = 0;
			this.lastAsyncWaitedBatchCount = 0;
			this.lastAsyncFailedBatchCount = 0;
			this.lastSyncGeneratedBatchCount = 0;
			this.lastComputeDispatchCount = 0;
			this.lastComputeFailureCount = 0;
			this.lastComputeComparedBatchCount = 0;
			this.lastComputeMatchedBatchCount = 0;
			this.lastComputeMismatchedBatchCount = 0;
			this.lastComputeMismatchReason = "none";
			this.lastDrawComparedBatchCount = 0;
			this.lastDrawMatchedBatchCount = 0;
			this.lastDrawMismatchedBatchCount = 0;
			this.lastDrawMismatchReason = "none";
			this.lastGeneratedSkipBatchCount = 0;
			this.lastFrustumTestedRegionCount = 0;
			this.lastFrustumCulledRegionCount = 0;
			this.lastFrustumTestedSectionCount = 0;
			this.lastFrustumVisibleSectionCount = 0;
			this.lastFrustumCulledSectionCount = 0;
			this.lastOpaqueComputeCullingEnabled = false;
			this.lastOpaqueComputeDispatchCount = 0;
			this.lastOpaqueComputeFailureCount = 0;
			this.lastOpaqueComputeComparedBatchCount = 0;
			this.lastOpaqueComputeMatchedBatchCount = 0;
			this.lastOpaqueComputeMismatchedBatchCount = 0;
			this.lastOpaqueComputeSkipMismatchCount = 0;
			this.lastOpaqueComputeCommandCountMismatchCount = 0;
			this.lastOpaqueComputeVisibleSectionMismatchCount = 0;
			this.lastOpaqueComputeCommandMismatchCount = 0;
			this.lastOpaqueComputeMismatchReason = "none";
			this.lastOpaqueComputeGeneratedBatchCount = 0;
			this.lastOpaqueComputeGeneratedCommandCount = 0;
			this.overriddenPassCount = 0L;
			this.fallbackPassCount = 0L;
			this.completedPassCount = 0L;
		}
	}

	private static String tr(String key, Object... args) {
		return I18n.get(key, args);
	}

	private enum CommandSource {
		GENERATED_CPU,
		GENERATED_COMPUTE,
		TRANSLATED
	}

	private record PreparedBatch(
		int firstCommandIndex,
		int commandCount,
		long drawCountOffsetBytes,
		CommandSource source,
		boolean skipDraw,
		boolean needsUpload,
		boolean gpuCounted
	) {
		private static PreparedBatch draw(int firstCommandIndex, int commandCount, CommandSource source, boolean needsUpload) {
			return new PreparedBatch(firstCommandIndex, commandCount, 0L, source, false, needsUpload, false);
		}

		private static PreparedBatch drawCounted(
			int firstCommandIndex,
			int maxCommandCount,
			long drawCountOffsetBytes,
			CommandSource source
		) {
			return new PreparedBatch(firstCommandIndex, maxCommandCount, drawCountOffsetBytes, source, false, false, true);
		}

		private static PreparedBatch skip(CommandSource source) {
			return new PreparedBatch(0, 0, 0L, source, true, false, false);
		}
	}

	private record PreparedTranslatedSceneIds(int[] sceneIds, int commandCount) {
		private static final PreparedTranslatedSceneIds EMPTY = new PreparedTranslatedSceneIds(new int[0], 0);
	}

	private record GeneratedCommandBatch(
		ByteBuffer commands,
		int firstCommandIndex,
		int commandCount,
		long drawCountOffsetBytes,
		boolean skipDraw,
		boolean gpuGenerated,
		boolean gpuCounted,
		int frustumTestedRegionCount,
		int frustumCulledRegionCount,
		int frustumTestedSectionCount,
		int frustumVisibleSectionCount,
		int frustumCulledSectionCount
	) {
		private static final ByteBuffer EMPTY_COMMANDS = ByteBuffer.allocate(0);

		private static GeneratedCommandBatch draw(
			ByteBuffer commands,
			int commandCount,
			int frustumTestedRegionCount,
			int frustumCulledRegionCount,
			int frustumTestedSectionCount,
			int frustumVisibleSectionCount,
			int frustumCulledSectionCount
		) {
			return new GeneratedCommandBatch(
				commands,
				0,
				commandCount,
				0L,
				false,
				false,
				false,
				frustumTestedRegionCount,
				frustumCulledRegionCount,
				frustumTestedSectionCount,
				frustumVisibleSectionCount,
				frustumCulledSectionCount
			);
		}

		private static GeneratedCommandBatch compute(
			int firstCommandIndex,
			int commandCount,
			int frustumTestedRegionCount,
			int frustumCulledRegionCount,
			int frustumTestedSectionCount,
			int frustumVisibleSectionCount,
			int frustumCulledSectionCount
		) {
			if (commandCount <= 0) {
				return skip(
					frustumTestedRegionCount,
					frustumCulledRegionCount,
					frustumTestedSectionCount,
					frustumVisibleSectionCount,
					frustumCulledSectionCount
				);
			}

			return new GeneratedCommandBatch(
				null,
				firstCommandIndex,
				commandCount,
				0L,
				false,
				true,
				false,
				frustumTestedRegionCount,
				frustumCulledRegionCount,
				frustumTestedSectionCount,
				frustumVisibleSectionCount,
				frustumCulledSectionCount
			);
		}

		private static GeneratedCommandBatch computeCounted(
			int firstCommandIndex,
			int maxCommandCount,
			long drawCountOffsetBytes,
			int frustumTestedRegionCount,
			int frustumCulledRegionCount,
			int frustumTestedSectionCount,
			int frustumVisibleSectionCount,
			int frustumCulledSectionCount
		) {
			return new GeneratedCommandBatch(
				null,
				firstCommandIndex,
				maxCommandCount,
				drawCountOffsetBytes,
				false,
				true,
				true,
				frustumTestedRegionCount,
				frustumCulledRegionCount,
				frustumTestedSectionCount,
				frustumVisibleSectionCount,
				frustumCulledSectionCount
			);
		}

		private static GeneratedCommandBatch skip(
			int frustumTestedRegionCount,
			int frustumCulledRegionCount,
			int frustumTestedSectionCount,
			int frustumVisibleSectionCount,
			int frustumCulledSectionCount
		) {
			return new GeneratedCommandBatch(
				EMPTY_COMMANDS,
				0,
				0,
				0L,
				true,
				false,
				false,
				frustumTestedRegionCount,
				frustumCulledRegionCount,
				frustumTestedSectionCount,
				frustumVisibleSectionCount,
				frustumCulledSectionCount
			);
		}
	}
}
