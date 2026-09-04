package org.weaw.game;

import lombok.Getter;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockRegistry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Chunk {
    private static final Logger LOGGER = LoggerFactory.getLogger(Chunk.class);

    public static final int SIZE = 32;
    public static final int TOTAL_BLOCKS = SIZE * SIZE * SIZE;

    @Getter
    private final Vector3i position;
    private final BlockCatalog blockCatalog;
    private final ChunkLighting lighting;
    private final ChunkSkyLight directSkyLight;

    private boolean isUniform = true;
    @Getter
    private short uniformBlockId = 0;

    private short[] palette;
    private int[] paletteCounts;
    private int paletteSize;
    private Map<Short, Integer> paletteIndexMap;
    private int bitsPerBlock;
    private long[] data;
    private int[] lightEmitterBlockIndices;
    private int lightEmitterCount;
    private boolean lightingInitialized;

    public Chunk(Vector3i position) {
        this(position, BlockRegistry.getDefaultCatalog());
    }

    public Chunk(Vector3i position, BlockCatalog blockCatalog) {
        this.position = new Vector3i(position);
        this.blockCatalog = Objects.requireNonNull(blockCatalog, "blockCatalog");
        this.lighting = new ChunkLighting();
        this.directSkyLight = new ChunkSkyLight();
        applyUniformBlock(blockCatalog.air().getId());
    }

    public BlockCatalog getBlockCatalog() {
        return blockCatalog;
    }

    public short getBlock(int x, int y, int z) {
        checkBounds(x, y, z);

        if (isUniform) {
            return uniformBlockId;
        }

        int index = getBlockIndex(x, y, z);
        int paletteIndex = readBlockData(index, data, bitsPerBlock);
        return palette[paletteIndex];
    }

    public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        int chunkWorldX = position.x * SIZE;
        int chunkWorldY = position.y * SIZE;
        int chunkWorldZ = position.z * SIZE;

        int localX = worldX - chunkWorldX;
        int localY = worldY - chunkWorldY;
        int localZ = worldZ - chunkWorldZ;

        if (isOutOfBounds(localX, localY, localZ)) {
            throw new IndexOutOfBoundsException("World coordinates not in this chunk: " + worldX + ", " + worldY + ", " + worldZ);
        }

        return getBlock(localX, localY, localZ);
    }

    public void setBlock(int x, int y, int z, BlockDefinition block) {
        Objects.requireNonNull(block, "block");
        checkBounds(x, y, z);

        short blockId = block.getId();
        short previousUniformBlockId = uniformBlockId;

        if (isUniform) {
            if (blockId == uniformBlockId) {
                return;
            }

            LOGGER.debug("Chunk {} switching from uniform storage to palette storage", position);
            switchToPaletteStorage();
            if (isLightEmitter(previousUniformBlockId)) {
                initializeUniformEmitterIndex(previousUniformBlockId);
            }
        }

        int blockIndex = getBlockIndex(x, y, z);
        int currentPaletteIndex = readBlockData(blockIndex, data, bitsPerBlock);
        short currentBlockId = palette[currentPaletteIndex];
        if (currentBlockId == blockId) {
            return;
        }

        int newPaletteIndex = getOrCreatePaletteIndex(blockId);
        writeBlockData(blockIndex, newPaletteIndex, data, bitsPerBlock);

        paletteCounts[currentPaletteIndex]--;
        paletteCounts[newPaletteIndex]++;
        updateLightEmitterIndex(blockIndex, currentBlockId, blockId);

        if (paletteCounts[currentPaletteIndex] == 0) {
            compactPalette();
        }
    }

    public void setAllBlocks(short[] blocks) {
        if (blocks.length != TOTAL_BLOCKS) {
            throw new IllegalArgumentException("Block array must contain exactly " + TOTAL_BLOCKS + " elements.");
        }

        boolean uniform = true;
        short first = blocks[0];
        for (int i = 1; i < blocks.length; i++) {
            if (blocks[i] != first) {
                uniform = false;
                break;
            }
        }

        if (uniform) {
            BlockDefinition blockDefinition = Objects.requireNonNull(blockCatalog.getBlock(first), "Unknown block id: " + first);
            LOGGER.debug("Chunk {} loaded as uniform chunk with block {}", position, blockDefinition);
            applyUniformBlock(blockDefinition.getId());
            return;
        }

        Map<Short, Integer> blockCounts = new HashMap<>();
        for (short blockId : blocks) {
            blockCounts.merge(blockId, 1, Integer::sum);
        }

        int uniqueBlocks = blockCounts.size();
        initializePaletteStorage(uniqueBlocks);

        int paletteIndex = 0;
        for (Map.Entry<Short, Integer> entry : blockCounts.entrySet()) {
            short blockId = entry.getKey();
            palette[paletteIndex] = blockId;
            paletteCounts[paletteIndex] = entry.getValue();
            paletteIndexMap.put(blockId, paletteIndex);
            paletteIndex++;
        }
        paletteSize = uniqueBlocks;
        bitsPerBlock = computeBitsForPaletteSize(paletteSize);
        data = createPackedDataArray(bitsPerBlock);

        for (int i = 0; i < blocks.length; i++) {
            int packedPaletteIndex = paletteIndexMap.get(blocks[i]);
            writeBlockData(i, packedPaletteIndex, data, bitsPerBlock);
        }

        isUniform = false;
        uniformBlockId = 0;

        LOGGER.debug("Chunk {} loaded with palette storage: {} palette entries, {} bits per block",
                position, paletteSize, bitsPerBlock);
        rebuildLightEmitterIndex(blocks);
    }



    public void fillChunk(BlockDefinition block) {
        Objects.requireNonNull(block, "block");
        applyUniformBlock(block.getId());
    }

    public ChunkLighting getLighting() {
        return lighting;
    }

    ChunkSkyLight getDirectSkyLight() {
        return directSkyLight;
    }

    public int getDirectSkyLight(int x, int y, int z) {
        checkBounds(x, y, z);
        return directSkyLight.get(x, y, z);
    }

    boolean isLightingInitialized() {
        return lightingInitialized;
    }

    void markLightingInitialized() {
        lightingInitialized = true;
    }

    public short getPackedLight(int x, int y, int z) {
        checkBounds(x, y, z);
        return lighting.getPackedLight(x, y, z);
    }

    public void setPackedLight(int x, int y, int z, short packedLight) {
        checkBounds(x, y, z);
        lighting.setPackedLight(x, y, z, packedLight);
    }

    public void setLight(int x, int y, int z, int red, int green, int blue, int sky) {
        checkBounds(x, y, z);
        lighting.setLight(x, y, z, red, green, blue, sky);
    }

    public void clearLighting() {
        lighting.clear();
    }

    public boolean hasLightEmitters() {
        if (isUniform) {
            BlockDefinition blockDefinition = blockCatalog.getBlock(uniformBlockId);
            return blockDefinition != null && blockDefinition.isLightEmitter();
        }
        return lightEmitterCount > 0;
    }

    public int getLightEmitterCount() {
        if (isUniform) {
            BlockDefinition blockDefinition = blockCatalog.getBlock(uniformBlockId);
            return blockDefinition != null && blockDefinition.isLightEmitter() ? TOTAL_BLOCKS : 0;
        }
        return lightEmitterCount;
    }

    public void forEachLightEmitter(LightEmitterConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (isUniform) {
            BlockDefinition blockDefinition = blockCatalog.getBlock(uniformBlockId);
            if (blockDefinition == null || !blockDefinition.isLightEmitter()) {
                return;
            }

            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int x = 0; x < SIZE; x++) {
                        consumer.accept(
                                x,
                                y,
                                z,
                                blockDefinition.getLightEmissionRed(),
                                blockDefinition.getLightEmissionGreen(),
                                blockDefinition.getLightEmissionBlue()
                        );
                    }
                }
            }
            return;
        }

        for (int index = 0; index < lightEmitterCount; index++) {
            int blockIndex = lightEmitterBlockIndices[index];
            int x = blockIndex % SIZE;
            int z = (blockIndex / SIZE) % SIZE;
            int y = blockIndex / (SIZE * SIZE);
            BlockDefinition blockDefinition = blockCatalog.getBlock(getBlock(x, y, z));
            if (blockDefinition == null || !blockDefinition.isLightEmitter()) {
                continue;
            }
            consumer.accept(
                    x,
                    y,
                    z,
                    blockDefinition.getLightEmissionRed(),
                    blockDefinition.getLightEmissionGreen(),
                    blockDefinition.getLightEmissionBlue()
            );
        }
    }

    public boolean isUniform() {
        return isUniform;
    }

    public Chunk copy() {
        return copy(true);
    }

    public Chunk copyForMeshing() {
        return copy(false);
    }

    public long estimateRetainedBytes() {
        long bytes = 168L + lighting.estimateRetainedBytes() + directSkyLight.estimateRetainedBytes();
        bytes += arrayBytes(palette == null ? 0 : palette.length, Short.BYTES);
        bytes += arrayBytes(paletteCounts == null ? 0 : paletteCounts.length, Integer.BYTES);
        bytes += arrayBytes(data == null ? 0 : data.length, Long.BYTES);
        bytes += arrayBytes(lightEmitterBlockIndices == null ? 0 : lightEmitterBlockIndices.length, Integer.BYTES);
        if (paletteIndexMap != null) {
            bytes += 48L + (long) paletteIndexMap.size() * 48L;
        }
        return bytes;
    }

    private Chunk copy(boolean includeLighting) {
        Chunk copy = new Chunk(position, blockCatalog);
        copy.isUniform = isUniform;
        copy.uniformBlockId = uniformBlockId;
        if (includeLighting) {
            copy.lighting.copyFrom(lighting);
            copy.directSkyLight.copyFrom(directSkyLight);
            copy.lightingInitialized = lightingInitialized;
        }

        if (isUniform) {
            return copy;
        }

        copy.palette = Arrays.copyOf(palette, palette.length);
        copy.paletteCounts = Arrays.copyOf(paletteCounts, paletteCounts.length);
        copy.paletteSize = paletteSize;
        copy.paletteIndexMap = new HashMap<>(paletteIndexMap);
        copy.bitsPerBlock = bitsPerBlock;
        copy.data = Arrays.copyOf(data, data.length);
        copy.lightEmitterBlockIndices = lightEmitterBlockIndices == null
                ? null
                : Arrays.copyOf(lightEmitterBlockIndices, lightEmitterBlockIndices.length);
        copy.lightEmitterCount = lightEmitterCount;
        return copy;
    }

    private static long arrayBytes(int length, int elementBytes) {
        return length == 0 ? 0L : 16L + (long) length * elementBytes;
    }

    public boolean isOutOfBounds(int x, int y, int z) {
        return (x < 0 || y < 0 || z < 0 || x >= SIZE || y >= SIZE || z >= SIZE);
    }

    public boolean isInBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE &&
                y >= 0 && y < SIZE &&
                z >= 0 && z < SIZE;
    }

    public Vector3f getWorldMin() {
        return new Vector3f(
                position.x * SIZE,
                position.y * SIZE,
                position.z * SIZE
        );
    }

    public Vector3f getWorldMax() {
        return new Vector3f(
                (position.x + 1) * SIZE,
                (position.y + 1) * SIZE,
                (position.z + 1) * SIZE
        );
    }

    // --- Internal ---

    private void checkBounds(int x, int y, int z) {
        if (isOutOfBounds(x, y, z)) {
            throw new IndexOutOfBoundsException("Chunk coordinates out of bounds: " + x + ", " + y + ", " + z);
        }
    }

    private int getBlockIndex(int x, int y, int z) {
        return x + (z * SIZE) + (y * SIZE * SIZE);
    }

    private void applyUniformBlock(short blockId) {
        isUniform = true;
        uniformBlockId = blockId;
        palette = null;
        paletteCounts = null;
        paletteSize = 0;
        paletteIndexMap = null;
        data = null;
        bitsPerBlock = 0;
        lightEmitterBlockIndices = null;
        lightEmitterCount = 0;

        BlockDefinition blockDefinition = blockCatalog.getBlock(blockId);
        LOGGER.debug("Chunk {} filled uniformly with block {}", position, blockDefinition != null ? blockDefinition : blockId);
    }

    private void switchToPaletteStorage() {
        initializePaletteStorage(2);
        palette[0] = uniformBlockId;
        paletteCounts[0] = TOTAL_BLOCKS;
        paletteIndexMap.put(uniformBlockId, 0);
        paletteSize = 1;
        bitsPerBlock = 1;
        data = createPackedDataArray(bitsPerBlock);
        isUniform = false;

        LOGGER.debug("Chunk {} initialized palette storage with {} entry and {} bit per block",
                position, paletteSize, bitsPerBlock);
    }

    private void initializePaletteStorage(int capacity) {
        int paletteCapacity = Math.max(2, capacity);
        palette = new short[paletteCapacity];
        paletteCounts = new int[paletteCapacity];
        paletteSize = 0;
        paletteIndexMap = new HashMap<>(paletteCapacity);
    }

    private int getOrCreatePaletteIndex(short blockId) {
        Integer existingIndex = paletteIndexMap.get(blockId);
        if (existingIndex != null) {
            return existingIndex;
        }

        ensurePaletteCapacity(paletteSize + 1);
        int newIndex = paletteSize++;
        palette[newIndex] = blockId;
        paletteCounts[newIndex] = 0;
        paletteIndexMap.put(blockId, newIndex);

        int requiredBits = computeBitsForPaletteSize(paletteSize);
        if (requiredBits != bitsPerBlock) {
            reallocateData(requiredBits);
        }

        BlockDefinition blockDefinition = blockCatalog.getBlock(blockId);
        LOGGER.debug("Chunk {} palette expanded to {} entries after adding block {}",
                position, paletteSize, blockDefinition != null ? blockDefinition : blockId);

        return newIndex;
    }

    private void ensurePaletteCapacity(int requiredCapacity) {
        if (palette.length >= requiredCapacity) {
            return;
        }

        int newCapacity = Math.max(requiredCapacity, palette.length * 2);
        palette = Arrays.copyOf(palette, newCapacity);
        paletteCounts = Arrays.copyOf(paletteCounts, newCapacity);
    }

    private void compactPalette() {
        int activeEntries = 0;
        int survivingIndex = -1;
        for (int i = 0; i < paletteSize; i++) {
            if (paletteCounts[i] > 0) {
                activeEntries++;
                survivingIndex = i;
            }
        }

        if (activeEntries == 0) {
            applyUniformBlock(blockCatalog.air().getId());
            LOGGER.warn("Chunk {} palette became empty, resetting chunk to AIR", position);
            return;
        }

        if (activeEntries == 1) {
            short survivingBlockId = palette[survivingIndex];
            LOGGER.debug("Chunk {} collapsed back to uniform storage with block {}", position,
                    blockCatalog.getBlock(survivingBlockId));
            applyUniformBlock(survivingBlockId);
            return;
        }

        int[] remap = new int[paletteSize];
        Arrays.fill(remap, -1);

        short[] compactedPalette = new short[activeEntries];
        int[] compactedCounts = new int[activeEntries];
        Map<Short, Integer> compactedIndexMap = new HashMap<>(activeEntries);

        int nextIndex = 0;
        for (int i = 0; i < paletteSize; i++) {
            if (paletteCounts[i] <= 0) {
                continue;
            }

            remap[i] = nextIndex;
            compactedPalette[nextIndex] = palette[i];
            compactedCounts[nextIndex] = paletteCounts[i];
            compactedIndexMap.put(palette[i], nextIndex);
            nextIndex++;
        }

        int newBits = computeBitsForPaletteSize(activeEntries);
        long[] newData = createPackedDataArray(newBits);
        for (int blockIndex = 0; blockIndex < TOTAL_BLOCKS; blockIndex++) {
            int oldPaletteIndex = readBlockData(blockIndex, data, bitsPerBlock);
            int remappedIndex = remap[oldPaletteIndex];
            writeBlockData(blockIndex, remappedIndex, newData, newBits);
        }

        palette = compactedPalette;
        paletteCounts = compactedCounts;
        paletteSize = activeEntries;
        paletteIndexMap = compactedIndexMap;
        bitsPerBlock = newBits;
        data = newData;

        LOGGER.debug("Chunk {} compacted palette to {} entries and {} bits per block",
                position, paletteSize, bitsPerBlock);
    }

    private int computeBitsForPaletteSize(int size) {
        if (size <= 1) {
            return 1;
        }
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    private long[] createPackedDataArray(int packedBitsPerBlock) {
        int totalBits = TOTAL_BLOCKS * packedBitsPerBlock;
        int dataLength = (totalBits + 63) / 64;
        return new long[dataLength];
    }

    private void reallocateData(int newBits) {
        LOGGER.debug("Chunk {} reallocating packed data: {} -> {} bits per block", position, bitsPerBlock, newBits);

        long[] newData = createPackedDataArray(newBits);

        for (int i = 0; i < TOTAL_BLOCKS; i++) {
            int paletteIndex = readBlockData(i, data, bitsPerBlock);
            writeBlockData(i, paletteIndex, newData, newBits);
        }

        this.bitsPerBlock = newBits;
        this.data = newData;
    }

    private void rebuildLightEmitterIndex(short[] blocks) {
        int emitterBlocks = 0;
        for (short blockId : blocks) {
            BlockDefinition blockDefinition = blockCatalog.getBlock(blockId);
            if (blockDefinition != null && blockDefinition.isLightEmitter()) {
                emitterBlocks++;
            }
        }

        if (emitterBlocks == 0) {
            lightEmitterBlockIndices = null;
            lightEmitterCount = 0;
            return;
        }

        lightEmitterBlockIndices = new int[emitterBlocks];
        lightEmitterCount = 0;
        for (int blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
            BlockDefinition blockDefinition = blockCatalog.getBlock(blocks[blockIndex]);
            if (blockDefinition == null || !blockDefinition.isLightEmitter()) {
                continue;
            }
            lightEmitterBlockIndices[lightEmitterCount++] = blockIndex;
        }
    }

    private void updateLightEmitterIndex(int blockIndex, short previousBlockId, short nextBlockId) {
        boolean previousEmitter = isLightEmitter(previousBlockId);
        boolean nextEmitter = isLightEmitter(nextBlockId);
        if (previousEmitter == nextEmitter) {
            return;
        }

        if (nextEmitter) {
            ensureLightEmitterCapacity(lightEmitterCount + 1);
            lightEmitterBlockIndices[lightEmitterCount++] = blockIndex;
            return;
        }

        for (int index = 0; index < lightEmitterCount; index++) {
            if (lightEmitterBlockIndices[index] != blockIndex) {
                continue;
            }

            int lastIndex = lightEmitterCount - 1;
            lightEmitterBlockIndices[index] = lightEmitterBlockIndices[lastIndex];
            lightEmitterCount = lastIndex;
            if (lightEmitterCount == 0) {
                lightEmitterBlockIndices = null;
            }
            return;
        }
    }

    private void ensureLightEmitterCapacity(int requiredCapacity) {
        if (lightEmitterBlockIndices == null) {
            lightEmitterBlockIndices = new int[Math.max(4, requiredCapacity)];
            return;
        }

        if (lightEmitterBlockIndices.length >= requiredCapacity) {
            return;
        }

        lightEmitterBlockIndices = Arrays.copyOf(
                lightEmitterBlockIndices,
                Math.max(lightEmitterBlockIndices.length * 2, requiredCapacity)
        );
    }

    private void initializeUniformEmitterIndex(short blockId) {
        if (!isLightEmitter(blockId)) {
            lightEmitterBlockIndices = null;
            lightEmitterCount = 0;
            return;
        }

        lightEmitterBlockIndices = new int[TOTAL_BLOCKS];
        for (int blockIndex = 0; blockIndex < TOTAL_BLOCKS; blockIndex++) {
            lightEmitterBlockIndices[blockIndex] = blockIndex;
        }
        lightEmitterCount = TOTAL_BLOCKS;
    }

    private boolean isLightEmitter(short blockId) {
        BlockDefinition blockDefinition = blockCatalog.getBlock(blockId);
        return blockDefinition != null && blockDefinition.isLightEmitter();
    }


    private void writeBlockData(int index, int paletteIndex, long[] dataArray, int bitsPerBlock) {
        int bitIndex = index * bitsPerBlock;
        int arrayIndex = bitIndex / 64;
        int bitOffset = bitIndex % 64;

        long mask = ((1L << bitsPerBlock) - 1L) << bitOffset;
        dataArray[arrayIndex] = (dataArray[arrayIndex] & ~mask) | ((long) paletteIndex << bitOffset);

        if (64 - bitOffset < bitsPerBlock) {
            int remaining = bitsPerBlock - (64 - bitOffset);
            mask = (1L << remaining) - 1;
            dataArray[arrayIndex + 1] = (dataArray[arrayIndex + 1] & ~mask) | ((long) paletteIndex >> (bitsPerBlock - remaining));
        }
    }

    private int readBlockData(int index, long[] dataArray, int bitsPerBlock) {
        int bitIndex = index * bitsPerBlock;
        int arrayIndex = bitIndex / 64;
        int bitOffset = bitIndex % 64;

        long value = (dataArray[arrayIndex] >>> bitOffset) & ((1L << bitsPerBlock) - 1);

        if (64 - bitOffset < bitsPerBlock) {
            int remaining = bitsPerBlock - (64 - bitOffset);
            value |= (dataArray[arrayIndex + 1] & ((1L << remaining) - 1)) << (bitsPerBlock - remaining);
        }

        return (int) value;
    }

    @FunctionalInterface
    public interface LightEmitterConsumer {
        void accept(int x, int y, int z, int red, int green, int blue);
    }
}
