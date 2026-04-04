package com.potassium.gl.buffer;

import com.potassium.core.PotassiumLogger;
import com.potassium.gl.GLCapabilities;
import com.potassium.world.data.BlockData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

public final class WorldDataBuffer implements AutoCloseable {
	private static final long MAX_JAVA_MAPPED_VIEW_BYTES = Integer.MAX_VALUE;
	private static final long MEBIBYTE_BYTES = 1024L * 1024L;
	private static final long SAFE_PERSISTENT_PAGE_CAPACITY_BYTES = 768L * MEBIBYTE_BYTES;
	public static final int MAX_SHADER_PAGES = 8;
	private static final int LAYOUT_HEADER_WORDS = 4;
	private static final int LAYOUT_WORLD_INFO_WORDS = 4;
	private static final int LAYOUT_PAGE_INFO_WORDS = 4;
	private static final int LAYOUT_BUFFER_BYTES = Integer.BYTES * (LAYOUT_HEADER_WORDS + LAYOUT_WORLD_INFO_WORDS + (MAX_SHADER_PAGES * LAYOUT_PAGE_INFO_WORDS));

	private final boolean persistentMappingEnabled;
	private final ArrayList<Page> pages = new ArrayList<>();
	private final PersistentBuffer layoutBuffer;
	private final ByteBuffer layoutUploadBuffer;
	private final ByteBuffer scratchIntBuffer;

	private long requestedCapacityBytes;
	private long totalCapacityBytes;
	private long targetPageCapacityBytes;
	private long lastUploadBytes;
	private int minSectionY;
	private int sectionsCount;
	private int chunksPerPage;
	private long bytesPerChunk;
	private boolean warnedAboutBindingLimit;
	private boolean warnedAboutPersistentPageFallback;
	private int lastBoundShaderPageCount = -1;
	private int lastBoundBaseBinding = -1;
	private boolean layoutDirty = true;

