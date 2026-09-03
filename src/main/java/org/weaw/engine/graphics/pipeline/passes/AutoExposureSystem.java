package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Vector2f;
import org.lwjgl.system.MemoryStack;
import org.weaw.engine.graphics.pipeline.ColorGradingSettings;
import org.weaw.engine.graphics.pipeline.resources.FullscreenQuad;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_COLOR;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_R16F;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glClearBufferfv;

/**
 * Measures HDR luminance and maintains a temporally adapted exposure entirely on the GPU.
 */
final class AutoExposureSystem {
    private static final int MAXIMUM_METER_DIMENSION = 256;

    private final List<RenderTarget> luminancePyramid = new ArrayList<>();
    private final RenderTarget[] exposureHistory = new RenderTarget[2];
    private final Vector2f outputSize = new Vector2f();

    private Shader downsampleShader;
    private Shader adaptationShader;
    private int sourceWidth = -1;
    private int sourceHeight = -1;
    private int historyReadIndex;
    private boolean historyInitialized;
    private boolean autoExposureWasEnabled;

    void create() {
        downsampleShader = new Shader("/shaders/auto-exposure-downsample.glsl");
        adaptationShader = new Shader("/shaders/auto-exposure-adapt.glsl");
        exposureHistory[0] = new RenderTarget("autoExposureHistory0", 1, 1, false, GL_R16F);
        exposureHistory[1] = new RenderTarget("autoExposureHistory1", 1, 1, false, GL_R16F);
    }

    int update(
            int hdrTexture,
            int width,
            int height,
            float frameDeltaSeconds,
            ColorGradingSettings settings,
            FullscreenQuad fullscreenQuad
    ) {
        settings.sanitizeAutoExposure();
        if (!settings.isAutoExposureEnabled() || hdrTexture == 0 || width <= 0 || height <= 0) {
            autoExposureWasEnabled = false;
            return currentExposureTexture();
        }

        ensureLuminancePyramid(width, height);
        renderLuminancePyramid(hdrTexture, fullscreenQuad);

        if (!historyInitialized || !autoExposureWasEnabled) {
            initializeHistory(settings.getExposure(), settings);
        }
        renderAdaptedExposure(frameDeltaSeconds, settings, fullscreenQuad);
        autoExposureWasEnabled = true;
        return currentExposureTexture();
    }

    void invalidateLuminancePyramid() {
        cleanupLuminancePyramid();
        sourceWidth = -1;
        sourceHeight = -1;
    }

    void cleanup() {
        cleanupLuminancePyramid();
        for (int index = 0; index < exposureHistory.length; index++) {
            if (exposureHistory[index] != null) {
                exposureHistory[index].cleanup();
                exposureHistory[index] = null;
            }
        }
        if (downsampleShader != null) {
            downsampleShader.cleanup();
            downsampleShader = null;
        }
        if (adaptationShader != null) {
            adaptationShader.cleanup();
            adaptationShader = null;
        }
        historyInitialized = false;
        autoExposureWasEnabled = false;
    }

    private void ensureLuminancePyramid(int width, int height) {
        if (width == sourceWidth && height == sourceHeight && !luminancePyramid.isEmpty()) {
            return;
        }

        cleanupLuminancePyramid();
        sourceWidth = width;
        sourceHeight = height;

        float scale = Math.min(1.0f, MAXIMUM_METER_DIMENSION / (float) Math.max(width, height));
        int levelWidth = Integer.highestOneBit(Math.max(1, Math.round(width * scale)));
        int levelHeight = Integer.highestOneBit(Math.max(1, Math.round(height * scale)));
        int level = 0;
        while (true) {
            luminancePyramid.add(new RenderTarget(
                    "autoExposureLuminance" + level,
                    levelWidth,
                    levelHeight,
                    false,
                    GL_R16F
            ));
            if (levelWidth == 1 && levelHeight == 1) {
                break;
            }
            levelWidth = Math.max(1, (levelWidth + 1) / 2);
            levelHeight = Math.max(1, (levelHeight + 1) / 2);
            level++;
        }
    }

    private void renderLuminancePyramid(int hdrTexture, FullscreenQuad fullscreenQuad) {
        downsampleShader.useProgram();
        downsampleShader.setUniform("uSourceTexture", 0);

        int inputTexture = hdrTexture;
        for (int index = 0; index < luminancePyramid.size(); index++) {
            RenderTarget level = luminancePyramid.get(index);
            level.bind();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, inputTexture);
            downsampleShader.setUniform("uFirstPass", index == 0 ? 1 : 0);
            outputSize.set(level.getWidth(), level.getHeight());
            downsampleShader.setUniform("uOutputSize", outputSize);
            fullscreenQuad.render();
            inputTexture = level.getColorTexture();
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        downsampleShader.unbind();
    }

    private void renderAdaptedExposure(
            float frameDeltaSeconds,
            ColorGradingSettings settings,
            FullscreenQuad fullscreenQuad
    ) {
        int historyWriteIndex = 1 - historyReadIndex;
        RenderTarget writeTarget = exposureHistory[historyWriteIndex];
        writeTarget.bind();

        adaptationShader.useProgram();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, luminancePyramid.getLast().getColorTexture());
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, exposureHistory[historyReadIndex].getColorTexture());

        adaptationShader.setUniform("uLogLuminanceTexture", 0);
        adaptationShader.setUniform("uPreviousExposureTexture", 1);
        adaptationShader.setUniform("uCompensationEv", settings.getExposure());
        adaptationShader.setUniform("uMinimumExposureEv", settings.getMinimumExposureEv());
        adaptationShader.setUniform("uMaximumExposureEv", settings.getMaximumExposureEv());
        adaptationShader.setUniform("uTargetLuminance", settings.getTargetLuminance());
        adaptationShader.setUniform("uDarkenSpeed", settings.getDarkenAdaptationSpeed());
        adaptationShader.setUniform("uBrightenSpeed", settings.getBrightenAdaptationSpeed());
        float safeDeltaTime = Float.isFinite(frameDeltaSeconds) ? frameDeltaSeconds : 0.0f;
        adaptationShader.setUniform("uDeltaTime", Math.max(0.0f, Math.min(0.1f, safeDeltaTime)));
        fullscreenQuad.render();

        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        adaptationShader.unbind();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        historyReadIndex = historyWriteIndex;
    }

    private void initializeHistory(float initialExposureEv, ColorGradingSettings settings) {
        float exposure = Math.max(
                settings.getMinimumExposureEv(),
                Math.min(settings.getMaximumExposureEv(), Float.isFinite(initialExposureEv) ? initialExposureEv : 0.0f)
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (RenderTarget historyTarget : exposureHistory) {
                historyTarget.bind();
                glClearBufferfv(GL_COLOR, 0, stack.floats(exposure, 0.0f, 0.0f, 1.0f));
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        historyReadIndex = 0;
        historyInitialized = true;
    }

    private int currentExposureTexture() {
        if (!historyInitialized || exposureHistory[historyReadIndex] == null) {
            return 0;
        }
        return exposureHistory[historyReadIndex].getColorTexture();
    }

    private void cleanupLuminancePyramid() {
        luminancePyramid.forEach(RenderTarget::cleanup);
        luminancePyramid.clear();
    }
}
