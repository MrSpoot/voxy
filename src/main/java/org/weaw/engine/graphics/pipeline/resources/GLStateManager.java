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

    /**
     * Configure depth testing and depth writing.
     *
     * @param test Enable depth testing (fragments farther away are discarded)
     * @param write Enable depth writing (update depth buffer)
     */
    public static void setDepthTest(boolean test, boolean write) {
        if (test) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        glDepthMask(write);
    }

    /**
     * Configure alpha blending.
     *
     * @param enabled Enable blending
     */
    public static void setBlending(boolean enabled) {
        if (enabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
    }

    /**
     * Configure face culling.
     *
     * @param enabled Enable face culling
     * @param cullFace Face to cull (GL_BACK, GL_FRONT, GL_FRONT_AND_BACK)
     */
    public static void setCulling(boolean enabled, int cullFace) {
        if (enabled) {
            glEnable(GL_CULL_FACE);
            glCullFace(cullFace);
        } else {
            glDisable(GL_CULL_FACE);
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
        glFrontFace(winding);
    }

    /**
     * Set polygon mode (fill or wireframe).
     *
     * @param mode GL_FILL or GL_LINE
     */
    public static void setPolygonMode(int mode) {
        glPolygonMode(GL_FRONT_AND_BACK, mode);
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
        glViewport(0, 0, width, height);
    }
}
