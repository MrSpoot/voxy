package org.weaw.engine.graphics.pipeline.passes;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import org.weaw.engine.graphics.pipeline.CloudSettings;
import org.weaw.engine.graphics.pipeline.ColorGradingSettings;
import org.weaw.engine.graphics.pipeline.FogSettings;
import org.weaw.engine.graphics.pipeline.LightingSettings;
import org.lwjgl.glfw.GLFW;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.RenderStats;
import org.weaw.engine.graphics.pipeline.WaterSettings;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.window.Window;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.World;
import org.weaw.game.WorldMemorySnapshot;
import org.weaw.game.WorldSettings;
import org.weaw.game.utils.BlockDefinition;

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
    private boolean showLightingWindow = false;
    private boolean showCloudWindow = false;
    private boolean showWaterWindow = false;
    private boolean showLightDebugWindow = true;
    private boolean showColorGradingWindow = false;
    private boolean showFogWindow = false;
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
    private WindowRect cloudRect = WindowRect.of(770.0f, 635.0f, 360.0f, 240.0f);
    private WindowRect waterRect = WindowRect.of(770.0f, 890.0f, 360.0f, 220.0f);
    private WindowRect lightDebugRect = WindowRect.of(770.0f, 1125.0f, 360.0f, 210.0f);
    private WindowRect colorGradingRect = WindowRect.of(770.0f, 1350.0f, 360.0f, 420.0f);
    private WindowRect fogRect = WindowRect.of(770.0f, 1785.0f, 360.0f, 265.0f);
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
        renderGpuWindow(context);
        renderArenaWindow(context);
        renderChunkProfilingWindow(context);
        renderResourcesWindow();
        renderLightingWindow(context);
        renderCloudWindow(context);
        renderWaterWindow(context);
        renderLightDebugWindow(context);
        renderColorGradingWindow(context);
        renderFogWindow(context);
        renderJvmWindow(context);
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
            showCloudWindow = toggleWindowMenuItem("Clouds", showCloudWindow);
            showWaterWindow = toggleWindowMenuItem("Water", showWaterWindow);
            showLightDebugWindow = toggleWindowMenuItem("Light Debug", showLightDebugWindow);
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
        WorldMemorySnapshot memory = context.getWorld().getMemorySnapshot();
        ImGui.text(String.format(
                "Requested / effective: %d / %d chunks",
                renderDistanceChunks,
                memory.effectiveRenderDistanceChunks()
        ));
        ImGui.text(String.format("Blocks: %d", renderDistanceChunks * Chunk.SIZE));
        ImGui.text(String.format(
                "World Y: %d..%d (%d layers)",
                settings.getHeightRange().minChunkY() * Chunk.SIZE,
                ((settings.getHeightRange().maxChunkY() + 1) * Chunk.SIZE) - 1,
                settings.getHeightRange().chunkCount()
        ));
        if (ImGui.button("Reset##DebugParameters")) {
            settings.reset();
        }
        ImGui.end();
    }


    private void renderGpuWindow(RenderContext context) {
        if (!showGpuWindow) {
            return;
        }

        applyWindowLayout(gpuRect, 0.88f);
        ImGui.begin("Render GPU Memory");
        ImGui.text(displaySnapshot.meshGpuLine);
        ImGui.text(displaySnapshot.textureGpuLine);
        ImGui.text(displaySnapshot.renderTargetGpuLine);
        ImGui.text(displaySnapshot.totalGpuLine);
        long chunkGpuBytes = context.getChunkGpuMemoryBudget() != null
                ? context.getChunkGpuMemoryBudget().getResidentBytes()
                : 0L;
        ImGui.separator();
        ImGui.text(String.format(
                "Chunk budget: %s / %s",
                formatBytes(chunkGpuBytes),
                formatBytes(context.getWorldSettings().getMemoryBudget().maxGpuResidentBytes())
        ));
        ImGui.end();
    }

    private void renderArenaWindow(RenderContext context) {
        if (!showArenaWindow) {
            return;
        }

        applyWindowLayout(arenaRect, 0.88f);
        ImGui.begin("Render Chunk Arenas");
        ChunkArenaDebugPanel.render("Opaque", context.getOpaqueChunkFaceArena());
        ImGui.separator();
        ChunkArenaDebugPanel.render("Cutout", context.getCutoutChunkFaceArena());
        ImGui.separator();
        ChunkArenaDebugPanel.render("Transparent", context.getTransparentChunkFaceArena());
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
        ImGui.sliderFloat("Shadow Strength", settings.shadowStrengthRef(), 0.0f, 1.0f);
        ImGui.colorEdit3("Sun Color", settings.sunColorRef());
        ImGui.sliderFloat("Sun Intensity", settings.sunIntensityRef(), 0.0f, 8.0f);
        ImGui.sliderFloat3("Sun Direction", settings.sunDirectionRef(), -1.0f, 1.0f);
        ImGui.colorEdit3("Sky Color", settings.skyColorRef());
        ImGui.sliderFloat("Indirect Sky Intensity", settings.skyIntensityRef(), 0.0f, 4.0f);
        ImGui.sliderFloat("Voxel Light Gamma", settings.voxelLightGammaRef(), 0.25f, 3.0f);
        ImGui.sliderFloat("Voxel Darkness Floor", settings.voxelDarknessFloorRef(), 0.0f, 0.25f);
        ImGui.separator();
        ImBoolean blockLightEnabled = new ImBoolean(settings.isBlockLightEnabled());
        if (ImGui.checkbox("Block Light", blockLightEnabled)) {
            settings.setBlockLightEnabled(blockLightEnabled.get());
        }
        ImGui.sliderFloat("Block Light Intensity", settings.blockLightIntensityRef(), 0.0f, 4.0f);
        if (ImGui.button("Reset##Lighting")) {
            settings.reset();
        }
        ImGui.end();
    }

    private void renderCloudWindow(RenderContext context) {
        if (!showCloudWindow) {
            return;
        }

        CloudSettings settings = context.getCloudSettings();
        applyWindowLayout(cloudRect, 0.9f);
        ImGui.begin("Render Clouds");
        ImBoolean enabled = new ImBoolean(settings.isEnabled());
        if (ImGui.checkbox("Enabled", enabled)) {
            settings.setEnabled(enabled.get());
        }
        ImGui.separator();
        ImGui.sliderFloat("Altitude", settings.altitudeRef(), 96.0f, 512.0f);
        ImGui.sliderFloat("West-East Speed", settings.speedRef(), 0.0f, 20.0f);
        ImGui.sliderFloat("Cloud Amount", settings.densityRef(), 0.0f, 1.0f);
        ImGui.sliderFloat("Cloud Size", settings.cloudSizeRef(), 0.25f, 4.0f);
        ImGui.sliderFloat("Cell Size", settings.cellSizeRef(), 4.0f, 32.0f);
        ImGui.text("Cloud Size: size of cloud groups");
        ImGui.text("Cell Size: size of individual voxels");
        if (ImGui.button("Reset##Clouds")) {
            settings.reset();
        }
        ImGui.end();
    }

    private void renderWaterWindow(RenderContext context) {
        if (!showWaterWindow) {
            return;
        }

        WaterSettings settings = context.getWaterSettings();
        applyWindowLayout(waterRect, 0.9f);
        ImGui.begin("Render Water");
        ImBoolean wavesEnabled = new ImBoolean(settings.areWavesEnabled());
        if (ImGui.checkbox("Waves Enabled", wavesEnabled)) {
            settings.setWavesEnabled(wavesEnabled.get());
        }
        ImGui.separator();
        ImGui.sliderFloat("Surface Inset", settings.surfaceInsetRef(), 0.0f, 0.5f);
        ImGui.sliderFloat("Wave Amplitude", settings.waveAmplitudeRef(), 0.0f, 0.12f);
        ImGui.sliderFloat("Wave Speed", settings.waveSpeedRef(), 0.0f, 4.0f);
        ImGui.sliderFloat("Wave Length", settings.waveLengthRef(), 2.0f, 32.0f);
        if (ImGui.button("Reset##Water")) {
            settings.reset();
        }
        ImGui.end();
    }

    private void renderLightDebugWindow(RenderContext context) {
        if (!showLightDebugWindow) {
            return;
        }

        applyWindowLayout(lightDebugRect, 0.9f);
        ImGui.begin("Render Light Debug");
        ImBoolean enabled = new ImBoolean(context.isLightDebugVisualizationEnabled());
        if (ImGui.checkbox("Visualize Voxel Light", enabled)) {
            context.setLightDebugVisualizationEnabled(enabled.get());
        }
        ImGui.text("Heatmap = max(Sky, R, G, B): black 0, white 15.");
        ImGui.separator();

        World world = context.getWorld();
        if (world == null || !context.hasBlockOutlineTarget()) {
            ImGui.text("Targeted block: none");
            ImGui.end();
            return;
        }

        int blockX = context.getBlockOutlineTargetX();
        int blockY = context.getBlockOutlineTargetY();
        int blockZ = context.getBlockOutlineTargetZ();
        short blockId = world.getBlockAtWorld(blockX, blockY, blockZ);
        BlockDefinition blockDefinition = world.getBlockCatalog().getBlock(blockId);
        short packedLight = world.getPackedLightAtWorld(blockX, blockY, blockZ);
        int red = ChunkLighting.getRed(packedLight);
        int green = ChunkLighting.getGreen(packedLight);
        int blue = ChunkLighting.getBlue(packedLight);
        int sky = ChunkLighting.getSky(packedLight);
        int intensity = Math.max(red, Math.max(green, blue));
        int combinedLevel = ChunkLighting.getCombinedLevel(packedLight);
        int chunkX = Math.floorDiv(blockX, Chunk.SIZE);
        int chunkY = Math.floorDiv(blockY, Chunk.SIZE);
        int chunkZ = Math.floorDiv(blockZ, Chunk.SIZE);
        int localX = Math.floorMod(blockX, Chunk.SIZE);
        int localY = Math.floorMod(blockY, Chunk.SIZE);
        int localZ = Math.floorMod(blockZ, Chunk.SIZE);
        int sampleX = context.getBlockOutlinePlacementX();
        int sampleY = context.getBlockOutlinePlacementY();
        int sampleZ = context.getBlockOutlinePlacementZ();
        short sampledPackedLight = world.getPackedLightAtWorld(sampleX, sampleY, sampleZ);
        int sampleRed = ChunkLighting.getRed(sampledPackedLight);
        int sampleGreen = ChunkLighting.getGreen(sampledPackedLight);
        int sampleBlue = ChunkLighting.getBlue(sampledPackedLight);
        int sampleSky = ChunkLighting.getSky(sampledPackedLight);
        int sampleIntensity = Math.max(sampleRed, Math.max(sampleGreen, sampleBlue));
        int sampleCombinedLevel = ChunkLighting.getCombinedLevel(sampledPackedLight);

        ImGui.text(String.format("Block: %s", blockDefinition != null ? blockDefinition.getStableId() : Short.toString(blockId)));
        ImGui.text(String.format("World: (%d, %d, %d)", blockX, blockY, blockZ));
        ImGui.text(String.format("Chunk: (%d, %d, %d) | Local: (%d, %d, %d)", chunkX, chunkY, chunkZ, localX, localY, localZ));
        ImGui.text(String.format("Chunk loaded: %s", world.containsChunkAtWorld(blockX, blockY, blockZ) ? "yes" : "no"));
        ImGui.text(String.format("Block cell light RGB: (%d, %d, %d)", red, green, blue));
        ImGui.text(String.format("Block absolute light: %d/15", combinedLevel));
        ImGui.text(String.format("RGB max: %d/15 | Sky: %d/15 | Packed: 0x%04X", intensity, sky, packedLight & 0xFFFF));
        ImGui.separator();
        ImGui.text(String.format("Face sample: (%d, %d, %d)", sampleX, sampleY, sampleZ));
        ImGui.text(String.format("Face light RGB: (%d, %d, %d)", sampleRed, sampleGreen, sampleBlue));
        ImGui.text(String.format("Face absolute light: %d/15", sampleCombinedLevel));
        ImGui.text(String.format("RGB max: %d/15 | Sky: %d/15 | Packed: 0x%04X", sampleIntensity, sampleSky, sampledPackedLight & 0xFFFF));
        if (blockDefinition != null) {
            ImGui.text(String.format(
                    "Opaque: %s | Blocks light: %s | Attenuation: %d | Emitter: %s",
                    blockDefinition.isOpaque() ? "yes" : "no",
                    blockDefinition.blocksLight() ? "yes" : "no",
                    blockDefinition.getLightAttenuation(),
                    blockDefinition.isLightEmitter() ? "yes" : "no"
            ));
            ImGui.text(String.format(
                    "Emission RGB: (%d, %d, %d)",
                    blockDefinition.getLightEmissionRed(),
                    blockDefinition.getLightEmissionGreen(),
                    blockDefinition.getLightEmissionBlue()
            ));
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
        ImBoolean autoExposureEnabled = new ImBoolean(settings.isAutoExposureEnabled());
        if (ImGui.checkbox("Auto Exposure", autoExposureEnabled)) {
            settings.setAutoExposureEnabled(autoExposureEnabled.get());
        }
        ImBoolean enabled = new ImBoolean(settings.isEnabled());
        if (ImGui.checkbox("Color Grading", enabled)) {
            settings.setEnabled(enabled.get());
        }
        ImGui.separator();
        ImGui.sliderFloat(
                settings.isAutoExposureEnabled() ? "Exposure compensation (EV)" : "Exposure (EV)",
                settings.exposureRef(),
                -4.0f,
                4.0f
        );
        if (settings.isAutoExposureEnabled()) {
            float maximumExposureEv = settings.getMaximumExposureEv();
            ImGui.sliderFloat(
                    "Minimum exposure (EV)",
                    settings.minimumExposureEvRef(),
                    Math.min(-8.0f, maximumExposureEv),
                    maximumExposureEv
            );
            float minimumExposureEv = settings.getMinimumExposureEv();
            ImGui.sliderFloat(
                    "Maximum exposure (EV)",
                    settings.maximumExposureEvRef(),
                    minimumExposureEv,
                    Math.max(8.0f, minimumExposureEv)
            );
            ImGui.sliderFloat("Target luminance", settings.targetLuminanceRef(), 0.01f, 1.0f);
            ImGui.sliderFloat("Darken speed", settings.darkenAdaptationSpeedRef(), 0.1f, 10.0f);
            ImGui.sliderFloat("Brighten speed", settings.brightenAdaptationSpeedRef(), 0.1f, 10.0f);
            settings.sanitizeAutoExposure();
        }
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
            ImGui.text(String.format("Upload: %.3f ms | Submit: %.3f ms | Other: %.3f ms",
                    nanosToMillis(passStats.getBatchUploadCpuTimeNs()),
                    nanosToMillis(passStats.getDrawSubmitCpuTimeNs()),
                    nanosToMillis(passStats.getOtherCpuTimeNs())));
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
                ImGui.plotLines("Other (ms)##" + passStats.getName(),
                        history.otherHistory(),
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

    private void renderJvmWindow(RenderContext context) {
        if (!showJvmWindow) {
            return;
        }

        applyWindowLayout(jvmRect, 0.88f);
        ImGui.begin("System JVM Memory");
        ImGui.text(displaySnapshot.heapUsedLine);
        ImGui.text(displaySnapshot.heapCommittedLine);
        ImGui.text(displaySnapshot.heapMaxLine);
        WorldMemorySnapshot memory = context.getWorld().getMemorySnapshot();
        ImGui.separator();
        ImGui.text(String.format(
                "World CPU: %s / %s",
                formatBytes(memory.estimatedCpuResidentBytes()),
                formatBytes(memory.maxCpuResidentBytes())
        ));
        ImGui.text(String.format("In-flight: %s", formatBytes(memory.reservedInFlightBytes())));
        ImGui.text(String.format(
                "Lighting compact/full: %d / %d",
                memory.compactLightingChunks(),
                memory.expandedLightingChunks()
        ));
        ImGui.text("Pressure: " + memory.pressureState());
        ImGui.text(String.format("Rejected loads: %d", memory.rejectedLoadCount()));
        ImGui.separator();
        ImGui.text(String.format(
                "Sparse chunks: %s | resident target: %d",
                memory.sparseChunkStreamingEnabled() ? "enabled" : "disabled",
                memory.desiredMaterializedChunks()
        ));
        ImGui.text(String.format(
                "Virtual empty/uniform: %d / %d",
                memory.virtualEmptyChunks(),
                memory.virtualUniformChunks()
        ));
        ImGui.text(String.format("Interaction bubble: %d", memory.interactionBubbleChunks()));
        long cacheQueries = memory.classificationCacheHits() + memory.classificationCacheMisses();
        double cacheHitRate = cacheQueries == 0L
                ? 0.0
                : memory.classificationCacheHits() * 100.0 / cacheQueries;
        ImGui.text(String.format(
                "Classification cache: %d columns | %.1f%% hits",
                memory.classificationCacheColumns(),
                cacheHitRate
        ));
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
            cloudRect = WindowRect.of(rightX, lightingRect.bottom() + rowGap, columnWidth, 240.0f);
            waterRect = WindowRect.of(rightX, cloudRect.bottom() + rowGap, columnWidth, 220.0f);
            lightDebugRect = WindowRect.of(rightX, waterRect.bottom() + rowGap, columnWidth, 210.0f);
            colorGradingRect = WindowRect.of(rightX, lightDebugRect.bottom() + rowGap, columnWidth, 420.0f);
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
            cloudRect = WindowRect.of(leftX, lightingRect.bottom() + rowGap, columnWidth, 240.0f);
            waterRect = WindowRect.of(leftX, cloudRect.bottom() + rowGap, columnWidth, 220.0f);
            lightDebugRect = WindowRect.of(leftX, waterRect.bottom() + rowGap, columnWidth, 210.0f);
            colorGradingRect = WindowRect.of(leftX, lightDebugRect.bottom() + rowGap, columnWidth, 420.0f);
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
        cloudRect = WindowRect.of(margin, lightingRect.bottom() + rowGap, fullWidth, 240.0f);
        waterRect = WindowRect.of(margin, cloudRect.bottom() + rowGap, fullWidth, 220.0f);
        lightDebugRect = WindowRect.of(margin, waterRect.bottom() + rowGap, fullWidth, 210.0f);
        colorGradingRect = WindowRect.of(margin, lightDebugRect.bottom() + rowGap, fullWidth, 420.0f);
        fogRect = WindowRect.of(margin, colorGradingRect.bottom() + rowGap, fullWidth, 265.0f);
        jvmRect = WindowRect.of(margin, fogRect.bottom() + rowGap, fullWidth, 110.0f);
        deviceRect = WindowRect.of(margin, jvmRect.bottom() + rowGap, fullWidth, 150.0f);
        chunkProfilingRect = WindowRect.of(margin, deviceRect.bottom() + rowGap, fullWidth, 220.0f);
        float passY = chunkProfilingRect.bottom() + rowGap;
        float passHeight = Math.max(180.0f, viewportHeight - (passY + margin));
        passBreakdownRect = WindowRect.of(margin, passY, fullWidth, passHeight);
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
                    nanosToMillis(passStats.getDrawSubmitCpuTimeNs()),
                    nanosToMillis(passStats.getOtherCpuTimeNs())
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
        showCloudWindow = true;
        showWaterWindow = true;
        showLightDebugWindow = true;
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
        showCloudWindow = true;
        showWaterWindow = true;
        showLightDebugWindow = true;
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
        showCloudWindow = true;
        showWaterWindow = true;
        showLightDebugWindow = true;
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
        showCloudWindow = false;
        showWaterWindow = false;
        showLightDebugWindow = false;
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
        private final float[] otherHistory;
        private final float[] totalHistory;
        private int writeIndex;
        private int sampleCount;

        private ChunkProfilingHistory(int capacity) {
            this.syncHistory = new float[capacity];
            this.visibilityHistory = new float[capacity];
            this.uploadHistory = new float[capacity];
            this.submitHistory = new float[capacity];
            this.otherHistory = new float[capacity];
            this.totalHistory = new float[capacity];
        }

        private void add(float syncMs, float visibilityMs, float uploadMs, float submitMs, float otherMs) {
            syncHistory[writeIndex] = syncMs;
            visibilityHistory[writeIndex] = visibilityMs;
            uploadHistory[writeIndex] = uploadMs;
            submitHistory[writeIndex] = submitMs;
            otherHistory[writeIndex] = otherMs;
            totalHistory[writeIndex] = syncMs + visibilityMs + uploadMs + submitMs + otherMs;
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

        private float[] otherHistory() {
            return otherHistory;
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
            max = Math.max(max, max(otherHistory));
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
                            String.format("   other %.3f | pass total %.3f ms",
                                    nanosToMillis(passStats.getOtherCpuTimeNs()),
                                    nanosToMillis(passStats.getCpuTimeNs())),
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
                    String.format("CPU render passes: %.3f ms | full frame CPU: %.3f ms",
                            nanosToMillis(stats.getTotalPassCpuTimeNs()),
                            nanosToMillis(stats.getFrameCpuTimeNs())),
                    String.format("Draw calls: %d", stats.getDrawCalls()),
                    String.format("Faces: %d | Triangles: %d | Vertices: %d",
                            stats.getDrawnFaceCount(),
                            stats.getDrawnTriangleCount(),
                            stats.getDrawnVertexCount()),
                    String.format("Visible meshes: %d | Culled: %d | Resident: %d",
                            stats.getVisibleMeshCount(),
                            stats.getCulledMeshCount(),
                            stats.getResidentMeshCount()),
                    String.format("Resident face data: %d | update %.3f ms | render %.3f ms | window %.3f ms",
                            stats.getResidentFaceCount(),
                            nanosToMillis(stats.getUpdateCpuTimeNs()),
                            nanosToMillis(stats.getRenderCpuTimeNs()),
                            nanosToMillis(stats.getWindowCpuTimeNs())),
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
