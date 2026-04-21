package org.weaw.engine.graphics.pipeline.passes;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import org.weaw.engine.graphics.pipeline.ColorGradingSettings;
import org.weaw.engine.graphics.pipeline.FogSettings;
import org.weaw.engine.graphics.pipeline.LightingSettings;
import org.lwjgl.glfw.GLFW;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.RenderStats;
import org.weaw.engine.graphics.utils.ChunkFaceArena;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.window.Window;
import org.weaw.game.Chunk;
import org.weaw.game.WorldSettings;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final int CHUNK_HISTORY_SAMPLES = 180;

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
    private boolean showParametersWindow = true;
    private boolean showFrameWindow = false;
    private boolean showGpuWindow = false;
    private boolean showArenaWindow = false;
    private boolean showChunkProfilingWindow = false;
    private boolean showResourcesWindow = false;
    private boolean showLightingWindow = true;
    private boolean showColorGradingWindow = true;
    private boolean showFogWindow = true;
    private boolean showJvmWindow = false;
    private boolean showDeviceWindow = false;
    private boolean showPassBreakdownWindow = false;
    private boolean layoutRefreshPending = false;
    private int lastLayoutWidth = -1;
    private int lastLayoutHeight = -1;
    private final Map<String, ChunkProfilingHistory> chunkProfilingHistories = new HashMap<>();
    private DisplaySnapshot displaySnapshot = DisplaySnapshot.empty();
    private WindowRect overviewRect = WindowRect.of(10.0f, 35.0f, 320.0f, 120.0f);
    private WindowRect parametersRect = WindowRect.of(10.0f, 170.0f, 320.0f, 120.0f);
    private WindowRect frameRect = WindowRect.of(10.0f, 170.0f, 360.0f, 145.0f);
    private WindowRect gpuRect = WindowRect.of(390.0f, 170.0f, 360.0f, 125.0f);
    private WindowRect arenaRect = WindowRect.of(390.0f, 315.0f, 360.0f, 300.0f);
    private WindowRect chunkProfilingRect = WindowRect.of(770.0f, 315.0f, 460.0f, 200.0f);
    private WindowRect resourcesRect = WindowRect.of(770.0f, 170.0f, 360.0f, 155.0f);
    private WindowRect lightingRect = WindowRect.of(770.0f, 335.0f, 360.0f, 285.0f);
    private WindowRect colorGradingRect = WindowRect.of(770.0f, 635.0f, 360.0f, 270.0f);
    private WindowRect fogRect = WindowRect.of(770.0f, 920.0f, 360.0f, 265.0f);
    private WindowRect jvmRect = WindowRect.of(10.0f, 335.0f, 360.0f, 110.0f);
    private WindowRect deviceRect = WindowRect.of(390.0f, 555.0f, 360.0f, 150.0f);
    private WindowRect passBreakdownRect = WindowRect.of(770.0f, 530.0f, 460.0f, 230.0f);

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
        updateChunkProfilingHistories(context.getRenderStats());
        refreshDisplaySnapshotIfNeeded(context.getRenderStats());
        updateWindowLayoutIfNeeded(context);

        imGuiGlfw.newFrame();
        imGuiGl3.newFrame();
        ImGui.newFrame();

        renderMainMenuBar();
        renderOverviewWindow(context);
        renderParametersWindow(context);
        renderFrameWindow();
        renderGpuWindow();
        renderArenaWindow(context);
        renderChunkProfilingWindow(context);
        renderResourcesWindow();
        renderLightingWindow(context);
        renderColorGradingWindow(context);
        renderFogWindow(context);
        renderJvmWindow();
        renderDeviceWindow();
        renderPassBreakdownWindow();

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
        layoutRefreshPending = false;
    }

    @Override
    public void resize(int width, int height) {
        layoutRefreshPending = true;
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

        if (ImGui.beginMenu("Debug")) {
            showParametersWindow = toggleWindowMenuItem("Parameters", showParametersWindow);
            ImGui.endMenu();
        }

        if (ImGui.beginMenu("Render")) {
            showFrameWindow = toggleWindowMenuItem("Frame Stats", showFrameWindow);
            showGpuWindow = toggleWindowMenuItem("GPU Memory", showGpuWindow);
            showArenaWindow = toggleWindowMenuItem("Chunk Arenas", showArenaWindow);
            showChunkProfilingWindow = toggleWindowMenuItem("Chunk Profiling", showChunkProfilingWindow);
            showResourcesWindow = toggleWindowMenuItem("Resources", showResourcesWindow);
            showLightingWindow = toggleWindowMenuItem("Lighting", showLightingWindow);
            showColorGradingWindow = toggleWindowMenuItem("Tone Mapping", showColorGradingWindow);
            showFogWindow = toggleWindowMenuItem("Fog", showFogWindow);
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
            if (ImGui.menuItem("Hide All")) {
                hideAllWindows();
            }
            ImGui.endMenu();
        }

        ImGui.endMainMenuBar();
    }

    private void renderOverviewWindow(RenderContext context) {
        if (!showOverviewWindow) {
            return;
        }

        applyWindowLayout(overviewRect, 0.85f);
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

        applyWindowLayout(frameRect, 0.88f);
        ImGui.begin("Render Frame");
        ImGui.text(displaySnapshot.cpuLine);
        ImGui.text(displaySnapshot.drawCallsLine);
        ImGui.text(displaySnapshot.geometryLine);
        ImGui.text(displaySnapshot.visibilityLine);
        ImGui.text(displaySnapshot.residentFaceLine);
        ImGui.end();
    }

    private void renderParametersWindow(RenderContext context) {
        if (!showParametersWindow) {
            return;
        }

        WorldSettings settings = context.getWorldSettings();
        applyWindowLayout(parametersRect, 0.9f);
        ImGui.begin("Debug Parameters");
        ImGui.sliderFloat(
                "Render Distance",
                settings.renderDistanceChunksRef(),
                WorldSettings.MIN_RENDER_DISTANCE_CHUNKS,
                WorldSettings.MAX_RENDER_DISTANCE_CHUNKS
        );
        int renderDistanceChunks = settings.getRenderDistanceChunks();
        ImGui.text(String.format("Chunks: %d", renderDistanceChunks));
        ImGui.text(String.format("Blocks: %d", renderDistanceChunks * Chunk.SIZE));
        if (ImGui.button("Reset##DebugParameters")) {
            settings.reset();
        }
        ImGui.end();
    }


    private void renderGpuWindow() {
        if (!showGpuWindow) {
            return;
        }

        applyWindowLayout(gpuRect, 0.88f);
        ImGui.begin("Render GPU Memory");
        ImGui.text(displaySnapshot.meshGpuLine);
        ImGui.text(displaySnapshot.textureGpuLine);
        ImGui.text(displaySnapshot.renderTargetGpuLine);
        ImGui.text(displaySnapshot.totalGpuLine);
        ImGui.end();
    }

    private void renderArenaWindow(RenderContext context) {
        if (!showArenaWindow) {
            return;
        }

        applyWindowLayout(arenaRect, 0.88f);
        ImGui.begin("Render Chunk Arenas");
        renderArenaStats("Opaque", context.getOpaqueChunkFaceArena());
        ImGui.separator();
        renderArenaStats("Cutout", context.getCutoutChunkFaceArena());
        ImGui.separator();
        renderArenaStats("Transparent", context.getTransparentChunkFaceArena());
        ImGui.end();
    }

    private void renderResourcesWindow() {
        if (!showResourcesWindow) {
            return;
        }

        applyWindowLayout(resourcesRect, 0.88f);
        ImGui.begin("Render Resources");
        ImGui.text(displaySnapshot.textureArrayLine);
        ImGui.text(displaySnapshot.meshSsboLine);
        ImGui.text(displaySnapshot.indirectBatchLine);
        ImGui.text(displaySnapshot.indirectBufferLine);
        ImGui.text(displaySnapshot.renderTargetLine);
        ImGui.text(displaySnapshot.attachmentLine);
        ImGui.end();
    }

    private void renderLightingWindow(RenderContext context) {
        if (!showLightingWindow) {
            return;
        }

        LightingSettings settings = context.getLightingSettings();
        applyWindowLayout(lightingRect, 0.9f);
        ImGui.begin("Render Lighting");
        ImBoolean enabled = new ImBoolean(settings.isEnabled());
        if (ImGui.checkbox("Enabled", enabled)) {
            settings.setEnabled(enabled.get());
        }
        ImGui.separator();
        ImGui.colorEdit3("Ambient Color", settings.ambientColorRef());
        ImGui.sliderFloat("Ambient Intensity", settings.ambientIntensityRef(), 0.0f, 2.0f);
        ImGui.colorEdit3("Sun Color", settings.sunColorRef());
        ImGui.sliderFloat("Sun Intensity", settings.sunIntensityRef(), 0.0f, 8.0f);
        ImGui.sliderFloat3("Sun Direction", settings.sunDirectionRef(), -1.0f, 1.0f);
        ImGui.colorEdit3("Sky Color", settings.skyColorRef());
        ImGui.sliderFloat("Sky Intensity", settings.skyIntensityRef(), 0.0f, 4.0f);
        if (ImGui.button("Reset##Lighting")) {
            settings.reset();
        }
        ImGui.end();
    }

    private void renderColorGradingWindow(RenderContext context) {
        if (!showColorGradingWindow) {
            return;
        }

        ColorGradingSettings settings = context.getColorGradingSettings();
        applyWindowLayout(colorGradingRect, 0.9f);
        ImGui.begin("Render Tone Mapping");
        ImBoolean toneMappingEnabled = new ImBoolean(settings.isToneMappingEnabled());
        if (ImGui.checkbox("Tone Mapping", toneMappingEnabled)) {
            settings.setToneMappingEnabled(toneMappingEnabled.get());
        }
        ImBoolean enabled = new ImBoolean(settings.isEnabled());
        if (ImGui.checkbox("Color Grading", enabled)) {
            settings.setEnabled(enabled.get());
        }
        ImGui.separator();
        ImGui.sliderFloat("Exposure", settings.exposureRef(), -2.0f, 2.0f);
        ImGui.sliderFloat("Contrast", settings.contrastRef(), 0.5f, 2.0f);
        ImGui.sliderFloat("Saturation", settings.saturationRef(), 0.0f, 2.5f);
        ImGui.sliderFloat("Vibrance", settings.vibranceRef(), -1.0f, 1.0f);
        ImGui.sliderFloat("Gamma", settings.gammaRef(), 1.0f, 3.0f);
        ImGui.sliderFloat("Temperature", settings.temperatureRef(), -0.4f, 0.4f);
        ImGui.separator();
        if (ImGui.button("Reset")) {
            settings.reset();
        }
        ImGui.end();
    }

    private void renderFogWindow(RenderContext context) {
        if (!showFogWindow) {
            return;
        }

        FogSettings settings = context.getFogSettings();
        applyWindowLayout(fogRect, 0.9f);
        ImGui.begin("Render Fog");
        ImBoolean enabled = new ImBoolean(settings.isEnabled());
        if (ImGui.checkbox("Enabled", enabled)) {
            settings.setEnabled(enabled.get());
        }
        ImGui.separator();
        ImGui.sliderFloat("Start Ratio", settings.startRatioRef(), 0.0f, 0.98f);
        ImGui.sliderFloat("End Ratio", settings.endRatioRef(), 0.01f, 1.2f);
        ImGui.sliderFloat("Intensity", settings.intensityRef(), 0.0f, 1.0f);
        ImGui.sliderFloat("Density", settings.densityRef(), 0.05f, 4.0f);
        ImGui.colorEdit3("Color", settings.colorRef());
        ImGui.separator();
        float renderDistance = context.getWorldSettings().getRenderDistanceChunks() * Chunk.SIZE;
        ImGui.text(String.format("Render distance: %d chunks", context.getWorldSettings().getRenderDistanceChunks()));
        ImGui.text(String.format("Fog start: %.0f blocks", renderDistance * settings.getStartRatio()));
        ImGui.text(String.format("Fog end: %.0f blocks", renderDistance * settings.getEndRatio()));
        if (ImGui.button("Reset##Fog")) {
            settings.reset();
        }
        ImGui.end();
    }

    private void renderChunkProfilingWindow(RenderContext context) {
        if (!showChunkProfilingWindow) {
            return;
        }

        applyWindowLayout(chunkProfilingRect, 0.88f);
        ImGui.begin("Render Chunk Profiling");

        boolean hasChunkPass = false;
        for (RenderStats.PassStats passStats : context.getRenderStats().getPassStats()) {
            if (!passStats.getName().contains("Chunk")) {
                continue;
            }
            hasChunkPass = true;
            ChunkProfilingHistory history = chunkProfilingHistories.get(passStats.getName());
            ImGui.text(passStats.getName());
            ImGui.separator();
            ImGui.text(String.format("Mode: %s | Draws: %d | Batch: %d/%d",
                    passStats.getSubmissionMode(),
                    passStats.getDrawCalls(),
                    passStats.getIndirectVisibleDrawCount(),
                    passStats.getIndirectDrawCapacity()));
            ImGui.text(String.format("Meshes: visible %d | culled %d | resident %d",
                    passStats.getVisibleMeshCount(),
                    passStats.getCulledMeshCount(),
                    passStats.getResidentMeshCount()));
            ImGui.text(String.format("Faces: drawn %d | GPU indirect %s",
                    passStats.getDrawnFaceCount(),
                    formatBytes(passStats.getIndirectBufferGpuBytes())));
            ImGui.text(String.format("Sync: %.3f ms | Visibility: %.3f ms",
                    nanosToMillis(passStats.getSyncCpuTimeNs()),
                    nanosToMillis(passStats.getVisibilityCpuTimeNs())));
            ImGui.text(String.format("Upload: %.3f ms | Submit: %.3f ms",
                    nanosToMillis(passStats.getBatchUploadCpuTimeNs()),
                    nanosToMillis(passStats.getDrawSubmitCpuTimeNs())));
            if (history != null && history.sampleCount() > 1) {
                float maxPhaseMs = Math.max(0.25f, history.maxPhaseMs());
                float maxTotalMs = Math.max(0.25f, history.maxTotalMs());
                ImGui.plotLines("Total CPU (ms)##" + passStats.getName(),
                        history.totalHistory(),
                        history.sampleCount(),
                        history.offset(),
                        "",
                        0.0f,
                        maxTotalMs);
                ImGui.plotLines("Sync (ms)##" + passStats.getName(),
                        history.syncHistory(),
                        history.sampleCount(),
                        history.offset(),
                        "",
                        0.0f,
                        maxPhaseMs);
                ImGui.plotLines("Visibility (ms)##" + passStats.getName(),
                        history.visibilityHistory(),
                        history.sampleCount(),
                        history.offset(),
                        "",
                        0.0f,
                        maxPhaseMs);
                ImGui.plotLines("Upload (ms)##" + passStats.getName(),
                        history.uploadHistory(),
                        history.sampleCount(),
                        history.offset(),
                        "",
                        0.0f,
                        maxPhaseMs);
                ImGui.plotLines("Submit (ms)##" + passStats.getName(),
                        history.submitHistory(),
                        history.sampleCount(),
                        history.offset(),
                        "",
                        0.0f,
                        maxPhaseMs);
            }
            ImGui.spacing();
        }

        if (!hasChunkPass) {
            ImGui.text("No chunk pass stats available");
        }

        ImGui.end();
    }

    private void renderJvmWindow() {
        if (!showJvmWindow) {
            return;
        }

        applyWindowLayout(jvmRect, 0.88f);
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

        applyWindowLayout(deviceRect, 0.88f);
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

        applyWindowLayout(passBreakdownRect, 0.88f);
        ImGui.begin("Render Pass Breakdown");
        for (String passLine : displaySnapshot.passLines) {
            ImGui.text(passLine);
        }
        ImGui.end();
    }

    private boolean toggleWindowMenuItem(String label, boolean visible) {
        if (ImGui.menuItem(label, null, visible)) {
            layoutRefreshPending = true;
            return !visible;
        }
        return visible;
    }

    private void applyWindowLayout(WindowRect rect, float alpha) {
        int condition = layoutRefreshPending ? ImGuiCond.Always : ImGuiCond.Once;
        ImGui.setNextWindowPos(rect.x(), rect.y(), condition);
        ImGui.setNextWindowSize(rect.width(), rect.height(), condition);
        ImGui.setNextWindowBgAlpha(alpha);
    }

    private void updateWindowLayoutIfNeeded(RenderContext context) {
        int viewportWidth = context.getViewportWidth();
        int viewportHeight = context.getViewportHeight();
        if (!layoutRefreshPending && viewportWidth == lastLayoutWidth && viewportHeight == lastLayoutHeight) {
            return;
        }

        lastLayoutWidth = viewportWidth;
        lastLayoutHeight = viewportHeight;

        float margin = 10.0f;
        float gap = 20.0f;
        float rowGap = 15.0f;
        float top = 35.0f;
        float contentWidth = Math.max(260.0f, viewportWidth - (margin * 2.0f));

        if (viewportWidth >= 1180) {
            float columnWidth = (contentWidth - (gap * 2.0f)) / 3.0f;
            float leftX = margin;
            float middleX = leftX + columnWidth + gap;
            float rightX = middleX + columnWidth + gap;

            overviewRect = WindowRect.of(leftX, top, columnWidth, 120.0f);
            parametersRect = WindowRect.of(leftX, overviewRect.bottom() + rowGap, columnWidth, 120.0f);
            frameRect = WindowRect.of(leftX, parametersRect.bottom() + rowGap, columnWidth, 145.0f);
            jvmRect = WindowRect.of(leftX, frameRect.bottom() + rowGap, columnWidth, 110.0f);

            gpuRect = WindowRect.of(middleX, top + 135.0f, columnWidth, 125.0f);
            arenaRect = WindowRect.of(middleX, gpuRect.bottom() + rowGap, columnWidth, 300.0f);
            deviceRect = WindowRect.of(middleX, arenaRect.bottom() + rowGap, columnWidth, 150.0f);

            resourcesRect = WindowRect.of(rightX, top + 135.0f, columnWidth, 155.0f);
            lightingRect = WindowRect.of(rightX, resourcesRect.bottom() + rowGap, columnWidth, 285.0f);
            colorGradingRect = WindowRect.of(rightX, lightingRect.bottom() + rowGap, columnWidth, 270.0f);
            fogRect = WindowRect.of(rightX, colorGradingRect.bottom() + rowGap, columnWidth, 265.0f);
            chunkProfilingRect = WindowRect.of(rightX, fogRect.bottom() + rowGap, columnWidth, 200.0f);
            float passHeight = Math.max(160.0f, viewportHeight - (chunkProfilingRect.bottom() + rowGap + margin));
            passBreakdownRect = WindowRect.of(rightX, chunkProfilingRect.bottom() + rowGap, columnWidth, passHeight);
            return;
        }

        if (viewportWidth >= 820) {
            float columnWidth = (contentWidth - gap) / 2.0f;
            float leftX = margin;
            float rightX = leftX + columnWidth + gap;

            overviewRect = WindowRect.of(margin, top, contentWidth, 120.0f);
            parametersRect = WindowRect.of(leftX, overviewRect.bottom() + rowGap, columnWidth, 120.0f);
            frameRect = WindowRect.of(leftX, parametersRect.bottom() + rowGap, columnWidth, 145.0f);
            gpuRect = WindowRect.of(rightX, overviewRect.bottom() + rowGap, columnWidth, 125.0f);
            jvmRect = WindowRect.of(leftX, frameRect.bottom() + rowGap, columnWidth, 110.0f);
            arenaRect = WindowRect.of(rightX, gpuRect.bottom() + rowGap, columnWidth, 300.0f);
            resourcesRect = WindowRect.of(leftX, jvmRect.bottom() + rowGap, columnWidth, 155.0f);
            lightingRect = WindowRect.of(leftX, resourcesRect.bottom() + rowGap, columnWidth, 285.0f);
            colorGradingRect = WindowRect.of(leftX, lightingRect.bottom() + rowGap, columnWidth, 270.0f);
            deviceRect = WindowRect.of(rightX, arenaRect.bottom() + rowGap, columnWidth, 150.0f);
            fogRect = WindowRect.of(rightX, deviceRect.bottom() + rowGap, columnWidth, 265.0f);
            chunkProfilingRect = WindowRect.of(leftX, Math.max(colorGradingRect.bottom(), fogRect.bottom()) + rowGap, contentWidth, 200.0f);

            float passY = Math.max(chunkProfilingRect.bottom(), deviceRect.bottom()) + rowGap;
            float passHeight = Math.max(180.0f, viewportHeight - (passY + margin));
            passBreakdownRect = WindowRect.of(margin, passY, contentWidth, passHeight);
            return;
        }

        float fullWidth = contentWidth;
        overviewRect = WindowRect.of(margin, top, fullWidth, 120.0f);
        parametersRect = WindowRect.of(margin, overviewRect.bottom() + rowGap, fullWidth, 120.0f);
        frameRect = WindowRect.of(margin, parametersRect.bottom() + rowGap, fullWidth, 145.0f);
        gpuRect = WindowRect.of(margin, frameRect.bottom() + rowGap, fullWidth, 125.0f);
        arenaRect = WindowRect.of(margin, gpuRect.bottom() + rowGap, fullWidth, 300.0f);
        resourcesRect = WindowRect.of(margin, arenaRect.bottom() + rowGap, fullWidth, 155.0f);
        lightingRect = WindowRect.of(margin, resourcesRect.bottom() + rowGap, fullWidth, 285.0f);
        colorGradingRect = WindowRect.of(margin, lightingRect.bottom() + rowGap, fullWidth, 270.0f);
        fogRect = WindowRect.of(margin, colorGradingRect.bottom() + rowGap, fullWidth, 265.0f);
        jvmRect = WindowRect.of(margin, fogRect.bottom() + rowGap, fullWidth, 110.0f);
        deviceRect = WindowRect.of(margin, jvmRect.bottom() + rowGap, fullWidth, 150.0f);
        chunkProfilingRect = WindowRect.of(margin, deviceRect.bottom() + rowGap, fullWidth, 220.0f);
        float passY = chunkProfilingRect.bottom() + rowGap;
        float passHeight = Math.max(180.0f, viewportHeight - (passY + margin));
        passBreakdownRect = WindowRect.of(margin, passY, fullWidth, passHeight);
    }

    private void renderArenaStats(String label, ChunkFaceArena arena) {
        ImGui.text(label);
        if (arena == null) {
            ImGui.text("Arena not initialized");
            return;
        }

        long capacityBytes = (long) arena.getCapacityInts() * Integer.BYTES;
        long reservedBytes = arena.getReservedInts() * Integer.BYTES;
        long payloadBytes = arena.getPayloadInts() * Integer.BYTES;
        long freeBytes = arena.getFreeInts() * Integer.BYTES;

        ImGui.text(String.format("Capacity: %s", formatBytes(capacityBytes)));
        ImGui.text(String.format("Reserved: %s (%.1f%%)", formatBytes(reservedBytes), arena.getReservationRatio() * 100.0f));
        ImGui.text(String.format("Payload: %s (%.1f%%)", formatBytes(payloadBytes), arena.getPayloadRatio() * 100.0f));
        ImGui.text(String.format("Free: %s", formatBytes(freeBytes)));
        ImGui.text(String.format("Allocations: %d | Free spans: %d",
                arena.getActiveAllocationCount(),
                arena.getFreeSpanCount()));
        ImGui.text(String.format("Largest free span: %s | Fragmentation: %.1f%%",
                formatBytes((long) arena.getLargestFreeSpanInts() * Integer.BYTES),
                arena.getFragmentationRatio() * 100.0f));
    }

    private void toggleMouseLock() {
        window.toggleCursorLock();
        inputManager.resetMouseDelta();
    }

    private void updateChunkProfilingHistories(RenderStats stats) {
        Set<String> activeChunkPasses = new HashSet<>();
        for (RenderStats.PassStats passStats : stats.getPassStats()) {
            if (!passStats.getName().contains("Chunk")) {
                continue;
            }

            activeChunkPasses.add(passStats.getName());
            ChunkProfilingHistory history = chunkProfilingHistories.computeIfAbsent(
                    passStats.getName(),
                    ignored -> new ChunkProfilingHistory(CHUNK_HISTORY_SAMPLES)
            );
            history.add(
                    nanosToMillis(passStats.getSyncCpuTimeNs()),
                    nanosToMillis(passStats.getVisibilityCpuTimeNs()),
                    nanosToMillis(passStats.getBatchUploadCpuTimeNs()),
                    nanosToMillis(passStats.getDrawSubmitCpuTimeNs())
            );
        }

        chunkProfilingHistories.keySet().removeIf(passName -> !activeChunkPasses.contains(passName));
    }

    private void applyCompactLayout() {
        showOverviewWindow = true;
        showParametersWindow = true;
        showFrameWindow = true;
        showGpuWindow = true;
        showArenaWindow = false;
        showChunkProfilingWindow = true;
        showResourcesWindow = false;
        showLightingWindow = true;
        showColorGradingWindow = true;
        showFogWindow = true;
        showJvmWindow = false;
        showDeviceWindow = false;
        showPassBreakdownWindow = true;
        layoutRefreshPending = true;
    }

    private void applyRenderingLayout() {
        showOverviewWindow = true;
        showParametersWindow = true;
        showFrameWindow = true;
        showGpuWindow = true;
        showArenaWindow = true;
        showChunkProfilingWindow = true;
        showResourcesWindow = true;
        showLightingWindow = true;
        showColorGradingWindow = true;
        showFogWindow = true;
        showJvmWindow = false;
        showDeviceWindow = false;
        showPassBreakdownWindow = true;
        layoutRefreshPending = true;
    }

    private void showAllWindows() {
        showOverviewWindow = true;
        showParametersWindow = true;
        showFrameWindow = true;
        showGpuWindow = true;
        showArenaWindow = true;
        showChunkProfilingWindow = true;
        showResourcesWindow = true;
        showLightingWindow = true;
        showColorGradingWindow = true;
        showFogWindow = true;
        showJvmWindow = true;
        showDeviceWindow = true;
        showPassBreakdownWindow = true;
        layoutRefreshPending = true;
    }

    private void hideAllWindows() {
        showOverviewWindow = false;
        showParametersWindow = false;
        showFrameWindow = false;
        showGpuWindow = false;
        showArenaWindow = false;
        showChunkProfilingWindow = false;
        showResourcesWindow = false;
        showLightingWindow = false;
        showColorGradingWindow = false;
        showFogWindow = false;
        showJvmWindow = false;
        showDeviceWindow = false;
        showPassBreakdownWindow = false;
        layoutRefreshPending = false;
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

    private record WindowRect(float x, float y, float width, float height) {
        private static WindowRect of(float x, float y, float width, float height) {
            return new WindowRect(x, y, width, height);
        }

        private float bottom() {
            return y + height;
        }
    }

    private static final class ChunkProfilingHistory {
        private final float[] syncHistory;
        private final float[] visibilityHistory;
        private final float[] uploadHistory;
        private final float[] submitHistory;
        private final float[] totalHistory;
        private int writeIndex;
        private int sampleCount;

        private ChunkProfilingHistory(int capacity) {
            this.syncHistory = new float[capacity];
            this.visibilityHistory = new float[capacity];
            this.uploadHistory = new float[capacity];
            this.submitHistory = new float[capacity];
            this.totalHistory = new float[capacity];
        }

        private void add(float syncMs, float visibilityMs, float uploadMs, float submitMs) {
            syncHistory[writeIndex] = syncMs;
            visibilityHistory[writeIndex] = visibilityMs;
            uploadHistory[writeIndex] = uploadMs;
            submitHistory[writeIndex] = submitMs;
            totalHistory[writeIndex] = syncMs + visibilityMs + uploadMs + submitMs;
            writeIndex = (writeIndex + 1) % totalHistory.length;
            sampleCount = Math.min(sampleCount + 1, totalHistory.length);
        }

        private int offset() {
            return sampleCount == totalHistory.length ? writeIndex : 0;
        }

        private int sampleCount() {
            return sampleCount;
        }

        private float[] syncHistory() {
            return syncHistory;
        }

        private float[] visibilityHistory() {
            return visibilityHistory;
        }

        private float[] uploadHistory() {
            return uploadHistory;
        }

        private float[] submitHistory() {
            return submitHistory;
        }

        private float[] totalHistory() {
            return totalHistory;
        }

        private float maxPhaseMs() {
            float max = 0.0f;
            max = Math.max(max, max(syncHistory));
            max = Math.max(max, max(visibilityHistory));
            max = Math.max(max, max(uploadHistory));
            max = Math.max(max, max(submitHistory));
            return max;
        }

        private float maxTotalMs() {
            return max(totalHistory);
        }

        private float max(float[] values) {
            float max = 0.0f;
            int count = sampleCount();
            if (count == 0) {
                return max;
            }
            for (int index = 0; index < count; index++) {
                max = Math.max(max, values[index]);
            }
            return max;
        }
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
            String indirectBatchLine,
            String indirectBufferLine,
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
                    "Chunk draw batches: collecting...",
                    "Indirect buffers: collecting...",
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
                            String.format("   mode %s | batch draws %d/%d | indirect %s",
                                    passStats.getSubmissionMode(),
                                    passStats.getIndirectVisibleDrawCount(),
                                    passStats.getIndirectDrawCapacity(),
                                    formatBytes(passStats.getIndirectBufferGpuBytes())),
                            String.format("   sync %.3f | visible %.3f | upload %.3f | submit %.3f ms",
                                    nanosToMillis(passStats.getSyncCpuTimeNs()),
                                    nanosToMillis(passStats.getVisibilityCpuTimeNs()),
                                    nanosToMillis(passStats.getBatchUploadCpuTimeNs()),
                                    nanosToMillis(passStats.getDrawSubmitCpuTimeNs())),
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
                    String.format("Chunk draw batches: %d | draws %d/%d",
                            stats.getChunkDrawBatchCount(),
                            stats.getIndirectVisibleDrawCount(),
                            stats.getIndirectDrawCapacity()),
                    String.format("Indirect buffers: %s", formatBytes(stats.getIndirectBufferGpuBytes())),
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
