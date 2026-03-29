package com.potassium.client.compat.sodium;

import com.potassium.client.PotassiumClientMod;
import com.potassium.client.compute.SectionVisibilityCompute;
import com.potassium.client.compute.SectionVisibilityCompute.ComputePassResult;
import com.potassium.client.compute.SectionVisibilityCompute.RegionBatchInput;
import com.potassium.client.render.indirect.IndexedCommandScratchBuffer;
import com.potassium.client.render.indirect.IndexedIndirectCommandBuffer;
import com.potassium.client.render.indirect.IndirectBackend;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

public final class SodiumBridge {
	private static final int MAX_CONSECUTIVE_OVERRIDE_FAILURES = 8;
	private static final int FACING_COUNT = ModelQuadFacing.COUNT;
	private static final int ALL_FACES_MASK = ModelQuadFacing.ALL;
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
		RenderPassContext context = new RenderPassContext(pass, countVisibleRegions(renderLists, pass), currentViewport);
		IndexedIndirectCommandBuffer commandBuffer = IndirectBackend.indexedCommandBuffer();
		commandBuffer.beginFrame();
		context.commandBuffer = commandBuffer;
		context.computeCullingEnabled = SectionVisibilityCompute.isEnabled() && currentViewport != null;
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
			renderLists == null ||
			renderPass == null ||
			cameraTransform == null
		) {
			return;
		}

		if (context.computeCullingEnabled) {
			SectionVisibilityCompute.captureFrustumPlanes(matrices, context.computeFrustumPlanes);
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

		boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
		boolean preferLocalIndices = renderPass.isTranslucent() && fragmentDiscard;

		for (java.util.Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isTranslucent()); iterator.hasNext(); ) {
			ChunkRenderList renderList = iterator.next();
			RenderRegion region = renderList.getRegion();
			SectionRenderDataStorage storage = region.getStorage(renderPass);
			if (storage == null) {
				continue;
			}

			context.scheduledBatchCount++;
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
						context.viewport
					),
					COMMAND_FILL_EXECUTOR
				)
			);
		}

		context.preparedBatchesScheduled = true;
	}

	public static void prepareIndexedBatchFromRenderData(
		RenderRegion region,
		SectionRenderDataStorage storage,
		ChunkRenderList renderList,
		CameraTransform cameraTransform,
		TerrainRenderPass renderPass,
		boolean useBlockFaceCulling,
		boolean preferLocalIndices
	) {
		RenderPassContext context = ACTIVE_PASS.get();
		if (
			context == null ||
			!drawOverrideEnabled ||
			region == null ||
			storage == null ||
			renderList == null ||
			cameraTransform == null ||
			renderPass == null
		) {
			return;
		}

		if (context.pendingPreparedBatch != null) {
			if (!context.pendingPreparedBatch.skipDraw()) {
				rollbackBatch(context, context.pendingPreparedBatch);
			}
			context.pendingPreparedBatch = null;
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
				context.viewport
			);
		}

		applyGeneratedBatch(context, generatedBatch);
	}

	public static boolean tryDrawIndexedBatch(CommandList commandList, GlTessellation tessellation, MultiDrawBatch batch) {
		RenderPassContext context = ACTIVE_PASS.get();
		if (context == null || batch == null || batch.isEmpty()) {
			return false;
		}

		context.seenBatchCount++;
		context.seenCommandCount += batch.size;
		drawOverrideAttemptCount++;

		if (!drawOverrideEnabled) {
			recordFallback(context, batch, drawOverrideDisableReason);
			return false;
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
				int commandCount = appendTranslatedIndexedBatch(context.commandBuffer, batch);
				if (commandCount <= 0) {
					recordFallback(context, batch, "empty translated batch");
					return false;
				}

				preparedBatch = PreparedBatch.draw(firstCommandIndex, commandCount, CommandSource.TRANSLATED, true);
				recordMirroredBatch(context, preparedBatch);
			}

			if (preparedBatch.needsUpload()) {
				context.commandBuffer.uploadAppendedCommands(preparedBatch.firstCommandIndex());
			}

			try (DrawCommandList ignored = commandList.beginTessellating(tessellation)) {
				context.commandBuffer.bindForDraw();
				GL43C.glMultiDrawElementsIndirect(
					tessellation.getPrimitiveType().getId(),
					GL11C.GL_UNSIGNED_INT,
					context.commandBuffer.drawOffsetBytes(preparedBatch.firstCommandIndex()),
					preparedBatch.commandCount(),
					IndexedIndirectCommandBuffer.COMMAND_STRIDE_BYTES
				);
			}

			context.executedBatchCount++;
			context.executedCommandCount += preparedBatch.commandCount();
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

		ACTIVE_PASS.remove();
	}

	public static List<String> getDebugLines() {
		return DEBUG_STATS.toDebugLines(
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
		List<RegionBatchInput> regionInputs = new ArrayList<>();
		int nextFirstCommandIndex = 0;

		for (java.util.Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isTranslucent()); iterator.hasNext(); ) {
			ChunkRenderList renderList = iterator.next();
			RenderRegion region = renderList.getRegion();
			SectionRenderDataStorage storage = region.getStorage(renderPass);
			if (storage == null) {
				continue;
			}

			context.scheduledBatchCount++;
			if (context.viewport != null && !isRegionVisible(context.viewport, region)) {
				context.preparedGeneratedBatches.put(region, GeneratedCommandBatch.skip(1, 1, 0, 0, 0));
				continue;
			}

			int expectedSectionCount = renderList.getSectionsWithGeometryCount();
			if (expectedSectionCount <= 0) {
				context.preparedGeneratedBatches.put(
					region,
					GeneratedCommandBatch.skip(context.viewport != null ? 1 : 0, 0, 0, 0, 0)
				);
				continue;
			}

			int maxCommandCount = estimateCommandCapacity(renderList);
			regionInputs.add(
				new RegionBatchInput(
					region,
					storage,
					renderList,
					nextFirstCommandIndex,
					maxCommandCount,
					expectedSectionCount
				)
			);
			nextFirstCommandIndex = Math.addExact(nextFirstCommandIndex, maxCommandCount);
		}

		if (regionInputs.isEmpty()) {
			return;
		}

		context.commandBuffer.reserveGpuCommandRange(nextFirstCommandIndex);

		try {
			ComputePassResult computePassResult = SectionVisibilityCompute.generateIndexedCommands(
				context.commandBuffer,
				regionInputs,
				renderPass.isTranslucent(),
				cameraTransform,
				context.computeFrustumPlanes,
				useBlockFaceCulling,
				preferLocalIndices
			);
			if (computePassResult.dispatched()) {
				context.computeDispatchCount++;
				context.commandBuffer.commitGpuGeneratedCommands(0, nextFirstCommandIndex);
			}

			for (int regionIndex = 0; regionIndex < regionInputs.size(); regionIndex++) {
				RegionBatchInput regionInput = regionInputs.get(regionIndex);
				int testedSectionCount = computePassResult.testedSectionCounts()[regionIndex];
				int commandCount = computePassResult.commandCounts()[regionIndex];
				int visibleSectionCount = computePassResult.visibleSectionCounts()[regionIndex];
				GeneratedCommandBatch generatedBatch = commandCount > 0
					? GeneratedCommandBatch.compute(
						regionInput.firstCommandIndex(),
						commandCount,
						context.viewport != null ? 1 : 0,
						0,
						testedSectionCount,
						visibleSectionCount,
						testedSectionCount - visibleSectionCount
					)
					: GeneratedCommandBatch.skip(
						context.viewport != null ? 1 : 0,
						0,
						testedSectionCount,
						visibleSectionCount,
						testedSectionCount - visibleSectionCount
					);
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
			preparedBatch = PreparedBatch.draw(
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
			return GeneratedCommandBatch.skip(viewport != null ? 1 : 0, 0, 0, 0, 0);
		}

		IndexedCommandScratchBuffer commandBuffer = new IndexedCommandScratchBuffer(estimateCommandCapacity(renderList));
		int regionChunkX = region.getChunkX();
		int regionChunkY = region.getChunkY();
		int regionChunkZ = region.getChunkZ();
		int frustumTestedSections = 0;
		int frustumVisibleSections = 0;
		int frustumCulledSections = 0;

		while (iterator.hasNext()) {
			int localSectionIndex = iterator.nextByteAsInt();
			long dataPointer = storage.getDataPointer(localSectionIndex);
			int sectionChunkX = regionChunkX + LocalSectionIndex.unpackX(localSectionIndex);
			int sectionChunkY = regionChunkY + LocalSectionIndex.unpackY(localSectionIndex);
			int sectionChunkZ = regionChunkZ + LocalSectionIndex.unpackZ(localSectionIndex);

			if (viewport != null) {
				frustumTestedSections++;
				if (!viewport.isBoxVisibleLooser(sectionChunkX << 4, sectionChunkY << 4, sectionChunkZ << 4)) {
					frustumCulledSections++;
					continue;
				}

				frustumVisibleSections++;
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

			if (preferLocalIndices && SectionRenderDataUnsafe.isLocalIndex(dataPointer)) {
				appendLocalIndexedCommands(commandBuffer, dataPointer, visibleFaces);
			} else {
				appendSharedIndexedCommands(commandBuffer, dataPointer, visibleFaces);
			}
		}

		if (commandBuffer.commandCount() == 0) {
			return GeneratedCommandBatch.skip(
				viewport != null ? 1 : 0,
				0,
				frustumTestedSections,
				frustumVisibleSections,
				frustumCulledSections
			);
		}

		return GeneratedCommandBatch.draw(
			commandBuffer.view(),
			commandBuffer.commandCount(),
			viewport != null ? 1 : 0,
			0,
			frustumTestedSections,
			frustumVisibleSections,
			frustumCulledSections
		);
	}

	private static int appendTranslatedIndexedBatch(IndexedIndirectCommandBuffer commandBuffer, MultiDrawBatch batch) {
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

			commandBuffer.addDrawElementsCommand(elementCount, 1, firstIndex, baseVertex, 0);
		}

		return commandBuffer.commandCount() - initialCommandCount;
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

	private static void recordMirroredBatch(RenderPassContext context, PreparedBatch preparedBatch) {
		context.mirroredBatchCount++;
		context.mirroredCommandCount += preparedBatch.commandCount();

		if (preparedBatch.source() == CommandSource.TRANSLATED) {
			context.translatedBatchCount++;
			context.translatedCommandCount += preparedBatch.commandCount();
			return;
		}

		context.generatedBatchCount++;
		context.generatedCommandCount += preparedBatch.commandCount();
		if (preparedBatch.source() == CommandSource.GENERATED_COMPUTE) {
			context.computeGeneratedBatchCount++;
			context.computeGeneratedCommandCount += preparedBatch.commandCount();
		}
	}

	private static void rollbackBatch(RenderPassContext context, PreparedBatch preparedBatch) {
		if (preparedBatch.source() != CommandSource.GENERATED_COMPUTE) {
			int executedCommandCount = context.executedCommandCount;
			context.commandBuffer.rewindToCommandCount(executedCommandCount);
			context.mirroredCommandCount = Math.max(context.mirroredCommandCount - preparedBatch.commandCount(), executedCommandCount);
		} else {
			context.mirroredCommandCount = Math.max(context.mirroredCommandCount - preparedBatch.commandCount(), 0);
		}

		context.mirroredBatchCount = Math.max(context.mirroredBatchCount - 1, context.executedBatchCount);

		if (preparedBatch.source() == CommandSource.TRANSLATED) {
			context.translatedBatchCount = Math.max(context.translatedBatchCount - 1, 0);
			context.translatedCommandCount = Math.max(context.translatedCommandCount - preparedBatch.commandCount(), 0);
			return;
		}

		context.generatedBatchCount = Math.max(context.generatedBatchCount - 1, 0);
		context.generatedCommandCount = Math.max(context.generatedCommandCount - preparedBatch.commandCount(), 0);
		if (preparedBatch.source() == CommandSource.GENERATED_COMPUTE) {
			context.computeGeneratedBatchCount = Math.max(context.computeGeneratedBatchCount - 1, 0);
			context.computeGeneratedCommandCount = Math.max(context.computeGeneratedCommandCount - preparedBatch.commandCount(), 0);
		}
	}

	private static void appendLocalIndexedCommands(IndexedCommandScratchBuffer commandBuffer, long dataPointer, int visibleFaces) {
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
					0
				);
			}

			baseVertex += vertexCount;
			baseElement += elementCount;
		}
	}

	private static void appendSharedIndexedCommands(IndexedCommandScratchBuffer commandBuffer, long dataPointer, int visibleFaces) {
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
						0
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

	private static boolean isRegionVisible(Viewport viewport, RenderRegion region) {
		return viewport.isBoxVisibleDirect(
			region.getOriginX() + REGION_HALF_WIDTH_BLOCKS,
			region.getOriginY() + REGION_HALF_HEIGHT_BLOCKS,
			region.getOriginZ() + REGION_HALF_LENGTH_BLOCKS,
			REGION_FRUSTUM_RADIUS
		);
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

	private static int countVisibleRegions(ChunkRenderListIterable renderLists, TerrainRenderPass pass) {
		int regions = 0;

		for (java.util.Iterator<ChunkRenderList> iterator = renderLists.iterator(pass.isTranslucent()); iterator.hasNext(); ) {
			iterator.next();
			regions++;
		}

		return regions;
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
		private final TerrainRenderPass pass;
		private final int visibleRegionCount;
		private final Viewport viewport;
		private final float[] computeFrustumPlanes = new float[24];
		private final Map<RenderRegion, GeneratedCommandBatch> preparedGeneratedBatches = new IdentityHashMap<>();
		private final Map<RenderRegion, CompletableFuture<GeneratedCommandBatch>> scheduledGeneratedBatches = new IdentityHashMap<>();
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

		private RenderPassContext(TerrainRenderPass pass, int visibleRegionCount, Viewport viewport) {
			this.pass = pass;
			this.visibleRegionCount = visibleRegionCount;
			this.viewport = viewport;
		}

		private String describePass() {
			return this.pass.isTranslucent() ? "translucent" : "opaque";
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
		private int lastGeneratedSkipBatchCount;
		private int lastFrustumTestedRegionCount;
		private int lastFrustumCulledRegionCount;
		private int lastFrustumTestedSectionCount;
		private int lastFrustumVisibleSectionCount;
		private int lastFrustumCulledSectionCount;
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
			this.lastGeneratedSkipBatchCount = context.generatedSkipBatchCount;
			this.lastFrustumTestedRegionCount = context.frustumTestedRegionCount;
			this.lastFrustumCulledRegionCount = context.frustumCulledRegionCount;
			this.lastFrustumTestedSectionCount = context.frustumTestedSectionCount;
			this.lastFrustumVisibleSectionCount = context.frustumVisibleSectionCount;
			this.lastFrustumCulledSectionCount = context.frustumCulledSectionCount;
			if (this.lastPassFullyOverridden) {
				this.overriddenPassCount++;
			} else if (context.seenBatchCount > 0) {
				this.fallbackPassCount++;
			}
			this.completedPassCount++;
		}

		private List<String> toDebugLines(
			boolean bridgeInstalled,
			boolean overrideEnabled,
			String overrideDisableReason,
			long overrideAttemptCount,
			long overrideSuccessCount,
			long overrideFailureCount,
			int consecutiveOverrideFailureCount
		) {
			List<String> lines = new ArrayList<>();
			lines.add("Potassium [Sodium Bridge]");
			lines.add("Bridge installed: " + bridgeInstalled);
			lines.add("Draw override: " + overrideEnabled);
			lines.add("Override disable reason: " + overrideDisableReason);
			lines.add("Override attempts: " + overrideAttemptCount);
			lines.add("Override successes: " + overrideSuccessCount);
			lines.add("Override failures: " + overrideFailureCount);
			lines.add("Consecutive override failures: " + consecutiveOverrideFailureCount);
			lines.add("Command fill workers: " + COMMAND_FILL_WORKER_COUNT);
			lines.add("Persistent mapping: " + this.lastPersistentMappingEnabled);
			lines.add("Compute culling: " + this.lastComputeCullingEnabled);
			lines.add("Last pass: " + this.lastPass);
			lines.add("Last pass fully overridden: " + this.lastPassFullyOverridden);
			lines.add("Seen batches: " + this.lastSeenBatchCount);
			lines.add("Seen commands: " + this.lastSeenCommandCount);
			lines.add("Mirrored batches: " + this.lastBatchCount);
			lines.add("Mirrored commands: " + this.lastCommandCount);
			lines.add("Generated batches: " + this.lastGeneratedBatchCount);
			lines.add("Generated commands: " + this.lastGeneratedCommandCount);
			lines.add("Compute generated batches: " + this.lastComputeGeneratedBatchCount);
			lines.add("Compute generated commands: " + this.lastComputeGeneratedCommandCount);
			lines.add("Generated skip batches: " + this.lastGeneratedSkipBatchCount);
			lines.add("Translated batches: " + this.lastTranslatedBatchCount);
			lines.add("Translated commands: " + this.lastTranslatedCommandCount);
			lines.add("Scheduled batches: " + this.lastScheduledBatchCount);
			lines.add("Async ready batches: " + this.lastAsyncReadyBatchCount);
			lines.add("Async waited batches: " + this.lastAsyncWaitedBatchCount);
			lines.add("Async failed batches: " + this.lastAsyncFailedBatchCount);
			lines.add("Sync generated batches: " + this.lastSyncGeneratedBatchCount);
			lines.add("Compute dispatches: " + this.lastComputeDispatchCount);
			lines.add("Compute failures: " + this.lastComputeFailureCount);
			lines.add("Visible regions: " + this.lastVisibleRegionCount);
			lines.add("Frustum tested regions: " + this.lastFrustumTestedRegionCount);
			lines.add("Frustum culled regions: " + this.lastFrustumCulledRegionCount);
			lines.add("Frustum tested sections: " + this.lastFrustumTestedSectionCount);
			lines.add("Frustum visible sections: " + this.lastFrustumVisibleSectionCount);
			lines.add("Frustum culled sections: " + this.lastFrustumCulledSectionCount);
			lines.add("Indexed indirect buffer: " + this.lastCommandCount + "/" + this.lastBufferCapacity + " commands");
			lines.add("Indexed command bytes: " + this.lastBufferBytes);
			lines.add("Potassium batches: " + this.lastExecutedBatchCount);
			lines.add("Potassium commands: " + this.lastExecutedCommandCount);
			lines.add("Culled batches: " + this.lastCulledBatchCount);
			lines.add("Fallback batches: " + this.lastFallbackBatchCount);
			lines.add("Fallback commands: " + this.lastFallbackCommandCount);
			lines.add("Last fallback reason: " + this.lastFallbackReason);
			lines.add("Overridden passes: " + this.overriddenPassCount);
			lines.add("Fallback passes: " + this.fallbackPassCount);
			lines.add("Completed mirrored passes: " + this.completedPassCount);
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
			this.lastGeneratedSkipBatchCount = 0;
			this.lastFrustumTestedRegionCount = 0;
			this.lastFrustumCulledRegionCount = 0;
			this.lastFrustumTestedSectionCount = 0;
			this.lastFrustumVisibleSectionCount = 0;
			this.lastFrustumCulledSectionCount = 0;
			this.overriddenPassCount = 0L;
			this.fallbackPassCount = 0L;
			this.completedPassCount = 0L;
		}
	}

	private enum CommandSource {
		GENERATED_CPU,
		GENERATED_COMPUTE,
		TRANSLATED
	}

	private record PreparedBatch(
		int firstCommandIndex,
		int commandCount,
		CommandSource source,
		boolean skipDraw,
		boolean needsUpload
	) {
		private static PreparedBatch draw(int firstCommandIndex, int commandCount, CommandSource source, boolean needsUpload) {
			return new PreparedBatch(firstCommandIndex, commandCount, source, false, needsUpload);
		}

		private static PreparedBatch skip(CommandSource source) {
			return new PreparedBatch(0, 0, source, true, false);
		}
	}

	private record GeneratedCommandBatch(
		ByteBuffer commands,
		int firstCommandIndex,
		int commandCount,
		boolean skipDraw,
		boolean gpuGenerated,
		int frustumTestedRegionCount,
		int frustumCulledRegionCount,
		int frustumTestedSectionCount,
		int frustumVisibleSectionCount,
		int frustumCulledSectionCount
	) {
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
				false,
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
				ByteBuffer.allocate(0),
				0,
				0,
				true,
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
