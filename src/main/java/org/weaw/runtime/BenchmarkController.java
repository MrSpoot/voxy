package org.weaw.runtime;

import org.joml.Vector3f;

import java.util.Objects;

public final class BenchmarkController {
    private static final float FLY_SPEED_BLOCKS_PER_SECOND = 18.0f;
    private static final float LATERAL_AMPLITUDE_BLOCKS = 48.0f;
    private static final float VERTICAL_AMPLITUDE_BLOCKS = 8.0f;
    private static final float LATERAL_WAVELENGTH_BLOCKS = 96.0f;
    private static final float VERTICAL_WAVELENGTH_BLOCKS = 192.0f;

    private final BenchmarkOptions options;

    public BenchmarkController(BenchmarkOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public BenchmarkFrame sample(double elapsedSeconds) {
        double clampedElapsed = Math.max(0.0d, Math.min(elapsedSeconds, options.durationSeconds()));
        float travelledDistance = (float) clampedElapsed * FLY_SPEED_BLOCKS_PER_SECOND;

        Vector3f spawn = options.spawn();
        float x = spawn.x + travelledDistance;
        float y = spawn.y + (float) Math.sin(travelledDistance / VERTICAL_WAVELENGTH_BLOCKS) * VERTICAL_AMPLITUDE_BLOCKS;
        float z = spawn.z + (float) Math.sin(travelledDistance / LATERAL_WAVELENGTH_BLOCKS) * LATERAL_AMPLITUDE_BLOCKS;

        float dx = FLY_SPEED_BLOCKS_PER_SECOND;
        float dy = (float) Math.cos(travelledDistance / VERTICAL_WAVELENGTH_BLOCKS)
                * (VERTICAL_AMPLITUDE_BLOCKS * FLY_SPEED_BLOCKS_PER_SECOND / VERTICAL_WAVELENGTH_BLOCKS);
        float dz = (float) Math.cos(travelledDistance / LATERAL_WAVELENGTH_BLOCKS)
                * (LATERAL_AMPLITUDE_BLOCKS * FLY_SPEED_BLOCKS_PER_SECOND / LATERAL_WAVELENGTH_BLOCKS);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx));
        float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        return new BenchmarkFrame(new Vector3f(x, y, z), yaw, pitch);
    }

    public record BenchmarkFrame(Vector3f position, float yaw, float pitch) {
        public BenchmarkFrame {
            position = new Vector3f(position);
        }
    }
}
