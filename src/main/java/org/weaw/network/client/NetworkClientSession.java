package org.weaw.network.client;

import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;
import org.weaw.gameplay.PlayerHotbar;
import org.weaw.gameplay.PlayerInput;
import org.weaw.gameplay.PlayerRenderPose;
import org.weaw.gameplay.TargetedBlock;
import org.weaw.network.protocol.CatalogFingerprint;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.NetworkPlayerState;
import org.weaw.network.protocol.Protocol;
import org.weaw.network.protocol.ServerMessage;
import org.weaw.network.transport.ClientTransport;
import org.weaw.server.GameServer;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/** Client simulation, prediction, reconciliation and replicated world state. */
public final class NetworkClientSession implements AutoCloseable {
    private static final long HANDSHAKE_TIMEOUT_NANOS = 10_000_000_000L;
    private static final int MAX_PENDING_PREDICTIONS = 256;
    private static final int MAX_WORLD_MESSAGES_PER_UPDATE = 4;
    private static final float CORRECTION_DURATION_SECONDS = 0.1f;
    private static final float SNAP_CORRECTION_DISTANCE = 2.0f;

    private final ClientTransport transport;
    private final BlockCatalog catalog;
    private final String playerName;
    private final int requestedViewDistance;
    private final RemotePlayerStore remotePlayers = new RemotePlayerStore();
    private final ArrayDeque<PendingPrediction> pendingPredictions = new ArrayDeque<>();
    private final ArrayDeque<ServerMessage> deferredHandshakeMessages = new ArrayDeque<>();
    private final String[] lastSentHotbar = new String[PlayerHotbar.SLOT_COUNT];
    private final Vector3f renderPositionCorrection = new Vector3f();

    private ServerMessage.Welcome welcome;
    private ClientWorld clientWorld;
    private GameplaySession gameplay;
    private long nextSequence;
    private long clientTick;
    private double accumulatedTime;
    private String rejectionReason;
    private float renderYawCorrection;
    private float renderPitchCorrection;
    private float correctionTimeRemaining;
    private float lastCorrectionDistance;
    private long reconciliationCount;
    private long receivedChunkSnapshots;
    private long receivedLightUpdates;
    private int desiredHotbarSlot;
    private boolean hotbarSelectionInitialized;
    private boolean hotbarSelectionDirty;
    private long pendingHotbarSelectionSequence = -1L;

    public NetworkClientSession(
            ClientTransport transport,
            BlockCatalog catalog,
            String playerName,
            int requestedViewDistance
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.requestedViewDistance = requestedViewDistance;
    }

