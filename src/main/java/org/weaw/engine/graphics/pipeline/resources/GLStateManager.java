package org.weaw.engine.graphics.pipeline.resources;

import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_COLOR;
import static org.lwjgl.opengl.GL30.glClearBufferfv;

/**
 * Helper for managing OpenGL state in render passes.
 *
 * Provides convenient methods to configure:
 * - Depth testing/writing
 * - Blending modes
 * - Face culling
 * - Polygon mode (fill/wireframe)
 *
 * Usage in RenderPass:
 * <pre>
 * GLStateManager.setDepthTest(true, true);  // test=ON, write=ON
 * GLStateManager.setBlending(false);        // blending=OFF
 * // ... render ...
 * </pre>
 */
public class GLStateManager {
    private static Boolean depthTestEnabled;
    private static Boolean depthWriteEnabled;
    private static Boolean blendingEnabled;
    private static Boolean cullingEnabled;
    private static int cullFace = Integer.MIN_VALUE;
    private static int frontFace = Integer.MIN_VALUE;
    private static int polygonMode = GL_FILL;
    private static int viewportWidth = -1;
    private static int viewportHeight = -1;

    /**
     * Configure depth testing and depth writing.
     *
     * @param test Enable depth testing (fragments farther away are discarded)
     * @param write Enable depth writing (update depth buffer)
     */
    public static void setDepthTest(boolean test, boolean write) {
        if (depthTestEnabled == null || depthTestEnabled != test) {
            if (test) {
                glEnable(GL_DEPTH_TEST);
            } else {
                glDisable(GL_DEPTH_TEST);
            }
            depthTestEnabled = test;
        }
        if (test) {
            glDepthFunc(GL_GREATER);
        }
        if (depthWriteEnabled == null || depthWriteEnabled != write) {
            glDepthMask(write);
            depthWriteEnabled = write;
        }
    }

    /**
     * Configure alpha blending.
     *
     * @param enabled Enable blending
     */
    public static void setBlending(boolean enabled) {
        if (blendingEnabled == null || blendingEnabled != enabled) {
            if (enabled) {
                glEnable(GL_BLEND);
            } else {
                glDisable(GL_BLEND);
            }
            blendingEnabled = enabled;
        }
        if (enabled) {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    /**
     * Configure face culling.
     *
     * @param enabled Enable face culling
     * @param cullFace Face to cull (GL_BACK, GL_FRONT, GL_FRONT_AND_BACK)
     */
    public static void setCulling(boolean enabled, int cullFace) {
        if (cullingEnabled == null || cullingEnabled != enabled) {
            if (enabled) {
                glEnable(GL_CULL_FACE);
            } else {
                glDisable(GL_CULL_FACE);
            }
            cullingEnabled = enabled;
        }
        if (enabled && GLStateManager.cullFace != cullFace) {
            glCullFace(cullFace);
            GLStateManager.cullFace = cullFace;
        }
    }

    /**
     * Convenience: Enable back-face culling.
     */
    public static void setCulling(boolean enabled) {
        setCulling(enabled, GL_BACK);
    }

    /**
     * Configure front-face winding order.
     *
     * @param winding GL_CCW or GL_CW
     */
    public static void setFrontFace(int winding) {
        if (frontFace != winding) {
            glFrontFace(winding);
            frontFace = winding;
        }
    }

    /**
     * Set polygon mode (fill or wireframe).
     *
     * @param mode GL_FILL or GL_LINE
     */
    public static void setPolygonMode(int mode) {
        if (polygonMode != mode) {
            glPolygonMode(GL_FRONT_AND_BACK, mode);
            polygonMode = mode;
        }
    }

    public static int getPolygonMode() {
        return polygonMode;
    }

    /**
     * Clear color and depth buffers.
     *
     * @param r Red component [0-1]
     * @param g Green component [0-1]
     * @param b Blue component [0-1]
     * @param a Alpha component [0-1]
     */
    public static void clear(float r, float g, float b, float a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glClearBufferfv(GL_COLOR, 0, stack.floats(r, g, b, a));
        }
        glClearDepth(0.0);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Set viewport dimensions.
     *
     * @param width Viewport width
     * @param height Viewport height
     */
    public static void setViewport(int width, int height) {
        if (viewportWidth != width || viewportHeight != height) {
            glViewport(0, 0, width, height);
            viewportWidth = width;
            viewportHeight = height;
        }
    }

    /** Invalidates states that external renderers may have modified between frames. */
    public static void invalidateFrameState() {
        depthTestEnabled = null;
        depthWriteEnabled = null;
        blendingEnabled = null;
        cullingEnabled = null;
        cullFace = Integer.MIN_VALUE;
        frontFace = Integer.MIN_VALUE;
        viewportWidth = -1;
        viewportHeight = -1;
    }
}
