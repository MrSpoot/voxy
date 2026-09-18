package org.weaw.engine.graphics.textures;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.utils.FileReader;
import org.weaw.game.utils.BlockDefinition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.lwjgl.opengl.ARBTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY;
import static org.lwjgl.opengl.ARBTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetFloat;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glTexParameterf;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL12.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL30.glTexImage3D;
import static org.lwjgl.opengl.GL30.glTexSubImage3D;
import static org.lwjgl.stb.STBImage.stbi_failure_reason;
import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load_from_memory;

public class BlockTextureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlockTextureManager.class);
    static final int FACE_COUNT = 6;
    private static final float TARGET_ANISOTROPY = 8.0f;

    private final List<BlockDefinition> blockDefinitions;
    private int textureArrayId;
    private int textureWidth;
    private int textureHeight;
    private int layerCount;

    public BlockTextureManager(Collection<BlockDefinition> blockDefinitions) {
        this.blockDefinitions = List.copyOf(blockDefinitions);
    }

    public void create() {
        List<BlockDefinition> texturedBlocks = new ArrayList<>();
        for (BlockDefinition blockDefinition : blockDefinitions) {
            if (blockDefinition.getTexturePath() != null) {
                texturedBlocks.add(blockDefinition);
            } else {
                blockDefinition.setTextureIndex(0);
            }
        }

        if (texturedBlocks.isEmpty()) {
            throw new IllegalStateException("No block textures registered");
        }

        TextureData referenceTexture = loadTexture(texturedBlocks.get(0).getTexturePath());
        textureWidth = faceWidth(referenceTexture.width, referenceTexture.height);
        textureHeight = referenceTexture.height;
        layerCount = (texturedBlocks.size() + 1) * FACE_COUNT;

        textureArrayId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, textureWidth, textureHeight, layerCount, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        uploadMissingTexture(textureWidth, textureHeight);

        int layer = 1;
        for (BlockDefinition blockDefinition : texturedBlocks) {
            TextureData textureData;
            if (blockDefinition == texturedBlocks.get(0)) {
                textureData = referenceTexture;
            } else {
                textureData = loadTexture(blockDefinition.getTexturePath());
            }

            if (textureData.width != textureWidth * FACE_COUNT || textureData.height != textureHeight) {
                textureData.free();
                throw new IllegalStateException("All block textures must share the same size. Expected "
                        + (textureWidth * FACE_COUNT) + "x" + textureHeight + " but got "
                        + textureData.width + "x" + textureData.height
                        + " for " + blockDefinition.getStableId());
            }

            uploadFaces(textureData, layer);
            blockDefinition.setTextureIndex(layer);
            LOGGER.info("Assigned texture index {} to block {}", layer, blockDefinition.getStableId());
            textureData.free();
            layer++;
        }

        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        configureAnisotropicFiltering();
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        LOGGER.info("Block texture array created with {} face layers ({} block textures)",
                layerCount, layerCount / FACE_COUNT);
    }

    public void bind(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
    }

    public void cleanup() {
        if (textureArrayId != 0) {
            glDeleteTextures(textureArrayId);
            textureArrayId = 0;
        }
        textureWidth = 0;
        textureHeight = 0;
        layerCount = 0;
    }

    public int getTextureArrayId() {
        return textureArrayId;
    }

    public int getLayerCount() {
        return layerCount;
    }

    public long getEstimatedGpuBytes() {
        long pixelsPerLayer = 0L;
        int mipWidth = textureWidth;
        int mipHeight = textureHeight;
        while (true) {
            pixelsPerLayer += (long) mipWidth * mipHeight;
            if (mipWidth == 1 && mipHeight == 1) {
                break;
            }
            mipWidth = Math.max(1, mipWidth / 2);
            mipHeight = Math.max(1, mipHeight / 2);
        }
        return pixelsPerLayer * layerCount * 4L;
    }

    private void uploadMissingTexture(int textureWidth, int textureHeight) {
        ByteBuffer missingTexture = MemoryUtil.memAlloc(textureWidth * textureHeight * 4);
        for (int i = 0; i < textureWidth * textureHeight; i++) {
            missingTexture.put((byte) 255);
            missingTexture.put((byte) 0);
            missingTexture.put((byte) 255);
            missingTexture.put((byte) 255);
        }
        missingTexture.flip();
        for (int face = 0; face < FACE_COUNT; face++) {
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, face, textureWidth, textureHeight, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, missingTexture);
        }
        MemoryUtil.memFree(missingTexture);
    }

    private void uploadFaces(TextureData textureData, int blockTextureIndex) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, textureData.width);
        try {
            for (int face = 0; face < FACE_COUNT; face++) {
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, face * textureWidth);
                glTexSubImage3D(
                        GL_TEXTURE_2D_ARRAY,
                        0,
                        0,
                        0,
                        textureLayer(blockTextureIndex, face),
                        textureWidth,
                        textureHeight,
                        1,
                        GL_RGBA,
                        GL_UNSIGNED_BYTE,
                        textureData.pixels
                );
            }
        } finally {
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
    }

    private void configureAnisotropicFiltering() {
        if (!GL.getCapabilities().GL_ARB_texture_filter_anisotropic
                && !GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            LOGGER.info("Anisotropic texture filtering is not supported by this GPU");
            return;
        }

        float anisotropy = Math.min(TARGET_ANISOTROPY, glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY));
        glTexParameterf(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_ANISOTROPY, anisotropy);
        LOGGER.info("Block texture anisotropy set to {}x", anisotropy);
    }

    static int faceWidth(int atlasWidth, int atlasHeight) {
        if (atlasWidth % FACE_COUNT != 0) {
            throw new IllegalArgumentException("Texture atlas width must be divisible by " + FACE_COUNT);
        }
        int faceWidth = atlasWidth / FACE_COUNT;
        if (faceWidth != atlasHeight) {
            throw new IllegalArgumentException("Block texture faces must be square, got "
                    + faceWidth + "x" + atlasHeight);
        }
        return faceWidth;
    }

    static int textureLayer(int blockTextureIndex, int face) {
        if (blockTextureIndex < 0) {
            throw new IllegalArgumentException("Block texture index must be non-negative");
        }
        if (face < 0 || face >= FACE_COUNT) {
            throw new IllegalArgumentException("Face index must be between 0 and " + (FACE_COUNT - 1));
        }
        return blockTextureIndex * FACE_COUNT + face;
    }

    private TextureData loadTexture(String texturePath) {
        String resourcePath = texturePath.startsWith("/") ? texturePath.substring(1) : texturePath;

        try {
            ByteBuffer fileBuffer = FileReader.read(resourcePath, 8192);
            IntBuffer width = BufferUtils.createIntBuffer(1);
            IntBuffer height = BufferUtils.createIntBuffer(1);
            IntBuffer channels = BufferUtils.createIntBuffer(1);

            ByteBuffer pixels = stbi_load_from_memory(fileBuffer, width, height, channels, 4);
            if (pixels == null) {
                throw new IllegalStateException("Failed to decode texture " + texturePath + ": " + stbi_failure_reason());
            }

            int textureWidth = width.get(0);
            int textureHeight = height.get(0);
            if (textureWidth % FACE_COUNT != 0) {
                stbi_image_free(pixels);
                throw new IllegalStateException("Block texture must contain " + FACE_COUNT
                        + " faces side by side: " + texturePath + " has width " + textureWidth
                        + ", which is not divisible by " + FACE_COUNT);
            }
            if (textureWidth / FACE_COUNT != textureHeight) {
                stbi_image_free(pixels);
                throw new IllegalStateException("Block texture faces must be square: " + texturePath
                        + " contains " + (textureWidth / FACE_COUNT) + "x" + textureHeight + " faces");
            }

            return new TextureData(pixels, textureWidth, textureHeight);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read texture " + texturePath, e);
        }
    }

    private static final class TextureData {
        private final ByteBuffer pixels;
        private final int width;
        private final int height;

        private TextureData(ByteBuffer pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }

        private void free() {
            stbi_image_free(pixels);
        }
    }
}
