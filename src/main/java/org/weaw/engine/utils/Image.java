package org.weaw.engine.utils;

import lombok.Data;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

@Data
public class Image {

    private ByteBuffer byteBuffer;
    private int width;
    private int height;
    private boolean hasTransparency;

    public Image(ByteBuffer byteBuffer, int width, int height) {
        this.byteBuffer = byteBuffer;
        this.width = width;
        this.height = height;
    }

    public Image(Vector4f color){
        this.width = 256;
        this.height = 256;

        int[] pixels = getPixels(color);
        this.hasTransparency = false;

        this.byteBuffer = ByteBuffer.allocateDirect(width * height * 4);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                this.byteBuffer.put((byte) ((pixel >> 16) & 0xFF)); // Rouge
                this.byteBuffer.put((byte) ((pixel >> 8) & 0xFF));  // Vert
                this.byteBuffer.put((byte) (pixel & 0xFF));         // Bleu
                byte alpha = (byte) ((pixel >> 24) & 0xFF);
                this.byteBuffer.put(alpha); // Alpha
            }
        }

        this.byteBuffer.flip();
    }

    private int[] getPixels(Vector4f color) {
        int r = (int) (color.x * 255);
        int g = (int) (color.y * 255);
        int b = (int) (color.z * 255);
        int a = (int) (color.w * 255);

        BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(new Color(r, g, b, a));
        g2d.fillRect(0, 0, width, height);
        g2d.dispose();

        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        return pixels;
    }

    public void cleanup(){
        if (this.byteBuffer != null) {
            MemoryUtil.memFree(this.byteBuffer);
            this.byteBuffer = null;
        }
    }

}
