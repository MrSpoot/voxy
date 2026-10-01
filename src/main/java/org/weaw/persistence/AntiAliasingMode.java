package org.weaw.persistence;

/** Anti-aliasing modes that can be switched while the renderer is running. */
public enum AntiAliasingMode {
    OFF(1, false),
    FXAA(1, true),
    MSAA_2X(2, false),
    MSAA_4X(4, false);

    private final int sampleCount;
    private final boolean fxaa;

    AntiAliasingMode(int sampleCount, boolean fxaa) {
        this.sampleCount = sampleCount;
        this.fxaa = fxaa;
    }

    public int sampleCount() {
        return sampleCount;
    }

    public boolean usesFxaa() {
        return fxaa;
    }
}
