package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.network.client.RemotePlayerStore.RenderedRemotePlayer;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glBindBufferBase;

/** Low-poly instanced capsules for remote players. */
public final class RemotePlayerRenderPass implements RenderPass {
    private static final int SEGMENTS = 12;
    private static final int INSTANCE_BINDING = 6;
    private static final int MAX_INSTANCES = 16;
    private static final int INSTANCE_FLOATS = 8;
    private static final float[] RING_Y = {-1.62f, -1.54f, -1.38f, -0.10f, 0.06f, 0.14f};
    private static final float[] RING_RADIUS = {0.0f, 0.20f, 0.30f, 0.30f, 0.20f, 0.0f};
    private static final float[] RING_NORMAL_Y = {-1.0f, -0.6f, 0.0f, 0.0f, 0.6f, 1.0f};

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private Shader shader;
    private int vao;
    private int vertexBuffer;
    private int indexBuffer;
    private int instanceBuffer;
    private int indexCount;
    private FloatBuffer instances;

    @Override
    public String getName() {
        return "RemotePlayerRenderPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/remote-player.glsl");
        float[] vertices = createVertices();
        int[] indices = createIndices();
        indexCount = indices.length;
        instances = MemoryUtil.memAllocFloat(MAX_INSTANCES * INSTANCE_FLOATS);

        vao = glGenVertexArrays();
        vertexBuffer = glGenBuffers();
        indexBuffer = glGenBuffers();
        instanceBuffer = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 6 * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, instanceBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) MAX_INSTANCES * INSTANCE_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        glBindVertexArray(0);
    }

    @Override
    public void execute(RenderContext context) {
        if (context.getRemotePlayerStore() == null) {
            return;
        }
        List<RenderedRemotePlayer> players = context.getRemotePlayerStore().sample(System.nanoTime());
        if (players.isEmpty()) {
            return;
        }
        instances.clear();
        for (RenderedRemotePlayer player : players) {
            if (instances.remaining() < INSTANCE_FLOATS) {
                break;
            }
            float hue = (player.playerId() * 0.61803398875f) % 1.0f;
            float[] color = hsvToRgb(hue, 0.58f, 0.95f);
            var position = player.position();
            instances.put(position.x).put(position.y).put(position.z).put(player.yaw());
            instances.put(color[0]).put(color[1]).put(color[2]).put(1.0f);
        }
        int count = instances.position() / INSTANCE_FLOATS;
        instances.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, instanceBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, instances);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_BINDING, instanceBuffer);

        RenderTarget target = context.getRenderTarget("sceneColor");
        if (target != null) {
            target.bind();
        }
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(true, true);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(true);
        context.getCamera().getProjectionMatrix(projection);
        context.getCamera().getViewMatrix(view);
        shader.useProgram();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        glBindVertexArray(vao);
        glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L, count);
        glBindVertexArray(0);
        shader.unbind();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, INSTANCE_BINDING, 0);
    }

    private static float[] createVertices() {
        float[] vertices = new float[RING_Y.length * SEGMENTS * 6];
        int cursor = 0;
        for (int ring = 0; ring < RING_Y.length; ring++) {
            for (int segment = 0; segment < SEGMENTS; segment++) {
                float angle = (float) (segment * Math.PI * 2.0 / SEGMENTS);
                float x = (float) Math.cos(angle);
                float z = (float) Math.sin(angle);
                float ny = RING_NORMAL_Y[ring];
                float horizontal = (float) Math.sqrt(Math.max(0.0f, 1.0f - ny * ny));
                vertices[cursor++] = x * RING_RADIUS[ring];
                vertices[cursor++] = RING_Y[ring];
                vertices[cursor++] = z * RING_RADIUS[ring];
                vertices[cursor++] = x * horizontal;
                vertices[cursor++] = ny;
                vertices[cursor++] = z * horizontal;
            }
        }
        return vertices;
    }

    private static int[] createIndices() {
        int[] indices = new int[(RING_Y.length - 1) * SEGMENTS * 6];
        int cursor = 0;
        for (int ring = 0; ring < RING_Y.length - 1; ring++) {
            for (int segment = 0; segment < SEGMENTS; segment++) {
                int next = (segment + 1) % SEGMENTS;
                int a = ring * SEGMENTS + segment;
                int b = ring * SEGMENTS + next;
                int c = (ring + 1) * SEGMENTS + segment;
                int d = (ring + 1) * SEGMENTS + next;
                indices[cursor++] = a;
                indices[cursor++] = c;
                indices[cursor++] = b;
                indices[cursor++] = b;
                indices[cursor++] = c;
                indices[cursor++] = d;
            }
        }
        return indices;
    }

    private static float[] hsvToRgb(float hue, float saturation, float value) {
        float scaled = hue * 6.0f;
        int sector = (int) Math.floor(scaled);
        float fraction = scaled - sector;
        float p = value * (1.0f - saturation);
        float q = value * (1.0f - fraction * saturation);
        float t = value * (1.0f - (1.0f - fraction) * saturation);
        return switch (Math.floorMod(sector, 6)) {
            case 0 -> new float[]{value, t, p};
            case 1 -> new float[]{q, value, p};
            case 2 -> new float[]{p, value, t};
            case 3 -> new float[]{p, q, value};
            case 4 -> new float[]{t, p, value};
            default -> new float[]{value, p, q};
        };
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void cleanup() {
        if (instances != null) {
            MemoryUtil.memFree(instances);
            instances = null;
        }
        if (instanceBuffer != 0) glDeleteBuffers(instanceBuffer);
        if (indexBuffer != 0) glDeleteBuffers(indexBuffer);
        if (vertexBuffer != 0) glDeleteBuffers(vertexBuffer);
        if (vao != 0) glDeleteVertexArrays(vao);
        instanceBuffer = indexBuffer = vertexBuffer = vao = 0;
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }
}
