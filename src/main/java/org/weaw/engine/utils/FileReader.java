package org.weaw.engine.utils;

import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.stb.STBImage.*;

public class FileReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileReader.class);

    public static String readFile(String path) {
        StringBuilder content = new StringBuilder();

        try (InputStream inputStream = FileReader.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("File not found: " + path);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            LOGGER.error("Failed to read file: " + path, e);
            throw new IllegalStateException("Failed to read file: " + path, e);
        }

        return content.toString();
    }

    // Lecture des images avec STBImage
    public static Image readImage(String path, boolean flip) {
        stbi_set_flip_vertically_on_load(flip);

        try (InputStream inputStream = FileReader.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                LOGGER.warn("Image not found: " + path);
                return null;
            }

            ByteBuffer buffer = readStreamToByteBuffer(inputStream, 1024);
            IntBuffer x = BufferUtils.createIntBuffer(1);
            IntBuffer y = BufferUtils.createIntBuffer(1);
            IntBuffer channels = BufferUtils.createIntBuffer(1);

            ByteBuffer image = stbi_load_from_memory(buffer, x, y, channels, STBI_rgb_alpha);
            if (image == null) {
                LOGGER.warn("Could not decode image file [{}]: [{}]", path, stbi_failure_reason());
                return null;
            }

            int width = x.get();
            int height = y.get();
            return new Image(image, width, height);
        } catch (IOException e) {
            LOGGER.error("Failed to read image: " + path, e);
            return null;
        }
    }

    // Lecture des fichiers binaires
    public static ByteBuffer read(String resourcePath, int bufferSize) throws IOException {
        try (InputStream inputStream = FileReader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return readStreamToByteBuffer(inputStream, bufferSize);
        }
    }

    // Méthode utilitaire pour convertir un InputStream en ByteBuffer
    private static ByteBuffer readStreamToByteBuffer(InputStream inputStream, int bufferSize) throws IOException {
        ByteBuffer buffer = BufferUtils.createByteBuffer(bufferSize);

        try (ReadableByteChannel channel = Channels.newChannel(inputStream)) {
            while (true) {
                int bytes = channel.read(buffer);
                if (bytes == -1) {
                    break;
                }
                if (buffer.remaining() == 0) {
                    buffer = resizeBuffer(buffer, buffer.capacity() * 2);
                }
            }
        }

        buffer.flip();
        return buffer;
    }

    // Redimensionner un ByteBuffer
    private static ByteBuffer resizeBuffer(ByteBuffer buffer, int newCapacity) {
        ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }
}
