package org.weaw.engine.window;

/** Immutable display resolution used by the video settings UI. */
public record DisplayResolution(int width, int height) {
    public DisplayResolution {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Display resolution dimensions must be positive");
        }
    }

    public String label() {
        return width + " × " + height;
    }
}