    public void connect() throws IOException {
        if (!transport.send(new ClientMessage.Hello(
                Protocol.VERSION,
                CatalogFingerprint.compute(catalog),
                playerName,
                requestedViewDistance
        ))) {
            throw new IOException("Unable to send multiplayer handshake");
        }
        long deadline = System.nanoTime() + HANDSHAKE_TIMEOUT_NANOS;
        while (welcome == null && System.nanoTime() < deadline) {
            pumpMessages(Integer.MAX_VALUE);
            if (rejectionReason != null) {
                throw new IOException(rejectionReason);
            }
            if (!transport.isOpen()) {
                break;
            }
            if (welcome == null) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
        pumpMessages(Integer.MAX_VALUE);
        if (rejectionReason != null) {
            throw new IOException(rejectionReason);
        }
        if (welcome == null) {
            throw new IOException(transport.isOpen() ? "Multiplayer handshake timed out" : transport.closeReason());
        }
        clientWorld = new ClientWorld(
                catalog,
                welcome.worldSeed(),
                welcome.minChunkY(),
                welcome.maxChunkY(),
                welcome.renderDistance()
        );
        gameplay = new GameplaySession(clientWorld.world(), new GameplaySettings());
        gameplay.getPlayer().setPosition(new Vector3f(16.0f, 12.0f, 48.0f));
        rememberCurrentHotbar();
        while (!deferredHandshakeMessages.isEmpty()) {
            handlePostHandshakeMessage(deferredHandshakeMessages.removeFirst());
        }
        if (!hotbarSelectionInitialized) {
            desiredHotbarSlot = gameplay.getHotbar().getSelectedIndex();
            hotbarSelectionInitialized = true;
        }
    }

    public int update(float frameDeltaTime, PlayerInput input) {
        ensureConnected();
        pumpMessages(MAX_WORLD_MESSAGES_PER_UPDATE);
        advanceVisualCorrection(frameDeltaTime);
        clientWorld.update();
        synchronizeHotbar();

        accumulatedTime += Math.max(0.0f, frameDeltaTime);
        float fixedDelta = 1.0f / GameServer.DEFAULT_TICKS_PER_SECOND;
        int ticks = 0;
        PlayerInput tickInput = input;
        while (accumulatedTime >= fixedDelta && ticks < 5) {
            long sequence = nextSequence;
            int commandHotbarSlot = tickInput.scrollDelta() == 0
                    ? desiredHotbarSlot
                    : Math.floorMod(
                            desiredHotbarSlot + tickInput.scrollDelta(),
                            PlayerHotbar.SLOT_COUNT
                    );
            PlayerInput commandInput = withoutHotbarScroll(tickInput);
            ClientMessage.PlayerCommand command = new ClientMessage.PlayerCommand(
                    sequence,
                    clientTick++,
                    commandInput,
                    commandHotbarSlot
            );
            if (!transport.send(command)) {
                break;
            }
            nextSequence++;
            if (commandHotbarSlot != desiredHotbarSlot) {
                desiredHotbarSlot = commandHotbarSlot;
                hotbarSelectionDirty = true;
            }
            gameplay.getHotbar().select(desiredHotbarSlot);
            if (hotbarSelectionDirty) {
                pendingHotbarSelectionSequence = sequence;
                hotbarSelectionDirty = false;
            }
            predict(command);
            pendingPredictions.addLast(new PendingPrediction(sequence, predictionInput(commandInput)));
            while (pendingPredictions.size() > MAX_PENDING_PREDICTIONS) {
                pendingPredictions.removeFirst();
            }
            accumulatedTime -= fixedDelta;
            ticks++;
            tickInput = tickInput.withoutFrameTransitions();
        }
        if (ticks == 5 && accumulatedTime >= fixedDelta) {
            accumulatedTime = 0.0d;
        }
        return ticks;
    }

    public ClientWorld getClientWorld() {
        ensureConnected();
        return clientWorld;
    }

    public GameplaySession getGameplay() {
        ensureConnected();
        return gameplay;
    }

    public PlayerHotbar getHotbar() {
        return getGameplay().getHotbar();
    }

    public void selectHotbarSlot(int index) {
        ensureConnected();
        gameplay.getHotbar().select(index);
        hotbarSelectionInitialized = true;
        if (desiredHotbarSlot != index) {
            desiredHotbarSlot = index;
            hotbarSelectionDirty = true;
        }
    }

    public PlayerRenderPose sampleRenderPose(float alpha) {
        PlayerRenderPose pose = getGameplay().sampleRenderPose(alpha);
        float factor = correctionFactor();
        return new PlayerRenderPose(
                pose.position().add(new Vector3f(renderPositionCorrection).mul(factor)),
                pose.yaw() + renderYawCorrection * factor,
                pose.pitch() + renderPitchCorrection * factor
        );
    }

    public float interpolationAlpha() {
        float fixedDelta = 1.0f / GameServer.DEFAULT_TICKS_PER_SECOND;
        return (float) Math.clamp(accumulatedTime / fixedDelta, 0.0d, 1.0d);
    }

    public TargetedBlock getTargetedBlock() {
        return getGameplay().getTargetedBlock();
    }

    public RemotePlayerStore getRemotePlayers() {
        return remotePlayers;
    }

    public long getLocalPlayerId() {
        return welcome == null ? -1L : welcome.playerId();
    }

    public boolean isOpen() {
        return transport.isOpen();
    }

    public String closeReason() {
        return rejectionReason != null ? rejectionReason : transport.closeReason();
    }

    public ClientNetworkStats getNetworkStats() {
        return new ClientNetworkStats(
                transport.inboundBacklog(),
                pendingPredictions.size(),
                clientWorld == null ? 0 : clientWorld.pendingMeshCount(),
                remotePlayers.size(),
                lastCorrectionDistance,
                reconciliationCount,
                receivedChunkSnapshots,
                receivedLightUpdates
        );
    }

    private void pumpMessages(int maxWorldMessages) {
        ServerMessage message;
        int worldMessages = 0;
        while ((message = transport.poll()) != null) {
            if (!(message instanceof ServerMessage.Welcome)
                    && !(message instanceof ServerMessage.Rejected)
                    && (clientWorld == null || gameplay == null)) {
                deferredHandshakeMessages.addLast(message);
                continue;
            }
            if (isWorldMessage(message)) {
                worldMessages++;
            }
            switch (message) {
                case ServerMessage.Welcome accepted -> welcome = accepted;
                case ServerMessage.Rejected rejected -> rejectionReason = rejected.reason();
                default -> handlePostHandshakeMessage(message);
            }
            if (worldMessages >= maxWorldMessages) {
                return;
            }
        }
    }

    private void handlePostHandshakeMessage(ServerMessage message) {
        switch (message) {
            case ServerMessage.StateSnapshot snapshot -> applyStateSnapshot(snapshot);
            case ServerMessage.PlayerLeft left -> remotePlayers.remove(left.playerId());
            case ServerMessage.ChunkSnapshot ignored -> {
                receivedChunkSnapshots++;
                applyWorldMessage(message);
            }
            case ServerMessage.ChunkUnload ignored -> applyWorldMessage(message);
            case ServerMessage.BlockUpdate ignored -> applyWorldMessage(message);
            case ServerMessage.ChunkLightUpdate ignored -> {
                receivedLightUpdates++;
                applyWorldMessage(message);
            }
            case ServerMessage.Welcome ignored -> {
                // Duplicate welcomes are ignored after the handshake.
            }
            case ServerMessage.Rejected rejected -> rejectionReason = rejected.reason();
        }
    }

    private void applyWorldMessage(ServerMessage message) {
        if (clientWorld != null) {
            clientWorld.apply(message);
        }
    }

    private void applyStateSnapshot(ServerMessage.StateSnapshot snapshot) {
        if (gameplay == null || welcome == null) {
            return;
        }
        long now = System.nanoTime();
        remotePlayers.accept(welcome.playerId(), snapshot.players(), now);
        NetworkPlayerState authoritative = snapshot.players().stream()
                .filter(player -> player.playerId() == welcome.playerId())
                .findFirst()
                .orElse(null);
        if (authoritative != null) {
            float previousFactor = correctionFactor();
            Vector3f predictedPosition = gameplay.getPlayer().getPosition();
            Vector3f previousVisualPosition = new Vector3f(predictedPosition)
                    .add(new Vector3f(renderPositionCorrection).mul(previousFactor));
            float previousVisualYaw = gameplay.getPlayer().getYaw() + renderYawCorrection * previousFactor;
            float previousVisualPitch = gameplay.getPlayer().getPitch() + renderPitchCorrection * previousFactor;
            while (!pendingPredictions.isEmpty()
                    && pendingPredictions.getFirst().sequence <= snapshot.acknowledgedSequence()) {
                pendingPredictions.removeFirst();
            }
            gameplay.getPlayer().apply(authoritative);
            for (PendingPrediction prediction : pendingPredictions) {
                gameplay.beginSimulationTick();
                gameplay.update(1.0f / GameServer.DEFAULT_TICKS_PER_SECOND, prediction.input);
            }
            Vector3f correctedPosition = gameplay.getPlayer().getPosition();
            lastCorrectionDistance = predictedPosition.distance(correctedPosition);
            reconciliationCount++;
            if (lastCorrectionDistance < SNAP_CORRECTION_DISTANCE) {
                renderPositionCorrection.set(previousVisualPosition).sub(correctedPosition);
                renderYawCorrection = shortestAngleDelta(gameplay.getPlayer().getYaw(), previousVisualYaw);
                renderPitchCorrection = previousVisualPitch - gameplay.getPlayer().getPitch();
                correctionTimeRemaining = CORRECTION_DURATION_SECONDS;
            } else {
                clearVisualCorrection();
            }
        }

        String[] authoritativeHotbar = snapshot.hotbarStableIds();
        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            String stableId = index < authoritativeHotbar.length ? authoritativeHotbar[index] : null;
            gameplay.getHotbar().setSlot(index, stableId == null ? null : catalog.getBlock(stableId));
            lastSentHotbar[index] = stableId;
        }
        int authoritativeSelectedSlot = Math.clamp(
                snapshot.selectedHotbarSlot(),
                0,
                PlayerHotbar.SLOT_COUNT - 1
        );
        if (!hotbarSelectionInitialized) {
            desiredHotbarSlot = authoritativeSelectedSlot;
            hotbarSelectionInitialized = true;
        } else if (pendingHotbarSelectionSequence >= 0L
                && snapshot.acknowledgedSequence() >= pendingHotbarSelectionSequence) {
            pendingHotbarSelectionSequence = -1L;
            if (!hotbarSelectionDirty) {
                desiredHotbarSlot = authoritativeSelectedSlot;
            }
        } else if (pendingHotbarSelectionSequence < 0L && !hotbarSelectionDirty) {
            desiredHotbarSlot = authoritativeSelectedSlot;
        }
        gameplay.getHotbar().select(desiredHotbarSlot);
    }

