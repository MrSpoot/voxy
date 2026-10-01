package org.weaw.engine.window;

import lombok.Getter;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.graphics.Renderer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL45.GL_LOWER_LEFT;
import static org.lwjgl.opengl.GL45.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45.glClipControl;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Window {

    private static final Logger LOGGER = LoggerFactory.getLogger(Window.class);

    @Getter
    private long id;
    private String title;
    @Getter
    private boolean fullscreen;
    @Getter
    private int width, height;
    @Getter
    private int logicalWidth, logicalHeight;
    @Getter
    private boolean cursorLocked = true;

    private GLFWFramebufferSizeCallback resizeCallback;
    private GLFWWindowSizeCallback windowSizeCallback;
    private GLFWErrorCallback errorCallback;
    private boolean glfwInitialized;
    private int windowedX = Integer.MIN_VALUE;
    private int windowedY = Integer.MIN_VALUE;
    private int nativeDisplayWidth;
    private int nativeDisplayHeight;

    // Renderer reference for resize notification
    private Renderer renderer;

    public Window(String title, int width, int height) {
        this(title, width, height, false);
    }

    public Window(String title, int width, int height, boolean fullscreen) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.fullscreen = fullscreen;
    }

    /**
     * Set the renderer to notify on window resize.
     * Call this after creating the renderer.
     */
    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public float aspectRatio() {
        return width / (float) height;
    }

    public boolean shouldClose(){
        return glfwWindowShouldClose(this.id);
    }

    public void create(){
        if (glfwInitialized || id != NULL) {
            throw new IllegalStateException("Window has already been created");
        }
        LOGGER.info("Creating window");
        try {
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
            glfwInitialized = true;

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
            glfwWindowHint(GLFW_DECORATED, GLFW_TRUE);

            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

            GLFWVidMode desktopMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (desktopMode == null) {
                throw new IllegalStateException("Unable to query the primary monitor video mode");
            }
            nativeDisplayWidth = desktopMode.width();
            nativeDisplayHeight = desktopMode.height();

            //TODO Make better code for fullscreen
            if (this.width == 0 && this.height == 0) {
                glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);
                width = nativeDisplayWidth;
                height = nativeDisplayHeight;
            }

            long monitor = fullscreen ? glfwGetPrimaryMonitor() : NULL;
            id = glfwCreateWindow(width, height, title, monitor, NULL);
            if (id == NULL) {
                throw new RuntimeException("Failed to create the GLFW window");
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                var widthBuffer = stack.mallocInt(1);
                var heightBuffer = stack.mallocInt(1);
                glfwGetWindowSize(id, widthBuffer, heightBuffer);
                logicalWidth = widthBuffer.get(0);
                logicalHeight = heightBuffer.get(0);
            }
            if (!fullscreen) {
                rememberWindowedBounds();
            }

            windowSizeCallback = new GLFWWindowSizeCallback() {
                @Override
                public void invoke(long window, int width, int height) {
                    Window.this.logicalWidth = width;
                    Window.this.logicalHeight = height;
                }
            };
            glfwSetWindowSizeCallback(id, windowSizeCallback);

            resizeCallback = new GLFWFramebufferSizeCallback() {
                @Override
                public void invoke(long window, int width, int height) {
                    glViewport(0, 0, width, height);
                    Window.this.width = width;
                    Window.this.height = height;

                    // Notify renderer to resize FBOs and passes
                    if (renderer != null) {
                        renderer.resize(width, height);
                    }
                }
            };
            glfwSetFramebufferSizeCallback(id, resizeCallback);

            errorCallback = GLFWErrorCallback.create((errorCode, msgPtr) -> {
                LOGGER.error("GLFW Error [{}]: {}", errorCode, MemoryUtil.memUTF8(msgPtr));
            });
            glfwSetErrorCallback(errorCallback);

            glfwMakeContextCurrent(id);
            setCursorLocked(true);
            GL.createCapabilities();

            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);

            glEnable(GL_DEPTH_TEST);
            glEnable(GL_MULTISAMPLE);
            glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
            glDepthFunc(GL_GREATER);
            glClearDepth(0.0);

            glfwSwapInterval(0);
            glViewport(0,0,width,height);
            glfwShowWindow(id);
        } catch (RuntimeException | Error exception) {
            cleanup();
            throw exception;
        }
    }

    public void update(){
        glfwSwapBuffers(id);
        glfwPollEvents();
    }

    public void requestClose(){
        glfwSetWindowShouldClose(id, true);
    }

    /** @deprecated Use {@link #requestClose()} to distinguish an application exit from a mode transition. */
    @Deprecated
    public void close() {
        requestClose();
    }

    public void setVsync(boolean enabled) {
        ensureCreated();
        glfwSwapInterval(enabled ? 1 : 0);
    }

    public DisplayResolution nativeDisplayResolution() {
        ensureCreated();
        return new DisplayResolution(nativeDisplayWidth, nativeDisplayHeight);
    }

    /** Applies resolution and fullscreen changes without replacing the GLFW window or OpenGL context. */
    public void applyDisplayMode(int requestedWidth, int requestedHeight, boolean requestedFullscreen) {
        ensureCreated();
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("Window dimensions must be positive");
        }

        long primaryMonitor = glfwGetPrimaryMonitor();
        GLFWVidMode videoMode = glfwGetVideoMode(primaryMonitor);
        if (videoMode == null) {
            throw new IllegalStateException("Unable to query the primary monitor video mode");
        }

        if (requestedFullscreen) {
            if (!fullscreen) {
                rememberWindowedBounds();
            }
            glfwSetWindowAttrib(id, GLFW_DECORATED, GLFW_FALSE);
            glfwSetWindowMonitor(
                    id,
                    primaryMonitor,
                    0,
                    0,
                    requestedWidth,
                    requestedHeight,
                    videoMode.refreshRate()
            );
        } else {
            int targetX;
            int targetY;
            if (fullscreen) {
                targetX = windowedX == Integer.MIN_VALUE
                        ? 0
                        : windowedX;
                targetY = windowedY == Integer.MIN_VALUE
                        ? 0
                        : windowedY;
            } else {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var x = stack.mallocInt(1);
                    var y = stack.mallocInt(1);
                    glfwGetWindowPos(id, x, y);
                    targetX = x.get(0);
                    targetY = y.get(0);
                }
            }

            glfwSetWindowAttrib(id, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowAttrib(id, GLFW_RESIZABLE, GLFW_TRUE);
            glfwSetWindowMonitor(id, NULL, targetX, targetY, requestedWidth, requestedHeight, GLFW_DONT_CARE);
            glfwSetWindowAttrib(id, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowAttrib(id, GLFW_RESIZABLE, GLFW_TRUE);
            glfwRestoreWindow(id);
            glfwSetWindowSize(id, requestedWidth, requestedHeight);

            MonitorWorkArea workArea = primaryMonitorWorkArea(
                    primaryMonitor,
                    new DisplayResolution(nativeDisplayWidth, nativeDisplayHeight)
            );
            if (windowedX == Integer.MIN_VALUE) {
                targetX = workArea.x() + Math.max(0, (workArea.width() - requestedWidth) / 2);
            }
            if (windowedY == Integer.MIN_VALUE) {
                targetY = workArea.y() + Math.max(0, (workArea.height() - requestedHeight) / 2);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                var left = stack.mallocInt(1);
                var top = stack.mallocInt(1);
                var right = stack.mallocInt(1);
                var bottom = stack.mallocInt(1);
                glfwGetWindowFrameSize(id, left, top, right, bottom);
                int minX = workArea.x() + left.get(0);
                int maxX = workArea.x() + workArea.width() - requestedWidth - right.get(0);
                int minY = workArea.y() + top.get(0);
                int maxY = workArea.y() + workArea.height() - requestedHeight - bottom.get(0);
                targetX = clampVisible(targetX, minX, maxX);
                targetY = clampVisible(targetY, minY, maxY);
            }
            glfwSetWindowPos(id, targetX, targetY);
            windowedX = targetX;
            windowedY = targetY;
        }
        fullscreen = requestedFullscreen;
    }

    public void setCursorLocked(boolean cursorLocked) {
        this.cursorLocked = cursorLocked;

        if (id != NULL) {
            glfwSetInputMode(id, GLFW_CURSOR, cursorLocked ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        }
    }

    public void toggleCursorLock() {
        setCursorLocked(!cursorLocked);
    }

    public void cleanup() {
        renderer = null;
        if (id != NULL && glfwInitialized) {
            if (resizeCallback != null) {
                glfwSetFramebufferSizeCallback(id, null);
            }
            if (windowSizeCallback != null) {
                glfwSetWindowSizeCallback(id, null);
            }
        }
        if (resizeCallback != null) {
            resizeCallback.close();
            resizeCallback = null;
        }
        if (windowSizeCallback != null) {
            windowSizeCallback.close();
            windowSizeCallback = null;
        }
        if (errorCallback != null) {
            if (glfwInitialized) {
                glfwSetErrorCallback(null);
            }
            errorCallback.close();
            errorCallback = null;
        }
        if (id != NULL) {
            if (glfwGetCurrentContext() == id) {
                glfwMakeContextCurrent(NULL);
                GL.setCapabilities(null);
            }
            glfwDestroyWindow(id);
            id = NULL;
        }
        if (glfwInitialized) {
            glfwTerminate();
            glfwInitialized = false;
        }
    }

    private void rememberWindowedBounds() {
        if (id == NULL) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1);
            var y = stack.mallocInt(1);
            glfwGetWindowPos(id, x, y);
            windowedX = x.get(0);
            windowedY = y.get(0);
        }
    }

    private void ensureCreated() {
        if (id == NULL || !glfwInitialized) {
            throw new IllegalStateException("Window has not been created");
        }
    }

    private static MonitorWorkArea primaryMonitorWorkArea(long monitor, DisplayResolution fallbackResolution) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1);
            var y = stack.mallocInt(1);
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            glfwGetMonitorWorkarea(monitor, x, y, width, height);
            if (width.get(0) > 0 && height.get(0) > 0) {
                return new MonitorWorkArea(x.get(0), y.get(0), width.get(0), height.get(0));
            }
        }
        return new MonitorWorkArea(0, 0, fallbackResolution.width(), fallbackResolution.height());
    }

    private static int clampVisible(int value, int minimum, int maximum) {
        return maximum < minimum ? minimum : Math.clamp(value, minimum, maximum);
    }

    private record MonitorWorkArea(int x, int y, int width, int height) {
    }

}