	public WorldDataBuffer(long initialCapacityBytes, boolean persistentMappingEnabled) {
		this.persistentMappingEnabled = persistentMappingEnabled;
		this.requestedCapacityBytes = Math.max(initialCapacityBytes, 1L);
		this.layoutBuffer = new PersistentBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, LAYOUT_BUFFER_BYTES, persistentMappingEnabled, 1);
		this.layoutUploadBuffer = MemoryUtil.memCalloc(LAYOUT_BUFFER_BYTES).order(ByteOrder.nativeOrder());
		this.scratchIntBuffer = MemoryUtil.memAlloc(Integer.BYTES).order(ByteOrder.nativeOrder());
	}

	public void beginFrame() {
		this.layoutBuffer.beginFrame();
		for (Page page : this.pages) {
			page.storage.beginFrame();
		}
	}

	public void endFrame() {
		this.layoutBuffer.endFrame();
		for (Page page : this.pages) {
			page.storage.endFrame();
		}
	}

	public void upload(ByteBuffer data) {
		ByteBuffer source = data.duplicate();
		this.lastUploadBytes = source.remaining();
		if (this.lastUploadBytes == 0L) {
			return;
		}

		if (!this.isConfigured()) {
			throw new IllegalStateException("World data buffer layout has not been configured.");
		}

		this.uploadToPages(source, 0L);
	}

	public void bind(int binding) {
		int bindablePages = this.resolveShaderVisiblePageCount(binding);
		this.uploadLayoutIfNeeded(binding, bindablePages);
		this.layoutBuffer.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding);
		for (int pageIndex = 0; pageIndex < bindablePages; pageIndex++) {
			this.pages.get(pageIndex).storage.bindBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding + 1 + pageIndex);
		}

		if (bindablePages < this.pages.size() && !this.warnedAboutBindingLimit) {
			PotassiumLogger.logger().warn(
				"World data buffer uses {} pages, but only {} pages are shader-visible from binding {}. Additional pages will stay inaccessible until the shader page window is widened.",
				this.pages.size(),
				bindablePages,
				binding
			);
			this.warnedAboutBindingLimit = true;
		}
	}

	public void configure(int minSectionY, int sectionsCount) {
		if (sectionsCount <= 0) {
			throw new IllegalArgumentException("sectionsCount must be positive.");
		}

		this.minSectionY = minSectionY;
		this.sectionsCount = sectionsCount;
		this.bytesPerChunk = (long) sectionsCount * LevelChunkSection.SECTION_SIZE * BlockData.BYTES;
		this.rebuildStorage();
	}

	public boolean isConfigured() {
		return this.bytesPerChunk > 0L;
	}

	public int minSectionY() {
		return this.minSectionY;
	}

	public int sectionsCount() {
		return this.sectionsCount;
	}

	public long bytesPerChunk() {
		return this.bytesPerChunk;
	}

	public int pageCount() {
		return this.pages.size();
	}

	public int shaderVisiblePageCount(int binding) {
		return this.resolveShaderVisiblePageCount(binding);
	}

	public int shaderVisibleChunkCapacity(int binding) {
		if (this.bytesPerChunk == 0L) {
			return 0;
		}

		int visiblePages = this.resolveShaderVisiblePageCount(binding);
		long visibleBytes = 0L;
		for (int pageIndex = 0; pageIndex < visiblePages; pageIndex++) {
			visibleBytes += this.pages.get(pageIndex).capacityBytes;
		}

		return (int) Math.min(Integer.MAX_VALUE, visibleBytes / this.bytesPerChunk);
	}

	public long targetPageCapacityBytes() {
		return this.targetPageCapacityBytes;
	}

	public int maxResidentChunkCapacity() {
		if (this.bytesPerChunk == 0L) {
			return 0;
		}

		return (int) Math.min(Integer.MAX_VALUE, this.totalCapacityBytes / this.bytesPerChunk);
	}

	public long chunkOffsetBytes(int residentSlot) {
		if (residentSlot < 0) {
			throw new IllegalArgumentException("residentSlot must be non-negative.");
		}

		return this.bytesPerChunk * residentSlot;
	}

	public void uploadChunk(int residentSlot, ByteBuffer data) {
		if (!this.isConfigured()) {
			throw new IllegalStateException("World data buffer layout has not been configured.");
		}

		ByteBuffer source = data.duplicate();
		long expectedBytes = this.bytesPerChunk;
		if (source.remaining() != expectedBytes) {
			throw new IllegalArgumentException(
				"Chunk upload size mismatch. expected=" + expectedBytes + " actual=" + source.remaining()
			);
		}

		this.lastUploadBytes = source.remaining();
		this.uploadToPages(source, this.chunkOffsetBytes(residentSlot));
	}

	public boolean ensureCapacity(long requiredCapacityBytes) {
		long previousCapacityBytes = this.totalCapacityBytes;
		this.requestedCapacityBytes = Math.max(this.requestedCapacityBytes, requiredCapacityBytes);
		if (!this.isConfigured()) {
			return false;
		}

		this.ensureConfiguredCapacity(requiredCapacityBytes);
		return this.totalCapacityBytes > previousCapacityBytes;
	}

	public boolean applyBlockChange(int residentSlot, BlockPos position, BlockState newState, int flags) {
		int localBlockIndex = this.localBlockIndex(position);
		if (localBlockIndex < 0) {
			return false;
		}

		return this.applyPackedBlockChange(residentSlot, localBlockIndex, BlockData.fromState(newState).packed());
	}

	public boolean applyPackedBlockChange(int residentSlot, int localBlockIndex, int packedBlock) {
		if (!this.isConfigured()) {
			return false;
		}
		if (localBlockIndex < 0 || localBlockIndex >= this.blocksPerChunk()) {
			return false;
		}

		long byteOffset = this.chunkOffsetBytes(residentSlot) + ((long) localBlockIndex * BlockData.BYTES);

		this.scratchIntBuffer.clear();
		this.scratchIntBuffer.putInt(packedBlock);
		this.scratchIntBuffer.flip();
		this.lastUploadBytes = BlockData.BYTES;
		this.uploadToPages(this.scratchIntBuffer, byteOffset);
		return true;
	}

	public int localBlockIndex(BlockPos position) {
		if (!this.isConfigured()) {
			return -1;
		}

		int sectionIndex = SectionPos.blockToSectionCoord(position.getY()) - this.minSectionY;
		if (sectionIndex < 0 || sectionIndex >= this.sectionsCount) {
			return -1;
		}

		int localX = SectionPos.sectionRelative(position.getX());
		int localY = SectionPos.sectionRelative(position.getY());
		int localZ = SectionPos.sectionRelative(position.getZ());
		return (sectionIndex * LevelChunkSection.SECTION_SIZE) + (localY * 256) + (localZ * 16) + localX;
	}

	public int blocksPerChunk() {
		if (!this.isConfigured()) {
			return 0;
		}

		return Math.toIntExact(this.bytesPerChunk / BlockData.BYTES);
	}

	public long capacityBytes() {
		return this.totalCapacityBytes;
	}

	public long lastUploadBytes() {
		return this.lastUploadBytes;
	}

	@Override
	public void close() {
		this.releasePages();
		this.layoutBuffer.close();
		MemoryUtil.memFree(this.layoutUploadBuffer);
		MemoryUtil.memFree(this.scratchIntBuffer);
		this.requestedCapacityBytes = 0L;
		this.totalCapacityBytes = 0L;
		this.targetPageCapacityBytes = 0L;
		this.lastUploadBytes = 0L;
		this.minSectionY = 0;
		this.sectionsCount = 0;
		this.chunksPerPage = 0;
		this.bytesPerChunk = 0L;
		this.warnedAboutBindingLimit = false;
		this.warnedAboutPersistentPageFallback = false;
		this.lastBoundShaderPageCount = -1;
		this.lastBoundBaseBinding = -1;
		this.layoutDirty = true;
	}

	private void rebuildStorage() {
		this.releasePages();
		this.totalCapacityBytes = 0L;
		this.warnedAboutBindingLimit = false;
		this.warnedAboutPersistentPageFallback = false;
		this.layoutDirty = true;
		this.lastBoundShaderPageCount = -1;
		this.lastBoundBaseBinding = -1;

		if (!this.isConfigured()) {
			return;
		}

		long maxPageCapacityBytes = this.resolveMaxPageCapacityBytes();
		if (this.bytesPerChunk > maxPageCapacityBytes) {
			throw new IllegalStateException(
				"World chunk payload exceeds the maximum page size. bytesPerChunk="
					+ this.bytesPerChunk
					+ ", maxPage="
					+ maxPageCapacityBytes
			);
		}

		this.chunksPerPage = Math.max(1, (int) (maxPageCapacityBytes / this.bytesPerChunk));
		this.targetPageCapacityBytes = this.bytesPerChunk * this.chunksPerPage;
		this.ensureConfiguredCapacity(Math.max(this.requestedCapacityBytes, this.bytesPerChunk));
	}

	private void ensureConfiguredCapacity(long requiredCapacityBytes) {
		long requiredChunkCount = ceilDiv(Math.max(requiredCapacityBytes, this.bytesPerChunk), this.bytesPerChunk);
		long requiredTotalCapacityBytes = Math.multiplyExact(requiredChunkCount, this.bytesPerChunk);

		while (this.totalCapacityBytes < requiredTotalCapacityBytes) {
			long remainingBytes = requiredTotalCapacityBytes - this.totalCapacityBytes;
			long pageChunkCount = Math.min((long) this.chunksPerPage, ceilDiv(remainingBytes, this.bytesPerChunk));
			long pageCapacityBytes = Math.multiplyExact(pageChunkCount, this.bytesPerChunk);
			this.addPage(pageCapacityBytes);
		}
	}

	private void addPage(long pageCapacityBytes) {
		if (pageCapacityBytes <= 0L) {
			throw new IllegalArgumentException("World data page capacity must be positive.");
		}

		long startOffsetBytes = this.totalCapacityBytes;
		PersistentBuffer storage = this.createPageStorage(pageCapacityBytes);
		this.pages.add(new Page(storage, startOffsetBytes, pageCapacityBytes));
		this.totalCapacityBytes += pageCapacityBytes;
		this.layoutDirty = true;
	}

	private void uploadToPages(ByteBuffer source, long logicalOffsetBytes) {
		long requiredCapacityBytes = logicalOffsetBytes + source.remaining();
		if (requiredCapacityBytes > this.totalCapacityBytes) {
			this.ensureConfiguredCapacity(requiredCapacityBytes);
		}

		long cursor = logicalOffsetBytes;
		ByteBuffer remaining = source.duplicate();
		while (remaining.hasRemaining()) {
			Page page = this.pageForOffset(cursor);
			long offsetInPage = cursor - page.startOffsetBytes;
			int writableBytes = Math.toIntExact(Math.min((long) remaining.remaining(), page.capacityBytes - offsetInPage));
			ByteBuffer slice = remaining.slice();
			slice.limit(writableBytes);
			page.storage.upload(slice, offsetInPage);
			remaining.position(remaining.position() + writableBytes);
			cursor += writableBytes;
		}
	}

	private Page pageForOffset(long logicalOffsetBytes) {
		for (Page page : this.pages) {
			if (logicalOffsetBytes >= page.startOffsetBytes && logicalOffsetBytes < page.endOffsetBytes()) {
				return page;
			}
		}

		throw new IllegalArgumentException(
			"World data buffer offset is out of range. offset=" + logicalOffsetBytes + ", capacity=" + this.totalCapacityBytes
		);
	}

	private long resolveMaxPageCapacityBytes() {
		long maxPageCapacityBytes = MAX_JAVA_MAPPED_VIEW_BYTES;
		long maxSsboBlockSizeBytes = GLCapabilities.getMaxShaderStorageBlockSizeBytes();
		if (maxSsboBlockSizeBytes > 0L) {
			maxPageCapacityBytes = Math.min(maxPageCapacityBytes, maxSsboBlockSizeBytes);
		}
		if (this.persistentMappingEnabled) {
			maxPageCapacityBytes = Math.min(maxPageCapacityBytes, SAFE_PERSISTENT_PAGE_CAPACITY_BYTES);
		}

		return Math.max(maxPageCapacityBytes, this.bytesPerChunk);
	}

	private PersistentBuffer createPageStorage(long pageCapacityBytes) {
		if (!this.persistentMappingEnabled) {
			return new PersistentBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, pageCapacityBytes, false, 1);
		}

		try {
			return new PersistentBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, pageCapacityBytes, true, 1);
		} catch (RuntimeException persistentFailure) {
			if (!this.warnedAboutPersistentPageFallback) {
				PotassiumLogger.logger().warn(
					"Falling back to non-persistent world-data pages after persistent page allocation failed at {} MiB. Reason={}",
					Math.max(1L, pageCapacityBytes / MEBIBYTE_BYTES),
					persistentFailure.getMessage()
				);
				this.warnedAboutPersistentPageFallback = true;
			}
			try {
				return new PersistentBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, pageCapacityBytes, false, 1);
			} catch (RuntimeException fallbackFailure) {
				fallbackFailure.addSuppressed(persistentFailure);
				throw fallbackFailure;
			}
		}
	}

	private int resolveShaderVisiblePageCount(int binding) {
		int maxBindings = GLCapabilities.getMaxShaderStorageBufferBindings();
		int availablePageBindings = maxBindings > 0 ? Math.max(0, maxBindings - binding - 1) : Integer.MAX_VALUE;
		return Math.min(this.pages.size(), Math.min(MAX_SHADER_PAGES, availablePageBindings));
	}

	private void uploadLayoutIfNeeded(int binding, int shaderVisiblePageCount) {
		if (!this.layoutDirty && this.lastBoundShaderPageCount == shaderVisiblePageCount && this.lastBoundBaseBinding == binding) {
			return;
		}

		this.layoutUploadBuffer.clear();
		this.layoutUploadBuffer.putInt((int) Math.min(this.bytesPerChunk, Integer.MAX_VALUE));
		this.layoutUploadBuffer.putInt((int) Math.min(this.bytesPerChunk / BlockData.BYTES, Integer.MAX_VALUE));
		this.layoutUploadBuffer.putInt(shaderVisiblePageCount);
		this.layoutUploadBuffer.putInt(this.pages.size());
		this.layoutUploadBuffer.putInt(this.minSectionY);
		this.layoutUploadBuffer.putInt(this.sectionsCount);
		this.layoutUploadBuffer.putInt(0);
		this.layoutUploadBuffer.putInt(0);

		for (int pageIndex = 0; pageIndex < MAX_SHADER_PAGES; pageIndex++) {
			if (pageIndex < shaderVisiblePageCount) {
				Page page = this.pages.get(pageIndex);
				this.layoutUploadBuffer.putInt((int) Math.min(page.startOffsetBytes / BlockData.BYTES, Integer.MAX_VALUE));
				this.layoutUploadBuffer.putInt((int) Math.min(page.capacityBytes / BlockData.BYTES, Integer.MAX_VALUE));
				this.layoutUploadBuffer.putInt((int) Math.min(page.startOffsetBytes / Math.max(this.bytesPerChunk, 1L), Integer.MAX_VALUE));
				this.layoutUploadBuffer.putInt((int) Math.min(page.capacityBytes / Math.max(this.bytesPerChunk, 1L), Integer.MAX_VALUE));
			} else {
				this.layoutUploadBuffer.putInt(0);
				this.layoutUploadBuffer.putInt(0);
				this.layoutUploadBuffer.putInt(0);
				this.layoutUploadBuffer.putInt(0);
			}
		}

		this.layoutUploadBuffer.flip();
		this.layoutBuffer.upload(this.layoutUploadBuffer, 0L);
		this.lastBoundShaderPageCount = shaderVisiblePageCount;
		this.lastBoundBaseBinding = binding;
		this.layoutDirty = false;
	}

	private void releasePages() {
		for (Page page : this.pages) {
			page.storage.close();
		}
		this.pages.clear();
	}

	private static long ceilDiv(long dividend, long divisor) {
		return (dividend + (divisor - 1L)) / divisor;
	}

	private static final class Page {
		private final PersistentBuffer storage;
		private final long startOffsetBytes;
		private final long capacityBytes;

		private Page(PersistentBuffer storage, long startOffsetBytes, long capacityBytes) {
			this.storage = storage;
			this.startOffsetBytes = startOffsetBytes;
			this.capacityBytes = capacityBytes;
		}

		private long endOffsetBytes() {
			return this.startOffsetBytes + this.capacityBytes;
		}
	}
}
