package org.weaw.engine.graphics.pipeline.passes;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import org.lwjgl.glfw.GLFW;
import org.weaw.engine.graphics.pipeline.RenderStats;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.window.Window;

import java.util.Locale;

import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.GL_VERSION;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL30.GL_MAX_ARRAY_TEXTURE_LAYERS;
import static org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS;

public class DebugImGuiPass implements RenderPass {
    private static final double STATS_REFRESH_INTERVAL_SECONDS = 0.5;

    private final Window window;
    private final InputManager inputManager;

    private ImGuiImplGlfw imGuiGlfw;
    private ImGuiImplGl3 imGuiGl3;
    private double lastFrameTime;
    private double statsAccumulator;
    private int framesAccumulated;
    private float fps;
    private float frameTimeMs;
    private String glVendor;
    private String glRenderer;
    private String glVersion;
    private int maxTextureSize;
    private int maxArrayTextureLayers;
    private int maxTextureUnits;
    private int maxShaderStorageBindings;
    private double lastStatsRefreshTime;
    private boolean showOverviewWindow = true;
    private boolean showFrameWindow = true;
    private boolean showGpuWindow = true;
    private boolean showResourcesWindow = false;
    private boolean showJvmWindow = false;
    private boolean showDeviceWindow = false;
    private boolean showPassBreakdownWindow = true;
    private DisplaySnapshot displaySnapshot = DisplaySnapshot.empty();

    public DebugImGuiPass(Window window, InputManager inputManager) {
        this.window = window;
        this.inputManager = inputManager;
    }

    @Override
    public String getName() {
        return "DebugImGuiPass";
    }

    @Override
    public void create() {
        ImGui.createContext();

        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);

        imGuiGlfw = new ImGuiImplGlfw();
        imGuiGlfw.init(window.getId(), true);

        imGuiGl3 = new ImGuiImplGl3();
        imGuiGl3.init("#version 460 core");

        lastFrameTime = GLFW.glfwGetTime();

