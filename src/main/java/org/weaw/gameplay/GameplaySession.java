package org.weaw.gameplay;

import org.joml.Vector3f;
import org.weaw.engine.input.InputManager;
import org.weaw.game.World;

import java.util.Objects;

public class GameplaySession {
    private final World world;
    private final Player player;
    private final PlayerController playerController;
    private final PlayerInteractionSystem playerInteractionSystem;

    public GameplaySession(World world, GameplaySettings settings) {
        this.world = Objects.requireNonNull(world, "world");
        GameplaySettings gameplaySettings = Objects.requireNonNull(settings, "settings");
        this.player = new Player();
        this.playerController = new PlayerController(gameplaySettings);
        this.playerInteractionSystem = new PlayerInteractionSystem(gameplaySettings);
    }

    public void update(float deltaTime, InputManager inputManager, boolean playerControlEnabled) {
        playerController.update(player, world, deltaTime, inputManager, playerControlEnabled);
        if (playerControlEnabled) {
            playerInteractionSystem.update(player, world, inputManager);
        }
        world.update(player.getPosition());
    }

    public Player getPlayer() {
        return player;
    }

    public TargetedBlock getTargetedBlock() {
        return playerInteractionSystem.getTargetedBlock();
    }

    public void setPlayerPosition(Vector3f position) {
        player.setPosition(position);
    }
}
