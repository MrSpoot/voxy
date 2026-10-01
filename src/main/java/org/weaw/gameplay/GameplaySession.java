package org.weaw.gameplay;

import org.joml.Vector3f;
import org.weaw.game.World;
import org.weaw.game.utils.BlockDefinition;

import java.util.Objects;

public class GameplaySession {
    private final World world;
    private final Player player;
    private final PlayerController playerController;
    private final PlayerInteractionSystem playerInteractionSystem;
    private final PlayerHotbar hotbar;
    private final GameplaySettings settings;

    public GameplaySession(World world, GameplaySettings settings) {
        this.world = Objects.requireNonNull(world, "world");
        GameplaySettings gameplaySettings = Objects.requireNonNull(settings, "settings");
        this.settings = gameplaySettings;
        this.player = new Player();
        this.playerController = new PlayerController(gameplaySettings);
        this.hotbar = new PlayerHotbar(world.getBlockCatalog());
        this.playerInteractionSystem = new PlayerInteractionSystem(gameplaySettings, hotbar);
    }

    public void update(float deltaTime, PlayerInput input) {
        updateMovement(deltaTime, input);
        if (input.controlsEnabled()) {
            playerInteractionSystem.update(player, world, input);
        }
    }

    public void updateMovement(float deltaTime, PlayerInput input) {
        playerController.update(player, world, deltaTime, input);
    }

    public BlockAction resolveBlockAction(PlayerInput input) {
        return input.controlsEnabled() ? playerInteractionSystem.resolveAction(player, world, input) : null;
    }

    public boolean applyBlockAction(BlockAction action) {
        return playerInteractionSystem.applyAction(world, action);
    }

    public void beginSimulationTick() {
        player.beginSimulationTick();
    }

    public PlayerRenderPose sampleRenderPose(float interpolationAlpha) {
        return player.sampleRenderPose(interpolationAlpha);
    }

    public Player getPlayer() {
        return player;
    }

    public TargetedBlock getTargetedBlock() {
        return playerInteractionSystem.getTargetedBlock();
    }

    public BlockDefinition getSelectedBlock() {
        return playerInteractionSystem.getSelectedBlock();
    }

    public PlayerHotbar getHotbar() {
        return hotbar;
    }

    public GameplaySettings getSettings() {
        return settings;
    }

    public void setPlayerPosition(Vector3f position) {
        player.setPosition(position);
    }

    public void setPlayerPose(Vector3f position, float yaw, float pitch) {
        player.setPose(position, yaw, pitch);
    }

    public void updateBenchmarkPose(Vector3f position, float yaw, float pitch) {
        player.setPose(position, yaw, pitch);
    }
}
