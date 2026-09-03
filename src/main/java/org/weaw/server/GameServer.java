package org.weaw.server;

import org.joml.Vector3f;
import org.weaw.game.World;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.PlayerInput;

import java.util.Objects;

public class GameServer implements AutoCloseable {
    public static final int DEFAULT_TICKS_PER_SECOND = 30;
    private static final int MAX_TICKS_PER_FRAME = 5;

    private final World world;
    private final GameplaySession gameplaySession;
    private final float fixedDeltaTime;
    private double accumulatedTimeSeconds;
    private long tickIndex;

    public GameServer(World world, GameplaySession gameplaySession) {
        this(world, gameplaySession, DEFAULT_TICKS_PER_SECOND);
    }

    public GameServer(World world, GameplaySession gameplaySession, int ticksPerSecond) {
        if (ticksPerSecond <= 0) {
            throw new IllegalArgumentException("ticksPerSecond must be positive");
        }
        this.world = Objects.requireNonNull(world, "world");
        this.gameplaySession = Objects.requireNonNull(gameplaySession, "gameplaySession");
        this.fixedDeltaTime = 1.0f / ticksPerSecond;
    }

    public int update(float frameDeltaTime, PlayerInput input) {
        if (frameDeltaTime < 0.0f) {
            throw new IllegalArgumentException("frameDeltaTime must be non-negative");
        }

        accumulatedTimeSeconds += frameDeltaTime;
        int ticks = 0;
        PlayerInput tickInput = Objects.requireNonNull(input, "input");
        while (accumulatedTimeSeconds >= fixedDeltaTime && ticks < MAX_TICKS_PER_FRAME) {
            gameplaySession.update(fixedDeltaTime, tickInput);
            accumulatedTimeSeconds -= fixedDeltaTime;
            tickIndex++;
            ticks++;
            tickInput = tickInput.withoutFrameTransitions();
        }

        if (ticks == MAX_TICKS_PER_FRAME && accumulatedTimeSeconds >= fixedDeltaTime) {
            accumulatedTimeSeconds = 0.0d;
        }

        return ticks;
    }

    public void updateBenchmarkPose(Vector3f position, float yaw, float pitch) {
        gameplaySession.updateBenchmarkPose(position, yaw, pitch);
        tickIndex++;
        accumulatedTimeSeconds = 0.0d;
    }

    public World getWorld() {
        return world;
    }

    public GameplaySession getGameplaySession() {
        return gameplaySession;
    }

    public float getFixedDeltaTime() {
        return fixedDeltaTime;
    }

    public long getTickIndex() {
        return tickIndex;
    }

    @Override
    public void close() {
        world.close();
    }
}
