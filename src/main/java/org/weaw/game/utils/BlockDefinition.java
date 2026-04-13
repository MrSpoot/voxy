package org.weaw.game.utils;

public final class BlockDefinition {
    public enum TransparencyType {
        OPAQUE,
        CUTOUT,
        TRANSPARENT
    }

    private final String stableId;
    private final String texturePath;
    private final TransparencyType transparencyType;
    private final boolean cullSameTypeFaces;
    private volatile int textureIndex = -1;
    private short runtimeId = -1;

    public BlockDefinition(String stableId, String texturePath, TransparencyType transparencyType, boolean cullSameTypeFaces) {
        this.stableId = stableId;
        this.texturePath = texturePath;
        this.transparencyType = transparencyType;
        this.cullSameTypeFaces = cullSameTypeFaces;
    }

    public String getStableId() {
        return stableId;
    }

    public String getTexturePath() {
        return texturePath;
    }

    public TransparencyType getTransparencyType() {
        return transparencyType;
    }

    public boolean isCullSameTypeFaces() {
        return cullSameTypeFaces;
    }

    public int getTextureIndex() {
        return textureIndex;
    }

    public void setTextureIndex(int textureIndex) {
        this.textureIndex = textureIndex;
    }

    public short getId() {
        if (runtimeId < 0) {
            BlockRegistry.initialize();
        }
        return runtimeId;
    }

    void setRuntimeId(short runtimeId) {
        this.runtimeId = runtimeId;
    }

    public boolean isOpaque() {
        return transparencyType == TransparencyType.OPAQUE;
    }

    public boolean isCutout() {
        return transparencyType == TransparencyType.CUTOUT;
    }

    public boolean isTransparent() {
        return transparencyType == TransparencyType.TRANSPARENT && this != Blocks.AIR;
    }

    public boolean isSolid() {
        return this != Blocks.AIR;
    }

    @Override
    public String toString() {
        return stableId;
    }
}
