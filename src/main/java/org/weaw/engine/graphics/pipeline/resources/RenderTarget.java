package org.weaw.engine.graphics.pipeline.resources;

import lombok.Getter;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE;
import static org.lwjgl.opengl.GL32.glTexImage2DMultisample;

/**
 * Wrapper for OpenGL framebuffer (FBO) with color and optional depth attachments.
 *
 * Simplifies FBO creation, binding, and resizing for render passes.
 * Supports:
 * - Color attachment (GL_RGBA8 or custom format)
 * - Depth attachment (optional, GL_DEPTH_COMPONENT24)
 * - Automatic resize with validation
 */
public class RenderTarget {

    private static final Logger LOGGER = LoggerFactory.getLogger(RenderTarget.class);

    @Getter
    private final String name;
    private final boolean hasDepth;
    private final int colorFormat;
    @Getter
    private final int sampleCount;

    @Getter
    private int fbo;
    @Getter
    private int colorTexture;
    @Getter
    private int depthTexture;

    @Getter
    private int width;
    @Getter
    private int height;

    /**
     * Create a render target with color + optional depth.
     *
     * @param name Debug name
     * @param width Initial width
     * @param height Initial height
     * @param hasDepth Whether to create depth attachment
     * @param colorFormat GL color format (e.g., GL_RGBA8, GL_RGB16F)
     */
    public RenderTarget(String name, int width, int height, boolean hasDepth, int colorFormat) {
        this(name, width, height, hasDepth, colorFormat, 1);
    }

    /**
     * Create a render target with an explicit sample count.
     */
    public RenderTarget(String name, int width, int height, boolean hasDepth, int colorFormat, int sampleCount) {
        if (sampleCount < 1) {
            throw new IllegalArgumentException("Sample count must be at least 1");
        }
        this.name = name;
        this.width = width;
        this.height = height;
        this.hasDepth = hasDepth;
        this.colorFormat = colorFormat;
        this.sampleCount = sampleCount;

        createFramebuffer();
        LOGGER.info("RenderTarget '{}' created: {}x{}, depth={}, format=0x{}, samples={}",
                    name, width, height, hasDepth, Integer.toHexString(colorFormat), sampleCount);
    }

    /**
     * Convenience constructor with RGBA8 color format.
     */
    public RenderTarget(String name, int width, int height, boolean hasDepth) {
        this(name, width, height, hasDepth, GL_RGBA8);
    }

    /**
     * Bind this FBO for rendering.
     */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        GLStateManager.setViewport(width, height);
    }

    /**
     * Unbind this FBO (bind default framebuffer).
     */
    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Resize render target (recreates FBO and textures).
     *
     * @param newWidth New width
     * @param newHeight New height
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;

        LOGGER.info("Resizing RenderTarget '{}': {}x{} -> {}x{}",
                    name, width, height, newWidth, newHeight);

        // Delete old resources
        cleanup();

        // Update dimensions
        this.width = newWidth;
        this.height = newHeight;

        // Recreate
        createFramebuffer();
    }

    /**
     * Cleanup GPU resources.
     */
    public void cleanup() {
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        if (colorTexture != 0) {
            glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (depthTexture != 0) {
            glDeleteTextures(depthTexture);
            depthTexture = 0;
        }
    }

    public boolean hasDepth() {
        return hasDepth;
    }

    public long estimateTotalGpuBytes() {
        return estimateColorGpuBytes() + estimateDepthGpuBytes();
    }

    public long estimateColorGpuBytes() {
        return (long) width * height * estimateBytesPerPixel(colorFormat) * sampleCount;
    }

    public long estimateDepthGpuBytes() {
        if (!hasDepth) {
            return 0L;
        }
        return (long) width * height * 4L * sampleCount;
    }

    /**
     * Copy this target into a single-sample target, resolving multisampling when present.
     */
    public void resolveTo(RenderTarget destination, boolean includeDepth) {
        if (destination.sampleCount != 1) {
            throw new IllegalArgumentException("Resolve destination must be single-sampled");
        }
        if (width != destination.width || height != destination.height) {
            throw new IllegalArgumentException("Resolve targets must have matching dimensions");
        }
        if (includeDepth && (!hasDepth || !destination.hasDepth)) {
            throw new IllegalArgumentException("Both resolve targets must have depth attachments");
        }

        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination.fbo);
        int mask = GL_COLOR_BUFFER_BIT | (includeDepth ? GL_DEPTH_BUFFER_BIT : 0);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, mask, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // ========================================================================
    // Private Methods
    // ========================================================================

    private void createFramebuffer() {
        // Create FBO
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Create color texture
        colorTexture = glGenTextures();
        int textureTarget = sampleCount > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D;
        glBindTexture(textureTarget, colorTexture);
        if (sampleCount > 1) {
            glTexImage2DMultisample(textureTarget, sampleCount, colorFormat, width, height, true);
        } else {
            glTexImage2D(textureTarget, 0, colorFormat, width, height, 0, GL_RGBA, colorDataType(colorFormat), 0);
            glTexParameteri(textureTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(textureTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(textureTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(textureTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, textureTarget, colorTexture, 0);

        // Explicitly tell OpenGL to draw to color attachment 0
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer drawBuffers = stack.mallocInt(1);
            drawBuffers.put(GL_COLOR_ATTACHMENT0);
            drawBuffers.flip();
            glDrawBuffers(drawBuffers);
        }

        // Create depth texture if needed
        if (hasDepth) {
            depthTexture = glGenTextures();
            glBindTexture(textureTarget, depthTexture);
            if (sampleCount > 1) {
                glTexImage2DMultisample(textureTarget, sampleCount, GL_DEPTH_COMPONENT24, width, height, true);
            } else {
                glTexImage2D(textureTarget, 0, GL_DEPTH_COMPONENT24, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
                glTexParameteri(textureTarget, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(textureTarget, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(textureTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(textureTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            }
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, textureTarget, depthTexture, 0);
        }

        // Validate FBO
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("Framebuffer '{}' incomplete: 0x{}", name, Integer.toHexString(status));
            throw new RuntimeException("Framebuffer creation failed for: " + name);
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindTexture(textureTarget, 0);
    }

    private static int estimateBytesPerPixel(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8 -> 1;
            case GL_RG8 -> 2;
            case GL_RGB8 -> 3;
            case GL_RGBA8 -> 4;
            case GL_R16F -> 2;
            case GL_RG16F -> 4;
            case GL_RGB16F -> 6;
            case GL_RGBA16F -> 8;
            case GL_R32F -> 4;
            case GL_RG32F -> 8;
            case GL_RGB32F -> 12;
            case GL_RGBA32F -> 16;
            default -> 4;
        };
    }

    private static int colorDataType(int internalFormat) {
        return switch (internalFormat) {
            case GL_R16F, GL_RG16F, GL_RGB16F, GL_RGBA16F,
                 GL_R32F, GL_RG32F, GL_RGB32F, GL_RGBA32F -> GL_FLOAT;
            default -> GL_UNSIGNED_BYTE;
        };
    }
}