        glVendor = glGetString(GL_VENDOR);
        glRenderer = glGetString(GL_RENDERER);
        glVersion = glGetString(GL_VERSION);
        maxTextureSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
        maxArrayTextureLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS);
        maxTextureUnits = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        maxShaderStorageBindings = glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        lastStatsRefreshTime = 0.0;
    }

    @Override
    public void execute(RenderContext context) {
        updateFrameStats();
        refreshDisplaySnapshotIfNeeded(context.getRenderStats());

        imGuiGlfw.newFrame();
        imGuiGl3.newFrame();
        ImGui.newFrame();

        renderMainMenuBar();
        renderOverviewWindow(context);
        renderFrameWindow();
        renderGpuWindow();
        renderResourcesWindow();
        renderJvmWindow();
        renderDeviceWindow();
        renderPassBreakdownWindow();

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    @Override
    public void resize(int width, int height) {
        // ImGui reads the framebuffer size from GLFW each frame.
    }

    @Override
    public void cleanup() {
        if (imGuiGl3 != null) {
            imGuiGl3.shutdown();
            imGuiGl3 = null;
        }
        if (imGuiGlfw != null) {
            imGuiGlfw.shutdown();
            imGuiGlfw = null;
        }
        ImGui.destroyContext();
    }

    private void updateFrameStats() {
        double now = GLFW.glfwGetTime();
        double deltaTime = now - lastFrameTime;
        lastFrameTime = now;

        if (deltaTime > 0.0) {
            statsAccumulator += deltaTime;
            framesAccumulated++;

            if (statsAccumulator >= 1.0) {
                fps = (float) (framesAccumulated / statsAccumulator);
                frameTimeMs = (float) ((statsAccumulator / framesAccumulated) * 1000.0);
                statsAccumulator = 0.0;
                framesAccumulated = 0;
            }
        }
    }

    private void refreshDisplaySnapshotIfNeeded(RenderStats stats) {
        double now = GLFW.glfwGetTime();
        if (displaySnapshot.isEmpty() || now - lastStatsRefreshTime >= STATS_REFRESH_INTERVAL_SECONDS) {
            displaySnapshot = DisplaySnapshot.from(stats);
            lastStatsRefreshTime = now;
        }
    }

    private void renderMainMenuBar() {
        if (!ImGui.beginMainMenuBar()) {
            return;
        }

        if (ImGui.beginMenu("View")) {
            showOverviewWindow = toggleWindowMenuItem("Overview", showOverviewWindow);
            ImGui.endMenu();
        }

        if (ImGui.beginMenu("Render")) {
            showFrameWindow = toggleWindowMenuItem("Frame Stats", showFrameWindow);
            showGpuWindow = toggleWindowMenuItem("GPU Memory", showGpuWindow);
            showResourcesWindow = toggleWindowMenuItem("Resources", showResourcesWindow);
            showPassBreakdownWindow = toggleWindowMenuItem("Pass Breakdown", showPassBreakdownWindow);
            ImGui.endMenu();
        }

        if (ImGui.beginMenu("System")) {
            showJvmWindow = toggleWindowMenuItem("JVM Memory", showJvmWindow);
            showDeviceWindow = toggleWindowMenuItem("Device", showDeviceWindow);
            ImGui.endMenu();
        }

        if (ImGui.beginMenu("Input")) {
            String mouseActionLabel = window.isCursorLocked() ? "Unlock Mouse" : "Lock Mouse";
            if (ImGui.menuItem(mouseActionLabel, inputManager.getBindingLabel(InputAction.TOGGLE_MOUSE_LOCK))) {
                toggleMouseLock();
            }
            ImGui.menuItem("Mouse Captured", null, window.isCursorLocked(), false);
            ImGui.endMenu();
        }

        if (ImGui.beginMenu("Layout")) {
            if (ImGui.menuItem("Compact")) {
                applyCompactLayout();
            }
            if (ImGui.menuItem("Rendering Focus")) {
                applyRenderingLayout();
            }
            if (ImGui.menuItem("Show All")) {
                showAllWindows();
            }
            ImGui.endMenu();
        }

        ImGui.endMainMenuBar();
    }

    private void renderOverviewWindow(RenderContext context) {
        if (!showOverviewWindow) {
            return;
        }

        ImGui.setNextWindowPos(10.0f, 35.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(320.0f, 120.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.85f);
        ImGui.begin("Debug Overview");
        ImGui.text(String.format("FPS: %.1f", fps));
        ImGui.text(String.format("Frame: %.2f ms", frameTimeMs));
        ImGui.text(String.format("Viewport: %d x %d", context.getViewportWidth(), context.getViewportHeight()));
        ImGui.separator();
        ImGui.text(window.isCursorLocked()
                ? "Mouse: locked (" + inputManager.getBindingLabel(InputAction.TOGGLE_MOUSE_LOCK) + " to unlock UI)"
                : "Mouse: unlocked (" + inputManager.getBindingLabel(InputAction.TOGGLE_MOUSE_LOCK) + " to relock)");
        ImGui.end();
    }

    private void renderFrameWindow() {
        if (!showFrameWindow) {
            return;
        }

        ImGui.setNextWindowPos(10.0f, 170.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(360.0f, 145.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.88f);
        ImGui.begin("Render Frame");
        ImGui.text(displaySnapshot.cpuLine);
        ImGui.text(displaySnapshot.drawCallsLine);
        ImGui.text(displaySnapshot.geometryLine);
        ImGui.text(displaySnapshot.visibilityLine);
        ImGui.text(displaySnapshot.residentFaceLine);
        ImGui.end();
    }

    private void renderGpuWindow() {
        if (!showGpuWindow) {
            return;
        }

        ImGui.setNextWindowPos(390.0f, 170.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(360.0f, 125.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.88f);
        ImGui.begin("Render GPU Memory");
        ImGui.text(displaySnapshot.meshGpuLine);
        ImGui.text(displaySnapshot.textureGpuLine);
        ImGui.text(displaySnapshot.renderTargetGpuLine);
        ImGui.text(displaySnapshot.totalGpuLine);
        ImGui.end();
    }

    private void renderResourcesWindow() {
        if (!showResourcesWindow) {
            return;
        }

        ImGui.setNextWindowPos(770.0f, 170.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(360.0f, 125.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.88f);
        ImGui.begin("Render Resources");
        ImGui.text(displaySnapshot.textureArrayLine);
        ImGui.text(displaySnapshot.meshSsboLine);
        ImGui.text(displaySnapshot.renderTargetLine);
        ImGui.text(displaySnapshot.attachmentLine);
        ImGui.end();
    }

    private void renderJvmWindow() {
        if (!showJvmWindow) {
            return;
        }

        ImGui.setNextWindowPos(10.0f, 335.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(360.0f, 110.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.88f);
        ImGui.begin("System JVM Memory");
        ImGui.text(displaySnapshot.heapUsedLine);
        ImGui.text(displaySnapshot.heapCommittedLine);
        ImGui.text(displaySnapshot.heapMaxLine);
        ImGui.end();
    }

    private void renderDeviceWindow() {
        if (!showDeviceWindow) {
            return;
        }

        ImGui.setNextWindowPos(390.0f, 315.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(360.0f, 150.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.88f);
        ImGui.begin("System Device");
        ImGui.text("Vendor: " + safe(glVendor));
        ImGui.text("Renderer: " + safe(glRenderer));
        ImGui.text("OpenGL: " + safe(glVersion));
        ImGui.separator();
        ImGui.text(String.format("Max texture size: %d", maxTextureSize));
        ImGui.text(String.format("Max array layers: %d", maxArrayTextureLayers));
        ImGui.text(String.format("Max texture units: %d", maxTextureUnits));
        ImGui.text(String.format("SSBO bindings: %d", maxShaderStorageBindings));
        ImGui.end();
    }

    private void renderPassBreakdownWindow() {
        if (!showPassBreakdownWindow) {
            return;
        }

        ImGui.setNextWindowPos(770.0f, 315.0f, ImGuiCond.Once);
        ImGui.setNextWindowSize(460.0f, 230.0f, ImGuiCond.Once);
        ImGui.setNextWindowBgAlpha(0.88f);
        ImGui.begin("Render Pass Breakdown");
        for (String passLine : displaySnapshot.passLines) {
            ImGui.text(passLine);
        }
        ImGui.end();
    }

    private boolean toggleWindowMenuItem(String label, boolean visible) {
        if (ImGui.menuItem(label, null, visible)) {
            return !visible;
        }
        return visible;
    }

    private void toggleMouseLock() {
        window.toggleCursorLock();
        inputManager.resetMouseDelta();
    }

    private void applyCompactLayout() {
        showOverviewWindow = true;
        showFrameWindow = true;
        showGpuWindow = true;
        showResourcesWindow = false;
        showJvmWindow = false;
        showDeviceWindow = false;
        showPassBreakdownWindow = true;
    }

    private void applyRenderingLayout() {
        showOverviewWindow = true;
        showFrameWindow = true;
        showGpuWindow = true;
        showResourcesWindow = true;
        showJvmWindow = false;
        showDeviceWindow = false;
        showPassBreakdownWindow = true;
    }

    private void showAllWindows() {
        showOverviewWindow = true;
        showFrameWindow = true;
        showGpuWindow = true;
        showResourcesWindow = true;
        showJvmWindow = true;
        showDeviceWindow = true;
        showPassBreakdownWindow = true;
    }

    private static float nanosToMillis(long nanos) {
        return nanos / 1_000_000.0f;
    }

    private static String safe(String value) {
        return value == null ? "Unknown" : value;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = -1;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex]);
    }

    private record DisplaySnapshot(
            String cpuLine,
            String drawCallsLine,
            String geometryLine,
            String visibilityLine,
            String residentFaceLine,
            String meshGpuLine,
            String textureGpuLine,
            String renderTargetGpuLine,
            String totalGpuLine,
            String textureArrayLine,
            String meshSsboLine,
            String renderTargetLine,
            String attachmentLine,
            String heapUsedLine,
            String heapCommittedLine,
            String heapMaxLine,
            String[] passLines
    ) {
        private static DisplaySnapshot empty() {
            return new DisplaySnapshot(
                    "CPU render passes: collecting...",
                    "Draw calls: collecting...",
                    "Faces: collecting...",
                    "Visible meshes: collecting...",
                    "Resident face data: collecting...",
                    "Mesh buffers: collecting...",
                    "Texture arrays: collecting...",
                    "Render targets: collecting...",
                    "Estimated total GPU memory: collecting...",
                    "Texture arrays: collecting...",
                    "Mesh SSBOs: collecting...",
                    "Render targets / FBOs: collecting...",
                    "Color attachments: collecting...",
                    "Heap used: collecting...",
                    "Heap committed: collecting...",
                    "Heap max: collecting...",
                    new String[]{"Pass breakdown: collecting..."}
            );
        }

        private static DisplaySnapshot from(RenderStats stats) {
            String[] passLines = stats.getPassStats().stream()
                    .flatMap(passStats -> java.util.stream.Stream.of(
                            String.format("%s: %.3f ms | draws %d | visible %d | culled %d",
                                    passStats.getName(),
                                    nanosToMillis(passStats.getCpuTimeNs()),
                                    passStats.getDrawCalls(),
                                    passStats.getVisibleMeshCount(),
                                    passStats.getCulledMeshCount()),
                            String.format("   faces %d | resident meshes %d | mesh gpu %s | textures %s",
                                    passStats.getDrawnFaceCount(),
                                    passStats.getResidentMeshCount(),
                                    formatBytes(passStats.getMeshGpuBytes()),
                                    formatBytes(passStats.getTextureGpuBytes()))
                    ))
                    .toArray(String[]::new);

            if (passLines.length == 0) {
                passLines = new String[]{"No pass stats available"};
            }

            return new DisplaySnapshot(
                    String.format("CPU render passes: %.3f ms", nanosToMillis(stats.getTotalPassCpuTimeNs())),
                    String.format("Draw calls: %d", stats.getDrawCalls()),
                    String.format("Faces: %d | Triangles: %d | Vertices: %d",
                            stats.getDrawnFaceCount(),
                            stats.getDrawnTriangleCount(),
                            stats.getDrawnVertexCount()),
                    String.format("Visible meshes: %d | Culled: %d | Resident: %d",
                            stats.getVisibleMeshCount(),
                            stats.getCulledMeshCount(),
                            stats.getResidentMeshCount()),
                    String.format("Resident face data: %d", stats.getResidentFaceCount()),
                    String.format("Mesh buffers: %s", formatBytes(stats.getMeshGpuBytes())),
                    String.format("Texture arrays: %s", formatBytes(stats.getTextureGpuBytes())),
                    String.format("Render targets: %s", formatBytes(stats.getRenderTargetGpuBytes())),
                    String.format("Estimated total GPU memory: %s", formatBytes(stats.getTotalEstimatedGpuBytes())),
                    String.format("Texture arrays: %d", stats.getTextureArrayCount()),
                    String.format("Mesh SSBOs: %d", stats.getResidentMeshCount()),
                    String.format("Render targets / FBOs: %d", stats.getRenderTargetCount()),
                    String.format("Color attachments: %d | Depth attachments: %d",
                            stats.getRenderTargetColorTextureCount(),
                            stats.getRenderTargetDepthTextureCount()),
                    String.format("Heap used: %s", formatBytes(stats.getJvmHeapUsedBytes())),
                    String.format("Heap committed: %s", formatBytes(stats.getJvmHeapCommittedBytes())),
                    String.format("Heap max: %s", formatBytes(stats.getJvmHeapMaxBytes())),
                    passLines
            );
        }

        private boolean isEmpty() {
            return passLines.length == 1 && "Pass breakdown: collecting...".equals(passLines[0]);
        }
    }
}