    private void predict(ClientMessage.PlayerCommand command) {
        gameplay.beginSimulationTick();
        gameplay.update(1.0f / GameServer.DEFAULT_TICKS_PER_SECOND, predictionInput(command.input()));
    }

    private static PlayerInput predictionInput(PlayerInput input) {
        return new PlayerInput(
                input.controlsEnabled(), input.moveForward(), input.moveBackward(), input.moveLeft(), input.moveRight(),
                input.moveUp(), input.moveDown(), input.jump(), input.sprint(), input.toggleNoclip(),
                false, false, input.mouseDeltaX(), input.mouseDeltaY(), 0
        );
    }

    private static PlayerInput withoutHotbarScroll(PlayerInput input) {
        if (input.scrollDelta() == 0) {
            return input;
        }
        return new PlayerInput(
                input.controlsEnabled(), input.moveForward(), input.moveBackward(), input.moveLeft(), input.moveRight(),
                input.moveUp(), input.moveDown(), input.jump(), input.sprint(), input.toggleNoclip(),
                input.breakBlock(), input.placeBlock(), input.mouseDeltaX(), input.mouseDeltaY(), 0
        );
    }

    private void advanceVisualCorrection(float deltaTime) {
        if (correctionTimeRemaining <= 0.0f) {
            return;
        }
        correctionTimeRemaining = Math.max(0.0f, correctionTimeRemaining - Math.max(0.0f, deltaTime));
        if (correctionTimeRemaining == 0.0f) {
            clearVisualCorrection();
        }
    }

