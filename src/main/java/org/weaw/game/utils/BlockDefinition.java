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
    private final int lightEmissionRed;
    private final int lightEmissionGreen;
    private final int lightEmissionBlue;
    private volatile int textureIndex = -1;
    private short runtimeId = -1;

    public BlockDefinition(String stableId, String texturePath, TransparencyType transparencyType, boolean cullSameTypeFaces) {
        this(stableId, texturePath, transparencyType, cullSameTypeFaces, 0, 0, 0);
    }

    public BlockDefinition(
            String stableId,
            String texturePath,
            TransparencyType transparencyType,
            boolean cullSameTypeFaces,
            int lightEmissionRed,
            int lightEmissionGreen,
            int lightEmissionBlue
    ) {
        this.stableId = stableId;
        this.texturePath = texturePath;
        this.transparencyType = transparencyType;
        this.cullSameTypeFaces = cullSameTypeFaces;
        this.lightEmissionRed = validateLightComponent(lightEmissionRed, "lightEmissionRed");
        this.lightEmissionGreen = validateLightComponent(lightEmissionGreen, "lightEmissionGreen");
        this.lightEmissionBlue = validateLightComponent(lightEmissionBlue, "lightEmissionBlue");
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
            throw new IllegalStateException("Block runtime id not assigned for " + stableId + ". Add it to a BlockCatalog first.");
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
        return transparencyType == TransparencyType.TRANSPARENT && !isAir();
    }

    public boolean isSolid() {
        return !isAir();
    }

    public int getLightEmissionRed() {
        return lightEmissionRed;
    }

    public int getLightEmissionGreen() {
        return lightEmissionGreen;
    }

    public int getLightEmissionBlue() {
        return lightEmissionBlue;
    }

    public boolean isLightEmitter() {
        return lightEmissionRed > 0 || lightEmissionGreen > 0 || lightEmissionBlue > 0;
    }

    public boolean blocksLight() {
        return isOpaque() && !isAir();
    }

    public boolean isAir() {
        return "voxy:air".equals(stableId);
    }

    @Override
    public String toString() {
        return stableId;
    }

    private static int validateLightComponent(int value, String fieldName) {
        if (value < 0 || value > 15) {
            throw new IllegalArgumentException(fieldName + " must be in range [0, 15], got " + value);
        }
        return value;
    }
}
