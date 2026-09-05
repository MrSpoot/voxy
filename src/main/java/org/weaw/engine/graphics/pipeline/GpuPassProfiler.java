package org.weaw.engine.graphics.pipeline;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15C.glBeginQuery;
import static org.lwjgl.opengl.GL15C.glDeleteQueries;
import static org.lwjgl.opengl.GL15C.glEndQuery;
import static org.lwjgl.opengl.GL15C.glGenQueries;
import static org.lwjgl.opengl.GL15C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL33C.glGetQueryObjectui64;

/** Non-blocking, delayed GPU timings for sequential render passes. */
final class GpuPassProfiler {
    private static final int MAX_PENDING_SAMPLES_PER_PASS = 4;

    private final Map<String, ArrayDeque<Integer>> pendingQueries = new LinkedHashMap<>();
    private final ArrayDeque<Integer> reusableQueries = new ArrayDeque<>();
    private int activeQuery;

    void collectAvailable(RenderStats renderStats) {
        for (Map.Entry<String, ArrayDeque<Integer>> entry : pendingQueries.entrySet()) {
            ArrayDeque<Integer> queries = entry.getValue();
            while (!queries.isEmpty()) {
                int query = queries.peekFirst();
                if (glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) == 0) {
                    break;
                }
                queries.removeFirst();
                renderStats.recordPassGpuTime(entry.getKey(), glGetQueryObjectui64(query, org.lwjgl.opengl.GL15C.GL_QUERY_RESULT));
                reusableQueries.addLast(query);
            }
        }
    }

    boolean begin(String passName) {
        ArrayDeque<Integer> queries = pendingQueries.computeIfAbsent(passName, ignored -> new ArrayDeque<>());
        if (queries.size() >= MAX_PENDING_SAMPLES_PER_PASS) {
            return false;
        }
        activeQuery = reusableQueries.isEmpty() ? glGenQueries() : reusableQueries.removeFirst();
        glBeginQuery(GL_TIME_ELAPSED, activeQuery);
        return true;
    }

    void end(String passName) {
        if (activeQuery == 0) {
            return;
        }
        glEndQuery(GL_TIME_ELAPSED);
        pendingQueries.get(passName).addLast(activeQuery);
        activeQuery = 0;
    }

    void cleanup() {
        if (activeQuery != 0) {
            glEndQuery(GL_TIME_ELAPSED);
            glDeleteQueries(activeQuery);
            activeQuery = 0;
        }
        for (ArrayDeque<Integer> queries : pendingQueries.values()) {
            for (int query : queries) {
                glDeleteQueries(query);
            }
        }
        for (int query : reusableQueries) {
            glDeleteQueries(query);
        }
        pendingQueries.clear();
        reusableQueries.clear();
    }
}
