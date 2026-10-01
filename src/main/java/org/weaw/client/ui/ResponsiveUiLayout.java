package org.weaw.client.ui;

/** Pure responsive sizing math shared by menu, pause, diagnostics and HUD layouts. */
public record ResponsiveUiLayout(float scale, float width, float height, float x, float y) {
    private static final float MIN_NUMERIC_SCALE = 0.01f;

    public static ResponsiveUiLayout fit(
            float viewportWidth,
            float viewportHeight,
            float desiredWidth,
            float desiredHeight,
            float requestedScale,
            float margin
    ) {
        float safeWidth = Math.max(1.0f, viewportWidth);
        float safeHeight = Math.max(1.0f, viewportHeight);
        float availableWidth = Math.max(1.0f, safeWidth - margin * 2.0f);
        float availableHeight = Math.max(1.0f, safeHeight - margin * 2.0f);
        float preferred = Math.max(MIN_NUMERIC_SCALE, requestedScale);
        float fit = Math.min(preferred, Math.min(availableWidth / desiredWidth, availableHeight / desiredHeight));
        fit = Math.max(MIN_NUMERIC_SCALE, fit);
        float width = Math.min(availableWidth, desiredWidth * fit);
        float height = Math.min(availableHeight, desiredHeight * fit);
        return new ResponsiveUiLayout(
                fit,
                width,
                height,
                Math.max(0.0f, (safeWidth - width) * 0.5f),
                Math.max(0.0f, (safeHeight - height) * 0.5f)
        );
    }

    public float scaled(float value) {
        return value * scale;
    }
}
