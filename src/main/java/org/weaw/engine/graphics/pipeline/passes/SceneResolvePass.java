package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;

/** Resolves the multisampled scene color and depth for post-processing. */
public final class SceneResolvePass implements RenderPass {
    @Override
    public String getName() {
        return "SceneResolvePass";
    }

    @Override
    public void create() {
        // Uses shared render targets managed by RenderPipeline.
    }

    @Override
    public void execute(RenderContext context) {
        RenderTarget source = context.getRenderTarget("sceneColor");
        RenderTarget destination = context.getRenderTarget("resolvedSceneColor");
        if (source == null || destination == null) {
            return;
        }

        source.resolveTo(destination, true);
        context.setCurrentColorTarget("resolvedSceneColor");
    }

    @Override
    public void resize(int width, int height) {
        // Uses shared render targets resized by RenderPipeline.
    }

    @Override
    public void cleanup() {
        // No pass-local resources.
    }
}
