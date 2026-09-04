package org.weaw.runtime;

import org.joml.Vector3f;

import java.util.Objects;

public final class BenchmarkController {
    private static final float FLY_SPEED_BLOCKS_PER_SECOND = 18.0f;
    private static final float LATERAL_AMPLITUDE_BLOCKS = 48.0f;
    private static final float VERTICAL_AMPLITUDE_BLOCKS = 8.0f;
    private static final float LATERAL_WAVELENGTH_BLOCKS = 96.0f;
    private static final float VERTICAL_WAVELENGTH_BLOCKS = 192.0f;
    private static final double RAPID_TURN_PERIOD_SECONDS = 4.0d;
    private static final double RAPID_TURN_START_SECONDS = 1.0d;
    private static final double RAPID_TURN_DURATION_SECONDS = 0.25d;
    private static final double RAPID_TURN_RETURN_SECONDS = 2.75d;

    private final BenchmarkOptions options;
    private final boolean rapidTurnsEnabled;
    private BenchmarkPhase phase = BenchmarkPhase.WARMUP;
    private double phaseElapsedSeconds;
    private double totalElapsedSeconds;
    private double loadingDurationSeconds;
    private boolean loadingConverged;

    public BenchmarkController(BenchmarkOptions options) {
        this(options, Boolean.getBoolean("voxy.benchmark.rapidTurns"));
    }

    BenchmarkController(BenchmarkOptions options, boolean rapidTurnsEnabled) {
        this.options = Objects.requireNonNull(options, "options");
        this.rapidTurnsEnabled = rapidTurnsEnabled;
    }

    public BenchmarkFrame update(double deltaSeconds, boolean streamingConverged) {
        double remainingSeconds = Math.max(0.0d, deltaSeconds);
        while (remainingSeconds > 0.0d && phase != BenchmarkPhase.COMPLETE) {
            if (phase == BenchmarkPhase.LOADING && streamingConverged) {
                loadingConverged = true;
                loadingDurationSeconds = phaseElapsedSeconds;
                transitionTo(BenchmarkPhase.TRAVERSAL);
                continue;
            }

            double durationSeconds = phaseDurationSeconds(phase);
            double stepSeconds = Math.min(remainingSeconds, Math.max(0.0d, durationSeconds - phaseElapsedSeconds));
            phaseElapsedSeconds += stepSeconds;
            totalElapsedSeconds += stepSeconds;
            remainingSeconds -= stepSeconds;

            if (phaseElapsedSeconds + 1.0e-9d < durationSeconds) {
                break;
            }
            finishCurrentPhase();
        }
        return currentFrame();
    }

    public BenchmarkFrame currentFrame() {
        double traversalElapsed = switch (phase) {
            case TRAVERSAL -> phaseElapsedSeconds;
            case SETTLE, COMPLETE -> options.durationSeconds();
            default -> 0.0d;
        };
        return sampleTrajectory(traversalElapsed);
    }

    public BenchmarkPhase phase() {
        return phase;
    }

    public double phaseElapsedSeconds() {
        return phaseElapsedSeconds;
    }

    public double totalElapsedSeconds() {
        return totalElapsedSeconds;
    }

    public boolean loadingConverged() {
        return loadingConverged;
    }

    public double loadingDurationSeconds() {
        return loadingDurationSeconds;
    }

    public boolean isComplete() {
        return phase == BenchmarkPhase.COMPLETE;
    }

    private BenchmarkFrame sampleTrajectory(double elapsedSeconds) {
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
        if (rapidTurnsEnabled) {
            yaw += rapidTurnYawOffset(clampedElapsed);
        }
        float pitch = (float) Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        return new BenchmarkFrame(
                new Vector3f(x, y, z),
                yaw,
                pitch,
                phase,
                phaseElapsedSeconds,
                totalElapsedSeconds,
                loadingConverged,
                loadingDurationSeconds
        );
    }

    private static float rapidTurnYawOffset(double elapsedSeconds) {
        double phase = elapsedSeconds % RAPID_TURN_PERIOD_SECONDS;
        if (phase < RAPID_TURN_START_SECONDS) {
            return 0.0f;
        }
        if (phase < RAPID_TURN_START_SECONDS + RAPID_TURN_DURATION_SECONDS) {
            double progress = (phase - RAPID_TURN_START_SECONDS) / RAPID_TURN_DURATION_SECONDS;
            return (float) (90.0d * smoothStep(progress));
        }
        if (phase < RAPID_TURN_RETURN_SECONDS) {
            return 90.0f;
        }
        if (phase < RAPID_TURN_RETURN_SECONDS + RAPID_TURN_DURATION_SECONDS) {
            double progress = (phase - RAPID_TURN_RETURN_SECONDS) / RAPID_TURN_DURATION_SECONDS;
            return (float) (90.0d * (1.0d - smoothStep(progress)));
        }
        return 0.0f;
    }

    private static double smoothStep(double value) {
        double clamped = Math.clamp(value, 0.0d, 1.0d);
        return clamped * clamped * (3.0d - 2.0d * clamped);
    }

    private double phaseDurationSeconds(BenchmarkPhase currentPhase) {
        return switch (currentPhase) {
            case WARMUP -> options.warmupSeconds();
            case LOADING -> options.loadingTimeoutSeconds();
            case TRAVERSAL -> options.durationSeconds();
            case SETTLE -> options.settleSeconds();
            case COMPLETE, MANUAL -> 0.0d;
        };
    }

    private void finishCurrentPhase() {
        switch (phase) {
            case WARMUP -> transitionTo(BenchmarkPhase.LOADING);
            case LOADING -> {
                loadingDurationSeconds = phaseElapsedSeconds;
                transitionTo(BenchmarkPhase.TRAVERSAL);
            }
            case TRAVERSAL -> transitionTo(BenchmarkPhase.SETTLE);
            case SETTLE -> transitionTo(BenchmarkPhase.COMPLETE);
            case COMPLETE, MANUAL -> { }
        }
    }

    private void transitionTo(BenchmarkPhase nextPhase) {
        phase = nextPhase;
        phaseElapsedSeconds = 0.0d;
    }

    public record BenchmarkFrame(
            Vector3f position,
            float yaw,
            float pitch,
            BenchmarkPhase phase,
            double phaseElapsedSeconds,
            double totalElapsedSeconds,
            boolean loadingConverged,
            double loadingDurationSeconds
    ) {
        public BenchmarkFrame {
            position = new Vector3f(position);
        }
    }
}