    private float correctionFactor() {
        return Math.clamp(correctionTimeRemaining / CORRECTION_DURATION_SECONDS, 0.0f, 1.0f);
    }

    private void clearVisualCorrection() {
        renderPositionCorrection.zero();
        renderYawCorrection = 0.0f;
        renderPitchCorrection = 0.0f;
        correctionTimeRemaining = 0.0f;
    }

    private static boolean isWorldMessage(ServerMessage message) {
        return message instanceof ServerMessage.ChunkSnapshot
                || message instanceof ServerMessage.ChunkUnload
                || message instanceof ServerMessage.BlockUpdate
                || message instanceof ServerMessage.ChunkLightUpdate;
    }

    private static float shortestAngleDelta(float from, float to) {
        float delta = (to - from) % 360.0f;
        if (delta >= 180.0f) delta -= 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        return delta;
    }

    private void synchronizeHotbar() {
        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            BlockDefinition block = gameplay.getHotbar().getSlot(index);
            String stableId = block == null ? null : block.getStableId();
            if (Objects.equals(lastSentHotbar[index], stableId)) {
                continue;
            }
            if (stableId != null) {
                if (!transport.send(new ClientMessage.SetHotbarSlot(nextSequence, index, stableId))) {
                    return;
                }
                nextSequence++;
            }
            lastSentHotbar[index] = stableId;
        }
    }

    private void rememberCurrentHotbar() {
        Arrays.fill(lastSentHotbar, null);
        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            BlockDefinition block = gameplay.getHotbar().getSlot(index);
            lastSentHotbar[index] = block == null ? null : block.getStableId();
        }
    }

    private void ensureConnected() {
        if (gameplay == null || clientWorld == null) {
            throw new IllegalStateException("Network client has not completed its handshake");
        }
    }

    @Override
    public void close() {
        transport.close();
        if (clientWorld != null) {
            clientWorld.close();
            clientWorld = null;
        }
    }

    private record PendingPrediction(long sequence, PlayerInput input) {
    }

    public record ClientNetworkStats(
            int inboundBacklog,
            int pendingPredictions,
            int pendingMeshes,
            int remotePlayers,
            float lastCorrectionDistance,
            long reconciliations,
            long receivedChunkSnapshots,
            long receivedLightUpdates
    ) {
    }
}
