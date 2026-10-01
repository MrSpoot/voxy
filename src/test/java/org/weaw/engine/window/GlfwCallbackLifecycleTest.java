package org.weaw.engine.window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.glfw.GLFWErrorCallback;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwTerminate;

@EnabledOnOs(OS.WINDOWS)
class GlfwCallbackLifecycleTest {
    @AfterEach
    void resetGlfw() {
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.close();
        }
        glfwTerminate();
    }

    @Test
    void unregistersTheGlobalCallbackBeforeClosingItAcrossRepeatedSessions() {
        for (int iteration = 0; iteration < 8; iteration++) {
            GLFWErrorCallback callback = GLFWErrorCallback.create((error, description) -> { });
            assertNull(glfwSetErrorCallback(callback));
            assertTrue(glfwInit());

            GLFWErrorCallback detached = glfwSetErrorCallback(null);
            detached.close();
            glfwTerminate();
        }
    }

    @Test
    void cleanupIsSafeBeforeCreationAndWhenRepeated() {
        Window window = new Window("not-created", 64, 64);

        window.cleanup();
        window.cleanup();
    }
}
